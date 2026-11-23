package com.github.jctp.server.gateway;

import com.github.jctp.server.FakeBrokers;
import com.github.jctp.struct.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentCatalogTest {

    private static final LocalDate DAY = FakeBrokers.TRADING_DAY;

    private static FakeBrokers.Td td() {
        return new FakeBrokers.Td(
            FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE"),
            FakeBrokers.future("rb2605", "螺纹钢2605", "rb", "SHFE"),
            FakeBrokers.future("IF2612", "沪深300指数2612", "IF", "CFFEX"),
            FakeBrokers.option("cu2601C80000", "cu_o", "cu2601", DataType.OptionsType.Call, 80000));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "等待超时");
            Thread.sleep(10);
        }
    }

    /**
     * 后台按品种加载期货合约（期权打开时才查）并写入当日缓存；同一交易日再次登录直接读缓存，不再查询柜台。
     */
    @Test
    void backgroundLoadWritesCacheForNextLogin(@TempDir Path dir) throws Exception {
        FakeBrokers.Td td = td();
        InstrumentCatalog catalog = new InstrumentCatalog(td, "test", dir, "sim");
        catalog.loadProducts();
        assertEquals(0, catalog.loadCache(DAY));
        assertTrue(catalog.products().stream().allMatch(p -> p.count() == null), "加载前合约数未知");

        catalog.startBackgroundLoad(DAY);
        Path cache = dir.resolve("sim-20261116.json");
        await(() -> Files.exists(cache) && catalog.products().stream().noneMatch(ProductView::loading));
        assertEquals(3, catalog.instrumentCount());
        assertEquals(Map.of("IF", 1, "rb", 2), catalog.products().stream().filter(p -> p.count() != null)
            .collect(Collectors.toMap(ProductView::productId, ProductView::count)));
        assertEquals(2, td.productQueries.size());
        ProductView option = catalog.products().stream().filter(p -> p.productId().equals("cu_o")).findFirst().orElseThrow();
        assertNull(option.count());
        assertFalse(option.loading(), "期权不在后台加载");

        FakeBrokers.Td next = td();
        InstrumentCatalog cached = new InstrumentCatalog(next, "test", dir, "sim");
        cached.loadProducts();
        assertEquals(2, cached.loadCache(DAY));
        assertEquals(0, cached.pendingCount());
        assertEquals(3, cached.instrumentCount());
        assertEquals("螺纹钢2601", cached.instrument("rb2601").orElseThrow().instrumentName());
        assertEquals(List.of("rb2601", "rb2605"), cached.instruments("rb").stream().map(i -> i.instrumentID()).toList());
        assertTrue(next.productQueries.isEmpty(), "读缓存后不再查询柜台");
    }

    /**
     * 有品种查询失败时仍写入已加载的品种；失败品种不再显示加载中，打开时按需查询并补进缓存；
     * 再次登录只补查缓存中没有的品种。
     */
    @Test
    void partialLoadIsCachedAndResumed(@TempDir Path dir) throws Exception {
        FakeBrokers.Td td = td();
        td.failingProducts.add("CFFEX|IF");
        InstrumentCatalog catalog = new InstrumentCatalog(td, "test", dir, "sim");
        catalog.retryInterval = Duration.ZERO;
        catalog.loadProducts();
        catalog.loadCache(DAY);
        catalog.startBackgroundLoad(DAY);
        await(() -> catalog.products().stream().noneMatch(ProductView::loading));
        Path cache = dir.resolve("sim-20261116.json");
        assertTrue(Files.exists(cache), "部分品种失败也写缓存");
        ProductView failedProduct = catalog.products().stream().filter(p -> p.productId().equals("IF")).findFirst().orElseThrow();
        assertNull(failedProduct.count());
        assertFalse(failedProduct.loading(), "失败的品种不再显示加载中");
        String partial = Files.readString(cache);

        // 下次登录：缓存中的 rb 不再查询，只补查 IF
        FakeBrokers.Td next = td();
        InstrumentCatalog resumed = new InstrumentCatalog(next, "test", dir, "sim");
        resumed.loadProducts();
        assertEquals(1, resumed.loadCache(DAY));
        assertEquals(1, resumed.pendingCount());
        resumed.startBackgroundLoad(DAY);
        await(() -> resumed.pendingCount() == 0 && resumed.products().stream().noneMatch(ProductView::loading));
        assertEquals(Map.of("CFFEX|IF", 1), next.productQueries.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        assertEquals(3, resumed.instrumentCount());

        // 后台加载结束后按需加载的品种也补进缓存（恢复为只有部分品种的缓存再验证）
        Files.writeString(cache, partial);
        td.failingProducts.clear();
        assertEquals(1, catalog.instruments("IF").size());
        assertEquals(1, catalog.instruments("cu_o").size());
        InstrumentCatalog third = new InstrumentCatalog(td(), "test", dir, "sim");
        third.loadProducts();
        assertEquals(3, third.loadCache(DAY), "打开的期货、期权品种都补进缓存");
    }

    /**
     * 旧格式缓存（合约数组，全部品种加载成功后写入）视为全部已加载。
     */
    @Test
    void legacyCacheFormat(@TempDir Path dir) throws Exception {
        FakeBrokers.Td td = td();
        Files.writeString(dir.resolve("sim-20261116.json"),
            com.github.jctp.server.Json.MAPPER.writeValueAsString(td.queryInstruments(_ -> {
            })));
        InstrumentCatalog catalog = new InstrumentCatalog(td(), "test", dir, "sim");
        catalog.loadProducts();
        assertEquals(3, catalog.loadCache(DAY));
        assertEquals(0, catalog.pendingCount());
        assertEquals(4, catalog.instrumentCount());
    }

    /**
     * 打开的品种立即单独查询，同一品种只查一次；未加载的合约按代码查询。
     */
    @Test
    void loadsOnDemand() {
        FakeBrokers.Td td = td();
        InstrumentCatalog catalog = new InstrumentCatalog(td, "test", null, "sim");
        catalog.loadProducts();

        assertEquals(2, catalog.instruments("rb").size());
        assertEquals(2, catalog.instruments("rb").size());
        assertEquals(1, td.productQueries.get("SHFE|rb").get());
        assertEquals(2, catalog.products().stream().filter(p -> p.productId().equals("rb")).findFirst().orElseThrow().count());

        assertTrue(catalog.cached("IF2612").isEmpty());
        assertEquals("CFFEX", catalog.instrument("IF2612").orElseThrow().exchangeID());
        assertTrue(catalog.cached("IF2612").isPresent());
        assertTrue(catalog.instrument("xx").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> catalog.instruments("nope"));
        // 完整代码在未加载时也能搜到
        assertEquals("cu2601C80000", catalog.search("cu2601C80000").getFirst().instrumentID());
    }

    /**
     * 缓存文件损坏时改为查询柜台。
     */
    @Test
    void corruptCacheFallsBackToQuery(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sim-20261116.json"), "[{坏数据");
        InstrumentCatalog catalog = new InstrumentCatalog(td(), "test", dir, "sim");
        catalog.loadProducts();
        assertEquals(0, catalog.loadCache(DAY));
        assertEquals(0, catalog.instrumentCount());
        assertEquals(2, catalog.instruments("rb").size());
    }
}
