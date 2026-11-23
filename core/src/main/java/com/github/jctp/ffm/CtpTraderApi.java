package com.github.jctp.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.jctp.ffm.CtpLibrary.struct;
import static java.util.Map.entry;

/**
 * CThostFtdcTraderApi 的 FFM 绑定（仅含网关用到的请求）。
 */
public final class CtpTraderApi extends CtpApi {

    /**
     * THOST_TE_RESUME_TYPE
     */
    public enum ResumeType {
        RESTART, RESUME, QUICK, NONE
    }

    // 虚函数槽位按方法名从当前版本的虚函数表查得
    private final int subscribePrivateTopicSlot = apiSlot("SubscribePrivateTopic");
    private final int subscribePublicTopicSlot = apiSlot("SubscribePublicTopic");
    private final int reqAuthenticateSlot = apiSlot("ReqAuthenticate");
    private final int reqUserLoginSlot = apiSlot("ReqUserLogin");
    private final int reqUserLogoutSlot = apiSlot("ReqUserLogout");
    private final int reqOrderInsertSlot = apiSlot("ReqOrderInsert");
    private final int reqOrderActionSlot = apiSlot("ReqOrderAction");
    private final int reqSettlementInfoConfirmSlot = apiSlot("ReqSettlementInfoConfirm");
    private final int reqQryOrderSlot = apiSlot("ReqQryOrder");
    private final int reqQryTradeSlot = apiSlot("ReqQryTrade");
    private final int reqQryInvestorPositionSlot = apiSlot("ReqQryInvestorPosition");
    private final int reqQryTradingAccountSlot = apiSlot("ReqQryTradingAccount");
    private final int reqQryInvestorSlot = apiSlot("ReqQryInvestor");
    private final int reqQrySettlementInfoSlot = apiSlot("ReqQrySettlementInfo");
    private final int reqQryInstrumentMarginRateSlot = apiSlot("ReqQryInstrumentMarginRate");
    private final int reqQryInstrumentCommissionRateSlot = apiSlot("ReqQryInstrumentCommissionRate");
    private final int reqQryProductSlot = apiSlot("ReqQryProduct");
    private final int reqQryInstrumentSlot = apiSlot("ReqQryInstrument");
    private final int reqQryDepthMarketDataSlot = apiSlot("ReqQryDepthMarketData");
    private final int reqQryInvestorPositionDetailSlot = apiSlot("ReqQryInvestorPositionDetail");

    private volatile TdSpi spi;

    /**
     * @param flowPath 流文件目录，须已存在
     */
    public CtpTraderApi(CtpLibrary library, Path flowPath) {
        super(library, flowPath, true);
    }

    /**
     * 只能注册一次，须在 {@link #init()} 之前调用。
     */
    public void registerSpi(TdSpi spi) {
        if (this.spi != null) {
            throw new IllegalStateException("TraderSpi 已注册");
        }
        this.spi = spi;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        registerSpi(newSpi(vtables.traderSpi, Map.ofEntries(
            entry("OnFrontConnected", upcall(lookup, "onFrontConnected", ON_VOID)),
            entry("OnFrontDisconnected", upcall(lookup, "onFrontDisconnected", ON_INT)),
            entry("OnHeartBeatWarning", upcall(lookup, "onHeartBeatWarning", ON_INT)),
            entry("OnRspAuthenticate", upcall(lookup, "onRspAuthenticate", ON_RSP)),
            entry("OnRspUserLogin", upcall(lookup, "onRspUserLogin", ON_RSP)),
            entry("OnRspUserLogout", upcall(lookup, "onRspUserLogout", ON_RSP)),
            entry("OnRspOrderInsert", upcall(lookup, "onRspOrderInsert", ON_RSP)),
            entry("OnRspOrderAction", upcall(lookup, "onRspOrderAction", ON_RSP)),
            entry("OnRspSettlementInfoConfirm", upcall(lookup, "onRspSettlementInfoConfirm", ON_RSP)),
            entry("OnRspQryOrder", upcall(lookup, "onRspQryOrder", ON_RSP)),
            entry("OnRspQryTrade", upcall(lookup, "onRspQryTrade", ON_RSP)),
            entry("OnRspQryInvestorPosition", upcall(lookup, "onRspQryInvestorPosition", ON_RSP)),
            entry("OnRspQryTradingAccount", upcall(lookup, "onRspQryTradingAccount", ON_RSP)),
            entry("OnRspQryInvestor", upcall(lookup, "onRspQryInvestor", ON_RSP)),
            entry("OnRspQrySettlementInfo", upcall(lookup, "onRspQrySettlementInfo", ON_RSP)),
            entry("OnRspQryInstrumentMarginRate", upcall(lookup, "onRspQryInstrumentMarginRate", ON_RSP)),
            entry("OnRspQryInstrumentCommissionRate", upcall(lookup, "onRspQryInstrumentCommissionRate", ON_RSP)),
            entry("OnRspQryProduct", upcall(lookup, "onRspQryProduct", ON_RSP)),
            entry("OnRspQryInstrument", upcall(lookup, "onRspQryInstrument", ON_RSP)),
            entry("OnRspQryDepthMarketData", upcall(lookup, "onRspQryDepthMarketData", ON_RSP)),
            entry("OnRspQryInvestorPositionDetail", upcall(lookup, "onRspQryInvestorPositionDetail", ON_RSP)),
            entry("OnRspError", upcall(lookup, "onRspError", ON_ERROR)),
            entry("OnRtnOrder", upcall(lookup, "onRtnOrder", ON_RTN)),
            entry("OnRtnTrade", upcall(lookup, "onRtnTrade", ON_RTN)),
            entry("OnErrRtnOrderInsert", upcall(lookup, "onErrRtnOrderInsert", ON_ERR_RTN)),
            entry("OnErrRtnOrderAction", upcall(lookup, "onErrRtnOrderAction", ON_ERR_RTN)),
            entry("OnRtnInstrumentStatus", upcall(lookup, "onRtnInstrumentStatus", ON_RTN)),
            entry("OnRtnTradingNotice", upcall(lookup, "onRtnTradingNotice", ON_RTN))
        )));
    }

    /**
     * 须在 {@link #init()} 之前调用。
     */
    public void subscribePrivateTopic(ResumeType type) {
        callVoid(subscribePrivateTopicSlot, type.ordinal());
    }

    public void subscribePublicTopic(ResumeType type) {
        callVoid(subscribePublicTopicSlot, type.ordinal());
    }

    /**
     * @param filler 填充 {@link CtpStructs.ReqAuthenticate}
     */
    public int reqAuthenticate(Consumer<MemorySegment> filler, int requestId) {
        return request(reqAuthenticateSlot, structs.reqAuthenticate.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.ReqUserLogin}
     */
    public int reqUserLogin(Consumer<MemorySegment> filler, int requestId) {
        return request(reqUserLoginSlot, structs.reqUserLogin.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.UserLogout}
     */
    public int reqUserLogout(Consumer<MemorySegment> filler, int requestId) {
        return request(reqUserLogoutSlot, structs.userLogout.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.InputOrder}
     */
    public int reqOrderInsert(Consumer<MemorySegment> filler, int requestId) {
        return request(reqOrderInsertSlot, structs.inputOrder.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.InputOrderAction}
     */
    public int reqOrderAction(Consumer<MemorySegment> filler, int requestId) {
        return request(reqOrderActionSlot, structs.inputOrderAction.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.SettlementInfoConfirm}
     */
    public int reqSettlementInfoConfirm(Consumer<MemorySegment> filler, int requestId) {
        return request(reqSettlementInfoConfirmSlot, structs.settlementInfoConfirm.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryOrder}
     */
    public int reqQryOrder(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryOrderSlot, structs.qryOrder.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryTrade}
     */
    public int reqQryTrade(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryTradeSlot, structs.qryTrade.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInvestorPosition}
     */
    public int reqQryInvestorPosition(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInvestorPositionSlot, structs.qryInvestorPosition.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryTradingAccount}
     */
    public int reqQryTradingAccount(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryTradingAccountSlot, structs.qryTradingAccount.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInvestor}
     */
    public int reqQryInvestor(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInvestorSlot, structs.qryInvestor.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QrySettlementInfo}
     */
    public int reqQrySettlementInfo(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQrySettlementInfoSlot, structs.qrySettlementInfo.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInstrumentMarginRate}
     */
    public int reqQryInstrumentMarginRate(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInstrumentMarginRateSlot, structs.qryInstrumentMarginRate.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInstrumentCommissionRate}
     */
    public int reqQryInstrumentCommissionRate(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInstrumentCommissionRateSlot, structs.qryInstrumentCommissionRate.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryProduct}
     */
    public int reqQryProduct(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryProductSlot, structs.qryProduct.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInstrument}
     */
    public int reqQryInstrument(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInstrumentSlot, structs.qryInstrument.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryDepthMarketData}
     */
    public int reqQryDepthMarketData(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryDepthMarketDataSlot, structs.qryDepthMarketData.SIZE, filler, requestId);
    }

    /**
     * @param filler 填充 {@link CtpStructs.QryInvestorPositionDetail}
     */
    public int reqQryInvestorPositionDetail(Consumer<MemorySegment> filler, int requestId) {
        return request(reqQryInvestorPositionDetailSlot, structs.qryInvestorPositionDetail.SIZE, filler, requestId);
    }

    // ---- upcall 分发：this 参数之后才是 C++ 回调的实参 ----

    private void onFrontConnected(MemorySegment self) {
        guard("OnFrontConnected", () -> spi.onFrontConnected());
    }

    private void onFrontDisconnected(MemorySegment self, int reason) {
        guard("OnFrontDisconnected", () -> spi.onFrontDisconnected(reason));
    }

    private void onHeartBeatWarning(MemorySegment self, int timeLapse) {
        guard("OnHeartBeatWarning", () -> spi.onHeartBeatWarning(timeLapse));
    }

    private void onRspAuthenticate(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspAuthenticate", () -> spi.onRspAuthenticate(rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspUserLogin(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspUserLogin", () -> spi.onRspUserLogin(struct(p, structs.rspUserLogin.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspUserLogout(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspUserLogout", () -> spi.onRspUserLogout(struct(p, structs.userLogout.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspOrderInsert(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspOrderInsert", () -> spi.onRspOrderInsert(struct(p, structs.inputOrder.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspOrderAction(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspOrderAction", () -> spi.onRspOrderAction(struct(p, structs.inputOrderAction.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspSettlementInfoConfirm(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspSettlementInfoConfirm", () -> spi.onRspSettlementInfoConfirm(struct(p, structs.settlementInfoConfirm.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryOrder(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryOrder", () -> spi.onRspQryOrder(struct(p, structs.order.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryTrade(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryTrade", () -> spi.onRspQryTrade(struct(p, structs.trade.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInvestorPosition(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInvestorPosition", () -> spi.onRspQryInvestorPosition(struct(p, structs.investorPosition.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryTradingAccount(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryTradingAccount", () -> spi.onRspQryTradingAccount(struct(p, structs.tradingAccount.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInvestor(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInvestor", () -> spi.onRspQryInvestor(struct(p, structs.investor.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQrySettlementInfo(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQrySettlementInfo", () -> spi.onRspQrySettlementInfo(struct(p, structs.settlementInfo.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInstrumentMarginRate(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInstrumentMarginRate", () -> spi.onRspQryInstrumentMarginRate(struct(p, structs.instrumentMarginRate.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInstrumentCommissionRate(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInstrumentCommissionRate", () -> spi.onRspQryInstrumentCommissionRate(struct(p, structs.instrumentCommissionRate.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryProduct(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryProduct", () -> spi.onRspQryProduct(struct(p, structs.product.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInstrument(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInstrument", () -> spi.onRspQryInstrument(struct(p, structs.instrument.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryDepthMarketData(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryDepthMarketData", () -> spi.onRspQryDepthMarketData(struct(p, structs.depthMarketData.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspQryInvestorPositionDetail(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspQryInvestorPositionDetail", () -> spi.onRspQryInvestorPositionDetail(struct(p, structs.investorPositionDetail.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspError(MemorySegment self, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspError", () -> spi.onRspError(rspInfo(rspInfo), requestId, isLast));
    }

    private void onRtnOrder(MemorySegment self, MemorySegment p) {
        guard("OnRtnOrder", () -> spi.onRtnOrder(struct(p, structs.order.SIZE)));
    }

    private void onRtnTrade(MemorySegment self, MemorySegment p) {
        guard("OnRtnTrade", () -> spi.onRtnTrade(struct(p, structs.trade.SIZE)));
    }

    private void onErrRtnOrderInsert(MemorySegment self, MemorySegment p, MemorySegment rspInfo) {
        guard("OnErrRtnOrderInsert", () -> spi.onErrRtnOrderInsert(struct(p, structs.inputOrder.SIZE), rspInfo(rspInfo)));
    }

    private void onErrRtnOrderAction(MemorySegment self, MemorySegment p, MemorySegment rspInfo) {
        guard("OnErrRtnOrderAction", () -> spi.onErrRtnOrderAction(struct(p, structs.orderAction.SIZE), rspInfo(rspInfo)));
    }

    private void onRtnInstrumentStatus(MemorySegment self, MemorySegment p) {
        guard("OnRtnInstrumentStatus", () -> spi.onRtnInstrumentStatus(struct(p, structs.instrumentStatus.SIZE)));
    }

    private void onRtnTradingNotice(MemorySegment self, MemorySegment p) {
        guard("OnRtnTradingNotice", () -> spi.onRtnTradingNotice(struct(p, structs.tradingNoticeInfo.SIZE)));
    }
}
