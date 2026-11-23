package com.github.jctp.broker;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.*;

class CtpQueryChannelTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Function<MemorySegment, Integer> MAPPER = s -> s.get(JAVA_INT, 0);

    /**
     * 代替 CtpTraderApi：记录发送的请求编号，按 onSend 返回码
     */
    private static final class FakeApi {
        final List<Integer> sent = new CopyOnWriteArrayList<>();
        volatile IntUnaryOperator onSend = _ -> 0;

        int send(int requestId) {
            sent.add(requestId);
            return onSend.applyAsInt(requestId);
        }
    }

    private final FakeApi api = new FakeApi();
    private final AtomicReference<FakeApi> ready = new AtomicReference<>(api);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger requestId = new AtomicInteger();
    private final CtpQueryChannel<FakeApi> channel = new CtpQueryChannel<>("交易", TIMEOUT, requestId::incrementAndGet,
        new CtpQueryChannel.Readiness<>() {
            @Override
            public FakeApi readyApi() {
                return ready.get();
            }

            @Override
            public boolean closed() {
                return closed.get();
            }
        }, LoggerFactory.getLogger(CtpQueryChannelTest.class));

    private static MemorySegment data(int value) {
        return MemorySegment.ofArray(new int[]{value});
    }

    private List<Integer> query() {
        return channel.query("合约查询", MAPPER, FakeApi::send, _ -> {
        });
    }

    /**
     * 在另一线程（模拟 CTP 回调线程）稍后送达应答
     */
    private void respondLater(int id, int... values) {
        Thread.ofVirtual().start(() -> {
            sleep(30);
            for (int i = 0; i < values.length; i++) {
                channel.onResponse(id, data(values[i]), null, i == values.length - 1);
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void collectsResponsesUntilLast() {
        api.onSend = id -> {
            respondLater(id, 1, 2, 3);
            return 0;
        };
        List<Integer> progress = new CopyOnWriteArrayList<>();
        assertEquals(List.of(1, 2, 3), channel.query("合约查询", MAPPER, FakeApi::send, progress::add));
        assertEquals(3, progress.getLast());
        assertEquals(0, channel.inFlight());
    }

    @Test
    void emptyResultIsSingleNullResponse() {
        api.onSend = id -> {
            Thread.ofVirtual().start(() -> channel.onResponse(id, null, null, true));
            return 0;
        };
        assertEquals(List.of(), query());
    }

    @Test
    void resendsWhenFlowControlled() {
        for (int rc : new int[]{-2, -3}) {
            api.sent.clear();
            api.onSend = id -> {
                if (api.sent.size() == 1) {
                    return rc;
                }
                respondLater(id, 7);
                return 0;
            };
            assertEquals(List.of(7), query());
            assertEquals(2, api.sent.size(), "流控时换新的请求编号重发");
            assertNotEquals(api.sent.getFirst(), api.sent.getLast());
        }
    }

    @Test
    void otherSendErrorsFailImmediately() {
        api.onSend = _ -> -1;
        IllegalStateException e = assertThrows(IllegalStateException.class, this::query);
        assertEquals("CTP 合约查询请求发送失败 rc=-1 网络连接失败", e.getMessage());
        assertEquals(1, api.sent.size());
    }

    @Test
    void failsWhenNotReady() {
        ready.set(null);
        assertEquals("CTP 交易未就绪", assertThrows(IllegalStateException.class, this::query).getMessage());
    }

    @Test
    void idleTimeoutCountsFromLastResponse() {
        api.onSend = id -> {
            Thread.ofVirtual().start(() -> {
                // 应答陆续到达时不判超时：总时长超过 timeout
                for (int i = 0; i < 4; i++) {
                    sleep(TIMEOUT.toMillis() / 2);
                    channel.onResponse(id, data(i), null, false);
                }
            });
            return 0;
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, this::query);
        assertTrue(e.getMessage().contains("超时") && e.getMessage().contains("已收到 4 条"), e.getMessage());
        assertEquals(0, channel.inFlight(), "超时放弃的查询不再收集");
        channel.onResponse(api.sent.getFirst(), data(9), null, true);
    }

    @Test
    void responseErrorFailsQuery() {
        api.onSend = id -> {
            Thread.ofVirtual().start(() -> channel.onResponse(id, null, "3 不合法的登录", true));
            return 0;
        };
        assertEquals("CTP 合约查询失败 3 不合法的登录", assertThrows(IllegalStateException.class, this::query).getMessage());
    }

    @Test
    void mapperFailureFailsQuery() {
        api.onSend = id -> {
            respondLater(id, 1);
            return 0;
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            channel.query("合约查询", _ -> {
                throw new IllegalArgumentException("坏数据");
            }, FakeApi::send, _ -> {
            }));
        assertEquals("坏数据", e.getMessage());
    }

    /**
     * 途中前置断开：等自动重连就绪后用新的请求编号重查
     */
    @Test
    void requeriesAfterReconnect() {
        AtomicInteger calls = new AtomicInteger();
        api.onSend = id -> {
            if (calls.incrementAndGet() == 1) {
                Thread.ofVirtual().start(() -> {
                    sleep(30);
                    ready.set(null);
                    channel.onDisconnected("前置断开 0x2001 接收心跳超时");
                    sleep(100);
                    ready.set(api);
                });
            } else {
                respondLater(id, 5);
            }
            return 0;
        };
        assertEquals(List.of(5), query());
        assertEquals(2, api.sent.size());
    }

    @Test
    void givesUpAfterRepeatedDisconnects() {
        api.onSend = _ -> {
            Thread.ofVirtual().start(() -> {
                sleep(20);
                channel.onDisconnected("前置断开 0x1001 网络读失败");
            });
            return 0;
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, this::query);
        assertTrue(e.getMessage().startsWith("CTP 合约查询失败，前置多次断开"), e.getMessage());
        assertEquals(CtpQueryChannel.DISCONNECT_RETRIES + 1, api.sent.size());
    }

    @Test
    void cancelledWhenClosedWhileWaitingForReconnect() {
        api.onSend = _ -> {
            Thread.ofVirtual().start(() -> {
                sleep(20);
                ready.set(null);
                closed.set(true);
                channel.onDisconnected("前置断开 0x1001 网络读失败");
            });
            return 0;
        };
        assertEquals("CTP 交易已断开，合约查询取消", assertThrows(IllegalStateException.class, this::query).getMessage());
    }

    @Test
    void closeFailsInFlightQueryWithoutRetry() {
        api.onSend = _ -> {
            Thread.ofVirtual().start(() -> {
                sleep(20);
                channel.close("CTP 交易已断开");
            });
            return 0;
        };
        assertEquals("CTP 交易已断开", assertThrows(IllegalStateException.class, this::query).getMessage());
        assertEquals(1, api.sent.size());
    }

    /**
     * 同时只有一个查询在途：并发调用依次发送
     */
    @Test
    void queriesAreSerialized() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        api.onSend = id -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.ofVirtual().start(() -> {
                sleep(50);
                inFlight.decrementAndGet();
                channel.onResponse(id, data(id), null, true);
            });
            return 0;
        };
        List<CompletableFuture<List<Integer>>> futures = List.of(
            CompletableFuture.supplyAsync(this::query), CompletableFuture.supplyAsync(this::query),
            CompletableFuture.supplyAsync(this::query));
        for (CompletableFuture<List<Integer>> f : futures) {
            assertEquals(1, f.get().size());
        }
        assertEquals(1, maxInFlight.get());
    }

    /**
     * 连接阶段的异步查询：发送失败、前置断开都通过 future 返回
     */
    @Test
    void asyncSend() throws Exception {
        CompletableFuture<List<Integer>> ok = channel.send("投资者查询", MAPPER, api, FakeApi::send);
        channel.onResponse(api.sent.getLast(), data(1), null, true);
        assertEquals(List.of(1), ok.get());

        api.onSend = _ -> -3;
        CompletableFuture<List<Integer>> rejected = channel.send("投资者查询", MAPPER, api, FakeApi::send);
        assertTrue(rejected.isCompletedExceptionally());
        assertEquals(0, channel.inFlight());

        api.onSend = _ -> 0;
        CompletableFuture<List<Integer>> pending = channel.send("投资者查询", MAPPER, api, FakeApi::send);
        channel.onDisconnected("前置断开 0x2001 接收心跳超时");
        Throwable cause = assertThrows(Exception.class, pending::join).getCause();
        assertInstanceOf(CtpQueryChannel.FrontDisconnected.class, cause);
    }
}
