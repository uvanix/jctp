package com.github.jctp.ffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CtpTraderApiTest {

    /**
     * 从 classpath 解压随附原生库并加载，版本须与 {@link CtpStructs} 的布局一致。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void loadBundledLibrary(CtpVersion version) {
        CtpLibrary library = CtpLibrary.load(version);
        assertSame(version, library.version());
        assertSame(library, CtpLibrary.load(version));
        assertTrue(library.traderApiVersion().startsWith(version.toString()), library.traderApiVersion());
        assertTrue(library.mdApiVersion().startsWith(version.toString()), library.mdApiVersion());
    }

    /**
     * 同名原生库按完整路径加载，两个版本在同一进程内互不覆盖。
     */
    @Test
    void loadAllVersionsSideBySide(@TempDir Path flow) throws IOException {
        for (CtpVersion version : CtpVersion.values()) {
            // 流文件目录须已存在，否则原生库直接退出进程
            Path dir = Files.createDirectories(flow.resolve(version.number));
            try (CtpTraderApi api = new CtpTraderApi(CtpLibrary.load(version), dir)) {
                assertSame(version.structs(), api.structs());
            }
        }
        for (CtpVersion version : CtpVersion.values()) {
            assertTrue(CtpLibrary.load(version).traderApiVersion().startsWith(version.toString()));
        }
    }

    @Test
    void parseVersion() {
        assertEquals(CtpVersion.V6_5_1, CtpVersion.parse("6.5.1"));
        assertEquals(CtpVersion.V6_5_1, CtpVersion.parse("v6.5.1"));
        assertEquals(CtpVersion.V6_5_1, CtpVersion.parse("V6_5_1"));
        assertEquals(CtpVersion.V6_7_8, CtpVersion.parse("6.7.8"));
        assertEquals(CtpVersion.V6_7_8_CP, CtpVersion.parse("6.7.8_CP"));
        assertEquals(CtpVersion.V6_7_2_CP, CtpVersion.parse("V6_7_2_CP"));
        assertEquals(CtpVersion.V6_3_15, CtpVersion.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CtpVersion.parse("6.7.5"));
    }

    /**
     * 生产版与测评版的版本串含相同版本号，加载时须互不匹配。
     */
    @Test
    void versionMatching() {
        assertTrue(CtpVersion.V6_7_8.matches("v6.7.8_20240918 14:34:34.8082"));
        assertFalse(CtpVersion.V6_7_8.matches("v6.7.8_CP_20240802 13:08:06.8082"));
        assertTrue(CtpVersion.V6_7_8_CP.matches("v6.7.8_CP_20240802 13:08:06.8082"));
        assertFalse(CtpVersion.V6_7_8_CP.matches("v6.7.8_20240918 14:34:34.8082"));
        assertFalse(CtpVersion.V6_7_2.matches("v6.7.8_20240918 14:34:34.8082"));
        assertTrue(CtpVersion.V6_3_15.matches("6.3.15_20190220 15:47:00"));
        assertFalse(CtpVersion.V6_5_1.matches("v6.6.8_20220712 11:55:47.2067"));
    }

    /**
     * 槽位按方法名取自各版本的虚函数表：6.5.1 起 MdSpi 插入一个回调，6.6.8 起 TraderApi/Spi 插入方法，
     * 6.7.8 连 RegisterSpi 等公共方法的槽位都后移。下标顺序同 CtpVersion 声明顺序。
     */
    @Test
    void vtableSlotsByVersion() {
        int[] detail = {54, 54, 55, 55, 55, 56, 56};
        int[] rtnOrder = {73, 73, 74, 74, 74, 74, 74};
        int[] mdError = {5, 6, 6, 6, 6, 6, 6};
        int[] registerSpi = {7, 7, 7, 7, 7, 8, 8};
        int[] orderInsert = {23, 23, 23, 23, 23, 24, 24};
        for (CtpVersion v : CtpVersion.values()) {
            CtpVtables t = CtpVtables.of(v);
            int i = v.ordinal();
            assertEquals(detail[i], CtpVtables.slot(t.traderApi, "ReqQryInvestorPositionDetail"), v.name());
            assertEquals(rtnOrder[i], CtpVtables.slot(t.traderSpi, "OnRtnOrder"), v.name());
            assertEquals(mdError[i], CtpVtables.slot(t.mdSpi, "OnRspError"), v.name());
            assertEquals(registerSpi[i], CtpVtables.slot(t.traderApi, "RegisterSpi"), v.name());
            assertEquals(orderInsert[i], CtpVtables.slot(t.traderApi, "ReqOrderInsert"), v.name());
        }
        assertSame(CtpVtables.of(CtpVersion.V6_7_8), CtpVtables.of(CtpVersion.V6_7_8_CP));
        assertThrows(IllegalStateException.class, () -> CtpVtables.slot(CtpVtables.of(CtpVersion.V6_3_15).traderApi, "Nope"));
    }

    /**
     * v6.5.1 合约代码扩至 81 字节并移到末尾；TAS 持仓字段 v6.3.15 没有。
     */
    @Test
    void layoutsDifferByVersion() {
        CtpStructs v6315 = CtpVersion.V6_3_15.structs();
        CtpStructs v651 = CtpVersion.V6_5_1.structs();
        assertEquals(31, v6315.order.InstrumentID.length());
        assertEquals(81, v651.order.InstrumentID.length());
        assertTrue(v651.order.InstrumentID.offset() >= v6315.order.SIZE);
        assertFalse(v6315.investorPosition.TasPosition.present());
        assertTrue(v651.investorPosition.TasPosition.present());
        assertEquals(v6315.rspInfo.SIZE, v651.rspInfo.SIZE);
    }

    /**
     * 注册 Spi 时逐个解析 upcall 分发方法，方法名或签名不匹配会在这里抛出。不调用 Init，不连网。
     */
    @ParameterizedTest
    @EnumSource(CtpVersion.class)
    void registerSpiBindsAllCallbacks(CtpVersion version, @TempDir Path flow) {
        try (CtpTraderApi api = new CtpTraderApi(CtpLibrary.load(version), flow)) {
            assertDoesNotThrow(() -> api.registerSpi(new TdSpi() {
            }));
        }
    }
}
