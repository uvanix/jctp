package com.github.jctp.ffm;

import java.lang.foreign.MemorySegment;

/**
 * CThostFtdcTraderSpi 中网关用到的回调（其余 100 余个槽位为空实现）。
 * <p>
 * 在 CTP 内部线程上调用；参数段只在回调期间有效，空指针以 null 传入。回调中不要阻塞，也不要关闭 Api。
 */
public interface TdSpi {

    default void onFrontConnected() {
    }

    default void onFrontDisconnected(int reason) {
    }

    default void onHeartBeatWarning(int timeLapse) {
    }

    default void onRspAuthenticate(MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspUserLogin(MemorySegment rspUserLogin, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspUserLogout(MemorySegment userLogout, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspError(MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryInvestor(MemorySegment investor, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQrySettlementInfo(MemorySegment settlementInfo, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspSettlementInfoConfirm(MemorySegment settlementInfoConfirm, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryProduct(MemorySegment product, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryInstrument(MemorySegment instrument, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryTradingAccount(MemorySegment tradingAccount, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryInvestorPosition(MemorySegment investorPosition, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryInvestorPositionDetail(MemorySegment investorPositionDetail, MemorySegment rspInfo, int requestId,
                                                boolean isLast) {
    }

    default void onRspQryInstrumentMarginRate(MemorySegment instrumentMarginRate, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryInstrumentCommissionRate(MemorySegment instrumentCommissionRate, MemorySegment rspInfo, int requestId,
                                                  boolean isLast) {
    }

    default void onRspQryOrder(MemorySegment order, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryTrade(MemorySegment trade, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspQryDepthMarketData(MemorySegment depthMarketData, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRtnOrder(MemorySegment order) {
    }

    default void onRtnTrade(MemorySegment trade) {
    }

    /**
     * 柜台拒绝报单
     */
    default void onRspOrderInsert(MemorySegment inputOrder, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    /**
     * 柜台拒绝撤单
     */
    default void onRspOrderAction(MemorySegment inputOrderAction, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    /**
     * 交易所拒绝报单
     */
    default void onErrRtnOrderInsert(MemorySegment inputOrder, MemorySegment rspInfo) {
    }

    /**
     * 交易所拒绝撤单
     */
    default void onErrRtnOrderAction(MemorySegment orderAction, MemorySegment rspInfo) {
    }

    /**
     * 合约交易状态变化（开盘、休市、收盘等）
     */
    default void onRtnInstrumentStatus(MemorySegment instrumentStatus) {
    }

    /**
     * 交易通知（如追保通知）
     */
    default void onRtnTradingNotice(MemorySegment tradingNoticeInfo) {
    }
}
