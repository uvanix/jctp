package com.github.jctp.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.jctp.server.FakeBrokers;
import com.github.jctp.server.Json;
import com.github.jctp.server.ServerConfig;
import com.github.jctp.server.gateway.Gateway;
import com.github.jctp.server.gateway.SessionManager;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Product;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApiServerTest {

    private static final ServerConfig.Counter COUNTER =
        new ServerConfig.Counter("sim", "模拟柜台", "9999", "tcp://td", "tcp://md", null, null, null);

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger created = new AtomicInteger();
    private FakeBrokers.Td td;
    private FakeBrokers.Md md;
    private SessionManager sessions;
    private Javalin app;
    private String base;

    @BeforeEach
    void start() {
        td = new FakeBrokers.Td(
            FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE"),
            FakeBrokers.future("rb2605", "螺纹钢2605", "rb", "SHFE"),
            FakeBrokers.option("cu2601C80000", "cu_o", "cu2601", DataType.OptionsType.Call, 80000));
        md = new FakeBrokers.Md();
        ServerConfig config = new ServerConfig(0, null, null, null, List.of(COUNTER));
        sessions = new SessionManager(config, (counter, userId, password, apiVersion) -> {
            created.incrementAndGet();
            return new Gateway(counter, userId, password, apiVersion, td, md);
        });
        app = ApiServer.create(config, sessions).start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
        sessions.close();
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String method, String path, String token, Object body) throws Exception {
        HttpResponse<String> response = send(method, path, token, body);
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
    }

    /**
     * 发起登录并轮询至结束，返回最终状态
     */
    private JsonNode loginStatus(ApiServer.LoginRequest request) throws Exception {
        JsonNode status = ok("POST", "/api/login", null, request);
        String loginId = status.get("loginId").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (status.get("state").asText().equals("connecting")) {
            assertTrue(System.nanoTime() < deadline, "登录超时 " + status);
            Thread.sleep(20);
            status = ok("GET", "/api/login/" + loginId, null, null);
        }
        return status;
    }

    private String login(String password) throws Exception {
        JsonNode status = loginStatus(new ApiServer.LoginRequest("sim", "000001", password, "6.5.1"));
        assertEquals("done", status.get("state").asText(), status.toString());
        return status.get("session").get("token").asText();
    }

    @Test
    void loginRequiredAndSharedGateway() throws Exception {
        JsonNode counters = ok("GET", "/api/counters", null, null);
        assertEquals("sim", counters.get(0).get("id").asText());
        assertFalse(counters.get(0).has("brokerId"), "柜台列表不应暴露前置地址等配置");
        JsonNode versions = ok("GET", "/api/versions", null, null);
        assertEquals("6.3.15", versions.get(0).asText());
        assertEquals("6.5.1", versions.get(1).asText());

        assertEquals(401, send("GET", "/api/products", null, null).statusCode());
        assertEquals(401, send("GET", "/api/products", "bad-token", null).statusCode());

        JsonNode first = loginStatus(new ApiServer.LoginRequest("sim", "000001", "pwd", "6.5.1"));
        assertEquals("done", first.get("state").asText(), first.toString());
        assertEquals("就绪", first.get("stage").asText());
        String token = first.get("session").get("token").asText();
        // 结果只交付一次
        assertEquals(400, send("GET", "/api/login/" + first.get("loginId").asText(), null, null).statusCode());
        JsonNode session = ok("GET", "/api/session", token, null);
        assertEquals("000001", session.get("userId").asText());
        assertEquals("2026-11-16", session.get("tradingDay").asText());
        assertEquals("6.5.1", session.get("apiVersion").asText());

        // 同一账号再次登录复用网关；密码不符拒绝
        String second = login("pwd");
        assertNotEquals(token, second);
        assertEquals(1, created.get());
        HttpResponse<String> wrong = send("POST", "/api/login", null, new ApiServer.LoginRequest("sim", "000001", "x", "6.5.1"));
        assertEquals(400, wrong.statusCode());
        assertTrue(wrong.body().contains("密码错误"));
        // 已登录的账号换版本拒绝；未指定版本即 6.3.15
        HttpResponse<String> otherVersion = send("POST", "/api/login", null, new ApiServer.LoginRequest("sim", "000001", "pwd", null));
        assertEquals(400, otherVersion.statusCode());
        assertTrue(otherVersion.body().contains("v6.5.1"), otherVersion.body());

        // 最后一个会话登出才断开 CTP
        ok("POST", "/api/logout", token, null);
        assertEquals(0, td.disconnects.get());
        assertEquals(401, send("GET", "/api/session", token, null).statusCode());
        ok("POST", "/api/logout", second, null);
        assertEquals(1, td.disconnects.get());
        assertEquals(1, md.disconnects.get());
    }

    /**
     * 连接耗时较长时登录请求立即返回；连接中同一账号再次登录复用同一次连接。
     */
    @Test
    void loginWhileConnectingSharesConnection() throws Exception {
        td.connectGate = new CountDownLatch(1);
        ApiServer.LoginRequest request = new ApiServer.LoginRequest("sim", "000001", "pwd", "6.5.1");
        JsonNode first = ok("POST", "/api/login", null, request);
        assertEquals("connecting", first.get("state").asText());
        JsonNode second = ok("POST", "/api/login", null, request);
        assertEquals("connecting", second.get("state").asText());
        // 连接中密码不符同样拒绝
        assertEquals(400, send("POST", "/api/login", null, new ApiServer.LoginRequest("sim", "000001", "x", "6.5.1")).statusCode());
        assertEquals("connecting", ok("GET", "/api/login/" + first.get("loginId").asText(), null, null).get("state").asText());

        td.connectGate.countDown();
        String a = waitDone(first.get("loginId").asText());
        String b = waitDone(second.get("loginId").asText());
        assertNotEquals(a, b);
        assertEquals(1, created.get());
        assertEquals(1, td.connects.get());
    }

    private String waitDone(String loginId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            JsonNode status = ok("GET", "/api/login/" + loginId, null, null);
            if (status.get("state").asText().equals("done")) {
                return status.get("session").get("token").asText();
            }
            assertEquals("connecting", status.get("state").asText(), status.toString());
            assertTrue(System.nanoTime() < deadline, "登录超时");
            Thread.sleep(20);
        }
    }

    @Test
    void loginFailureIsReported() throws Exception {
        td.connectError = new IllegalStateException("CTP 登录失败 [3]不合法的登录");
        JsonNode failed = loginStatus(new ApiServer.LoginRequest("sim", "000001", "pwd", null));
        assertEquals("failed", failed.get("state").asText());
        assertTrue(failed.get("error").asText().contains("不合法的登录"), failed.toString());
        // 产品、合约须都有结果才继续，否则登录失败并释放连接
        td.connectError = null;
        Map<String, Product> products = Map.copyOf(td.products);
        td.products.clear();
        JsonNode noProducts = loginStatus(new ApiServer.LoginRequest("sim", "000001", "pwd", null));
        assertEquals("failed", noProducts.get("state").asText());
        assertTrue(noProducts.get("error").asText().contains("产品查询无结果"), noProducts.toString());
        assertEquals(1, td.disconnects.get());
        assertEquals(0, md.connects.get(), "行情最后连接，产品失败时不应已连行情");
        td.products.putAll(products);

        // 失败后可以重新登录
        assertEquals("done", loginStatus(new ApiServer.LoginRequest("sim", "000001", "pwd", null)).get("state").asText());
        assertEquals(400, send("POST", "/api/login", null, new ApiServer.LoginRequest("nope", "000001", "pwd", null)).statusCode());
        HttpResponse<String> badVersion = send("POST", "/api/login", null, new ApiServer.LoginRequest("sim", "000001", "pwd", "6.7.5"));
        assertEquals(400, badVersion.statusCode());
        assertTrue(badVersion.body().contains("不支持的 CTP 版本"), badVersion.body());
    }

    /**
     * 合约在登录后由后台按品种加载（期权除外），等后台加载结束
     */
    private JsonNode awaitProductsLoaded(String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            JsonNode products = ok("GET", "/api/products", token, null);
            boolean loaded = true;
            for (JsonNode p : products) {
                loaded &= !p.get("loading").asBoolean();
            }
            if (loaded) {
                return products;
            }
            assertTrue(System.nanoTime() < deadline, "合约加载超时 " + products);
            Thread.sleep(20);
        }
    }

    @Test
    void settlement() throws Exception {
        String token = login("pwd");
        assertEquals(204, send("GET", "/api/settlement", token, null).statusCode(), "没有结算单");

        td.settlement = SettlementInfo.builder().tradingDay(LocalDate.of(2026, 11, 13)).settlementID(1)
            .brokerID("9999").investorID("000001").content("交易结算单(盯市)\n期初结存 1000000.00").build();
        JsonNode latest = ok("GET", "/api/settlement", token, null);
        assertEquals("2026-11-13", latest.get("tradingDay").asText());
        assertTrue(latest.get("content").asText().startsWith("交易结算单"));
        ok("GET", "/api/settlement?tradingDay=2026-11-13", token, null);
        assertEquals(List.of(Optional.empty(), Optional.empty(), Optional.of(LocalDate.of(2026, 11, 13))), td.settlementQueries);

        assertEquals(400, send("GET", "/api/settlement?tradingDay=20261113", token, null).statusCode());
        assertEquals(401, send("GET", "/api/settlement", null, null).statusCode());
    }

    @Test
    void instrumentsQuotesAndBars() throws Exception {
        String token = login("pwd");
        JsonNode products = awaitProductsLoaded(token);
        assertEquals(2, products.size());

        JsonNode rb = ok("GET", "/api/instruments?productId=rb", token, null);
        assertEquals(List.of("rb2601", "rb2605"), List.of(rb.get(0).get("instrumentID").asText(), rb.get(1).get("instrumentID").asText()));
        // 期权不在后台加载，按完整代码搜索时即时查询
        assertEquals(0, ok("GET", "/api/instruments?q=cu", token, null).size());
        assertEquals(1, ok("GET", "/api/instruments?q=cu2601C80000", token, null).size());
        assertEquals("螺纹钢2601", ok("GET", "/api/instruments/rb2601", token, null).get("instrumentName").asText());
        assertEquals(400, send("GET", "/api/instruments/xx", token, null).statusCode());
        assertEquals(0.12, ok("GET", "/api/instruments/rb2601/rates", token, null)
            .get("margin").get("longMarginRatioByMoney").asDouble());

        // 请求 K 线即订阅；之后的 tick 合成 K 线
        assertEquals(0, ok("GET", "/api/bars/rb2601?period=M1", token, null).size());
        assertTrue(md.subscribed.contains("rb2601"));
        long t = java.time.LocalDateTime.of(2026, 11, 16, 9, 0, 5).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        md.push(FakeBrokers.tick("rb2601", t, 3100, 0));
        md.push(FakeBrokers.tick("rb2601", t + 1000, 3102, 7));
        JsonNode bars = ok("GET", "/api/bars/rb2601?period=M5", token, null);
        assertEquals(1, bars.size());
        assertEquals(3102, bars.get(0).get("high").asDouble());
        assertEquals(7, bars.get(0).get("volume").asLong());

        JsonNode quotes = ok("GET", "/api/quotes?ids=rb2601,rb2605", token, null);
        assertEquals(1, quotes.size());
        assertEquals(3102, quotes.get(0).get("lastPrice").asDouble());
        // NaN（无效价格）序列化为 null
        assertTrue(quotes.get(0).get("settlementPrice").isNull());
    }

    @Test
    void orderLifecyclePushedOverWebSocket() throws Exception {
        String token = login("pwd");
        LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
            .buildAsync(URI.create(base.replace("http", "ws") + "/ws?token=" + token), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        try {
                            messages.add(Json.MAPPER.readTree(buffer.toString()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        buffer.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);

        ws.sendText("{\"type\":\"subscribe\",\"instruments\":[\"rb2601\"]}", true).get();
        ws.sendText("{\"type\":\"ping\"}", true).get();
        assertEquals("pong", next(messages, "pong").get("type").asText());
        md.push(FakeBrokers.tick("rb2601", System.currentTimeMillis(), 3100, 1));
        md.push(FakeBrokers.tick("rb2605", System.currentTimeMillis(), 3200, 1));
        assertEquals("rb2601", next(messages, "tick").get("data").get("instrumentID").asText());

        JsonNode order = ok("POST", "/api/orders", token, Json.MAPPER.readTree("""
            {"instrumentId":"rb2601","direction":"Buy","offsetFlag":"Open","price":3000,"volume":2}"""));
        String orderId = order.get("frontID").asInt() + "_" + order.get("sessionID").asInt() + "_" + order.get("orderRef").asText();
        assertEquals("SHFE", order.get("exchangeID").asText());
        assertEquals("GFD", order.get("timeCondition").asText());
        assertEquals("NoTradeQueueing", next(messages, "order").get("data").get("status").asText());

        ok("POST", "/api/orders/" + orderId + "/cancel", token, null);
        assertEquals("Canceled", next(messages, "order").get("data").get("status").asText());
        JsonNode orders = ok("GET", "/api/orders", token, null);
        assertEquals("Canceled", orders.get(0).get("status").asText());

        td.fill(Trade.builder().instrumentID("rb2601").exchangeID("SHFE").tradeID("T1").direction(DataType.Direction.Buy)
            .offsetFlag(DataType.OffsetFlag.Open).price(3000).volume(1).tradeTime(LocalTime.of(9, 31)).build());
        assertEquals("T1", next(messages, "trade").get("data").get("tradeID").asText());
        assertEquals(1, ok("GET", "/api/trades", token, null).size());

        assertEquals(400, send("POST", "/api/orders", token, Json.MAPPER.readTree("""
            {"instrumentId":"rb2601","direction":"Buy","offsetFlag":"Open","price":0,"volume":1}""")).statusCode());
        assertEquals(1_000_000, ok("GET", "/api/account", token, null).get("balance").asDouble());
        assertEquals(0, ok("GET", "/api/positions", token, null).size());

        ok("POST", "/api/logout", token, null);
        assertEquals("closed", next(messages, "closed").get("type").asText());
        ws.abort();
    }

    /**
     * 跳过不关心的消息类型，直到出现指定类型。
     */
    private static JsonNode next(LinkedBlockingQueue<JsonNode> messages, String type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && type.equals(message.get("type").asText())) {
                return message;
            }
        }
        fail("等待 WebSocket 消息超时 " + type);
        return null;
    }
}
