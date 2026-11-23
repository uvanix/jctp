package com.github.jctp.server.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.jctp.broker.CtpTdBroker;
import com.github.jctp.server.Json;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Product;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 一个账号的产品与合约。
 * <p>
 * 盘中全量查询合约可能要数分钟（SimNow 每秒仅返回数十个），登录不等合约：登录时只查产品，
 * 合约优先读当日缓存，没有缓存则在后台按品种逐个查询（每个品种一次查询，查询之间让出查询通道，不阻塞下单与其他查询），
 * 用户打开的品种、用到的合约随时按需单独查询。
 * <p>
 * 期权品种合约成千上万，盘中查询极慢且常中途停顿；查询应答与报单、撤单回报共用一条连接，
 * 大查询堵塞时回报也收不到，因此期权不在后台加载，打开时才查询。
 * <p>
 * 当日缓存按品种增量保存（记录已加载的品种）：后台加载途中定期、结束时（即使有品种失败）及之后按需加载时写入。
 * 同一交易日再次登录读取缓存中的品种，只在后台补查其余品种。盘中期权等大品种常查询超时，不致前功尽弃。
 */
@Slf4j
final class InstrumentCatalog {

    /**
     * 单个品种查询失败后的重试次数与间隔。失败多为前置拥堵超时或断开，暂停一段时间让前置先发完积压的应答与回报
     */
    private static final int RETRIES = 3;
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);
    private static final int SEARCH_LIMIT = 50;
    private static final List<String> EXCHANGE_ORDER = List.of("CFFEX", "SHFE", "INE", "DCE", "CZCE", "GFEX");
    /**
     * 后台加载每完成这么多品种写一次缓存
     */
    private static final int SAVE_EVERY = 20;

    /**
     * 缓存文件内容
     *
     * @param products    已完整加载的品种（交易所|品种代码）
     * @param instruments 这些品种的合约
     */
    record CacheFile(List<String> products, List<Instrument> instruments) {
    }

    private final CtpTdBroker td;
    private final String name;
    /**
     * 当日缓存目录，为空时不缓存
     */
    private final Path cacheDir;
    private final String cachePrefix;

    /**
     * 重试间隔，测试中可调小
     */
    Duration retryInterval = RETRY_INTERVAL;
    private final Map<String, Product> products = new ConcurrentHashMap<>();
    private final Map<String, Instrument> instruments = new ConcurrentHashMap<>();
    /**
     * 品种（交易所|品种代码）→ 加载结果；加载中的品种并发请求等待同一次查询，失败时移除以便重试
     */
    private final Map<String, CompletableFuture<List<Instrument>>> loads = new ConcurrentHashMap<>();
    /**
     * 后台加载失败的品种，打开时再按需查询
     */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private volatile List<ProductView> productViews = List.of();
    private volatile Thread loader;
    private volatile boolean backgroundRunning;
    private volatile LocalDate tradingDay;
    private volatile boolean closed;

    /**
     * @param cacheDir    当日缓存目录，为空时不缓存
     * @param cachePrefix 缓存文件名前缀（柜台标识），文件名为 前缀-交易日.json
     */
    InstrumentCatalog(CtpTdBroker td, String name, Path cacheDir, String cachePrefix) {
        this.td = td;
        this.name = name;
        this.cacheDir = cacheDir;
        this.cachePrefix = cachePrefix;
    }

    /**
     * 查询全部产品，没有产品视为柜台异常。
     */
    void loadProducts() {
        List<Product> list = td.queryProducts();
        if (list.isEmpty()) {
            throw new IllegalStateException("CTP 产品查询无结果");
        }
        list.forEach(p -> products.put(key(p.exchangeID(), p.productID()), p));
        rebuild();
    }

    /**
     * 读取当日合约缓存，缓存中的品种视为已加载。
     *
     * @return 读到的品种数，没有缓存或读取失败时为 0
     */
    int loadCache(LocalDate tradingDay) {
        this.tradingDay = tradingDay;
        Path file = cacheFile(tradingDay);
        if (file == null || !Files.isRegularFile(file)) {
            return 0;
        }
        try {
            JsonNode root = Json.MAPPER.readTree(file.toFile());
            // 旧格式为合约数组，当时只在全部品种加载成功后写入
            CacheFile cache = root.isArray()
                ? new CacheFile(List.copyOf(products.keySet()), Json.MAPPER.convertValue(root, new TypeReference<List<Instrument>>() {
            }))
                : Json.MAPPER.treeToValue(root, CacheFile.class);
            Map<String, List<Instrument>> byProduct = cache.instruments().stream()
                .collect(Collectors.groupingBy(i -> key(i.exchangeID(), i.productID())));
            int count = 0;
            for (String k : cache.products()) {
                if (products.containsKey(k)) {
                    List<Instrument> list = byProduct.getOrDefault(k, List.of());
                    list.forEach(i -> instruments.put(i.instrumentID(), i));
                    loads.put(k, CompletableFuture.completedFuture(list));
                    count++;
                }
            }
            rebuild();
            log.info("{} 读取合约缓存 品种 {}/{} 合约 {} 个 {}", name, count, products.size(), instruments.size(), file);
            return count;
        } catch (IOException | RuntimeException e) {
            log.warn("{} 合约缓存读取失败，改为查询柜台: {}", name, e.getMessage());
            instruments.clear();
            loads.clear();
            return 0;
        }
    }

    /**
     * 待后台加载（尚未加载且不是期权）的品种数
     */
    long pendingCount() {
        return products.entrySet().stream().filter(e -> !isOption(e.getValue()) && !isLoaded(e.getKey())).count();
    }

    /**
     * 后台按品种加载尚未加载的非期权合约（期货在前），途中与结束时写入当日缓存。
     */
    void startBackgroundLoad(LocalDate tradingDay) {
        this.tradingDay = tradingDay;
        backgroundRunning = true;
        rebuild();
        loader = Thread.ofVirtual().name("instruments-" + name).start(this::backgroundLoad);
    }

    void close() {
        closed = true;
        Thread t = loader;
        if (t != null) {
            t.interrupt();
        }
    }

    List<ProductView> products() {
        return productViews;
    }

    int instrumentCount() {
        return instruments.size();
    }

    /**
     * 品种下当前可交易的合约，尚未加载时立即查询该品种。
     */
    List<Instrument> instruments(String productId) {
        List<Product> matched = products.values().stream().filter(p -> p.productID().equals(productId)).toList();
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("未知品种 " + productId);
        }
        matched.forEach(this::load);
        return active(instruments.values())
            .filter(i -> i.productID().equals(productId))
            .sorted(Comparator.comparing(Instrument::instrumentID))
            .toList();
    }

    /**
     * 查找合约，未加载时向柜台查询该合约。
     */
    Optional<Instrument> instrument(String instrumentId) {
        Instrument known = instruments.get(instrumentId);
        if (known != null || closed) {
            return Optional.ofNullable(known);
        }
        Optional<Instrument> found = td.queryInstrument(instrumentId);
        found.ifPresent(i -> instruments.put(i.instrumentID(), i));
        return found;
    }

    /**
     * 只在已加载的合约中查找，不查询柜台（持仓汇总等批量场景）。
     */
    Optional<Instrument> cached(String instrumentId) {
        return Optional.ofNullable(instruments.get(instrumentId));
    }

    /**
     * 按合约代码前缀或名称搜索已加载的合约；输入完整代码而尚未加载时向柜台查询该合约。
     */
    List<Instrument> search(String query) {
        String text = query.trim();
        String q = text.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Instrument> found = active(instruments.values())
            .filter(i -> i.instrumentID().toLowerCase(Locale.ROOT).startsWith(q) || i.instrumentName().contains(text))
            .sorted(Comparator.comparing((Instrument i) -> i.productClass() != DataType.ProductClass.Futures)
                .thenComparing(Instrument::instrumentID))
            .limit(SEARCH_LIMIT)
            .toList();
        if (found.isEmpty() && text.matches("[A-Za-z]+\\d{3,4}.*")) {
            try {
                return instrument(text).filter(InstrumentCatalog::isActive).map(List::of).orElse(List.of());
            } catch (RuntimeException e) {
                log.debug("{} 合约查询失败 {}: {}", name, text, e.getMessage());
            }
        }
        return found;
    }

    static Stream<Instrument> active(Collection<Instrument> list) {
        return list.stream().filter(InstrumentCatalog::isActive);
    }

    static boolean isActive(Instrument i) {
        return i.isTrading() || i.instLifePhase() == DataType.InstLifePhase.Started;
    }

    /**
     * 品种列表：名称取 CTP 产品名称（为空时显示代码）；合约数为可交易合约数，未加载时为 null；已加载但没有可交易合约的品种不列出。
     */
    static List<ProductView> buildProducts(Collection<Product> products, Collection<Instrument> instruments,
                                           Predicate<String> loaded, Predicate<String> loading) {
        Map<String, Long> counts = active(instruments)
            .collect(Collectors.groupingBy(i -> key(i.exchangeID(), i.productID()), Collectors.counting()));
        return products.stream()
            .map(p -> {
                String k = key(p.exchangeID(), p.productID());
                boolean done = loaded.test(k);
                Integer count = done ? counts.getOrDefault(k, 0L).intValue() : null;
                String productName = p.productName() == null || p.productName().isBlank() ? p.productID() : p.productName();
                return new ProductView(p.productID(), p.exchangeID(), p.productClass(), productName, count,
                    !done && loading.test(k));
            })
            .filter(v -> v.count() == null || v.count() > 0)
            .sorted(Comparator.comparing(ProductView::exchangeId).thenComparing(ProductView::productId))
            .toList();
    }

    private List<Instrument> load(Product product) {
        String k = key(product.exchangeID(), product.productID());
        CompletableFuture<List<Instrument>> mine = new CompletableFuture<>();
        CompletableFuture<List<Instrument>> existing = loads.putIfAbsent(k, mine);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        try {
            List<Instrument> list = td.queryInstruments(product.exchangeID(), product.productID());
            instruments.values().removeIf(i -> product.exchangeID().equals(i.exchangeID()) && product.productID().equals(i.productID()));
            list.forEach(i -> instruments.put(i.instrumentID(), i));
            mine.complete(list);
            failed.remove(k);
            rebuild();
            // 后台加载已结束时（如打开了后台失败的品种）即时补进缓存
            if (!backgroundRunning) {
                saveCache();
            }
            return list;
        } catch (RuntimeException e) {
            loads.remove(k, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private void backgroundLoad() {
        try {
            backgroundLoadPending();
        } finally {
            backgroundRunning = false;
            rebuild();
        }
    }

    private void backgroundLoadPending() {
        long start = System.nanoTime();
        List<Product> pending = products.values().stream()
            .filter(p -> !isOption(p) && !isLoaded(key(p.exchangeID(), p.productID())))
            .sorted(Comparator.comparingInt(InstrumentCatalog::classRank)
                .thenComparingInt(p -> exchangeRank(p.exchangeID()))
                .thenComparing(Product::productID))
            .toList();
        if (pending.isEmpty()) {
            return;
        }
        log.info("{} 后台加载合约 待加载品种 {}/{}", name, pending.size(), products.size());
        List<Product> failures = new ArrayList<>();
        int done = 0;
        for (Product p : pending) {
            if (closed) {
                return;
            }
            if (!loadWithRetry(p)) {
                failures.add(p);
            }
            if (++done % SAVE_EVERY == 0) {
                log.info("{} 后台加载合约 品种 {}/{} 合约 {} 个", name, done, pending.size(), instruments.size());
                saveCache();
            }
        }
        // 补查一轮失败的品种
        failures.removeIf(this::loadWithRetry);
        if (closed) {
            return;
        }
        failures.forEach(p -> failed.add(key(p.exchangeID(), p.productID())));
        saveCache();
        long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        if (!failures.isEmpty()) {
            log.warn("{} 后台合约加载结束 用时 {}s，{} 个品种失败（打开时再查询，下次登录补查）: {}", name, seconds, failures.size(),
                failures.stream().map(Product::productID).collect(Collectors.joining(",")));
            return;
        }
        log.info("{} 后台合约加载完成 品种 {} 个 合约 {} 个 用时 {}s", name, pending.size(), instruments.size(), seconds);
    }

    private boolean loadWithRetry(Product p) {
        for (int attempt = 1; attempt <= RETRIES && !closed; attempt++) {
            try {
                load(p);
                return true;
            } catch (RuntimeException e) {
                log.warn("{} 品种 {} 合约查询失败（第 {} 次）: {}", name, p.productID(), attempt, e.getMessage());
                try {
                    Thread.sleep(retryInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 写入已完整加载的品种及其合约
     */
    private synchronized void saveCache() {
        Path file = cacheFile(tradingDay);
        if (file == null || closed) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            List<String> loaded = products.keySet().stream().filter(this::isLoaded).sorted().toList();
            Set<String> loadedSet = Set.copyOf(loaded);
            List<Instrument> list = instruments.values().stream()
                .filter(i -> loadedSet.contains(key(i.exchangeID(), i.productID())))
                .toList();
            Path tmp = Files.createTempFile(cacheDir, cachePrefix, ".tmp");
            Json.MAPPER.writeValue(tmp.toFile(), new CacheFile(loaded, list));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 清理该柜台以前交易日的缓存
            try (Stream<Path> old = Files.list(cacheDir)) {
                old.filter(f -> f.getFileName().toString().startsWith(cachePrefix + "-") && !f.equals(file))
                    .forEach(f -> f.toFile().delete());
            }
            log.info("{} 合约缓存已写入 品种 {}/{} 合约 {} 个", name, loaded.size(), products.size(), list.size());
        } catch (IOException | RuntimeException e) {
            log.warn("{} 合约缓存写入失败: {}", name, e.getMessage());
        }
    }

    private Path cacheFile(LocalDate tradingDay) {
        if (cacheDir == null || tradingDay == null) {
            return null;
        }
        return cacheDir.resolve(cachePrefix + "-" + tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE) + ".json");
    }

    private synchronized void rebuild() {
        productViews = buildProducts(products.values(), instruments.values(), this::isLoaded,
            k -> backgroundRunning && !failed.contains(k) && !isOption(products.get(k)));
    }

    private boolean isLoaded(String key) {
        CompletableFuture<List<Instrument>> f = loads.get(key);
        return f != null && f.isDone() && !f.isCompletedExceptionally();
    }

    private static int classRank(Product p) {
        return p.productClass() == DataType.ProductClass.Futures ? 0 : 1;
    }

    /**
     * 期权品种：不在后台加载
     */
    private static boolean isOption(Product p) {
        return p != null && (p.productClass() == DataType.ProductClass.Options || p.productClass() == DataType.ProductClass.SpotOption);
    }

    private static int exchangeRank(String exchangeId) {
        int i = EXCHANGE_ORDER.indexOf(exchangeId);
        return i < 0 ? EXCHANGE_ORDER.size() : i;
    }

    private static String key(String exchangeId, String productId) {
        return exchangeId + "|" + productId;
    }
}
