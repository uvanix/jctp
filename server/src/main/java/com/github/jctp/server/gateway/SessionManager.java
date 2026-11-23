package com.github.jctp.server.gateway;

import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.ServerConfig;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网页会话与网关。登录成功签发随机令牌；同一柜台同一账号的多个会话共用一个网关，最后一个会话登出时断开 CTP。
 * CTP API 版本由登录时选择，共用网关的会话版本须一致。
 * <p>
 * 盘中连接柜台、加载合约可能需要数十秒，登录因此是异步的：{@link #startLogin} 立即返回登录任务，
 * 连接在后台虚拟线程进行，前端用 {@link #loginTask} 轮询进度。同一账号连接中再次登录会等待同一次连接。
 * <p>
 * 页面关闭、刷新而未登出的会话没有人再使用，空闲超时后自动登出，网关随最后一个会话释放。
 */
@Slf4j
public final class SessionManager implements AutoCloseable {

    /**
     * 登录结束后前端须在此时间内取走结果，否则视为放弃（页面已关闭），释放签发的会话
     */
    private static final Duration UNCLAIMED_LOGIN_TTL = Duration.ofMinutes(2);
    /**
     * 会话无任何请求（含 WebSocket 心跳）超过此时间即登出。打开的页面每 15 秒有心跳与资金刷新
     */
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(10);

    public static final class Session {

        private final String token;
        private final Gateway gateway;
        private final Instant createdAt = Instant.now();
        private volatile Instant lastActive = createdAt;

        Session(String token, Gateway gateway) {
            this.token = token;
            this.gateway = gateway;
        }

        public String token() {
            return token;
        }

        public Gateway gateway() {
            return gateway;
        }

        public Instant createdAt() {
            return createdAt;
        }
    }

    /**
     * 一次网页登录。session 在连接成功后完成，失败时其异常即失败原因。
     */
    public static final class LoginTask {

        private final String id;
        private final Gateway gateway;
        private final CompletableFuture<Session> session;
        private final Instant createdAt = Instant.now();
        private volatile Instant finishedAt;

        LoginTask(String id, Gateway gateway, CompletableFuture<Session> session) {
            this.id = id;
            this.gateway = gateway;
            this.session = session.whenComplete((_, _) -> finishedAt = Instant.now());
        }

        public String id() {
            return id;
        }

        public Gateway gateway() {
            return gateway;
        }

        public CompletableFuture<Session> session() {
            return session;
        }

        public Duration elapsed() {
            return Duration.between(createdAt, finishedAt == null ? Instant.now() : finishedAt);
        }
    }

    /**
     * 连接中的网关
     */
    private record Connecting(Gateway gateway, CompletableFuture<Gateway> ready) {
    }

    private final ServerConfig config;
    private final GatewayFactory factory;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Gateway> gateways = new ConcurrentHashMap<>();
    private final Map<String, Connecting> connecting = new ConcurrentHashMap<>();
    private final Map<String, LoginTask> loginTasks = new ConcurrentHashMap<>();
    /**
     * 按账号串行化网关的创建、登记与释放
     */
    private final Map<String, Object> loginLocks = new ConcurrentHashMap<>();
    private final Duration sessionIdleTimeout;
    private final ScheduledExecutorService sweeper =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("session-sweeper").factory());

    public SessionManager(ServerConfig config, GatewayFactory factory) {
        this(config, factory, SESSION_IDLE_TIMEOUT);
    }

    /**
     * @param sessionIdleTimeout 会话空闲超时
     */
    public SessionManager(ServerConfig config, GatewayFactory factory, Duration sessionIdleTimeout) {
        this.config = config;
        this.factory = factory;
        this.sessionIdleTimeout = sessionIdleTimeout;
        long period = Math.clamp(sessionIdleTimeout.dividedBy(4).toMillis(), 10, Duration.ofMinutes(1).toMillis());
        sweeper.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * 校验参数并发起登录，不等待连接完成。
     *
     * @param apiVersion CTP API 版本，如 6.5.1；为空时 6.3.15
     * @throws IllegalArgumentException 参数错误、密码与已登录（或连接中）的会话不符、API 版本不一致
     */
    public LoginTask startLogin(String counterId, String userId, String password, String apiVersion) {
        if (userId == null || userId.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请输入账号和密码");
        }
        sweepLoginTasks();
        ServerConfig.Counter counter = config.counter(counterId);
        CtpVersion version = CtpVersion.parse(apiVersion);
        String user = userId.trim();
        String key = counter.id() + "/" + user;
        Gateway gateway;
        CompletableFuture<Gateway> ready;
        synchronized (lock(key)) {
            Connecting pending = connecting.get(key);
            gateway = pending != null ? pending.gateway() : gateways.get(key);
            if (gateway != null) {
                if (!gateway.passwordMatches(password)) {
                    throw new IllegalArgumentException("密码错误");
                }
                // 本地模拟柜台不用 CTP 原生库，版本无关
                if (!counter.simulated() && gateway.getApiVersion() != version) {
                    throw new IllegalArgumentException("该账号已以 API %s 登录，请选择相同版本".formatted(gateway.getApiVersion()));
                }
                ready = pending != null ? pending.ready() : CompletableFuture.completedFuture(gateway);
            } else {
                gateway = factory.create(counter, user, password, version);
                ready = connect(key, gateway);
            }
        }
        log.info("{} 发起登录 {} API {}", key, counter.name(), version);
        LoginTask task = new LoginTask(newToken(), gateway, ready.thenApply(g -> newSession(key, g)));
        loginTasks.put(task.id(), task);
        return task;
    }

    public Optional<LoginTask> loginTask(String id) {
        sweepLoginTasks();
        return id == null ? Optional.empty() : Optional.ofNullable(loginTasks.get(id));
    }

    /**
     * 登录结果（成功或失败）已交给前端，删除任务，令牌只交付一次。
     */
    public void claimLoginTask(String id) {
        loginTasks.remove(id);
    }

    /**
     * 查找会话并记为活跃
     */
    public Optional<Session> session(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session != null) {
            session.lastActive = Instant.now();
        }
        return Optional.ofNullable(session);
    }

    public void logout(String token) {
        Session session = sessions.remove(token);
        if (session == null) {
            return;
        }
        Gateway gateway = session.gateway();
        String key = key(gateway);
        synchronized (lock(key)) {
            boolean inUse = sessions.values().stream().anyMatch(s -> s.gateway() == gateway);
            if (!inUse && gateways.remove(key, gateway)) {
                gateway.close();
            }
        }
        log.info("{} 登出，当前会话 {}", key, sessions.size());
    }

    public List<ServerConfig.Counter> counters() {
        return config.counters();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        // 连接中的网关在连接线程结束时自行释放
        connecting.values().forEach(c -> c.ready().cancel(false));
        gateways.values().forEach(Gateway::close);
        gateways.clear();
        sessions.clear();
        loginTasks.clear();
    }

    /**
     * 在虚拟线程上连接，HTTP 请求不随连接耗时挂起。
     */
    private CompletableFuture<Gateway> connect(String key, Gateway gateway) {
        CompletableFuture<Gateway> ready = new CompletableFuture<>();
        connecting.put(key, new Connecting(gateway, ready));
        Thread.ofVirtual().name("login-" + key).start(() -> {
            try {
                gateway.connect();
            } catch (RuntimeException e) {
                synchronized (lock(key)) {
                    connecting.remove(key);
                }
                log.warn("{} 连接失败 {}", key, e.getMessage());
                ready.completeExceptionally(e);
                return;
            }
            synchronized (lock(key)) {
                connecting.remove(key);
                if (ready.isCancelled()) {
                    gateway.close();
                    return;
                }
                gateways.put(key, gateway);
            }
            ready.complete(gateway);
        });
        return ready;
    }

    private Session newSession(String key, Gateway gateway) {
        Session session = new Session(newToken(), gateway);
        sessions.put(session.token(), session);
        log.info("{} 登录成功，当前会话 {}", key, sessions.size());
        return session;
    }

    private void sweep() {
        try {
            sweepLoginTasks();
            sweepIdleSessions();
        } catch (RuntimeException e) {
            log.warn("清理会话失败", e);
        }
    }

    /**
     * 登出长时间无请求的会话：页面已关闭或刷新后换了新会话。
     */
    private void sweepIdleSessions() {
        Instant expire = Instant.now().minus(sessionIdleTimeout);
        sessions.values().stream().filter(s -> s.lastActive.isBefore(expire)).toList().forEach(s -> {
            log.info("{} 会话空闲超过 {}，自动登出", key(s.gateway()), sessionIdleTimeout);
            logout(s.token());
        });
    }

    /**
     * 清理结束后长时间无人取走的登录任务：页面已关闭，签发的会话一并登出，避免网关无人使用却一直连着。
     */
    private void sweepLoginTasks() {
        Instant expire = Instant.now().minus(UNCLAIMED_LOGIN_TTL);
        loginTasks.values().removeIf(task -> {
            Instant finished = task.finishedAt;
            if (finished == null || finished.isAfter(expire)) {
                return false;
            }
            if (task.session().state() == Future.State.SUCCESS) {
                Session session = task.session().resultNow();
                log.info("{} 登录结果无人取走，释放会话", key(task.gateway()));
                logout(session.token());
            }
            return true;
        });
    }

    private Object lock(String key) {
        return loginLocks.computeIfAbsent(key, _ -> new Object());
    }

    private static String key(Gateway gateway) {
        return gateway.getCounter().id() + "/" + gateway.getUserId();
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
