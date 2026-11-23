package com.github.jctp.struct;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

/**
 * CTP 字典类型（ThostFtdcUserApiDataType.h 中的 THOST_FTDC_XXX 常量）。
 */
public interface DataType {

    /**
     * CTP 字典值，code 即结构体中的 char 取值。
     */
    interface Coded {
        char code();

        String label();
    }

    /**
     * 按 CTP 取值查找枚举，未知取值返回 null。
     */
    @SuppressWarnings("unchecked")
    static <E extends Enum<E> & Coded> E of(Class<E> type, char code) {
        return (E) Codes.BY_TYPE.get(type).get(code);
    }

    final class Codes {
        private static final ClassValue<Map<Character, Object>> BY_TYPE = new ClassValue<>() {
            @Override
            protected Map<Character, Object> computeValue(Class<?> type) {
                Map<Character, Object> map = new HashMap<>();
                for (Object e : type.getEnumConstants()) {
                    map.put(((Coded) e).code(), e);
                }
                return map;
            }
        };

        private Codes() {
        }
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ProductClass implements Coded {
        Futures('1', "期货"),
        Options('2', "期货期权"),
        Combination('3', "组合"),
        Spot('4', "即期"),
        EFP('5', "期转现"),
        SpotOption('6', "现货期权"),
        TAS('7', "TAS合约"),
        MI('I', "金属指数"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum InstLifePhase implements Coded {
        NotStart('0', "未上市"),
        Started('1', "上市"),
        Pause('2', "停牌"),
        Expired('3', "到期"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum PositionType implements Coded {
        Net('1', "净持仓"),
        Gross('2', "综合持仓"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OptionsType implements Coded {
        Call('1', "看涨"),
        Put('2', "看跌"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum MaxMarginSideAlgorithm implements Coded {
        NO('0', "不使用大额单边保证金算法"),
        YES('1', "使用大额单边保证金算法"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum CombinationType implements Coded {
        Future('0', "期货组合"),
        BUL('1', "垂直价差BUL"),
        BER('2', "垂直价差BER"),
        STD('3', "跨式组合"),
        STG('4', "宽跨式组合"),
        PRT('5', "备兑组合"),
        CLD('6', "时间价差组合"),
        OPL('7', "期权对锁组合"),
        BFO('8', "买备兑组合"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum Direction implements Coded {
        Buy('0', "买"),
        Sell('1', "卖"),
        ;
        private final char code;
        private final String label;
    }

    /**
     * 持仓多空方向
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum PosiDirection implements Coded {
        Net('1', "净"),
        Long('2', "多头"),
        Short('3', "空头"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum PositionDate implements Coded {
        Today('1', "今日持仓"),
        History('2', "历史持仓"),
        ;
        private final char code;
        private final String label;
    }

    /**
     * 开平标志。上期所、能源中心平仓须区分平今（CloseToday）与平昨（CloseYesterday 或 Close）。
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OffsetFlag implements Coded {
        Open('0', "开仓"),
        Close('1', "平仓"),
        ForceClose('2', "强平"),
        CloseToday('3', "平今"),
        CloseYesterday('4', "平昨"),
        ForceOff('5', "强减"),
        LocalForceClose('6', "本地强平"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum HedgeFlag implements Coded {
        Speculation('1', "投机"),
        Arbitrage('2', "套利"),
        Hedge('3', "套保"),
        MarketMaker('5', "做市商"),
        SpecHedge('6', "第一腿投机第二腿套保"),
        HedgeSpec('7', "第一腿套保第二腿投机"),
        ;
        private final char code;
        private final String label;
    }

    /**
     * 报单价格条件。任意价（市价）须配合 {@link TimeCondition#IOC}，上期所、能源中心不支持市价单。
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OrderPriceType implements Coded {
        AnyPrice('1', "任意价"),
        LimitPrice('2', "限价"),
        BestPrice('3', "最优价"),
        LastPrice('4', "最新价"),
        AskPrice1('8', "卖一价"),
        BidPrice1('C', "买一价"),
        FiveLevelPrice('G', "五档价"),
        ;
        private final char code;
        private final String label;
    }

    /**
     * 有效期类型。FAK/FOK 均为 IOC，配合 {@link VolumeCondition} 区分。
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum TimeCondition implements Coded {
        IOC('1', "立即完成，否则撤销"),
        GFS('2', "本节有效"),
        GFD('3', "当日有效"),
        GTD('4', "指定日期前有效"),
        GTC('5', "撤销前有效"),
        GFA('6', "集合竞价有效"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum VolumeCondition implements Coded {
        AV('1', "任何数量"),
        MV('2', "最小数量"),
        CV('3', "全部数量"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OrderStatus implements Coded {
        AllTraded('0', "全部成交"),
        PartTradedQueueing('1', "部分成交还在队列中"),
        PartTradedNotQueueing('2', "部分成交不在队列中"),
        NoTradeQueueing('3', "未成交还在队列中"),
        NoTradeNotQueueing('4', "未成交不在队列中"),
        Canceled('5', "撤单"),
        Unknown('a', "未知"),
        NotTouched('b', "尚未触发"),
        Touched('c', "已触发"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OrderSubmitStatus implements Coded {
        InsertSubmitted('0', "已经提交"),
        CancelSubmitted('1', "撤单已经提交"),
        ModifySubmitted('2', "修改已经提交"),
        Accepted('3', "已经接受"),
        InsertRejected('4', "报单已经被拒绝"),
        CancelRejected('5', "撤单已经被拒绝"),
        ModifyRejected('6', "改单已经被拒绝"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum TradeType implements Coded {
        SplitCombination('#', "组合持仓拆分为单一持仓"),
        Common('0', "普通成交"),
        OptionsExecution('1', "期权执行"),
        OTC('2', "OTC成交"),
        EFPDerived('3', "期转现衍生成交"),
        CombinationDerived('4', "组合衍生成交"),
        BlockTrade('5', "大宗交易成交"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum InstrumentStatus implements Coded {
        BeforeTrading('0', "开盘前"),
        NoTrading('1', "非交易"),
        Continous('2', "连续交易"),
        AuctionOrdering('3', "集合竞价报单"),
        AuctionBalance('4', "集合竞价价格平衡"),
        AuctionMatch('5', "集合竞价撮合"),
        Closed('6', "收盘"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ContingentCondition implements Coded {
        Immediately('1', "立即"),
        Touch('2', "止损"),
        TouchProfit('3', "止赢"),
        ParkedOrder('4', "预埋单"),
        LastPriceGreaterThanStopPrice('5', "最新价大于条件价"),
        LastPriceGreaterEqualStopPrice('6', "最新价大于等于条件价"),
        LastPriceLesserThanStopPrice('7', "最新价小于条件价"),
        LastPriceLesserEqualStopPrice('8', "最新价小于等于条件价"),
        AskPriceGreaterThanStopPrice('9', "卖一价大于条件价"),
        AskPriceGreaterEqualStopPrice('A', "卖一价大于等于条件价"),
        AskPriceLesserThanStopPrice('B', "卖一价小于条件价"),
        AskPriceLesserEqualStopPrice('C', "卖一价小于等于条件价"),
        BidPriceGreaterThanStopPrice('D', "买一价大于条件价"),
        BidPriceGreaterEqualStopPrice('E', "买一价大于等于条件价"),
        BidPriceLesserThanStopPrice('F', "买一价小于条件价"),
        BidPriceLesserEqualStopPrice('H', "买一价小于等于条件价"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ForceCloseReason implements Coded {
        NotForceClose('0', "非强平"),
        LackDeposit('1', "资金不足"),
        ClientOverPositionLimit('2', "客户超仓"),
        MemberOverPositionLimit('3', "会员超仓"),
        NotMultiple('4', "持仓非整数倍"),
        Violation('5', "违规"),
        Other('6', "其它"),
        PersonDeliv('7', "自然人临近交割"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OrderSource implements Coded {
        Participant('0', "来自参与者"),
        Administrator('1', "来自管理员"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum OrderType implements Coded {
        Normal('0', "正常"),
        DeriveFromQuote('1', "报价衍生"),
        DeriveFromCombination('2', "组合衍生"),
        Combination('3', "组合报单"),
        ConditionalOrder('4', "条件单"),
        Swap('5', "互换单"),
        DeriveFromBlockTrade('6', "大宗交易成交衍生"),
        DeriveFromEFPTrade('7', "期转现成交衍生"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum TradingRole implements Coded {
        Broker('1', "代理"),
        Host('2', "自营"),
        Maker('3', "做市商"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum PriceSource implements Coded {
        LastPrice('0', "前成交价"),
        Buy('1', "买委托价"),
        Sell('2', "卖委托价"),
        OTC('3', "场外成交价"),
        ;
        private final char code;
        private final String label;
    }

    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum TradeSource implements Coded {
        NORMAL('0', "来自交易所普通回报"),
        QUERY('1', "来自查询"),
        ;
        private final char code;
        private final String label;
    }
}
