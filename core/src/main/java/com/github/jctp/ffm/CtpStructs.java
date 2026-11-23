package com.github.jctp.ffm;

import static com.github.jctp.ffm.CtpField.*;

/**
 * CTP x64 结构体布局（ThostFtdcUserApiStruct.h），每个 {@link CtpVersion} 一份，经 {@link CtpVersion#structs()} 取得。
 * <p>
 * 偏移与长度由各版本随附的官方头文件计算（自然对齐）。{@code reserveN} 占位字段已略去；
 * 某版本没有的字段为 {@link CtpField#absent}，读取得缺省值、写入抛异常。
 * 字段只含 char[]/char/short/int/double，MSVC 与 GCC 的 x64 布局一致。由头文件生成，请勿手改。
 */
@SuppressWarnings("unused")
public final class CtpStructs {

    static final CtpStructs V6_3_15 = new CtpStructs(CtpVersion.V6_3_15);
    static final CtpStructs V6_5_1 = new CtpStructs(CtpVersion.V6_5_1);
    static final CtpStructs V6_6_8 = new CtpStructs(CtpVersion.V6_6_8);
    static final CtpStructs V6_7_2 = new CtpStructs(CtpVersion.V6_7_2);
    static final CtpStructs V6_7_2_CP = new CtpStructs(CtpVersion.V6_7_2_CP);
    static final CtpStructs V6_7_8 = new CtpStructs(CtpVersion.V6_7_8);
    static final CtpStructs V6_7_8_CP = new CtpStructs(CtpVersion.V6_7_8_CP);

    static CtpStructs of(CtpVersion version) {
        return switch (version) {
            case V6_3_15 -> V6_3_15;
            case V6_5_1 -> V6_5_1;
            case V6_6_8 -> V6_6_8;
            case V6_7_2 -> V6_7_2;
            case V6_7_2_CP -> V6_7_2_CP;
            case V6_7_8 -> V6_7_8;
            case V6_7_8_CP -> V6_7_8_CP;
        };
    }

    public final CtpVersion version;
    public final ReqUserLogin reqUserLogin;
    public final RspUserLogin rspUserLogin;
    public final UserLogout userLogout;
    public final RspInfo rspInfo;
    public final SpecificInstrument specificInstrument;
    public final DepthMarketData depthMarketData;
    public final ReqAuthenticate reqAuthenticate;
    public final SettlementInfoConfirm settlementInfoConfirm;
    public final QrySettlementInfo qrySettlementInfo;
    public final SettlementInfo settlementInfo;
    public final QryInvestor qryInvestor;
    public final Investor investor;
    public final InputOrder inputOrder;
    public final InputOrderAction inputOrderAction;
    public final Order order;
    public final Trade trade;
    public final QryTradingAccount qryTradingAccount;
    public final TradingAccount tradingAccount;
    public final QryInvestorPosition qryInvestorPosition;
    public final InvestorPosition investorPosition;
    public final QryInstrument qryInstrument;
    public final Instrument instrument;
    public final QryProduct qryProduct;
    public final Product product;
    public final OrderAction orderAction;
    public final QryOrder qryOrder;
    public final QryTrade qryTrade;
    public final QryInstrumentMarginRate qryInstrumentMarginRate;
    public final InstrumentMarginRate instrumentMarginRate;
    public final QryInstrumentCommissionRate qryInstrumentCommissionRate;
    public final InstrumentCommissionRate instrumentCommissionRate;
    public final QryDepthMarketData qryDepthMarketData;
    public final QryInvestorPositionDetail qryInvestorPositionDetail;
    public final InvestorPositionDetail investorPositionDetail;
    public final InstrumentStatus instrumentStatus;
    public final TradingNoticeInfo tradingNoticeInfo;

    private CtpStructs(CtpVersion version) {
        this.version = version;
        reqUserLogin = new ReqUserLogin();
        rspUserLogin = new RspUserLogin();
        userLogout = new UserLogout();
        rspInfo = new RspInfo();
        specificInstrument = new SpecificInstrument();
        depthMarketData = new DepthMarketData();
        reqAuthenticate = new ReqAuthenticate();
        settlementInfoConfirm = new SettlementInfoConfirm();
        qrySettlementInfo = new QrySettlementInfo();
        settlementInfo = new SettlementInfo();
        qryInvestor = new QryInvestor();
        investor = new Investor();
        inputOrder = new InputOrder();
        inputOrderAction = new InputOrderAction();
        order = new Order();
        trade = new Trade();
        qryTradingAccount = new QryTradingAccount();
        tradingAccount = new TradingAccount();
        qryInvestorPosition = new QryInvestorPosition();
        investorPosition = new InvestorPosition();
        qryInstrument = new QryInstrument();
        instrument = new Instrument();
        qryProduct = new QryProduct();
        product = new Product();
        orderAction = new OrderAction();
        qryOrder = new QryOrder();
        qryTrade = new QryTrade();
        qryInstrumentMarginRate = new QryInstrumentMarginRate();
        instrumentMarginRate = new InstrumentMarginRate();
        qryInstrumentCommissionRate = new QryInstrumentCommissionRate();
        instrumentCommissionRate = new InstrumentCommissionRate();
        qryDepthMarketData = new QryDepthMarketData();
        qryInvestorPositionDetail = new QryInvestorPositionDetail();
        investorPositionDetail = new InvestorPositionDetail();
        instrumentStatus = new InstrumentStatus();
        tradingNoticeInfo = new TradingNoticeInfo();
    }

    /**
     * 按版本取值，实参顺序与 {@link CtpVersion} 声明顺序一致
     */
    private CtpField pick(CtpField... byVersion) {
        return byVersion[version.ordinal()];
    }

    private long pick(long... byVersion) {
        return byVersion[version.ordinal()];
    }

    /**
     * CThostFtdcReqUserLoginField
     */
    public final class ReqUserLogin {
        public final long SIZE = pick(228, 264, 264, 264, 264, 264, 264);
        public final CtpField TradingDay = str(0, 9);
        public final CtpField BrokerID = str(9, 11);
        public final CtpField UserID = str(20, 16);
        public final CtpField Password = str(36, 41);
        public final CtpField UserProductInfo = str(77, 11);
        public final CtpField InterfaceProductInfo = str(88, 11);
        public final CtpField ProtocolInfo = str(99, 11);
        public final CtpField MacAddress = str(110, 21);
        public final CtpField OneTimePassword = str(131, 41);
        public final CtpField ClientIPAddress = pick(str(172, 16), str(228, 33), str(228, 33), str(228, 33), str(228, 33), str(228, 33), str(228, 33));
        public final CtpField LoginRemark = str(188, 36);
        public final CtpField ClientIPPort = i32(224);

        private ReqUserLogin() {
        }
    }

    /**
     * CThostFtdcRspUserLoginField
     */
    public final class RspUserLogin {
        public final long SIZE = pick(156, 156, 204, 204, 204, 212, 212);
        public final CtpField TradingDay = str(0, 9);
        public final CtpField LoginTime = str(9, 9);
        public final CtpField BrokerID = str(18, 11);
        public final CtpField UserID = str(29, 16);
        public final CtpField SystemName = str(45, 41);
        public final CtpField FrontID = i32(88);
        public final CtpField SessionID = i32(92);
        public final CtpField MaxOrderRef = str(96, 13);
        public final CtpField SHFETime = str(109, 9);
        public final CtpField DCETime = str(118, 9);
        public final CtpField CZCETime = str(127, 9);
        public final CtpField FFEXTime = str(136, 9);
        public final CtpField INETime = str(145, 9);

        private RspUserLogin() {
        }
    }

    /**
     * CThostFtdcUserLogoutField
     */
    public final class UserLogout {
        public final long SIZE = 27;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField UserID = str(11, 16);

        private UserLogout() {
        }
    }

    /**
     * CThostFtdcRspInfoField
     */
    public final class RspInfo {
        public final long SIZE = 88;
        public final CtpField ErrorID = i32(0);
        public final CtpField ErrorMsg = str(4, 81);

        private RspInfo() {
        }
    }

    /**
     * CThostFtdcSpecificInstrumentField
     */
    public final class SpecificInstrument {
        public final long SIZE = pick(31, 112, 112, 112, 112, 112, 112);
        public final CtpField InstrumentID = pick(str(0, 31), str(31, 81), str(31, 81), str(31, 81), str(31, 81), str(31, 81), str(31, 81));

        private SpecificInstrument() {
        }
    }

    /**
     * CThostFtdcDepthMarketDataField
     */
    public final class DepthMarketData {
        public final long SIZE = pick(408, 568, 584, 584, 584, 584, 584);
        public final CtpField TradingDay = str(0, 9);
        public final CtpField InstrumentID = pick(str(9, 31), str(401, 81), str(401, 81), str(401, 81), str(401, 81), str(401, 81), str(401, 81));
        public final CtpField ExchangeID = str(40, 9);
        public final CtpField ExchangeInstID = pick(str(49, 31), str(482, 81), str(482, 81), str(482, 81), str(482, 81), str(482, 81), str(482, 81));
        public final CtpField LastPrice = f64(80);
        public final CtpField PreSettlementPrice = f64(88);
        public final CtpField PreClosePrice = f64(96);
        public final CtpField PreOpenInterest = f64(104);
        public final CtpField OpenPrice = f64(112);
        public final CtpField HighestPrice = f64(120);
        public final CtpField LowestPrice = f64(128);
        public final CtpField Volume = i32(136);
        public final CtpField Turnover = f64(144);
        public final CtpField OpenInterest = f64(152);
        public final CtpField ClosePrice = f64(160);
        public final CtpField SettlementPrice = f64(168);
        public final CtpField UpperLimitPrice = f64(176);
        public final CtpField LowerLimitPrice = f64(184);
        public final CtpField PreDelta = f64(192);
        public final CtpField CurrDelta = f64(200);
        public final CtpField UpdateTime = str(208, 9);
        public final CtpField UpdateMillisec = i32(220);
        public final CtpField BidPrice1 = f64(224);
        public final CtpField BidVolume1 = i32(232);
        public final CtpField AskPrice1 = f64(240);
        public final CtpField AskVolume1 = i32(248);
        public final CtpField BidPrice2 = f64(256);
        public final CtpField BidVolume2 = i32(264);
        public final CtpField AskPrice2 = f64(272);
        public final CtpField AskVolume2 = i32(280);
        public final CtpField BidPrice3 = f64(288);
        public final CtpField BidVolume3 = i32(296);
        public final CtpField AskPrice3 = f64(304);
        public final CtpField AskVolume3 = i32(312);
        public final CtpField BidPrice4 = f64(320);
        public final CtpField BidVolume4 = i32(328);
        public final CtpField AskPrice4 = f64(336);
        public final CtpField AskVolume4 = i32(344);
        public final CtpField BidPrice5 = f64(352);
        public final CtpField BidVolume5 = i32(360);
        public final CtpField AskPrice5 = f64(368);
        public final CtpField AskVolume5 = i32(376);
        public final CtpField AveragePrice = f64(384);
        public final CtpField ActionDay = str(392, 9);

        private DepthMarketData() {
        }
    }

    /**
     * CThostFtdcReqAuthenticateField
     */
    public final class ReqAuthenticate {
        public final long SIZE = 88;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField UserID = str(11, 16);
        public final CtpField UserProductInfo = str(27, 11);
        public final CtpField AuthCode = str(38, 17);
        public final CtpField AppID = str(55, 33);

        private ReqAuthenticate() {
        }
    }

    /**
     * CThostFtdcSettlementInfoConfirmField
     */
    public final class SettlementInfoConfirm {
        public final long SIZE = 68;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField ConfirmDate = str(24, 9);
        public final CtpField ConfirmTime = str(33, 9);
        public final CtpField SettlementID = i32(44);
        public final CtpField AccountID = str(48, 13);
        public final CtpField CurrencyID = str(61, 4);

        private SettlementInfoConfirm() {
        }
    }

    /**
     * CThostFtdcQrySettlementInfoField
     */
    public final class QrySettlementInfo {
        public final long SIZE = 50;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField TradingDay = str(24, 9);
        public final CtpField AccountID = str(33, 13);
        public final CtpField CurrencyID = str(46, 4);

        private QrySettlementInfo() {
        }
    }

    /**
     * CThostFtdcSettlementInfoField
     */
    public final class SettlementInfo {
        public final long SIZE = 564;
        public final CtpField TradingDay = str(0, 9);
        public final CtpField SettlementID = i32(12);
        public final CtpField BrokerID = str(16, 11);
        public final CtpField InvestorID = str(27, 13);
        public final CtpField SequenceNo = i32(40);
        public final CtpField Content = str(44, 501);
        public final CtpField AccountID = str(545, 13);
        public final CtpField CurrencyID = str(558, 4);

        private SettlementInfo() {
        }
    }

    /**
     * CThostFtdcQryInvestorField
     */
    public final class QryInvestor {
        public final long SIZE = 24;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);

        private QryInvestor() {
        }
    }

    /**
     * CThostFtdcInvestorField
     */
    public final class Investor {
        public final long SIZE = 396;
        public final CtpField InvestorID = str(0, 13);
        public final CtpField BrokerID = str(13, 11);
        public final CtpField InvestorGroupID = str(24, 13);
        public final CtpField InvestorName = str(37, 81);
        public final CtpField IdentifiedCardType = chr(118);
        public final CtpField IdentifiedCardNo = str(119, 51);
        public final CtpField IsActive = i32(172);
        public final CtpField Telephone = str(176, 41);
        public final CtpField Address = str(217, 101);
        public final CtpField OpenDate = str(318, 9);
        public final CtpField Mobile = str(327, 41);
        public final CtpField CommModelID = str(368, 13);
        public final CtpField MarginModelID = str(381, 13);

        private Investor() {
        }
    }

    /**
     * CThostFtdcInputOrderField
     */
    public final class InputOrder {
        public final long SIZE = pick(272, 392, 392, 392, 392, 408, 408);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(271, 81), str(271, 81), str(271, 81), str(271, 81), str(271, 81), str(271, 81));
        public final CtpField OrderRef = str(55, 13);
        public final CtpField UserID = str(68, 16);
        public final CtpField OrderPriceType = chr(84);
        public final CtpField Direction = chr(85);
        public final CtpField CombOffsetFlag = str(86, 5);
        public final CtpField CombHedgeFlag = str(91, 5);
        public final CtpField LimitPrice = f64(96);
        public final CtpField VolumeTotalOriginal = i32(104);
        public final CtpField TimeCondition = chr(108);
        public final CtpField GTDDate = str(109, 9);
        public final CtpField VolumeCondition = chr(118);
        public final CtpField MinVolume = i32(120);
        public final CtpField ContingentCondition = chr(124);
        public final CtpField StopPrice = f64(128);
        public final CtpField ForceCloseReason = chr(136);
        public final CtpField IsAutoSuspend = i32(140);
        public final CtpField BusinessUnit = str(144, 21);
        public final CtpField RequestID = i32(168);
        public final CtpField UserForceClose = i32(172);
        public final CtpField IsSwapOrder = i32(176);
        public final CtpField ExchangeID = str(180, 9);
        public final CtpField InvestUnitID = str(189, 17);
        public final CtpField AccountID = str(206, 13);
        public final CtpField CurrencyID = str(219, 4);
        public final CtpField ClientID = str(223, 11);
        public final CtpField IPAddress = pick(str(234, 16), str(352, 33), str(352, 33), str(352, 33), str(352, 33), str(352, 33), str(352, 33));
        public final CtpField MacAddress = str(250, 21);

        private InputOrder() {
        }
    }

    /**
     * CThostFtdcInputOrderActionField
     */
    public final class InputOrderAction {
        public final long SIZE = pick(208, 320, 320, 320, 320, 336, 336);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField OrderActionRef = i32(24);
        public final CtpField OrderRef = str(28, 13);
        public final CtpField RequestID = i32(44);
        public final CtpField FrontID = i32(48);
        public final CtpField SessionID = i32(52);
        public final CtpField ExchangeID = str(56, 9);
        public final CtpField OrderSysID = str(65, 21);
        public final CtpField ActionFlag = chr(86);
        public final CtpField LimitPrice = f64(88);
        public final CtpField VolumeChange = i32(96);
        public final CtpField UserID = str(100, 16);
        public final CtpField InstrumentID = pick(str(116, 31), str(201, 81), str(201, 81), str(201, 81), str(201, 81), str(201, 81), str(201, 81));
        public final CtpField InvestUnitID = str(147, 17);
        public final CtpField IPAddress = pick(str(164, 16), str(282, 33), str(282, 33), str(282, 33), str(282, 33), str(282, 33), str(282, 33));
        public final CtpField MacAddress = str(180, 21);

        private InputOrderAction() {
        }
    }

    /**
     * CThostFtdcOrderField
     */
    public final class Order {
        public final long SIZE = pick(656, 856, 856, 856, 856, 872, 872);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(656, 81), str(656, 81), str(656, 81), str(656, 81), str(656, 81), str(656, 81));
        public final CtpField OrderRef = str(55, 13);
        public final CtpField UserID = str(68, 16);
        public final CtpField OrderPriceType = chr(84);
        public final CtpField Direction = chr(85);
        public final CtpField CombOffsetFlag = str(86, 5);
        public final CtpField CombHedgeFlag = str(91, 5);
        public final CtpField LimitPrice = f64(96);
        public final CtpField VolumeTotalOriginal = i32(104);
        public final CtpField TimeCondition = chr(108);
        public final CtpField GTDDate = str(109, 9);
        public final CtpField VolumeCondition = chr(118);
        public final CtpField MinVolume = i32(120);
        public final CtpField ContingentCondition = chr(124);
        public final CtpField StopPrice = f64(128);
        public final CtpField ForceCloseReason = chr(136);
        public final CtpField IsAutoSuspend = i32(140);
        public final CtpField BusinessUnit = str(144, 21);
        public final CtpField RequestID = i32(168);
        public final CtpField OrderLocalID = str(172, 13);
        public final CtpField ExchangeID = str(185, 9);
        public final CtpField ParticipantID = str(194, 11);
        public final CtpField ClientID = str(205, 11);
        public final CtpField ExchangeInstID = pick(str(216, 31), str(737, 81), str(737, 81), str(737, 81), str(737, 81), str(737, 81), str(737, 81));
        public final CtpField TraderID = str(247, 21);
        public final CtpField InstallID = i32(268);
        public final CtpField OrderSubmitStatus = chr(272);
        public final CtpField NotifySequence = i32(276);
        public final CtpField TradingDay = str(280, 9);
        public final CtpField SettlementID = i32(292);
        public final CtpField OrderSysID = str(296, 21);
        public final CtpField OrderSource = chr(317);
        public final CtpField OrderStatus = chr(318);
        public final CtpField OrderType = chr(319);
        public final CtpField VolumeTraded = i32(320);
        public final CtpField VolumeTotal = i32(324);
        public final CtpField InsertDate = str(328, 9);
        public final CtpField InsertTime = str(337, 9);
        public final CtpField ActiveTime = str(346, 9);
        public final CtpField SuspendTime = str(355, 9);
        public final CtpField UpdateTime = str(364, 9);
        public final CtpField CancelTime = str(373, 9);
        public final CtpField ActiveTraderID = str(382, 21);
        public final CtpField ClearingPartID = str(403, 11);
        public final CtpField SequenceNo = i32(416);
        public final CtpField FrontID = i32(420);
        public final CtpField SessionID = i32(424);
        public final CtpField UserProductInfo = str(428, 11);
        public final CtpField StatusMsg = str(439, 81);
        public final CtpField UserForceClose = i32(520);
        public final CtpField ActiveUserID = str(524, 16);
        public final CtpField BrokerOrderSeq = i32(540);
        public final CtpField RelativeOrderSysID = str(544, 21);
        public final CtpField ZCETotalTradedVolume = i32(568);
        public final CtpField IsSwapOrder = i32(572);
        public final CtpField BranchID = str(576, 9);
        public final CtpField InvestUnitID = str(585, 17);
        public final CtpField AccountID = str(602, 13);
        public final CtpField CurrencyID = str(615, 4);
        public final CtpField IPAddress = pick(str(619, 16), str(818, 33), str(818, 33), str(818, 33), str(818, 33), str(818, 33), str(818, 33));
        public final CtpField MacAddress = str(635, 21);

        private Order() {
        }
    }

    /**
     * CThostFtdcTradeField
     */
    public final class Trade {
        public final long SIZE = pick(336, 496, 496, 496, 496, 496, 496);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(334, 81), str(334, 81), str(334, 81), str(334, 81), str(334, 81), str(334, 81));
        public final CtpField OrderRef = str(55, 13);
        public final CtpField UserID = str(68, 16);
        public final CtpField ExchangeID = str(84, 9);
        public final CtpField TradeID = str(93, 21);
        public final CtpField Direction = chr(114);
        public final CtpField OrderSysID = str(115, 21);
        public final CtpField ParticipantID = str(136, 11);
        public final CtpField ClientID = str(147, 11);
        public final CtpField TradingRole = chr(158);
        public final CtpField ExchangeInstID = pick(str(159, 31), str(415, 81), str(415, 81), str(415, 81), str(415, 81), str(415, 81), str(415, 81));
        public final CtpField OffsetFlag = chr(190);
        public final CtpField HedgeFlag = chr(191);
        public final CtpField Price = f64(192);
        public final CtpField Volume = i32(200);
        public final CtpField TradeDate = str(204, 9);
        public final CtpField TradeTime = str(213, 9);
        public final CtpField TradeType = chr(222);
        public final CtpField PriceSource = chr(223);
        public final CtpField TraderID = str(224, 21);
        public final CtpField OrderLocalID = str(245, 13);
        public final CtpField ClearingPartID = str(258, 11);
        public final CtpField BusinessUnit = str(269, 21);
        public final CtpField SequenceNo = i32(292);
        public final CtpField TradingDay = str(296, 9);
        public final CtpField SettlementID = i32(308);
        public final CtpField BrokerOrderSeq = i32(312);
        public final CtpField TradeSource = chr(316);
        public final CtpField InvestUnitID = str(317, 17);

        private Trade() {
        }
    }

    /**
     * CThostFtdcQryTradingAccountField
     */
    public final class QryTradingAccount {
        public final long SIZE = 42;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField CurrencyID = str(24, 4);
        public final CtpField BizType = chr(28);
        public final CtpField AccountID = str(29, 13);

        private QryTradingAccount() {
        }
    }

    /**
     * CThostFtdcTradingAccountField
     */
    public final class TradingAccount {
        public final long SIZE = 400;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField AccountID = str(11, 13);
        public final CtpField PreMortgage = f64(24);
        public final CtpField PreCredit = f64(32);
        public final CtpField PreDeposit = f64(40);
        public final CtpField PreBalance = f64(48);
        public final CtpField PreMargin = f64(56);
        public final CtpField InterestBase = f64(64);
        public final CtpField Interest = f64(72);
        public final CtpField Deposit = f64(80);
        public final CtpField Withdraw = f64(88);
        public final CtpField FrozenMargin = f64(96);
        public final CtpField FrozenCash = f64(104);
        public final CtpField FrozenCommission = f64(112);
        public final CtpField CurrMargin = f64(120);
        public final CtpField CashIn = f64(128);
        public final CtpField Commission = f64(136);
        public final CtpField CloseProfit = f64(144);
        public final CtpField PositionProfit = f64(152);
        public final CtpField Balance = f64(160);
        public final CtpField Available = f64(168);
        public final CtpField WithdrawQuota = f64(176);
        public final CtpField Reserve = f64(184);
        public final CtpField TradingDay = str(192, 9);
        public final CtpField SettlementID = i32(204);
        public final CtpField Credit = f64(208);
        public final CtpField Mortgage = f64(216);
        public final CtpField ExchangeMargin = f64(224);
        public final CtpField DeliveryMargin = f64(232);
        public final CtpField ExchangeDeliveryMargin = f64(240);
        public final CtpField ReserveBalance = f64(248);
        public final CtpField CurrencyID = str(256, 4);
        public final CtpField PreFundMortgageIn = f64(264);
        public final CtpField PreFundMortgageOut = f64(272);
        public final CtpField FundMortgageIn = f64(280);
        public final CtpField FundMortgageOut = f64(288);
        public final CtpField FundMortgageAvailable = f64(296);
        public final CtpField MortgageableFund = f64(304);
        public final CtpField SpecProductMargin = f64(312);
        public final CtpField SpecProductFrozenMargin = f64(320);
        public final CtpField SpecProductCommission = f64(328);
        public final CtpField SpecProductFrozenCommission = f64(336);
        public final CtpField SpecProductPositionProfit = f64(344);
        public final CtpField SpecProductCloseProfit = f64(352);
        public final CtpField SpecProductPositionProfitByAlg = f64(360);
        public final CtpField SpecProductExchangeMargin = f64(368);
        public final CtpField BizType = chr(376);
        public final CtpField FrozenSwap = f64(384);
        public final CtpField RemainSwap = f64(392);

        private TradingAccount() {
        }
    }

    /**
     * CThostFtdcQryInvestorPositionField
     */
    public final class QryInvestorPosition {
        public final long SIZE = pick(81, 162, 162, 162, 162, 162, 162);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81));
        public final CtpField ExchangeID = str(55, 9);
        public final CtpField InvestUnitID = str(64, 17);

        private QryInvestorPosition() {
        }
    }

    /**
     * CThostFtdcInvestorPositionField
     */
    public final class InvestorPosition {
        public final long SIZE = pick(368, 472, 472, 472, 472, 472, 472);
        public final CtpField InstrumentID = pick(str(0, 31), str(384, 81), str(384, 81), str(384, 81), str(384, 81), str(384, 81), str(384, 81));
        public final CtpField BrokerID = str(31, 11);
        public final CtpField InvestorID = str(42, 13);
        public final CtpField PosiDirection = chr(55);
        public final CtpField HedgeFlag = chr(56);
        public final CtpField PositionDate = chr(57);
        public final CtpField YdPosition = i32(60);
        public final CtpField Position = i32(64);
        public final CtpField LongFrozen = i32(68);
        public final CtpField ShortFrozen = i32(72);
        public final CtpField LongFrozenAmount = f64(80);
        public final CtpField ShortFrozenAmount = f64(88);
        public final CtpField OpenVolume = i32(96);
        public final CtpField CloseVolume = i32(100);
        public final CtpField OpenAmount = f64(104);
        public final CtpField CloseAmount = f64(112);
        public final CtpField PositionCost = f64(120);
        public final CtpField PreMargin = f64(128);
        public final CtpField UseMargin = f64(136);
        public final CtpField FrozenMargin = f64(144);
        public final CtpField FrozenCash = f64(152);
        public final CtpField FrozenCommission = f64(160);
        public final CtpField CashIn = f64(168);
        public final CtpField Commission = f64(176);
        public final CtpField CloseProfit = f64(184);
        public final CtpField PositionProfit = f64(192);
        public final CtpField PreSettlementPrice = f64(200);
        public final CtpField SettlementPrice = f64(208);
        public final CtpField TradingDay = str(216, 9);
        public final CtpField SettlementID = i32(228);
        public final CtpField OpenCost = f64(232);
        public final CtpField ExchangeMargin = f64(240);
        public final CtpField CombPosition = i32(248);
        public final CtpField CombLongFrozen = i32(252);
        public final CtpField CombShortFrozen = i32(256);
        public final CtpField CloseProfitByDate = f64(264);
        public final CtpField CloseProfitByTrade = f64(272);
        public final CtpField TodayPosition = i32(280);
        public final CtpField MarginRateByMoney = f64(288);
        public final CtpField MarginRateByVolume = f64(296);
        public final CtpField StrikeFrozen = i32(304);
        public final CtpField StrikeFrozenAmount = f64(312);
        public final CtpField AbandonFrozen = i32(320);
        public final CtpField ExchangeID = str(324, 9);
        public final CtpField YdStrikeFrozen = i32(336);
        public final CtpField InvestUnitID = str(340, 17);
        public final CtpField PositionCostOffset = f64(360);
        public final CtpField TasPosition = pick(absent(Kind.INT), i32(368), i32(368), i32(368), i32(368), i32(368), i32(368));
        public final CtpField TasPositionCost = pick(absent(Kind.DOUBLE), f64(376), f64(376), f64(376), f64(376), f64(376), f64(376));

        private InvestorPosition() {
        }
    }

    /**
     * CThostFtdcQryInstrumentField
     */
    public final class QryInstrument {
        public final long SIZE = pick(102, 345, 345, 345, 345, 345, 345);
        public final CtpField InstrumentID = pick(str(0, 31), str(102, 81), str(102, 81), str(102, 81), str(102, 81), str(102, 81), str(102, 81));
        public final CtpField ExchangeID = str(31, 9);
        public final CtpField ExchangeInstID = pick(str(40, 31), str(183, 81), str(183, 81), str(183, 81), str(183, 81), str(183, 81), str(183, 81));
        public final CtpField ProductID = pick(str(71, 31), str(264, 81), str(264, 81), str(264, 81), str(264, 81), str(264, 81), str(264, 81));

        private QryInstrument() {
        }
    }

    /**
     * CThostFtdcInstrumentField
     */
    public final class Instrument {
        public final long SIZE = pick(296, 616, 616, 616, 616, 616, 616);
        public final CtpField InstrumentID = pick(str(0, 31), str(289, 81), str(289, 81), str(289, 81), str(289, 81), str(289, 81), str(289, 81));
        public final CtpField ExchangeID = str(31, 9);
        public final CtpField InstrumentName = str(40, 21);
        public final CtpField ExchangeInstID = pick(str(61, 31), str(370, 81), str(370, 81), str(370, 81), str(370, 81), str(370, 81), str(370, 81));
        public final CtpField ProductID = pick(str(92, 31), str(451, 81), str(451, 81), str(451, 81), str(451, 81), str(451, 81), str(451, 81));
        public final CtpField ProductClass = chr(123);
        public final CtpField DeliveryYear = i32(124);
        public final CtpField DeliveryMonth = i32(128);
        public final CtpField MaxMarketOrderVolume = i32(132);
        public final CtpField MinMarketOrderVolume = i32(136);
        public final CtpField MaxLimitOrderVolume = i32(140);
        public final CtpField MinLimitOrderVolume = i32(144);
        public final CtpField VolumeMultiple = i32(148);
        public final CtpField PriceTick = f64(152);
        public final CtpField CreateDate = str(160, 9);
        public final CtpField OpenDate = str(169, 9);
        public final CtpField ExpireDate = str(178, 9);
        public final CtpField StartDelivDate = str(187, 9);
        public final CtpField EndDelivDate = str(196, 9);
        public final CtpField InstLifePhase = chr(205);
        public final CtpField IsTrading = i32(208);
        public final CtpField PositionType = chr(212);
        public final CtpField PositionDateType = chr(213);
        public final CtpField LongMarginRatio = f64(216);
        public final CtpField ShortMarginRatio = f64(224);
        public final CtpField MaxMarginSideAlgorithm = chr(232);
        public final CtpField UnderlyingInstrID = pick(str(233, 31), str(532, 81), str(532, 81), str(532, 81), str(532, 81), str(532, 81), str(532, 81));
        public final CtpField StrikePrice = f64(264);
        public final CtpField OptionsType = chr(272);
        public final CtpField UnderlyingMultiple = f64(280);
        public final CtpField CombinationType = chr(288);

        private Instrument() {
        }
    }

    /**
     * CThostFtdcQryProductField
     */
    public final class QryProduct {
        public final long SIZE = pick(41, 122, 122, 122, 122, 122, 122);
        public final CtpField ProductID = pick(str(0, 31), str(41, 81), str(41, 81), str(41, 81), str(41, 81), str(41, 81), str(41, 81));
        public final CtpField ProductClass = chr(31);
        public final CtpField ExchangeID = str(32, 9);

        private QryProduct() {
        }
    }

    /**
     * CThostFtdcProductField
     */
    public final class Product {
        public final long SIZE = pick(144, 312, 312, 312, 312, 312, 312);
        public final CtpField ProductID = pick(str(0, 31), str(144, 81), str(144, 81), str(144, 81), str(144, 81), str(144, 81), str(144, 81));
        public final CtpField ProductName = str(31, 21);
        public final CtpField ExchangeID = str(52, 9);
        public final CtpField ProductClass = chr(61);
        public final CtpField VolumeMultiple = i32(64);
        public final CtpField PriceTick = f64(72);
        public final CtpField MaxMarketOrderVolume = i32(80);
        public final CtpField MinMarketOrderVolume = i32(84);
        public final CtpField MaxLimitOrderVolume = i32(88);
        public final CtpField MinLimitOrderVolume = i32(92);
        public final CtpField PositionType = chr(96);
        public final CtpField PositionDateType = chr(97);
        public final CtpField CloseDealType = chr(98);
        public final CtpField TradeCurrencyID = str(99, 4);
        public final CtpField MortgageFundUseRange = chr(103);
        public final CtpField ExchangeProductID = pick(str(104, 31), str(225, 81), str(225, 81), str(225, 81), str(225, 81), str(225, 81), str(225, 81));
        public final CtpField UnderlyingMultiple = f64(136);

        private Product() {
        }
    }

    /**
     * CThostFtdcOrderActionField
     */
    public final class OrderAction {
        public final long SIZE = pick(408, 520, 520, 520, 520, 536, 536);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField OrderActionRef = i32(24);
        public final CtpField OrderRef = str(28, 13);
        public final CtpField RequestID = i32(44);
        public final CtpField FrontID = i32(48);
        public final CtpField SessionID = i32(52);
        public final CtpField ExchangeID = str(56, 9);
        public final CtpField OrderSysID = str(65, 21);
        public final CtpField ActionFlag = chr(86);
        public final CtpField LimitPrice = f64(88);
        public final CtpField VolumeChange = i32(96);
        public final CtpField ActionDate = str(100, 9);
        public final CtpField ActionTime = str(109, 9);
        public final CtpField TraderID = str(118, 21);
        public final CtpField InstallID = i32(140);
        public final CtpField OrderLocalID = str(144, 13);
        public final CtpField ActionLocalID = str(157, 13);
        public final CtpField ParticipantID = str(170, 11);
        public final CtpField ClientID = str(181, 11);
        public final CtpField BusinessUnit = str(192, 21);
        public final CtpField OrderActionStatus = chr(213);
        public final CtpField UserID = str(214, 16);
        public final CtpField StatusMsg = str(230, 81);
        public final CtpField InstrumentID = pick(str(311, 31), str(405, 81), str(405, 81), str(405, 81), str(405, 81), str(405, 81), str(405, 81));
        public final CtpField BranchID = str(342, 9);
        public final CtpField InvestUnitID = str(351, 17);
        public final CtpField IPAddress = pick(str(368, 16), str(486, 33), str(486, 33), str(486, 33), str(486, 33), str(486, 33), str(486, 33));
        public final CtpField MacAddress = str(384, 21);

        private OrderAction() {
        }
    }

    /**
     * CThostFtdcQryOrderField
     */
    public final class QryOrder {
        public final long SIZE = pick(120, 201, 201, 201, 201, 201, 201);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(120, 81), str(120, 81), str(120, 81), str(120, 81), str(120, 81), str(120, 81));
        public final CtpField ExchangeID = str(55, 9);
        public final CtpField OrderSysID = str(64, 21);
        public final CtpField InsertTimeStart = str(85, 9);
        public final CtpField InsertTimeEnd = str(94, 9);
        public final CtpField InvestUnitID = str(103, 17);

        private QryOrder() {
        }
    }

    /**
     * CThostFtdcQryTradeField
     */
    public final class QryTrade {
        public final long SIZE = pick(120, 201, 201, 201, 201, 201, 201);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(120, 81), str(120, 81), str(120, 81), str(120, 81), str(120, 81), str(120, 81));
        public final CtpField ExchangeID = str(55, 9);
        public final CtpField TradeID = str(64, 21);
        public final CtpField TradeTimeStart = str(85, 9);
        public final CtpField TradeTimeEnd = str(94, 9);
        public final CtpField InvestUnitID = str(103, 17);

        private QryTrade() {
        }
    }

    /**
     * CThostFtdcQryInstrumentMarginRateField
     */
    public final class QryInstrumentMarginRate {
        public final long SIZE = pick(82, 163, 163, 163, 163, 163, 163);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(82, 81), str(82, 81), str(82, 81), str(82, 81), str(82, 81), str(82, 81));
        public final CtpField HedgeFlag = chr(55);
        public final CtpField ExchangeID = str(56, 9);
        public final CtpField InvestUnitID = str(65, 17);

        private QryInstrumentMarginRate() {
        }
    }

    /**
     * CThostFtdcInstrumentMarginRateField
     */
    public final class InstrumentMarginRate {
        public final long SIZE = pick(128, 208, 208, 208, 208, 208, 208);
        public final CtpField InstrumentID = pick(str(0, 31), str(126, 81), str(126, 81), str(126, 81), str(126, 81), str(126, 81), str(126, 81));
        public final CtpField InvestorRange = chr(31);
        public final CtpField BrokerID = str(32, 11);
        public final CtpField InvestorID = str(43, 13);
        public final CtpField HedgeFlag = chr(56);
        public final CtpField LongMarginRatioByMoney = f64(64);
        public final CtpField LongMarginRatioByVolume = f64(72);
        public final CtpField ShortMarginRatioByMoney = f64(80);
        public final CtpField ShortMarginRatioByVolume = f64(88);
        public final CtpField IsRelative = i32(96);
        public final CtpField ExchangeID = str(100, 9);
        public final CtpField InvestUnitID = str(109, 17);

        private InstrumentMarginRate() {
        }
    }

    /**
     * CThostFtdcQryInstrumentCommissionRateField
     */
    public final class QryInstrumentCommissionRate {
        public final long SIZE = pick(81, 162, 162, 162, 162, 162, 162);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81));
        public final CtpField ExchangeID = str(55, 9);
        public final CtpField InvestUnitID = str(64, 17);

        private QryInstrumentCommissionRate() {
        }
    }

    /**
     * CThostFtdcInstrumentCommissionRateField
     */
    public final class InstrumentCommissionRate {
        public final long SIZE = pick(136, 216, 216, 216, 216, 216, 216);
        public final CtpField InstrumentID = pick(str(0, 31), str(131, 81), str(131, 81), str(131, 81), str(131, 81), str(131, 81), str(131, 81));
        public final CtpField InvestorRange = chr(31);
        public final CtpField BrokerID = str(32, 11);
        public final CtpField InvestorID = str(43, 13);
        public final CtpField OpenRatioByMoney = f64(56);
        public final CtpField OpenRatioByVolume = f64(64);
        public final CtpField CloseRatioByMoney = f64(72);
        public final CtpField CloseRatioByVolume = f64(80);
        public final CtpField CloseTodayRatioByMoney = f64(88);
        public final CtpField CloseTodayRatioByVolume = f64(96);
        public final CtpField ExchangeID = str(104, 9);
        public final CtpField BizType = chr(113);
        public final CtpField InvestUnitID = str(114, 17);

        private InstrumentCommissionRate() {
        }
    }

    /**
     * CThostFtdcQryDepthMarketDataField
     */
    public final class QryDepthMarketData {
        public final long SIZE = pick(40, 121, 121, 121, 121, 122, 122);
        public final CtpField InstrumentID = pick(str(0, 31), str(40, 81), str(40, 81), str(40, 81), str(40, 81), str(40, 81), str(40, 81));
        public final CtpField ExchangeID = str(31, 9);

        private QryDepthMarketData() {
        }
    }

    /**
     * CThostFtdcQryInvestorPositionDetailField
     */
    public final class QryInvestorPositionDetail {
        public final long SIZE = pick(81, 162, 162, 162, 162, 162, 162);
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField InstrumentID = pick(str(24, 31), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81), str(81, 81));
        public final CtpField ExchangeID = str(55, 9);
        public final CtpField InvestUnitID = str(64, 17);

        private QryInvestorPositionDetail() {
        }
    }

    /**
     * CThostFtdcInvestorPositionDetailField
     */
    public final class InvestorPositionDetail {
        public final long SIZE = pick(288, 448, 448, 448, 448, 448, 448);
        public final CtpField InstrumentID = pick(str(0, 31), str(286, 81), str(286, 81), str(286, 81), str(286, 81), str(286, 81), str(286, 81));
        public final CtpField BrokerID = str(31, 11);
        public final CtpField InvestorID = str(42, 13);
        public final CtpField HedgeFlag = chr(55);
        public final CtpField Direction = chr(56);
        public final CtpField OpenDate = str(57, 9);
        public final CtpField TradeID = str(66, 21);
        public final CtpField Volume = i32(88);
        public final CtpField OpenPrice = f64(96);
        public final CtpField TradingDay = str(104, 9);
        public final CtpField SettlementID = i32(116);
        public final CtpField TradeType = chr(120);
        public final CtpField CombInstrumentID = pick(str(121, 31), str(367, 81), str(367, 81), str(367, 81), str(367, 81), str(367, 81), str(367, 81));
        public final CtpField ExchangeID = str(152, 9);
        public final CtpField CloseProfitByDate = f64(168);
        public final CtpField CloseProfitByTrade = f64(176);
        public final CtpField PositionProfitByDate = f64(184);
        public final CtpField PositionProfitByTrade = f64(192);
        public final CtpField Margin = f64(200);
        public final CtpField ExchMargin = f64(208);
        public final CtpField MarginRateByMoney = f64(216);
        public final CtpField MarginRateByVolume = f64(224);
        public final CtpField LastSettlementPrice = f64(232);
        public final CtpField SettlementPrice = f64(240);
        public final CtpField CloseVolume = i32(248);
        public final CtpField CloseAmount = f64(256);
        public final CtpField TimeFirstVolume = i32(264);
        public final CtpField InvestUnitID = str(268, 17);
        public final CtpField SpecPosiType = pick(absent(Kind.CHAR), chr(285), chr(285), chr(285), chr(285), chr(285), chr(285));

        private InvestorPositionDetail() {
        }
    }

    /**
     * CThostFtdcInstrumentStatusField
     */
    public final class InstrumentStatus {
        public final long SIZE = pick(100, 260, 260, 260, 260, 260, 260);
        public final CtpField ExchangeID = str(0, 9);
        public final CtpField ExchangeInstID = pick(str(9, 31), str(98, 81), str(98, 81), str(98, 81), str(98, 81), str(98, 81), str(98, 81));
        public final CtpField SettlementGroupID = str(40, 9);
        public final CtpField InstrumentID = pick(str(49, 31), str(179, 81), str(179, 81), str(179, 81), str(179, 81), str(179, 81), str(179, 81));
        public final CtpField InstrumentStatus = chr(80);
        public final CtpField TradingSegmentSN = i32(84);
        public final CtpField EnterTime = str(88, 9);
        public final CtpField EnterReason = chr(97);

        private InstrumentStatus() {
        }
    }

    /**
     * CThostFtdcTradingNoticeInfoField
     */
    public final class TradingNoticeInfo {
        public final long SIZE = 560;
        public final CtpField BrokerID = str(0, 11);
        public final CtpField InvestorID = str(11, 13);
        public final CtpField SendTime = str(24, 9);
        public final CtpField FieldContent = str(33, 501);
        public final CtpField SequenceSeries = i16(534);
        public final CtpField SequenceNo = i32(536);
        public final CtpField InvestUnitID = str(540, 17);

        private TradingNoticeInfo() {
        }
    }
}
