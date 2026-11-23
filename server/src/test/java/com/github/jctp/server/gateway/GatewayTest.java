package com.github.jctp.server.gateway;

import com.github.jctp.server.FakeBrokers;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Position;
import com.github.jctp.struct.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayTest {

    /**
     * 合约数为可交易合约数；未加载的品种合约数为 null；已加载但没有可交易合约的品种不列出。
     */
    @Test
    void productsCountActiveInstruments() {
        Instrument expired = FakeBrokers.future("rb2511", "螺纹钢2511", "rb", "SHFE").toBuilder()
            .isTrading(false).instLifePhase(DataType.InstLifePhase.Expired).build();
        List<Instrument> instruments = List.of(
            FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE"),
            FakeBrokers.future("rb2605", "螺纹钢2605", "rb", "SHFE"),
            expired,
            FakeBrokers.option("rb2601C3200", "rb_o", "rb2601", DataType.OptionsType.Call, 3200));
        List<Product> ctp = List.of(
            product("rb", "SHFE", "螺纹钢"), product("rb_o", "SHFE", "螺纹钢期权"),
            product("IF", "CFFEX", "沪深300股指"), product("wr", "SHFE", "线材"));
        // IF 尚未加载；wr 已加载但没有可交易合约
        List<ProductView> products = InstrumentCatalog.buildProducts(ctp, instruments, k -> !k.equals("CFFEX|IF"), _ -> true);

        assertEquals(List.of("IF", "rb", "rb_o"), products.stream().map(ProductView::productId).toList());
        assertNull(products.getFirst().count());
        assertTrue(products.getFirst().loading());
        assertEquals(new ProductView("rb", "SHFE", null, "螺纹钢", 2, false), products.get(1));
        assertEquals(1, products.get(2).count());
    }

    /**
     * 名称只取 CTP 产品名称，为空时显示品种代码。
     */
    @Test
    void productNamesFromCtpProducts() {
        List<Product> ctp = List.of(product("rb", "SHFE", "螺纹钢"), product("IF", "CFFEX", " "));
        assertEquals(List.of("IF", "螺纹钢"),
            InstrumentCatalog.buildProducts(ctp, List.of(), _ -> false, _ -> false).stream().map(ProductView::name).toList());
    }

    private static Product product(String id, String exchange, String name) {
        return Product.builder().productID(id).exchangeID(exchange).productName(name).build();
    }

    /**
     * 上期所今昨仓两条记录合并；其他交易所单条记录按今仓拆出昨仓。
     */
    @Test
    void aggregatePositions() {
        Instrument rb = FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE");
        Instrument m = FakeBrokers.future("m2601", "豆粕2601", "m", "DCE");
        List<Position> rows = List.of(
            position("rb2601", DataType.PositionDate.History, 3, 0, 3 * 3000 * 10),
            position("rb2601", DataType.PositionDate.Today, 2, 2, 2 * 3100 * 10),
            position("m2601", DataType.PositionDate.Today, 5, 1, 5 * 2800 * 10),
            position("m2605", DataType.PositionDate.Today, 0, 0, 0));
        List<PositionView> views = Gateway.aggregate(rows, id -> id.startsWith("rb") ? rb : m);

        assertEquals(2, views.size());
        PositionView m2601 = views.getFirst();
        assertEquals("m2601", m2601.instrumentID());
        assertEquals(5, m2601.position());
        assertEquals(4, m2601.ydPosition());
        assertEquals(1, m2601.todayPosition());
        assertEquals(2800, m2601.openPrice(), 1e-9);

        PositionView rb2601 = views.get(1);
        assertEquals(5, rb2601.position());
        assertEquals(3, rb2601.ydPosition());
        assertEquals(2, rb2601.todayPosition());
        assertEquals(3040, rb2601.openPrice(), 1e-9);
        assertEquals(10, rb2601.volumeMultiple());
    }

    /**
     * 今昨仓合并：数量、金额相加；结算价、保证金率取首个有效值（跳过 NaN 与 0）。
     */
    @Test
    void aggregateSumsAmountsAndKeepsPrices() {
        Instrument rb = FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE");
        Position history = position("rb2601", DataType.PositionDate.History, 3, 0, 3 * 3000 * 10).toBuilder()
            .shortFrozen(1).closeVolume(2).closeAmount(60_000).frozenMargin(100).closeProfitByDate(50)
            .preSettlementPrice(3010).settlementPrice(Double.NaN).marginRateByMoney(0).build();
        Position today = position("rb2601", DataType.PositionDate.Today, 2, 2, 2 * 3100 * 10).toBuilder()
            .shortFrozen(2).openVolume(2).openAmount(62_000).frozenMargin(200).closeProfitByDate(-20)
            .preSettlementPrice(3010).settlementPrice(Double.NaN).marginRateByMoney(0.12).build();
        PositionView v = Gateway.aggregate(List.of(history, today), _ -> rb).getFirst();

        assertEquals(3, v.shortFrozen());
        assertEquals(2, v.openVolume());
        assertEquals(2, v.closeVolume());
        assertEquals(62_000, v.openAmount(), 1e-9);
        assertEquals(60_000, v.closeAmount(), 1e-9);
        assertEquals(300, v.frozenMargin(), 1e-9);
        assertEquals(30, v.closeProfitByDate(), 1e-9);
        assertEquals(3 * 3000 * 10 + 2 * 3100 * 10, v.openCost(), 1e-9);
        assertEquals(3010, v.preSettlementPrice(), 1e-9);
        assertEquals(0.12, v.marginRateByMoney(), 1e-9);
        assertTrue(Double.isNaN(v.settlementPrice()));
    }

    private static Position position(String id, DataType.PositionDate date, int position, int today, double openCost) {
        return Position.builder().instrumentID(id).direction(DataType.PosiDirection.Long)
            .hedgeFlag(DataType.HedgeFlag.Speculation).positionDate(date)
            .position(position).todayPosition(today).openCost(openCost).positionCost(openCost).build();
    }
}
