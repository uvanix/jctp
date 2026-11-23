package com.github.jctp.ffm;

import com.github.jctp.struct.Account;
import com.github.jctp.struct.CommissionRate;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Investor;
import com.github.jctp.struct.MarginRate;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Position;
import com.github.jctp.struct.Product;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CTP 结构体 → {@code struct} 记录类的转换，以及 CTP 字段的取值约定（日期时间格式、无效值、时区）。
 * <p>
 * 结构体段只在 Spi 回调期间有效，转换须在回调内完成。
 */
public final class CtpStructConvert {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CtpStructConvert() {
    }

    /**
     * CTP 以 DBL_MAX 表示无效价格（无挂单、未结算等）。
     */
    public static double price(double value) {
        return value >= Double.MAX_VALUE / 2 ? Double.NaN : value;
    }

    /**
     * 合约属性中的无效值（DBL_MAX）按 0 处理。
     */
    public static double valueOrZero(double value) {
        double v = price(value);
        return Double.isNaN(v) ? 0 : v;
    }

    /**
     * 行情归属自然日。各交易所夜盘 ActionDay 的口径不一（大商所填交易日），统一按本地时钟推算：
     * 行情时间与本地时间分处午夜两侧时修正一天。
     */
    public static LocalDate actionDay(LocalTime actionTime, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (actionTime.getHour() >= 20 && now.getHour() < 4) {
            return today.minusDays(1);
        }
        if (actionTime.getHour() < 4 && now.getHour() >= 20) {
            return today.plusDays(1);
        }
        return today;
    }

    /**
     * 空串或非法日期返回 null（个别合约的日期字段可能不规范）。
     */
    public static LocalDate date(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(yyyyMMdd, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 空串或非法时间返回 null。
     */
    public static LocalTime time(String hhmmss) {
        if (hhmmss == null || hhmmss.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmmss);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * CombOffsetFlag、CombHedgeFlag 等组合字段取首字符，空串返回 '\0'。
     */
    public static char first(String value) {
        return value == null || value.isEmpty() ? '\0' : value.charAt(0);
    }

    /**
     * 深度行情 → MarketData。成交量、成交额、持仓量的增量相对上一笔同合约 MarketData 计算。
     */
    public static MarketData toMarketData(CtpStructs structs, String instrumentID, MemorySegment d, MarketData previous, long now) {
        LocalDate tradingDay = date(structs.depthMarketData.TradingDay.getString(d));
        String exchangeId = structs.depthMarketData.ExchangeID.getString(d);
        String exchangeInstID = structs.depthMarketData.ExchangeInstID.getString(d);

        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZONE);
        String updateTime = structs.depthMarketData.UpdateTime.getString(d);
        LocalTime actionTime = (updateTime.isEmpty() ? local.toLocalTime().withNano(0) : LocalTime.parse(updateTime))
            .plusNanos(structs.depthMarketData.UpdateMillisec.getInt(d) * 1_000_000L);
        LocalDate actionDay = CtpStructConvert.actionDay(actionTime, local);

        double lastPrice = price(structs.depthMarketData.LastPrice.getDouble(d));
        double preOpenInterest = price(structs.depthMarketData.PreOpenInterest.getDouble(d));
        double preClosePrice = price(structs.depthMarketData.PreClosePrice.getDouble(d));
        double preSettlementPrice = price(structs.depthMarketData.PreSettlementPrice.getDouble(d));

        double openPrice = price(structs.depthMarketData.OpenPrice.getDouble(d));
        double highestPrice = price(structs.depthMarketData.HighestPrice.getDouble(d));
        double lowestPrice = price(structs.depthMarketData.LowestPrice.getDouble(d));
        double closePrice = price(structs.depthMarketData.ClosePrice.getDouble(d));
        double upperLimitPrice = price(structs.depthMarketData.UpperLimitPrice.getDouble(d));
        double lowerLimitPrice = price(structs.depthMarketData.LowerLimitPrice.getDouble(d));
        double settlementPrice = price(structs.depthMarketData.SettlementPrice.getDouble(d));
        double preDelta = price(structs.depthMarketData.PreDelta.getDouble(d));
        double currDelta = price(structs.depthMarketData.CurrDelta.getDouble(d));
        double averagePrice = price(structs.depthMarketData.AveragePrice.getDouble(d));

        int volume = structs.depthMarketData.Volume.getInt(d);
        double turnover = structs.depthMarketData.Turnover.getDouble(d);
        double openInterest = structs.depthMarketData.OpenInterest.getDouble(d);
        // 跨交易日或重连后累计量可能回落，此时增量即当日累计
        boolean continuous = previous != null && Objects.equals(previous.tradingDay(), tradingDay);
        int volumeDelta = continuous && volume >= previous.volume() ? volume - previous.volume() : 0;
        double turnoverDelta = continuous && turnover >= previous.turnover() ? turnover - previous.turnover() : 0;
        double openInterestDelta = continuous ? openInterest - previous.openInterest() : 0;

        double bidPrice1 = price(structs.depthMarketData.BidPrice1.getDouble(d));
        double bidPrice2 = price(structs.depthMarketData.BidPrice2.getDouble(d));
        double bidPrice3 = price(structs.depthMarketData.BidPrice3.getDouble(d));
        double bidPrice4 = price(structs.depthMarketData.BidPrice4.getDouble(d));
        double bidPrice5 = price(structs.depthMarketData.BidPrice5.getDouble(d));

        double askPrice1 = price(structs.depthMarketData.AskPrice1.getDouble(d));
        double askPrice2 = price(structs.depthMarketData.AskPrice2.getDouble(d));
        double askPrice3 = price(structs.depthMarketData.AskPrice3.getDouble(d));
        double askPrice4 = price(structs.depthMarketData.AskPrice4.getDouble(d));
        double askPrice5 = price(structs.depthMarketData.AskPrice5.getDouble(d));

        int bidVolume1 = structs.depthMarketData.BidVolume1.getInt(d);
        int bidVolume2 = structs.depthMarketData.BidVolume2.getInt(d);
        int bidVolume3 = structs.depthMarketData.BidVolume3.getInt(d);
        int bidVolume4 = structs.depthMarketData.BidVolume4.getInt(d);
        int bidVolume5 = structs.depthMarketData.BidVolume5.getInt(d);

        int askVolume1 = structs.depthMarketData.AskVolume1.getInt(d);
        int askVolume2 = structs.depthMarketData.AskVolume2.getInt(d);
        int askVolume3 = structs.depthMarketData.AskVolume3.getInt(d);
        int askVolume4 = structs.depthMarketData.AskVolume4.getInt(d);
        int askVolume5 = structs.depthMarketData.AskVolume5.getInt(d);

        return MarketData.builder()
            .instrumentID(instrumentID)
            .exchangeId(exchangeId)
            .exchangeInstID(exchangeInstID)
            .systemTimestamp(now)
            .actionTimestamp(actionDay.atTime(actionTime).atZone(ZONE).toInstant().toEpochMilli())
            .actionTime(actionTime)
            .actionDay(actionDay)
            .tradingDay(tradingDay)
            .lastPrice(lastPrice)
            .preOpenInterest(preOpenInterest)
            .preClosePrice(preClosePrice)
            .preSettlementPrice(preSettlementPrice)
            .openPrice(openPrice)
            .highestPrice(highestPrice)
            .lowestPrice(lowestPrice)
            .closePrice(closePrice)
            .upperLimitPrice(upperLimitPrice)
            .lowerLimitPrice(lowerLimitPrice)
            .settlementPrice(settlementPrice)
            .preDelta(preDelta)
            .currDelta(currDelta)
            .averagePrice(averagePrice)
            .volume(volume)
            .volumeDelta(volumeDelta)
            .turnover(turnover)
            .turnoverDelta(turnoverDelta)
            .openInterest(openInterest)
            .openInterestDelta(openInterestDelta)
            .bidPrice1(bidPrice1)
            .bidPrice2(bidPrice2)
            .bidPrice3(bidPrice3)
            .bidPrice4(bidPrice4)
            .bidPrice5(bidPrice5)
            .bidVolume1(bidVolume1)
            .bidVolume2(bidVolume2)
            .bidVolume3(bidVolume3)
            .bidVolume4(bidVolume4)
            .bidVolume5(bidVolume5)
            .askPrice1(askPrice1)
            .askPrice2(askPrice2)
            .askPrice3(askPrice3)
            .askPrice4(askPrice4)
            .askPrice5(askPrice5)
            .askVolume1(askVolume1)
            .askVolume2(askVolume2)
            .askVolume3(askVolume3)
            .askVolume4(askVolume4)
            .askVolume5(askVolume5)
            .build();
    }

    /**
     * {@link Instrument}
     */
    public static Instrument toInstrument(CtpStructs structs, MemorySegment s) {
        return Instrument.builder()
            .instrumentID(structs.instrument.InstrumentID.getString(s))
            .exchangeID(structs.instrument.ExchangeID.getString(s))
            .instrumentName(structs.instrument.InstrumentName.getString(s))
            .exchangeInstID(structs.instrument.ExchangeInstID.getString(s))
            .productID(structs.instrument.ProductID.getString(s))
            .productClass(DataType.of(DataType.ProductClass.class, structs.instrument.ProductClass.getChar(s)))
            .instLifePhase(DataType.of(DataType.InstLifePhase.class, structs.instrument.InstLifePhase.getChar(s)))
            .isTrading(structs.instrument.IsTrading.getInt(s) != 0)
            .priceTick(valueOrZero(structs.instrument.PriceTick.getDouble(s)))
            .volumeMultiple(structs.instrument.VolumeMultiple.getInt(s))
            .underlyingMultiple(valueOrZero(structs.instrument.UnderlyingMultiple.getDouble(s)))
            .underlyingInstrID(structs.instrument.UnderlyingInstrID.getString(s))
            .strikePrice(valueOrZero(structs.instrument.StrikePrice.getDouble(s)))
            .optionsType(DataType.of(DataType.OptionsType.class, structs.instrument.OptionsType.getChar(s)))
            .createDate(date(structs.instrument.CreateDate.getString(s)))
            .openDate(date(structs.instrument.OpenDate.getString(s)))
            .expireDate(date(structs.instrument.ExpireDate.getString(s)))
            .deliveryYear(structs.instrument.DeliveryYear.getInt(s))
            .deliveryMonth(structs.instrument.DeliveryMonth.getInt(s))
            .startDelivDate(date(structs.instrument.StartDelivDate.getString(s)))
            .endDelivDate(date(structs.instrument.EndDelivDate.getString(s)))
            .longMarginRatio(valueOrZero(structs.instrument.LongMarginRatio.getDouble(s)))
            .shortMarginRatio(valueOrZero(structs.instrument.ShortMarginRatio.getDouble(s)))
            .maxMarketOrderVolume(structs.instrument.MaxMarketOrderVolume.getInt(s))
            .minMarketOrderVolume(structs.instrument.MinMarketOrderVolume.getInt(s))
            .maxLimitOrderVolume(structs.instrument.MaxLimitOrderVolume.getInt(s))
            .minLimitOrderVolume(structs.instrument.MinLimitOrderVolume.getInt(s))
            .maxMarginSideAlgorithm(DataType.of(DataType.MaxMarginSideAlgorithm.class,
                structs.instrument.MaxMarginSideAlgorithm.getChar(s)))
            .positionType(DataType.of(DataType.PositionType.class, structs.instrument.PositionType.getChar(s)))
            .combinationType(DataType.of(DataType.CombinationType.class, structs.instrument.CombinationType.getChar(s)))
            .build();
    }

    /**
     * {@link Product}
     */
    public static Product toProduct(CtpStructs structs, MemorySegment s) {
        return Product.builder()
            .productID(structs.product.ProductID.getString(s))
            .productName(structs.product.ProductName.getString(s))
            .exchangeID(structs.product.ExchangeID.getString(s))
            .exchangeProductID(structs.product.ExchangeProductID.getString(s))
            .productClass(DataType.of(DataType.ProductClass.class, structs.product.ProductClass.getChar(s)))
            .volumeMultiple(structs.product.VolumeMultiple.getInt(s))
            .priceTick(structs.product.PriceTick.getDouble(s))
            .underlyingMultiple(valueOrZero(structs.product.UnderlyingMultiple.getDouble(s)))
            .maxMarketOrderVolume(structs.product.MaxMarketOrderVolume.getInt(s))
            .minMarketOrderVolume(structs.product.MinMarketOrderVolume.getInt(s))
            .maxLimitOrderVolume(structs.product.MaxLimitOrderVolume.getInt(s))
            .minLimitOrderVolume(structs.product.MinLimitOrderVolume.getInt(s))
            .positionType(DataType.of(DataType.PositionType.class, structs.product.PositionType.getChar(s)))
            .positionDateType(structs.product.PositionDateType.getChar(s))
            .closeDealType(structs.product.CloseDealType.getChar(s))
            .tradeCurrencyID(structs.product.TradeCurrencyID.getString(s))
            .mortgageFundUseRange(structs.product.MortgageFundUseRange.getChar(s))
            .build();
    }

    /**
     * {@link Investor}
     */
    /**
     * 结算单的一段。content 为该段原始 GBK 字节，汉字可能在段边界被截断，须用 {@link #mergeSettlement} 拼接后解码。
     */
    public record SettlementPart(LocalDate tradingDay, int settlementID, String brokerID, String investorID,
                                 String accountID, String currencyID, int sequenceNo, byte[] content) {
    }

    public static SettlementPart toSettlementPart(CtpStructs structs, MemorySegment s) {
        return new SettlementPart(
            date(structs.settlementInfo.TradingDay.getString(s)),
            structs.settlementInfo.SettlementID.getInt(s),
            structs.settlementInfo.BrokerID.getString(s),
            structs.settlementInfo.InvestorID.getString(s),
            structs.settlementInfo.AccountID.getString(s),
            structs.settlementInfo.CurrencyID.getString(s),
            structs.settlementInfo.SequenceNo.getInt(s),
            structs.settlementInfo.Content.getBytes(s));
    }

    /**
     * 按序号拼接各段字节后一次解码。没有任何段（无结算单）时为空。
     */
    public static Optional<SettlementInfo> mergeSettlement(List<SettlementPart> parts) {
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        List<SettlementPart> sorted = parts.stream().sorted(Comparator.comparingInt(SettlementPart::sequenceNo)).toList();
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        sorted.forEach(p -> content.writeBytes(p.content()));
        SettlementPart first = sorted.getFirst();
        return Optional.of(SettlementInfo.builder()
            .tradingDay(first.tradingDay())
            .settlementID(first.settlementID())
            .brokerID(first.brokerID())
            .investorID(first.investorID())
            .accountID(first.accountID())
            .currencyID(first.currencyID())
            .content(content.toString(CtpField.GBK))
            .build());
    }

    public static Investor toInvestor(CtpStructs structs, MemorySegment s) {
        return Investor.builder()
            .investorID(structs.investor.InvestorID.getString(s))
            .brokerID(structs.investor.BrokerID.getString(s))
            .investorGroupID(structs.investor.InvestorGroupID.getString(s))
            .investorName(structs.investor.InvestorName.getString(s))
            .identifiedCardType(structs.investor.IdentifiedCardType.getChar(s))
            .identifiedCardNo(structs.investor.IdentifiedCardNo.getString(s))
            .active(structs.investor.IsActive.getInt(s) != 0)
            .telephone(structs.investor.Telephone.getString(s))
            .address(structs.investor.Address.getString(s))
            .openDate(date(structs.investor.OpenDate.getString(s)))
            .mobile(structs.investor.Mobile.getString(s))
            .commModelID(structs.investor.CommModelID.getString(s))
            .marginModelID(structs.investor.MarginModelID.getString(s))
            .build();
    }

    /**
     * {@link Account}
     */
    public static Account toAccount(CtpStructs structs, MemorySegment s) {
        return Account.builder()
            .accountID(structs.tradingAccount.AccountID.getString(s))
            .currencyID(structs.tradingAccount.CurrencyID.getString(s))
            .tradingDay(date(structs.tradingAccount.TradingDay.getString(s)))
            .preBalance(structs.tradingAccount.PreBalance.getDouble(s))
            .balance(structs.tradingAccount.Balance.getDouble(s))
            .available(structs.tradingAccount.Available.getDouble(s))
            .withdrawQuota(structs.tradingAccount.WithdrawQuota.getDouble(s))
            .deposit(structs.tradingAccount.Deposit.getDouble(s))
            .withdraw(structs.tradingAccount.Withdraw.getDouble(s))
            .currMargin(structs.tradingAccount.CurrMargin.getDouble(s))
            .exchangeMargin(structs.tradingAccount.ExchangeMargin.getDouble(s))
            .frozenMargin(structs.tradingAccount.FrozenMargin.getDouble(s))
            .frozenCash(structs.tradingAccount.FrozenCash.getDouble(s))
            .frozenCommission(structs.tradingAccount.FrozenCommission.getDouble(s))
            .commission(structs.tradingAccount.Commission.getDouble(s))
            .closeProfit(structs.tradingAccount.CloseProfit.getDouble(s))
            .positionProfit(structs.tradingAccount.PositionProfit.getDouble(s))
            .credit(structs.tradingAccount.Credit.getDouble(s))
            .mortgage(structs.tradingAccount.Mortgage.getDouble(s))
            .preMargin(structs.tradingAccount.PreMargin.getDouble(s))
            .preCredit(structs.tradingAccount.PreCredit.getDouble(s))
            .preMortgage(structs.tradingAccount.PreMortgage.getDouble(s))
            .preDeposit(structs.tradingAccount.PreDeposit.getDouble(s))
            .interestBase(structs.tradingAccount.InterestBase.getDouble(s))
            .interest(structs.tradingAccount.Interest.getDouble(s))
            .cashIn(structs.tradingAccount.CashIn.getDouble(s))
            .reserve(structs.tradingAccount.Reserve.getDouble(s))
            .reserveBalance(structs.tradingAccount.ReserveBalance.getDouble(s))
            .deliveryMargin(structs.tradingAccount.DeliveryMargin.getDouble(s))
            .exchangeDeliveryMargin(structs.tradingAccount.ExchangeDeliveryMargin.getDouble(s))
            .preFundMortgageIn(structs.tradingAccount.PreFundMortgageIn.getDouble(s))
            .preFundMortgageOut(structs.tradingAccount.PreFundMortgageOut.getDouble(s))
            .fundMortgageIn(structs.tradingAccount.FundMortgageIn.getDouble(s))
            .fundMortgageOut(structs.tradingAccount.FundMortgageOut.getDouble(s))
            .fundMortgageAvailable(structs.tradingAccount.FundMortgageAvailable.getDouble(s))
            .mortgageableFund(structs.tradingAccount.MortgageableFund.getDouble(s))
            .specProductMargin(structs.tradingAccount.SpecProductMargin.getDouble(s))
            .specProductFrozenMargin(structs.tradingAccount.SpecProductFrozenMargin.getDouble(s))
            .specProductCommission(structs.tradingAccount.SpecProductCommission.getDouble(s))
            .specProductFrozenCommission(structs.tradingAccount.SpecProductFrozenCommission.getDouble(s))
            .specProductPositionProfit(structs.tradingAccount.SpecProductPositionProfit.getDouble(s))
            .specProductCloseProfit(structs.tradingAccount.SpecProductCloseProfit.getDouble(s))
            .specProductPositionProfitByAlg(structs.tradingAccount.SpecProductPositionProfitByAlg.getDouble(s))
            .specProductExchangeMargin(structs.tradingAccount.SpecProductExchangeMargin.getDouble(s))
            .frozenSwap(structs.tradingAccount.FrozenSwap.getDouble(s))
            .remainSwap(structs.tradingAccount.RemainSwap.getDouble(s))
            .settlementID(structs.tradingAccount.SettlementID.getInt(s))
            .build();
    }

    /**
     * {@link Position}
     */
    public static Position toPosition(CtpStructs structs, MemorySegment s) {
        return Position.builder()
            .instrumentID(structs.investorPosition.InstrumentID.getString(s))
            .exchangeID(structs.investorPosition.ExchangeID.getString(s))
            .direction(DataType.of(DataType.PosiDirection.class, structs.investorPosition.PosiDirection.getChar(s)))
            .hedgeFlag(DataType.of(DataType.HedgeFlag.class, structs.investorPosition.HedgeFlag.getChar(s)))
            .positionDate(DataType.of(DataType.PositionDate.class, structs.investorPosition.PositionDate.getChar(s)))
            .tradingDay(date(structs.investorPosition.TradingDay.getString(s)))
            .position(structs.investorPosition.Position.getInt(s))
            .ydPosition(structs.investorPosition.YdPosition.getInt(s))
            .todayPosition(structs.investorPosition.TodayPosition.getInt(s))
            .longFrozen(structs.investorPosition.LongFrozen.getInt(s))
            .shortFrozen(structs.investorPosition.ShortFrozen.getInt(s))
            .openVolume(structs.investorPosition.OpenVolume.getInt(s))
            .closeVolume(structs.investorPosition.CloseVolume.getInt(s))
            .tasPosition(structs.investorPosition.TasPosition.getInt(s))
            .positionCost(structs.investorPosition.PositionCost.getDouble(s))
            .openCost(structs.investorPosition.OpenCost.getDouble(s))
            .useMargin(structs.investorPosition.UseMargin.getDouble(s))
            .frozenMargin(structs.investorPosition.FrozenMargin.getDouble(s))
            .commission(structs.investorPosition.Commission.getDouble(s))
            .closeProfit(structs.investorPosition.CloseProfit.getDouble(s))
            .positionProfit(structs.investorPosition.PositionProfit.getDouble(s))
            .preSettlementPrice(price(structs.investorPosition.PreSettlementPrice.getDouble(s)))
            .settlementPrice(price(structs.investorPosition.SettlementPrice.getDouble(s)))
            .tasPositionCost(structs.investorPosition.TasPositionCost.getDouble(s))
            .longFrozenAmount(structs.investorPosition.LongFrozenAmount.getDouble(s))
            .shortFrozenAmount(structs.investorPosition.ShortFrozenAmount.getDouble(s))
            .openAmount(structs.investorPosition.OpenAmount.getDouble(s))
            .closeAmount(structs.investorPosition.CloseAmount.getDouble(s))
            .preMargin(structs.investorPosition.PreMargin.getDouble(s))
            .exchangeMargin(structs.investorPosition.ExchangeMargin.getDouble(s))
            .frozenCash(structs.investorPosition.FrozenCash.getDouble(s))
            .frozenCommission(structs.investorPosition.FrozenCommission.getDouble(s))
            .cashIn(structs.investorPosition.CashIn.getDouble(s))
            .closeProfitByDate(structs.investorPosition.CloseProfitByDate.getDouble(s))
            .closeProfitByTrade(structs.investorPosition.CloseProfitByTrade.getDouble(s))
            .marginRateByMoney(structs.investorPosition.MarginRateByMoney.getDouble(s))
            .marginRateByVolume(structs.investorPosition.MarginRateByVolume.getDouble(s))
            .combPosition(structs.investorPosition.CombPosition.getInt(s))
            .combLongFrozen(structs.investorPosition.CombLongFrozen.getInt(s))
            .combShortFrozen(structs.investorPosition.CombShortFrozen.getInt(s))
            .strikeFrozen(structs.investorPosition.StrikeFrozen.getInt(s))
            .strikeFrozenAmount(structs.investorPosition.StrikeFrozenAmount.getDouble(s))
            .abandonFrozen(structs.investorPosition.AbandonFrozen.getInt(s))
            .ydStrikeFrozen(structs.investorPosition.YdStrikeFrozen.getInt(s))
            .positionCostOffset(structs.investorPosition.PositionCostOffset.getDouble(s))
            .settlementID(structs.investorPosition.SettlementID.getInt(s))
            .investUnitID(structs.investorPosition.InvestUnitID.getString(s))
            .build();
    }

    /**
     * {@link Order}
     */
    public static Order toOrder(CtpStructs structs, MemorySegment s, long now) {
        return Order.builder()
            .instrumentID(structs.order.InstrumentID.getString(s))
            .exchangeID(structs.order.ExchangeID.getString(s))
            .direction(DataType.of(DataType.Direction.class, structs.order.Direction.getChar(s)))
            .offsetFlag(DataType.of(DataType.OffsetFlag.class, first(structs.order.CombOffsetFlag.getString(s))))
            .hedgeFlag(DataType.of(DataType.HedgeFlag.class, first(structs.order.CombHedgeFlag.getString(s))))
            .priceType(DataType.of(DataType.OrderPriceType.class, structs.order.OrderPriceType.getChar(s)))
            .timeCondition(DataType.of(DataType.TimeCondition.class, structs.order.TimeCondition.getChar(s)))
            .volumeCondition(DataType.of(DataType.VolumeCondition.class, structs.order.VolumeCondition.getChar(s)))
            .limitPrice(structs.order.LimitPrice.getDouble(s))
            .volume(structs.order.VolumeTotalOriginal.getInt(s))
            .minVolume(structs.order.MinVolume.getInt(s))
            .frontID(structs.order.FrontID.getInt(s))
            .sessionID(structs.order.SessionID.getInt(s))
            .orderRef(structs.order.OrderRef.getString(s))
            // 原样保留上期所等右对齐的前导空格，按报单编号撤单时须原样回填
            .orderSysID(structs.order.OrderSysID.getRawString(s))
            .submitStatus(DataType.of(DataType.OrderSubmitStatus.class, structs.order.OrderSubmitStatus.getChar(s)))
            .status(DataType.of(DataType.OrderStatus.class, structs.order.OrderStatus.getChar(s)))
            .statusMsg(structs.order.StatusMsg.getString(s))
            .volumeTraded(structs.order.VolumeTraded.getInt(s))
            .volumeTotal(structs.order.VolumeTotal.getInt(s))
            .tradingDay(date(structs.order.TradingDay.getString(s)))
            .insertDate(date(structs.order.InsertDate.getString(s)))
            .insertTime(time(structs.order.InsertTime.getString(s)))
            .updateTime(time(structs.order.UpdateTime.getString(s)))
            .cancelTime(time(structs.order.CancelTime.getString(s)))
            .investorID(structs.order.InvestorID.getString(s))
            .userID(structs.order.UserID.getString(s))
            .accountID(structs.order.AccountID.getString(s))
            .currencyID(structs.order.CurrencyID.getString(s))
            .gtdDate(date(structs.order.GTDDate.getString(s)))
            .contingentCondition(DataType.of(DataType.ContingentCondition.class, structs.order.ContingentCondition.getChar(s)))
            .stopPrice(price(structs.order.StopPrice.getDouble(s)))
            .forceCloseReason(DataType.of(DataType.ForceCloseReason.class, structs.order.ForceCloseReason.getChar(s)))
            .autoSuspend(structs.order.IsAutoSuspend.getInt(s) != 0)
            .userForceClose(structs.order.UserForceClose.getInt(s) != 0)
            .swapOrder(structs.order.IsSwapOrder.getInt(s) != 0)
            .orderSource(DataType.of(DataType.OrderSource.class, structs.order.OrderSource.getChar(s)))
            .orderType(DataType.of(DataType.OrderType.class, structs.order.OrderType.getChar(s)))
            .activeTime(time(structs.order.ActiveTime.getString(s)))
            .suspendTime(time(structs.order.SuspendTime.getString(s)))
            .requestID(structs.order.RequestID.getInt(s))
            .orderLocalID(structs.order.OrderLocalID.getString(s))
            .brokerOrderSeq(structs.order.BrokerOrderSeq.getInt(s))
            .relativeOrderSysID(structs.order.RelativeOrderSysID.getRawString(s))
            .zceTotalTradedVolume(structs.order.ZCETotalTradedVolume.getInt(s))
            .participantID(structs.order.ParticipantID.getString(s))
            .clientID(structs.order.ClientID.getString(s))
            .traderID(structs.order.TraderID.getString(s))
            .activeTraderID(structs.order.ActiveTraderID.getString(s))
            .clearingPartID(structs.order.ClearingPartID.getString(s))
            .installID(structs.order.InstallID.getInt(s))
            .notifySequence(structs.order.NotifySequence.getInt(s))
            .sequenceNo(structs.order.SequenceNo.getInt(s))
            .settlementID(structs.order.SettlementID.getInt(s))
            .activeUserID(structs.order.ActiveUserID.getString(s))
            .userProductInfo(structs.order.UserProductInfo.getString(s))
            .businessUnit(structs.order.BusinessUnit.getString(s))
            .branchID(structs.order.BranchID.getString(s))
            .investUnitID(structs.order.InvestUnitID.getString(s))
            .ipAddress(structs.order.IPAddress.getString(s))
            .macAddress(structs.order.MacAddress.getString(s))
            .systemTimestamp(now)
            .build();
    }

    /**
     * {@link Trade}
     */
    public static Trade toTrade(CtpStructs structs, MemorySegment s, long now) {
        return Trade.builder()
            .instrumentID(structs.trade.InstrumentID.getString(s))
            .exchangeID(structs.trade.ExchangeID.getString(s))
            .tradeID(structs.trade.TradeID.getString(s))
            .orderRef(structs.trade.OrderRef.getString(s))
            .orderSysID(structs.trade.OrderSysID.getRawString(s))
            .direction(DataType.of(DataType.Direction.class, structs.trade.Direction.getChar(s)))
            .offsetFlag(DataType.of(DataType.OffsetFlag.class, structs.trade.OffsetFlag.getChar(s)))
            .hedgeFlag(DataType.of(DataType.HedgeFlag.class, structs.trade.HedgeFlag.getChar(s)))
            .tradeType(DataType.of(DataType.TradeType.class, structs.trade.TradeType.getChar(s)))
            .price(structs.trade.Price.getDouble(s))
            .volume(structs.trade.Volume.getInt(s))
            .tradingDay(date(structs.trade.TradingDay.getString(s)))
            .tradeDate(date(structs.trade.TradeDate.getString(s)))
            .tradeTime(time(structs.trade.TradeTime.getString(s)))
            .investorID(structs.trade.InvestorID.getString(s))
            .userID(structs.trade.UserID.getString(s))
            .tradingRole(DataType.of(DataType.TradingRole.class, structs.trade.TradingRole.getChar(s)))
            .priceSource(DataType.of(DataType.PriceSource.class, structs.trade.PriceSource.getChar(s)))
            .tradeSource(DataType.of(DataType.TradeSource.class, structs.trade.TradeSource.getChar(s)))
            .orderLocalID(structs.trade.OrderLocalID.getString(s))
            .brokerOrderSeq(structs.trade.BrokerOrderSeq.getInt(s))
            .participantID(structs.trade.ParticipantID.getString(s))
            .clientID(structs.trade.ClientID.getString(s))
            .traderID(structs.trade.TraderID.getString(s))
            .clearingPartID(structs.trade.ClearingPartID.getString(s))
            .sequenceNo(structs.trade.SequenceNo.getInt(s))
            .settlementID(structs.trade.SettlementID.getInt(s))
            .businessUnit(structs.trade.BusinessUnit.getString(s))
            .investUnitID(structs.trade.InvestUnitID.getString(s))
            .systemTimestamp(now)
            .build();
    }

    /**
     * {@link CommissionRate}
     */
    public static CommissionRate toCommissionRate(CtpStructs structs, MemorySegment s) {
        return CommissionRate.builder()
            .instrumentID(structs.instrumentCommissionRate.InstrumentID.getString(s))
            .exchangeID(structs.instrumentCommissionRate.ExchangeID.getString(s))
            .openRatioByMoney(structs.instrumentCommissionRate.OpenRatioByMoney.getDouble(s))
            .openRatioByVolume(structs.instrumentCommissionRate.OpenRatioByVolume.getDouble(s))
            .closeRatioByMoney(structs.instrumentCommissionRate.CloseRatioByMoney.getDouble(s))
            .closeRatioByVolume(structs.instrumentCommissionRate.CloseRatioByVolume.getDouble(s))
            .closeTodayRatioByMoney(structs.instrumentCommissionRate.CloseTodayRatioByMoney.getDouble(s))
            .closeTodayRatioByVolume(structs.instrumentCommissionRate.CloseTodayRatioByVolume.getDouble(s))
            .build();
    }

    /**
     * {@link MarginRate}
     */
    public static MarginRate toMarginRate(CtpStructs structs, MemorySegment s) {
        return MarginRate.builder()
            .instrumentID(structs.instrumentMarginRate.InstrumentID.getString(s))
            .exchangeID(structs.instrumentMarginRate.ExchangeID.getString(s))
            .hedgeFlag(DataType.of(DataType.HedgeFlag.class, structs.instrumentMarginRate.HedgeFlag.getChar(s)))
            .longMarginRatioByMoney(structs.instrumentMarginRate.LongMarginRatioByMoney.getDouble(s))
            .longMarginRatioByVolume(structs.instrumentMarginRate.LongMarginRatioByVolume.getDouble(s))
            .shortMarginRatioByMoney(structs.instrumentMarginRate.ShortMarginRatioByMoney.getDouble(s))
            .shortMarginRatioByVolume(structs.instrumentMarginRate.ShortMarginRatioByVolume.getDouble(s))
            .relative(structs.instrumentMarginRate.IsRelative.getInt(s) != 0)
            .build();
    }
}
