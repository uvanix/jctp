package com.github.jctp.server.sim;

import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.ServerConfig;
import com.github.jctp.server.gateway.Gateway;
import com.github.jctp.server.gateway.GatewayFactory;
import com.github.jctp.server.gateway.OrderRequest;
import com.github.jctp.server.gateway.ProductView;
import com.github.jctp.server.market.Bar;
import com.github.jctp.server.market.BarPeriod;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimExchangeTest {

    private static final ServerConfig.Counter COUNTER =
        new ServerConfig.Counter("local-sim", "本地模拟柜台", "9999", null, null, null, null, null, true);

    private final Gateway gateway = GatewayFactory.ctp(new ServerConfig(0, null, null, null, List.of(COUNTER)))
        .create(COUNTER, "u1", "any", CtpVersion.V6_7_8);

    @AfterEach
    void close() {
        gateway.close();
    }

    @Test
    void contractsFollowCurrentDate() {
        gateway.connect();
        assertEquals("u1", gateway.account().accountID());
        List<String> products = gateway.products().stream().map(ProductView::productId).toList();
        assertTrue(products.containsAll(List.of("au", "ag", "rb", "IF", "m", "m_o", "TA")), products.toString());

        // 黄金为偶数月合约，从下月起
        YearMonth next = YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(1);
        YearMonth first = next.getMonthValue() % 2 == 0 ? next : next.plusMonths(1);
        List<Instrument> gold = gateway.instruments("au");
        assertEquals(3, gold.size());
        assertTrue(gold.stream().anyMatch(i -> i.instrumentID().equals("au%02d%02d".formatted(first.getYear() % 100, first.getMonthValue()))),
            gold.toString());
        assertTrue(gold.stream().allMatch(i -> i.expireDate().isAfter(LocalDate.now())));
        // 郑商所年份一位
        assertTrue(gateway.instruments("TA").stream().allMatch(i -> i.instrumentID().matches("TA\\d{3}")));
        assertFalse(gateway.instruments("m_o").isEmpty());
    }

    @Test
    void accountsShareQuotesButNotTrades() throws Exception {
        gateway.connect();
        Gateway other = GatewayFactory.ctp(new ServerConfig(0, null, null, null, List.of(COUNTER)))
            .create(COUNTER, "u2", "any", CtpVersion.V6_7_8);
        try {
            other.connect();
            String id = gateway.instruments("au").getFirst().instrumentID();
            gateway.subscribe(List.of(id));
            Thread.sleep(1500);
            // 晚订阅的账号回放到同一份历史：K 线（含先订阅者收到的逐笔实时行情）与最新行情完全一致
            other.subscribe(List.of(id));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                assertTrue(System.nanoTime() < deadline, "两个账号的行情不一致");
                long time = gateway.quote(id).orElseThrow().systemTimestamp();
                List<Bar> mine = gateway.bars(id, BarPeriod.M1);
                List<Bar> theirs = other.bars(id, BarPeriod.M1);
                // 两次读取之间没有新行情才可比较
                if (time == other.quote(id).orElseThrow().systemTimestamp() && time == gateway.quote(id).orElseThrow().systemTimestamp()) {
                    assertEquals(mine, theirs);
                    assertEquals(gateway.quote(id).orElseThrow().lastPrice(), other.quote(id).orElseThrow().lastPrice());
                    break;
                }
                Thread.sleep(20);
            }

            gateway.submit(new OrderRequest(id, DataType.Direction.Buy, DataType.OffsetFlag.Open, 0, 1,
                OrderRequest.OrderType.MARKET));
            assertEquals(1, gateway.orders().size());
            assertTrue(other.orders().isEmpty(), "报单按账号独立");
        } finally {
            other.close();
        }
    }

    @Test
    void quotesAndMarketOrderFill() throws Exception {
        gateway.connect();
        String id = gateway.instruments("au").getFirst().instrumentID();
        gateway.subscribe(List.of(id));
        assertTrue(gateway.quote(id).isPresent(), "订阅后应回放历史行情");
        assertFalse(gateway.bars(id, BarPeriod.M1).isEmpty());

        Order order = gateway.submit(new OrderRequest(id, DataType.Direction.Buy, DataType.OffsetFlag.Open, 0, 2,
            OrderRequest.OrderType.MARKET));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (gateway.positions().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "市价单未成交 " + gateway.orders());
            Thread.sleep(50);
        }
        assertEquals(2, gateway.positions().getFirst().position());
        assertEquals(DataType.OrderStatus.AllTraded, gateway.orders().stream()
            .filter(o -> o.orderId().equals(order.orderId())).findFirst().orElseThrow().status());
        assertEquals(1, gateway.trades().size());

        // 当日结算单含资金与成交，其他交易日没有
        String content = gateway.settlement(null).orElseThrow().content();
        assertTrue(content.contains("客户号 Client ID：u1") && content.contains(id), content);
        assertTrue(gateway.settlement(LocalDate.now().minusDays(1)).isEmpty());

        // 卖平今 2 手（对手价限价）平掉多头
        double bid = gateway.quote(id).orElseThrow().bidPrice1();
        Order close = gateway.submit(new OrderRequest(id, DataType.Direction.Sell, DataType.OffsetFlag.CloseToday, bid, 2,
            OrderRequest.OrderType.LIMIT));
        assertNotEquals(DataType.OrderStatus.Canceled, close.status(), close.statusMsg());
        while (!gateway.positions().isEmpty() && gateway.positions().getFirst().position() > 0) {
            assertTrue(System.nanoTime() < deadline + Duration.ofSeconds(5).toNanos(), "平仓未成交 " + gateway.orders());
            Thread.sleep(50);
        }
    }
}
