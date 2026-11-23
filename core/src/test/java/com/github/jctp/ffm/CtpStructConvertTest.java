package com.github.jctp.ffm;

import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Investor;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Product;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class CtpStructConvertTest {

    private static final double DBL_MAX = Double.MAX_VALUE;

    private static long millis(LocalDateTime time) {
        return time.atZone(CtpStructConvert.ZONE).toInstant().toEpochMilli();
    }

    private static MemorySegment depth(CtpStructs structs, Arena arena, String tradingDay, String updateTime, int millisec,
                                       int volume, double turnover, double openInterest) {
        MemorySegment d = arena.allocate(structs.depthMarketData.SIZE);
        structs.depthMarketData.TradingDay.set(d, tradingDay);
        structs.depthMarketData.InstrumentID.set(d, "rb2601");
        structs.depthMarketData.ExchangeID.set(d, "SHFE");
        structs.depthMarketData.UpdateTime.set(d, updateTime);
        structs.depthMarketData.UpdateMillisec.set(d, millisec);
        structs.depthMarketData.LastPrice.set(d, 3100.0);
        structs.depthMarketData.UpperLimitPrice.set(d, 3300.0);
        structs.depthMarketData.LowerLimitPrice.set(d, 2900.0);
        structs.depthMarketData.Volume.set(d, volume);
        structs.depthMarketData.Turnover.set(d, turnover);
        structs.depthMarketData.OpenInterest.set(d, openInterest);
        structs.depthMarketData.BidPrice1.set(d, 3099.0);
        structs.depthMarketData.BidVolume1.set(d, 12);
        structs.depthMarketData.AskPrice1.set(d, 3101.0);
        structs.depthMarketData.AskVolume1.set(d, 8);
        // 无效价格：未结算、无二档挂单
        structs.depthMarketData.SettlementPrice.set(d, DBL_MAX);
        structs.depthMarketData.BidPrice2.set(d, DBL_MAX);
        structs.depthMarketData.AskPrice2.set(d, DBL_MAX);
        return d;
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toMarketData(CtpVersion version) {
        CtpStructs structs = version.structs();
        long now = millis(LocalDateTime.of(2026, 11, 13, 10, 0, 1));
        try (Arena arena = Arena.ofConfined()) {
            MarketData m = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261113", "10:00:00", 500, 100, 3_100_000, 5000), null, now);

            assertEquals("rb2601", m.instrumentID());
            assertEquals("SHFE", m.exchangeId());
            assertEquals(LocalDate.of(2026, 11, 13), m.tradingDay());
            assertEquals(LocalDate.of(2026, 11, 13), m.actionDay());
            assertEquals(LocalTime.of(10, 0, 0, 500_000_000), m.actionTime());
            assertEquals(millis(LocalDateTime.of(2026, 11, 13, 10, 0, 0, 500_000_000)), m.actionTimestamp());
            assertEquals(now, m.systemTimestamp());
            assertEquals(3100.0, m.lastPrice());
            assertEquals(3099.0, m.bidPrice1());
            assertEquals(12, m.bidVolume1());
            assertEquals(3101.0, m.askPrice1());
            assertEquals(8, m.askVolume1());
            assertTrue(Double.isNaN(m.settlementPrice()));
            assertTrue(Double.isNaN(m.bidPrice2()));
            assertTrue(Double.isNaN(m.askPrice2()));
            // 首笔行情没有参照，增量为 0
            assertEquals(0, m.volumeDelta());
            assertEquals(0, m.turnoverDelta());
            assertEquals(0, m.openInterestDelta());
        }
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void deltasRelativeToPrevious(CtpVersion version) {
        CtpStructs structs = version.structs();
        long now = millis(LocalDateTime.of(2026, 11, 13, 10, 0, 1));
        try (Arena arena = Arena.ofConfined()) {
            MarketData first = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261113", "10:00:00", 0, 100, 3_100_000, 5000), null, now);
            MarketData second = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261113", "10:00:00", 500, 130, 4_030_000, 4990), first, now);
            assertEquals(30, second.volumeDelta());
            assertEquals(930_000, second.turnoverDelta());
            assertEquals(-10, second.openInterestDelta());

            // 累计量回落（重连后柜台数据异常）不产生负增量
            MarketData dropped = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261113", "10:00:01", 0, 120, 4_000_000, 4990), second, now);
            assertEquals(0, dropped.volumeDelta());
            assertEquals(0, dropped.turnoverDelta());

            // 跨交易日不与上一交易日比较
            MarketData nextDay = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261116", "21:00:00", 0, 50, 1_550_000, 6000), second, now);
            assertEquals(0, nextDay.volumeDelta());
            assertEquals(0, nextDay.openInterestDelta());
        }
    }

    /**
     * 夜盘跨午夜：行情时间与本地时钟分处午夜两侧时，归属日按本地时钟修正。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void nightSessionActionDay(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            // 交易所 23:59:59.900 的行情在本地 00:00:00.100 收到 → 归属前一自然日
            long afterMidnight = millis(LocalDateTime.of(2026, 11, 14, 0, 0, 0, 100_000_000));
            MarketData late = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261116", "23:59:59", 900, 1, 1, 1), null, afterMidnight);
            assertEquals(LocalDate.of(2026, 11, 13), late.actionDay());
            assertEquals(LocalDate.of(2026, 11, 16), late.tradingDay());

            // 交易所 00:00:00 的行情在本地 23:59:59 收到（本地时钟慢）→ 归属后一自然日
            long beforeMidnight = millis(LocalDateTime.of(2026, 11, 13, 23, 59, 59));
            MarketData early = CtpStructConvert.toMarketData(structs, "rb2601",
                depth(structs, arena, "20261116", "00:00:00", 0, 1, 1, 1), null, beforeMidnight);
            assertEquals(LocalDate.of(2026, 11, 14), early.actionDay());
        }
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toInstrumentToleratesInvalidValues(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(structs.instrument.SIZE);
            structs.instrument.InstrumentID.set(s, "rb2601");
            structs.instrument.ProductClass.set(s, '1');
            structs.instrument.VolumeMultiple.set(s, 10);
            structs.instrument.PriceTick.set(s, 1.0);
            structs.instrument.StrikePrice.set(s, Double.MAX_VALUE);
            structs.instrument.ExpireDate.set(s, "20260115");
            structs.instrument.CreateDate.set(s, "00000000");

            Instrument i = CtpStructConvert.toInstrument(structs, s);
            assertEquals(DataType.ProductClass.Futures, i.productClass());
            assertEquals(10, i.volumeMultiple());
            assertEquals(0, i.strikePrice());
            assertNull(i.optionsType());
            assertEquals(LocalDate.of(2026, 1, 15), i.expireDate());
            assertNull(i.createDate());
        }
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toProduct(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(structs.product.SIZE);
            structs.product.ProductID.set(s, "rb");
            structs.product.ProductName.set(s, "螺纹钢");
            structs.product.ExchangeID.set(s, "SHFE");
            structs.product.ProductClass.set(s, '1');
            structs.product.VolumeMultiple.set(s, 10);
            structs.product.PriceTick.set(s, 1.0);
            structs.product.MaxLimitOrderVolume.set(s, 500);
            structs.product.PositionType.set(s, '2');
            structs.product.UnderlyingMultiple.set(s, Double.MAX_VALUE);

            Product p = CtpStructConvert.toProduct(structs, s);
            assertEquals("rb", p.productID());
            assertEquals("螺纹钢", p.productName());
            assertEquals("SHFE", p.exchangeID());
            assertEquals(DataType.ProductClass.Futures, p.productClass());
            assertEquals(10, p.volumeMultiple());
            assertEquals(1.0, p.priceTick());
            assertEquals(500, p.maxLimitOrderVolume());
            assertEquals(DataType.PositionType.Gross, p.positionType());
            assertEquals(0, p.underlyingMultiple());
        }
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toInvestor(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(structs.investor.SIZE);
            structs.investor.InvestorID.set(s, "000001");
            structs.investor.BrokerID.set(s, "9999");
            structs.investor.InvestorName.set(s, "张三");
            structs.investor.IsActive.set(s, 1);
            structs.investor.OpenDate.set(s, "20200102");
            structs.investor.CommModelID.set(s, "C1");

            Investor i = CtpStructConvert.toInvestor(structs, s);
            assertEquals("000001", i.investorID());
            assertEquals("9999", i.brokerID());
            assertEquals("张三", i.investorName());
            assertTrue(i.active());
            assertEquals(LocalDate.of(2020, 1, 2), i.openDate());
            assertEquals("C1", i.commModelID());
            assertEquals("", i.marginModelID());
        }
    }

    /**
     * 结算单分段返回，汉字可能在段边界被截断：按序号拼接字节后再解码。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void settlementPartsMergedByBytes(CtpVersion version) {
        CtpStructs structs = version.structs();
        String text = "交易结算单(盯市)\n客户号 Client ID：000001\n期初结存 Balance b/f：1000000.00";
        byte[] bytes = text.getBytes(CtpField.GBK);
        // 第 1 个字节后截断“交”字（GBK 双字节）
        int[] cuts = {1, 15, bytes.length};
        List<CtpStructConvert.SettlementPart> parts = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            int from = 0;
            for (int i = 0; i < cuts.length; i++) {
                MemorySegment s = arena.allocate(structs.settlementInfo.SIZE);
                structs.settlementInfo.TradingDay.set(s, "20261119");
                structs.settlementInfo.SettlementID.set(s, 1);
                structs.settlementInfo.BrokerID.set(s, "9999");
                structs.settlementInfo.InvestorID.set(s, "000001");
                structs.settlementInfo.SequenceNo.set(s, i + 1);
                MemorySegment.copy(bytes, from, s, JAVA_BYTE,
                    structs.settlementInfo.Content.offset(), cuts[i] - from);
                from = cuts[i];
                parts.add(CtpStructConvert.toSettlementPart(structs, s));
            }
        }
        // 应答顺序与序号无关
        Collections.reverse(parts);
        SettlementInfo info = CtpStructConvert.mergeSettlement(parts).orElseThrow();
        assertEquals(text, info.content());
        assertEquals(LocalDate.of(2026, 11, 19), info.tradingDay());
        assertEquals("000001", info.investorID());
        assertEquals(1, info.settlementID());
        assertTrue(CtpStructConvert.mergeSettlement(List.of()).isEmpty(), "无结算单");
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toOrder(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(structs.order.SIZE);
            structs.order.InstrumentID.set(s, "rb2601");
            structs.order.ExchangeID.set(s, "SHFE");
            structs.order.Direction.set(s, '0');
            structs.order.CombOffsetFlag.set(s, "3");
            structs.order.CombHedgeFlag.set(s, "1");
            structs.order.OrderPriceType.set(s, '2');
            structs.order.TimeCondition.set(s, '3');
            structs.order.VolumeCondition.set(s, '1');
            structs.order.LimitPrice.set(s, 3100.0);
            structs.order.VolumeTotalOriginal.set(s, 5);
            structs.order.FrontID.set(s, 1);
            structs.order.SessionID.set(s, -12345);
            structs.order.OrderRef.set(s, "42");
            structs.order.OrderSysID.set(s, "      123456");
            structs.order.OrderSubmitStatus.set(s, '3');
            structs.order.OrderStatus.set(s, '1');
            structs.order.VolumeTraded.set(s, 2);
            structs.order.VolumeTotal.set(s, 3);
            structs.order.StatusMsg.set(s, "部分成交");
            structs.order.TradingDay.set(s, "20261113");
            structs.order.InsertTime.set(s, "21:00:01");
            structs.order.ActiveTime.set(s, "21:00:00");
            structs.order.ContingentCondition.set(s, '1');
            structs.order.ForceCloseReason.set(s, '0');
            structs.order.IsAutoSuspend.set(s, 1);
            structs.order.OrderType.set(s, '0');
            structs.order.OrderSource.set(s, '0');
            structs.order.ClientID.set(s, "88880001");
            structs.order.StopPrice.set(s, DBL_MAX);

            Order o = CtpStructConvert.toOrder(structs, s, 1L);
            assertEquals("1_-12345_42", o.orderId());
            assertEquals(DataType.Direction.Buy, o.direction());
            assertEquals(DataType.OffsetFlag.CloseToday, o.offsetFlag());
            assertEquals(DataType.HedgeFlag.Speculation, o.hedgeFlag());
            assertEquals(DataType.OrderSubmitStatus.Accepted, o.submitStatus());
            assertEquals(DataType.OrderStatus.PartTradedQueueing, o.status());
            assertEquals("      123456", o.orderSysID(), "报单编号原样保留前导空格，撤单时回填");
            assertEquals("部分成交", o.statusMsg());
            assertEquals(3100.0, o.limitPrice());
            assertEquals(LocalDate.of(2026, 11, 13), o.tradingDay());
            assertEquals(LocalTime.of(21, 0, 1), o.insertTime());
            assertNull(o.cancelTime());
            assertFalse(o.done());
            assertEquals(LocalTime.of(21, 0), o.activeTime());
            assertEquals(DataType.ContingentCondition.Immediately, o.contingentCondition());
            assertEquals(DataType.ForceCloseReason.NotForceClose, o.forceCloseReason());
            assertTrue(o.autoSuspend());
            assertFalse(o.userForceClose());
            assertEquals(DataType.OrderType.Normal, o.orderType());
            assertEquals(DataType.OrderSource.Participant, o.orderSource());
            assertEquals("88880001", o.clientID());
            assertTrue(Double.isNaN(o.stopPrice()));
        }
    }

    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void toTrade(CtpVersion version) {
        CtpStructs structs = version.structs();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(structs.trade.SIZE);
            structs.trade.InstrumentID.set(s, "IF2612");
            structs.trade.ExchangeID.set(s, "CFFEX");
            structs.trade.TradeID.set(s, "T1");
            structs.trade.Direction.set(s, '1');
            structs.trade.OffsetFlag.set(s, '0');
            structs.trade.HedgeFlag.set(s, '1');
            structs.trade.TradeType.set(s, '0');
            structs.trade.Price.set(s, 4000.2);
            structs.trade.Volume.set(s, 1);
            structs.trade.TradeTime.set(s, "09:30:00");
            structs.trade.TradingRole.set(s, '1');
            structs.trade.PriceSource.set(s, '0');
            structs.trade.TradeSource.set(s, '1');
            structs.trade.ClientID.set(s, "88880001");

            Trade t = CtpStructConvert.toTrade(structs, s, 1L);
            assertEquals("CFFEX_T1_1", t.id());
            assertEquals(DataType.Direction.Sell, t.direction());
            assertEquals(DataType.OffsetFlag.Open, t.offsetFlag());
            assertEquals(DataType.TradeType.Common, t.tradeType());
            assertEquals(4000.2, t.price());
            assertEquals(LocalTime.of(9, 30), t.tradeTime());
            assertEquals(DataType.TradingRole.Broker, t.tradingRole());
            assertEquals(DataType.PriceSource.LastPrice, t.priceSource());
            assertEquals(DataType.TradeSource.QUERY, t.tradeSource());
            assertEquals("88880001", t.clientID());
        }
    }
}
