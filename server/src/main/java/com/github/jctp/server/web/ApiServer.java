package com.github.jctp.server.web;

import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.Json;
import com.github.jctp.server.ServerConfig;
import com.github.jctp.server.gateway.Gateway;
import com.github.jctp.server.gateway.OrderRequest;
import com.github.jctp.server.gateway.SessionManager;
import com.github.jctp.server.market.BarPeriod;
import com.github.jctp.struct.MarketData;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST 接口（/api）与推送（/ws）。除柜台列表、API 版本列表与登录外，请求须带 {@code Authorization: Bearer <token>}，
 * WebSocket 通过 {@code /ws?token=<token>} 鉴权。错误统一返回 {@code {"error": "..."}}。
 */
@Slf4j
public final class ApiServer {

    private static final String SESSION = "session";

    /**
     * @param apiVersion CTP API 版本，取自 /api/versions；为空时 6.3.15
     */
    public record LoginRequest(String counterId, String userId, String password, String apiVersion) {
    }

    public record SessionInfo(String token, String userId, String counterId, String counterName, boolean simulated,
                              String apiVersion, LocalDate tradingDay) {
        static SessionInfo of(SessionManager.Session s) {
            Gateway g = s.gateway();
            return new SessionInfo(s.token(), g.getUserId(), g.getCounter().id(), g.getCounter().name(),
                g.getCounter().simulated(), g.getApiVersion().number, g.tradingDay());
        }
    }

    public record CounterView(String id, String name, boolean simulated) {
    }

    /**
     * 登录进度。前端拿到 loginId 后轮询 /api/login/{loginId}，直至 state 为 done（附 session）或 failed（附 error）。
     *
     * @param state       connecting | done | failed
     * @param stage       当前阶段，如"登录"、"加载合约"
     * @param instruments 已加载的合约数
     */
    public record LoginStatus(String loginId, String state, String stage, int instruments, long elapsedMillis,
                              String error, SessionInfo session) {
        static LoginStatus of(SessionManager.LoginTask task) {
            Gateway.Progress p = task.gateway().progress();
            long elapsed = task.elapsed().toMillis();
            CompletableFuture<SessionManager.Session> f = task.session();
            return switch (f.state()) {
                case SUCCESS -> new LoginStatus(task.id(), "done", p.stage(), p.instruments(), elapsed, null,
                    SessionInfo.of(f.resultNow()));
                case FAILED -> new LoginStatus(task.id(), "failed", p.stage(), p.instruments(), elapsed,
                    Optional.ofNullable(f.exceptionNow().getMessage()).orElse(f.exceptionNow().getClass().getSimpleName()), null);
                case CANCELLED -> new LoginStatus(task.id(), "failed", p.stage(), p.instruments(), elapsed, "服务端已关闭", null);
                case RUNNING -> new LoginStatus(task.id(), "connecting", p.stage(), p.instruments(), elapsed, null, null);
            };
        }
    }

    static final class Unauthorized extends RuntimeException {
        Unauthorized(String message) {
            super(message);
        }
    }

    /**
     * 打包进 jar 的前端目录（server/pom.xml 把 web/dist 打入此处）
     */
    private static final String CLASSPATH_WEB = "/web";

    private ApiServer() {
    }

    public static Javalin create(ServerConfig config, SessionManager sessions) {
        Map<String, WsClient> clients = new ConcurrentHashMap<>();
        return Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, true));
            // 前端：优先配置的 webDir（开发时或单独替换前端），否则用打包进 jar 的 /web
            Path web = config.webDir() == null ? null : Path.of(config.webDir()).toAbsolutePath().normalize();
            if (web != null && Files.isRegularFile(web.resolve("index.html"))) {
                cfg.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = web.toString();
                    files.location = Location.EXTERNAL;
                });
                cfg.spaRoot.addFile("/", web.resolve("index.html").toString(), Location.EXTERNAL);
                log.info("托管前端 {}", web);
            } else if (ApiServer.class.getResource(CLASSPATH_WEB + "/index.html") != null) {
                cfg.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = CLASSPATH_WEB;
                    files.location = Location.CLASSPATH;
                });
                cfg.spaRoot.addFile("/", CLASSPATH_WEB + "/index.html", Location.CLASSPATH);
                log.info("托管前端（jar 内置）");
            }

            cfg.routes.exception(Unauthorized.class, (e, ctx) -> ctx.status(401).json(error(e)));
            cfg.routes.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(400).json(error(e)));
            cfg.routes.exception(IllegalStateException.class, (e, ctx) -> ctx.status(400).json(error(e)));
            cfg.routes.exception(NullPointerException.class, (e, ctx) -> ctx.status(400).json(Map.of("error", "缺少参数 " + e.getMessage())));
            cfg.routes.exception(Exception.class, (e, ctx) -> {
                log.error("{} {} 处理失败", ctx.method(), ctx.path(), e);
                ctx.status(500).json(error(e));
            });

            cfg.routes.before("/api/*", ctx -> {
                String path = ctx.path();
                if (path.equals("/api/counters") || path.equals("/api/versions") || path.equals("/api/login")
                    || path.startsWith("/api/login/")) {
                    return;
                }
                String header = Optional.ofNullable(ctx.header("Authorization")).orElse("");
                String token = header.startsWith("Bearer ") ? header.substring(7) : null;
                SessionManager.Session session = sessions.session(token)
                    .orElseThrow(() -> new Unauthorized("未登录或会话已失效"));
                ctx.attribute(SESSION, session);
            });

            cfg.routes.get("/api/counters", ctx -> ctx.json(sessions.counters().stream()
                .map(c -> new CounterView(c.id(), c.name(), c.simulated())).toList()));
            // 首个为缺省版本
            cfg.routes.get("/api/versions", ctx -> ctx.json(Arrays.stream(CtpVersion.values()).map(v -> v.number).toList()));
            // 参数错误立即返回 400；连接柜台在后台进行，结果经 /api/login/{loginId} 轮询
            cfg.routes.post("/api/login", ctx -> {
                LoginRequest r = ctx.bodyAsClass(LoginRequest.class);
                ctx.json(loginStatus(sessions, sessions.startLogin(r.counterId(), r.userId(), r.password(), r.apiVersion())));
            });
            cfg.routes.get("/api/login/{loginId}", ctx -> ctx.json(loginStatus(sessions, sessions.loginTask(ctx.pathParam("loginId"))
                .orElseThrow(() -> new IllegalArgumentException("登录任务不存在或已过期，请重新登录")))));
            cfg.routes.post("/api/logout", ctx -> {
                sessions.logout(session(ctx).token());
                ctx.json(Map.of());
            });
            cfg.routes.get("/api/session", ctx -> ctx.json(SessionInfo.of(session(ctx))));

            cfg.routes.get("/api/products", ctx -> ctx.json(gateway(ctx).products()));
            cfg.routes.get("/api/instruments", ctx -> {
                String productId = ctx.queryParam("productId");
                String q = ctx.queryParam("q");
                if (productId != null) {
                    ctx.json(gateway(ctx).instruments(productId));
                } else if (q != null) {
                    ctx.json(gateway(ctx).search(q));
                } else {
                    throw new IllegalArgumentException("须指定 productId 或 q");
                }
            });
            cfg.routes.get("/api/instruments/{id}", ctx -> ctx.json(gateway(ctx).instrument(ctx.pathParam("id"))));
            cfg.routes.get("/api/instruments/{id}/rates", ctx -> ctx.json(gateway(ctx).rates(ctx.pathParam("id"))));
            cfg.routes.get("/api/quotes", ctx -> {
                Gateway gateway = gateway(ctx);
                List<String> ids = Arrays.stream(Optional.ofNullable(ctx.queryParam("ids")).orElse("").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
                gateway.subscribe(ids);
                List<MarketData> quotes = ids.stream().map(gateway::quote).flatMap(Optional::stream).toList();
                ctx.json(quotes);
            });
            cfg.routes.get("/api/bars/{id}", ctx -> ctx.json(gateway(ctx).bars(ctx.pathParam("id"),
                BarPeriod.parse(Optional.ofNullable(ctx.queryParam("period")).orElse("M1")))));

            cfg.routes.get("/api/account", ctx -> ctx.json(gateway(ctx).account()));
            // tradingDay 为 yyyy-MM-dd，缺省为最近一次结算单；没有结算单时返回 204
            cfg.routes.get("/api/settlement", ctx -> {
                String day = ctx.queryParam("tradingDay");
                LocalDate tradingDay;
                try {
                    tradingDay = day == null || day.isBlank() ? null : LocalDate.parse(day);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("交易日格式应为 yyyy-MM-dd: " + day);
                }
                gateway(ctx).settlement(tradingDay).ifPresentOrElse(ctx::json, () -> ctx.status(204));
            });
            cfg.routes.get("/api/positions", ctx -> ctx.json(gateway(ctx).positions()));
            cfg.routes.get("/api/orders", ctx -> ctx.json(gateway(ctx).orders()));
            cfg.routes.get("/api/trades", ctx -> ctx.json(gateway(ctx).trades()));
            cfg.routes.post("/api/orders", ctx -> ctx.json(gateway(ctx).submit(ctx.bodyAsClass(OrderRequest.class))));
            cfg.routes.post("/api/orders/{orderId}/cancel", ctx -> ctx.json(gateway(ctx).cancel(ctx.pathParam("orderId"))));

            cfg.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> {
                    Optional<SessionManager.Session> session = sessions.session(ctx.queryParam("token"));
                    if (session.isEmpty()) {
                        ctx.closeSession(4401, "unauthorized");
                        return;
                    }
                    clients.put(ctx.sessionId(), new WsClient(ctx, session.get().gateway()));
                });
                ws.onMessage(ctx -> {
                    // 心跳等消息使会话保持活跃，页面开着就不会因空闲被登出
                    sessions.session(ctx.queryParam("token"));
                    WsClient client = clients.get(ctx.sessionId());
                    if (client != null) {
                        client.onMessage(ctx.message());
                    }
                });
                ws.onClose(ctx -> Optional.ofNullable(clients.remove(ctx.sessionId())).ifPresent(WsClient::close));
                ws.onError(ctx -> Optional.ofNullable(clients.remove(ctx.sessionId())).ifPresent(WsClient::close));
            });
        });
    }

    /**
     * 按同一份快照决定返回内容与是否删除任务：返回了结果（done / failed）的任务即删除，结果只交付一次。
     */
    private static LoginStatus loginStatus(SessionManager sessions, SessionManager.LoginTask task) {
        LoginStatus status = LoginStatus.of(task);
        if (!status.state().equals("connecting")) {
            sessions.claimLoginTask(task.id());
        }
        return status;
    }

    private static Map<String, String> error(Exception e) {
        return Map.of("error", Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
    }

    private static SessionManager.Session session(Context ctx) {
        SessionManager.Session session = ctx.attribute(SESSION);
        if (session == null) {
            throw new Unauthorized("未登录或会话已失效");
        }
        return session;
    }

    private static Gateway gateway(Context ctx) {
        return session(ctx).gateway();
    }
}
