package com.github.jctp.broker;

import com.github.jctp.CtpConfig;
import com.github.jctp.struct.Account;
import com.github.jctp.struct.CommissionRate;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.MarginRate;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Position;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimNow 联调测试，需要网络与 SimNow 账号：
 * <pre>
 * SIMNOW_USER_ID   投资者代码（必填，未设置时跳过）
 * SIMNOW_PASSWORD  密码（必填）
 * SIMNOW_ENV       GROUP1/GROUP2/GROUP3/SEVEN_BY_24，缺省 GROUP1；非交易时段可用 SEVEN_BY_24
 * SIMNOW_PRODUCT   用于行情与报单测试的品种，缺省 rb
 * </pre>
 * 运行（在项目根目录）：
 * <pre>
 * Git Bash / Linux:  SIMNOW_USER_ID=xxx SIMNOW_PASSWORD=yyy mvn test -Dtest=SimNowTest
 * PowerShell:        $env:SIMNOW_USER_ID="xxx"; $env:SIMNOW_PASSWORD="yyy"; mvn test "-Dtest=SimNowTest"
 * cmd:               set "SIMNOW_USER_ID=xxx" &amp;&amp; set "SIMNOW_PASSWORD=yyy" &amp;&amp; mvn test -Dtest=SimNowTest
 * IntelliJ IDEA:     运行配置 → Environment variables 填 SIMNOW_USER_ID=xxx;SIMNOW_PASSWORD=yyy
 * </pre>
 * <p>
 * 收不到行情（非交易时段）或报单被拒（如休市）时相关用例以 aborted 结束而不是失败。
 * 报单用例以跌停价买入 1 手后立即撤单，一般不会成交。
 */
@Slf4j
@Tag("simnow")
@EnabledIfEnvironmentVariable(named = "SIMNOW_USER_ID", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimNowTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, MarketData> ticks = new ConcurrentHashMap<>();

    private CtpTdBroker td;
    private CtpMdBroker md;
    private List<Instrument> futures;

    @BeforeAll
    void connect() {
        CtpConfig.SimNow env = CtpConfig.SimNow.valueOf(env("SIMNOW_ENV", "GROUP1"));
        CtpConfig config = CtpConfig.simnow(env, System.getenv("SIMNOW_USER_ID"), System.getenv("SIMNOW_PASSWORD"))
            .toBuilder()
            .flowPath(Path.of("target", "ctp-flow"))
            .build();
        log.info("SimNow {} 交易 {} 行情 {}", env, env.tdFront, env.mdFront);

        td = new CtpTdBrokerImpl(config);
        td.setOrderListener(o -> orders.put(o.orderId(), o));
        td.connect();

        md = new CtpMdBrokerImpl(config);
        md.setMarketDataListener(t -> ticks.put(t.instrumentID(), t));
        md.connect();

        String product = env("SIMNOW_PRODUCT", "au");
        futures = td.instruments().stream()
            .filter(i -> i.productClass() == DataType.ProductClass.Futures)
            .filter(i -> product.equals(i.productID()))
            .filter(Instrument::isTrading)
            .sorted(Comparator.comparing(Instrument::instrumentID))
            .toList();
    }

    @AfterAll
    void disconnect() {
        if (md != null) {
            md.disconnect();
        }
        if (td != null) {
            td.disconnect();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void loginLoadsInstruments() {
        assertNotNull(td.tradingDay());
        assertFalse(td.instruments().isEmpty());
        assertFalse(futures.isEmpty(), "未找到可交易的期货合约");
        Instrument instrument = futures.getFirst();
        assertEquals(instrument, td.instrument(instrument.instrumentID()).orElseThrow());
        assertTrue(instrument.priceTick() > 0);
        assertTrue(instrument.volumeMultiple() > 0);
        assertFalse(instrument.exchangeID().isEmpty());
        log.info("交易日 {} 合约 {} 个，{}", td.tradingDay(), td.instruments().size(), instrument);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void queryAccount() {
        Account account = td.queryAccount();
        log.info("资金 {}", account);
        assertFalse(account.accountID().isEmpty());
        assertEquals(td.tradingDay(), account.tradingDay());
        assertTrue(account.balance() >= 0);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void queryPositionsOrdersTrades() {
        List<Position> positions = td.queryPositions();
        positions.forEach(p -> log.info("持仓 {}", p));
        positions.forEach(p -> {
            assertNotNull(p.direction());
            assertNotNull(p.positionDate());
        });
        List<Order> queried = td.queryOrders();
        queried.forEach(o -> assertNotNull(o.status()));
        log.info("当日报单 {} 笔，成交 {} 笔", queried.size(), td.queryTrades().size());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void queryRates() {
        String instrumentId = futures.getFirst().instrumentID();
        Optional<CommissionRate> commission = td.queryCommissionRate(instrumentId);
        Optional<MarginRate> margin = td.queryMarginRate(instrumentId);
        log.info("手续费率 {}", commission);
        log.info("保证金率 {}", margin);
        assertTrue(commission.isPresent());
        assertTrue(margin.isPresent());
        assertEquals(instrumentId, margin.get().instrumentID());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void subscribeMarketData() {
        futures.forEach(i -> assertTrue(md.subscribe(i.instrumentID())));
        MarketData tick = awaitTick();
        log.info("行情 {}", tick);
        assertTrue(futures.stream().anyMatch(i -> i.instrumentID().equals(tick.instrumentID())));
        assertEquals(tick, md.lastMarketData(tick.instrumentID()).orElseThrow());
        assertNotNull(tick.tradingDay());
        assertTrue(tick.upperLimitPrice() >= tick.lowerLimitPrice());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void submitAndCancelOrder() {
        futures.forEach(i -> md.subscribe(i.instrumentID()));
        MarketData tick = awaitTick();
        Order submitted = td.submitOrder(Order.builder()
            .instrumentID(tick.instrumentID())
            .direction(DataType.Direction.Buy)
            .offsetFlag(DataType.OffsetFlag.Open)
            .limitPrice(tick.lowerLimitPrice())
            .volume(1)
            .build());
        log.info("报单 {}", submitted);
        assertEquals(DataType.OrderSubmitStatus.InsertSubmitted, submitted.submitStatus());
        assertEquals(td.instrument(tick.instrumentID()).orElseThrow().exchangeID(), submitted.exchangeID());

        Order accepted = awaitOrder(submitted.orderId(), o -> o.done() || o.status() == DataType.OrderStatus.NoTradeQueueing);
        log.info("报单回报 {}", accepted);
        Assumptions.assumeFalse(accepted.submitStatus() == DataType.OrderSubmitStatus.InsertRejected,
            () -> "报单被拒，可能处于非交易时段: " + accepted.statusMsg());
        assertFalse(accepted.orderSysID().isEmpty());
        assertEquals(accepted, td.order(submitted.orderId()).orElseThrow());

        td.cancelOrder(accepted);
        Order canceled = awaitOrder(submitted.orderId(), Order::done);
        log.info("撤单回报 {}", canceled);
        assertEquals(DataType.OrderStatus.Canceled, canceled.status());
        assertEquals(0, canceled.volumeTraded());
    }

    private MarketData awaitTick() {
        MarketData tick = await(() -> ticks.values().stream().findFirst());
        Assumptions.assumeTrue(tick != null, "未收到行情，可能处于非交易时段");
        return tick;
    }

    private Order awaitOrder(String orderId, Predicate<Order> condition) {
        Order order = await(() -> Optional.ofNullable(orders.get(orderId)).filter(condition));
        assertNotNull(order, () -> "报单状态等待超时 " + td.order(orderId));
        return order;
    }

    private static <T> T await(java.util.function.Supplier<Optional<T>> supplier) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> value = supplier.get();
            if (value.isPresent()) {
                return value.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
