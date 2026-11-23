package com.github.jctp.ffm;

import java.lang.foreign.MemorySegment;

/**
 * CThostFtdcMdSpi 的 Java 侧回调。
 * <p>
 * 在 CTP 内部线程上调用；参数段只在回调期间有效（已按 {@link CtpStructs} 中的结构体大小重新解释），
 * 空指针以 null 传入。回调中不要阻塞，也不要关闭 Api。
 */
public interface MdSpi {

    default void onFrontConnected() {
    }

    /**
     * @param reason 0x1001 网络读失败、0x1002 网络写失败、0x2001 接收心跳超时、0x2002 发送心跳失败、0x2003 收到错误报文
     */
    default void onFrontDisconnected(int reason) {
    }

    default void onHeartBeatWarning(int timeLapse) {
    }

    default void onRspUserLogin(MemorySegment rspUserLogin, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspUserLogout(MemorySegment userLogout, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspError(MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspSubMarketData(MemorySegment specificInstrument, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRspUnSubMarketData(MemorySegment specificInstrument, MemorySegment rspInfo, int requestId, boolean isLast) {
    }

    default void onRtnDepthMarketData(MemorySegment depthMarketData) {
    }
}
