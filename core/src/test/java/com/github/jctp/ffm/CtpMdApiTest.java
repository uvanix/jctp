package com.github.jctp.ffm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只创建、注册、释放 Api，不调用 Init，不连网。
 */
class CtpMdApiTest {

    /**
     * 注册 Spi 时逐个解析 upcall 分发方法，方法名或签名不匹配会在这里抛出。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void registerSpiBindsAllCallbacks(CtpVersion version, @TempDir Path flow) {
        try (CtpMdApi api = new CtpMdApi(CtpLibrary.load(version), flow)) {
            assertDoesNotThrow(() -> api.registerSpi(new MdSpi() {
            }));
            assertThrows(IllegalStateException.class, () -> api.registerSpi(new MdSpi() {
            }));
        }
    }

    /**
     * 释放后请求不再进入原生库：返回 -1 而不是访问已释放的对象。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void requestsAfterCloseReturnMinusOne(CtpVersion version, @TempDir Path flow) {
        CtpMdApi api = new CtpMdApi(CtpLibrary.load(version), flow);
        api.registerSpi(new MdSpi() {
        });
        api.close();
        assertDoesNotThrow(api::close);
        assertEquals(-1, api.subscribeMarketData("rb2601"));
        assertEquals(-1, api.unSubscribeMarketData("rb2601"));
        assertEquals(-1, api.reqUserLogin(_ -> {
        }, 1));
        assertThrows(IllegalStateException.class, api::getTradingDay);
    }
}
