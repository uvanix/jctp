package com.github.jctp.broker;

import org.slf4j.Logger;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static com.github.jctp.broker.BrokerSupport.requestError;

/**
 * CTP 查询通道：按请求编号收集 OnRspQryXxx 应答，处理 CTP 查询的通用约定——
 * 同时只有一个查询在途、发送返回 -2/-3 为流控须稍后重发、应答逐条到达且最后一条带 isLast、
 * 前置断开后 Api 自动重连。
 * <p>
 * 应答段只在回调期间有效，mapper 在回调线程上执行，须在其中完成转换。
 *
 * @param <A> Api 类型（CtpTraderApi、CtpMdApi）
 */
final class CtpQueryChannel<A> {

    /**
     * 查询遇到流控（rc=-2/-3）后的重试间隔。CTP 查询限制为每秒一次。
     */
    static final Duration RETRY_INTERVAL = Duration.ofMillis(250);
    /**
     * 查询等待中检查超时、上报进度的间隔
     */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /**
     * 查询途中前置断开时，等待自动重连就绪后重发的次数
     */
    static final int DISCONNECT_RETRIES = 3;

    /**
     * 发送一个查询请求
     */
    @FunctionalInterface
    interface Request<A> {
        /**
         * @return CTP 请求返回码，0 为成功，-2/-3 为流控
         */
        int send(A api, int requestId);
    }

    /**
     * 连接状态，由所属 broker 掌握
     */
    interface Readiness<A> {
        /**
         * 已登录就绪时返回 Api，否则（连接中、断线重连中、已断开）返回 null
         */
        A readyApi();

        /**
         * 已主动断开（不会再自动重连）
         */
        boolean closed();
    }

    /**
     * 前置断开导致的查询失败：等自动重连就绪后可以重发
     */
    static final class FrontDisconnected extends IllegalStateException {
        FrontDisconnected(String message) {
            super(message);
        }
    }

    private final String side;
    private final Duration timeout;
    private final IntSupplier nextRequestId;
    private final Readiness<A> readiness;
    private final Logger log;
    private final Map<Integer, Pending<?>> pending = new ConcurrentHashMap<>();
    /**
     * CTP 同时只允许一个查询在途
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param side          日志中的一侧名称，如“交易”
     * @param timeout       发送重试的总时限、应答空闲超时、等待重连的时限
     * @param nextRequestId 请求编号，与报单等其他请求共用同一序列
     */
    CtpQueryChannel(String side, Duration timeout, IntSupplier nextRequestId, Readiness<A> readiness, Logger log) {
        this.side = side;
        this.timeout = timeout;
        this.nextRequestId = nextRequestId;
        this.readiness = readiness;
        this.log = log;
    }

    /**
     * 发送查询并阻塞等待全部应答。遇到流控时按间隔重发；应答陆续到达就一直等，连续 timeout 无应答才判超时；
     * 途中前置断开则等自动重连就绪后重发（至多 {@value #DISCONNECT_RETRIES} 次）。不可在 CTP 回调线程上调用。
     *
     * @param what     查询名称，用于日志与异常
     * @param progress 已收到的条数，在调用线程上回调
     */
    <T> List<T> query(String what, Function<MemorySegment, T> mapper, Request<A> request, IntConsumer progress) {
        lock.lock();
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return queryOnce(what, mapper, request, progress);
                } catch (FrontDisconnected e) {
                    if (attempt >= DISCONNECT_RETRIES) {
                        throw new IllegalStateException("CTP " + what + "失败，前置多次断开: " + e.getMessage(), e);
                    }
                    log.warn("CTP {}途中{}，等待重连后重新查询", what, e.getMessage());
                    awaitReady(what);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CTP " + what + "被中断", e);
        } finally {
            lock.unlock();
        }
    }

    <T> List<T> query(String what, Function<MemorySegment, T> mapper, Request<A> request) {
        return query(what, mapper, request, _ -> {
        });
    }

    /**
     * 立即发出查询，不等待、不重发（连接阶段在回调线程上使用）。发送失败时返回已失败的 future；
     * 途中前置断开以 {@link FrontDisconnected} 失败。
     */
    <T> CompletableFuture<List<T>> send(String what, Function<MemorySegment, T> mapper, A api, Request<A> request) {
        int id = nextRequestId.getAsInt();
        Pending<T> query = new Pending<>(what, mapper);
        pending.put(id, query);
        int rc = request.send(api, id);
        if (rc != 0) {
            pending.remove(id);
            query.future.completeExceptionally(new IllegalStateException("CTP " + what + "请求发送失败 " + requestError(rc)));
        }
        return query.future;
    }

    /**
     * 查询应答（Spi 回调线程）。出错或最后一条时结束该查询；未知请求编号（已超时放弃的查询）忽略。
     *
     * @param data  应答数据，可能为 null（无结果）
     * @param error 已解析的错误信息，没有错误时为 null
     */
    void onResponse(int requestId, MemorySegment data, String error, boolean isLast) {
        Pending<?> query = pending.get(requestId);
        if (query == null) {
            return;
        }
        if (error != null || isLast) {
            pending.remove(requestId);
        }
        query.accept(data, error, isLast);
    }

    /**
     * 前置断开：在途查询以 {@link FrontDisconnected} 失败，同步查询会等重连后重发
     */
    void onDisconnected(String reason) {
        failAll(new FrontDisconnected(reason));
    }

    /**
     * 主动断开：在途查询失败，不再重发
     */
    void close(String reason) {
        failAll(new IllegalStateException(reason));
    }

    /**
     * 在途查询数
     */
    int inFlight() {
        return pending.size();
    }

    private void failAll(IllegalStateException reason) {
        pending.values().forEach(q -> q.future.completeExceptionally(reason));
        pending.clear();
    }

    private <T> List<T> queryOnce(String what, Function<MemorySegment, T> mapper, Request<A> request,
                                  IntConsumer progress) throws InterruptedException {
        long sendDeadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            A api = readiness.readyApi();
            if (api == null) {
                throw new IllegalStateException("CTP " + side + "未就绪");
            }
            int id = nextRequestId.getAsInt();
            Pending<T> query = new Pending<>(what, mapper);
            pending.put(id, query);
            int rc = request.send(api, id);
            if (rc == 0) {
                return await(what, id, query, progress);
            }
            pending.remove(id);
            if ((rc != -2 && rc != -3) || System.nanoTime() + RETRY_INTERVAL.toNanos() > sendDeadline) {
                throw new IllegalStateException("CTP " + what + "请求发送失败 " + requestError(rc));
            }
            Thread.sleep(RETRY_INTERVAL);
        }
    }

    private <T> List<T> await(String what, int id, Pending<T> query, IntConsumer progress) throws InterruptedException {
        long idle = timeout.toNanos();
        int reported = -1;
        while (true) {
            try {
                List<T> result = query.future.get(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                progress.accept(result.size());
                return result;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("CTP " + what + "失败", e.getCause());
            } catch (TimeoutException e) {
                int n = query.received.get();
                if (n != reported) {
                    reported = n;
                    progress.accept(n);
                }
                if (System.nanoTime() - query.lastActivity.get() > idle) {
                    pending.remove(id);
                    throw new IllegalStateException("CTP %s超时，%s 内无应答（已收到 %d 条）".formatted(what, timeout, n));
                }
            } catch (InterruptedException e) {
                pending.remove(id);
                throw e;
            }
        }
    }

    /**
     * 等待自动重连并重新就绪，至多 timeout。
     */
    private void awaitReady(String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (readiness.readyApi() == null) {
            if (readiness.closed()) {
                throw new IllegalStateException("CTP " + side + "已断开，" + what + "取消");
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("CTP " + what + "失败，等待重连超时 " + timeout);
            }
            Thread.sleep(RETRY_INTERVAL);
        }
    }

    /**
     * 一次查询的应答收集。received、lastActivity 供等待线程判断进度与超时。
     */
    private static final class Pending<T> {
        final String what;
        final Function<MemorySegment, T> mapper;
        final List<T> items = new ArrayList<>();
        final CompletableFuture<List<T>> future = new CompletableFuture<>();
        final AtomicInteger received = new AtomicInteger();
        final AtomicLong lastActivity = new AtomicLong(System.nanoTime());

        Pending(String what, Function<MemorySegment, T> mapper) {
            this.what = what;
            this.mapper = mapper;
        }

        void accept(MemorySegment data, String error, boolean isLast) {
            lastActivity.set(System.nanoTime());
            if (error != null) {
                future.completeExceptionally(new IllegalStateException("CTP " + what + "失败 " + error));
                return;
            }
            try {
                if (data != null) {
                    items.add(mapper.apply(data));
                    received.incrementAndGet();
                }
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
                return;
            }
            if (isLast) {
                future.complete(List.copyOf(items));
            }
        }
    }
}
