package com.github.jctp.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.jctp.ffm.CtpLibrary.struct;
import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * CThostFtdcMdApi 的 FFM 绑定。
 */
public final class CtpMdApi extends CtpApi {

    // 虚函数槽位按方法名从当前版本的虚函数表查得
    private final int subscribeMarketDataSlot = apiSlot("SubscribeMarketData");
    private final int unSubscribeMarketDataSlot = apiSlot("UnSubscribeMarketData");
    private final int reqUserLoginSlot = apiSlot("ReqUserLogin");
    private final int reqUserLogoutSlot = apiSlot("ReqUserLogout");

    private volatile MdSpi spi;

    /**
     * @param flowPath 流文件目录，须已存在
     */
    public CtpMdApi(CtpLibrary library, Path flowPath) {
        super(library, flowPath, false);
    }

    /**
     * 只能注册一次，须在 {@link #init()} 之前调用。
     */
    public void registerSpi(MdSpi spi) {
        if (this.spi != null) {
            throw new IllegalStateException("MdSpi 已注册");
        }
        this.spi = spi;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        registerSpi(newSpi(vtables.mdSpi, Map.of(
            "OnFrontConnected", upcall(lookup, "onFrontConnected", ON_VOID),
            "OnFrontDisconnected", upcall(lookup, "onFrontDisconnected", ON_INT),
            "OnHeartBeatWarning", upcall(lookup, "onHeartBeatWarning", ON_INT),
            "OnRspUserLogin", upcall(lookup, "onRspUserLogin", ON_RSP),
            "OnRspUserLogout", upcall(lookup, "onRspUserLogout", ON_RSP),
            "OnRspError", upcall(lookup, "onRspError", ON_ERROR),
            "OnRspSubMarketData", upcall(lookup, "onRspSubMarketData", ON_RSP),
            "OnRspUnSubMarketData", upcall(lookup, "onRspUnSubMarketData", ON_RSP),
            "OnRtnDepthMarketData", upcall(lookup, "onRtnDepthMarketData", ON_RTN)
        )));
    }

    public int subscribeMarketData(String... instrumentIds) {
        return instruments(subscribeMarketDataSlot, instrumentIds);
    }

    public int unSubscribeMarketData(String... instrumentIds) {
        return instruments(unSubscribeMarketDataSlot, instrumentIds);
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
     * int SubscribeMarketData(char *ppInstrumentID[], int nCount)
     */
    private int instruments(int slot, String... instrumentIds) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment array = temp.allocate(ADDRESS, instrumentIds.length);
            for (int i = 0; i < instrumentIds.length; i++) {
                array.setAtIndex(ADDRESS, i, CtpField.cString(temp, instrumentIds[i]));
            }
            return callInt(slot, array, instrumentIds.length);
        }
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

    private void onRspUserLogin(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspUserLogin", () -> spi.onRspUserLogin(struct(p, structs.rspUserLogin.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspUserLogout(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspUserLogout", () -> spi.onRspUserLogout(struct(p, structs.userLogout.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspError(MemorySegment self, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspError", () -> spi.onRspError(rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspSubMarketData(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspSubMarketData", () -> spi.onRspSubMarketData(struct(p, structs.specificInstrument.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRspUnSubMarketData(MemorySegment self, MemorySegment p, MemorySegment rspInfo, int requestId, boolean isLast) {
        guard("OnRspUnSubMarketData", () -> spi.onRspUnSubMarketData(struct(p, structs.specificInstrument.SIZE), rspInfo(rspInfo), requestId, isLast));
    }

    private void onRtnDepthMarketData(MemorySegment self, MemorySegment p) {
        guard("OnRtnDepthMarketData", () -> spi.onRtnDepthMarketData(struct(p, structs.depthMarketData.SIZE)));
    }
}
