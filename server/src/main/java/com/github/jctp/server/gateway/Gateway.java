package com.github.jctp.server.gateway;

import com.github.jctp.broker.CtpMdBroker;
import com.github.jctp.broker.CtpTdBroker;
import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.ServerConfig;
import com.github.jctp.server.market.Bar;
import com.github.jctp.server.market.BarPeriod;
import com.github.jctp.server.market.BarSeries;
import com.github.jctp.struct.Account;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Position;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * 一个已登录的 CTP 账号：交易 + 行情连接、报单成交状态、内存 K 线。同一账号的多个网页会话共用一个网关。
 */
@Slf4j
public class Gateway implements AutoCloseable {

    /**
     * 查询结果的复用时长。CTP 查询每秒一次，多个页面同时刷新时合并为一次查询
     */
    private static final long QUERY_CACHE_MILLIS = 1000;

    @Getter
    private final ServerConfig.Counter counter;
    @Getter
    private final String userId;
    private final String password;
    @Getter
    private final CtpVersion apiVersion;
    private final CtpTdBroker td;
    private final CtpMdBroker md;
    private final InstrumentCatalog catalog;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Trade> trades = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> bars = new ConcurrentHashMap<>();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Map<String, InstrumentRates> rates = new ConcurrentHashMap<>();
    private final Set<GatewayListener> listeners = new CopyOnWriteArraySet<>();

    private volatile Progress progress = new Progress("等待连接", 0);
    // 成交后置空使下次查询重新取数；不加锁，避免 CTP 回调线程等待进行中的查询
    private volatile Cached<List<PositionView>> positions;
    private volatile Cached<Account> account;

    /**
     * 连接进度
     *
     * @param stage       当前阶段，如"登录"、"加载合约"
     * @param instruments 已加载的合约数
     */
    public record Progress(String stage, int instruments) {
    }

    private record Cached<T>(T value, long time) {
        boolean fresh() {
            return System.currentTimeMillis() - time < QUERY_CACHE_MILLIS;
        }
    }

    public Gateway(ServerConfig.Counter counter, String userId, String password, CtpVersion apiVersion,
                   CtpTdBroker td, CtpMdBroker md) {
        this(counter, userId, password, apiVersion, td, md, null);
    }

    /**
     * @param cacheDir 合约当日缓存目录，为空时不缓存
     */
    public Gateway(ServerConfig.Counter counter, String userId, String password, CtpVersion apiVersion,
                   CtpTdBroker td, CtpMdBroker md, Path cacheDir) {
        this.counter = counter;
        this.userId = userId;
        this.password = password;
        this.apiVersion = apiVersion;
        this.td = td;
        this.md = md;
        this.catalog = new InstrumentCatalog(td, userId + "@" + counter.id(), cacheDir, counter.id());
        // 先挂监听再连接：私有流续传的回报在登录过程中就会到达
        td.setOrderListener(this::onOrder);
        td.setTradeListener(this::onTrade);
        md.setMarketDataListener(this::onTick);
        td.setConnectListener(stage -> progress = new Progress(stage, 0));
    }

    public Progress progress() {
        return progress;
    }

    /**
     * 依次连接交易、加载产品、查询当日报单与成交，最后连接行情。阻塞至完成，失败时释放已建立的连接。
     * 合约优先读当日缓存，缓存中没有的品种就绪后在后台按品种加载（盘中全量查询可能要数分钟，不阻塞登录）。
     */
    public void connect() {
        td.connect();
        int cached;
        try {
            progress = new Progress("加载产品", 0);
            catalog.loadProducts();
            cached = catalog.loadCache(td.tradingDay());

            progress = new Progress("查询当日报单、成交", catalog.instrumentCount());
            try {
                td.queryOrders().forEach(o -> orders.putIfAbsent(o.orderId(), o));
                td.queryTrades().forEach(t -> trades.putIfAbsent(t.id(), t));
            } catch (RuntimeException e) {
                log.warn("{} 当日报单/成交查询失败，仅显示实时回报: {}", userId, e.getMessage());
            }

            progress = new Progress("连接行情", catalog.instrumentCount());
            md.connect();
        } catch (RuntimeException e) {
            md.disconnect();
            td.disconnect();
            throw e;
        }
        long pending = catalog.pendingCount();
        if (pending > 0) {
            catalog.startBackgroundLoad(td.tradingDay());
        }
        progress = new Progress("就绪", catalog.instrumentCount());
        log.info("{}@{} 网关就绪 交易日 {} 品种 {} 合约 {}{} 报单 {} 成交 {}", userId, counter.id(), td.tradingDay(),
            catalog.products().size(), cached > 0 ? catalog.instrumentCount() + "（缓存）" : 0,
            pending > 0 ? "，后台加载 " + pending + " 个品种" : "", orders.size(), trades.size());
    }

    @Override
    public void close() {
        catalog.close();
        listeners.forEach(GatewayListener::onClosed);
        listeners.clear();
        md.disconnect();
        td.disconnect();
        log.info("{}@{} 网关已关闭", userId, counter.id());
    }

    public boolean passwordMatches(String candidate) {
        return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8),
            String.valueOf(candidate).getBytes(StandardCharsets.UTF_8));
    }

    public void addListener(GatewayListener listener) {
        listeners.add(listener);
    }

    public void removeListener(GatewayListener listener) {
        listeners.remove(listener);
    }

    public LocalDate tradingDay() {
        return td.tradingDay();
    }

    // ---- 产品合约 ----

    public List<ProductView> products() {
        return catalog.products();
    }

    /**
     * 品种下当前可交易的合约，按代码排序；尚未加载的品种立即查询。
     */
    public List<Instrument> instruments(String productId) {
        return catalog.instruments(productId);
    }

    /**
     * 按合约代码前缀或名称搜索。
     */
    public List<Instrument> search(String query) {
        return catalog.search(query);
    }

    public Instrument instrument(String instrumentId) {
        return catalog.instrument(instrumentId).orElseThrow(() -> new IllegalArgumentException("未知合约 " + instrumentId));
    }

    /**
     * 手续费率与保证金率，首次查询后缓存（交易日内不变）。
     */
    public InstrumentRates rates(String instrumentId) {
        instrument(instrumentId);
        InstrumentRates cached = rates.get(instrumentId);
        if (cached != null) {
            return cached;
        }
        InstrumentRates fresh = new InstrumentRates(
            td.queryCommissionRate(instrumentId).orElse(null),
            td.queryMarginRate(instrumentId).orElse(null));
        rates.put(instrumentId, fresh);
        return fresh;
    }

    // ---- 行情 ----

    /**
     * 订阅行情。已订阅的合约不重复发送；为了持续合成 K 线，订阅后不再退订。
     */
    public void subscribe(Collection<String> instrumentIds) {
        for (String id : instrumentIds) {
            if (subscribed.contains(id) || catalog.instrument(id).isEmpty()) {
                continue;
            }
            if (md.subscribe(id)) {
                subscribed.add(id);
            }
        }
    }

    public Optional<MarketData> quote(String instrumentId) {
        return md.lastMarketData(instrumentId);
    }

    /**
     * 内存合成的 K 线。首次请求时订阅该合约，此前没有数据。
     */
    public List<Bar> bars(String instrumentId, BarPeriod period) {
        instrument(instrumentId);
        subscribe(List.of(instrumentId));
        BarSeries series = bars.get(instrumentId);
        return series == null ? List.of() : series.bars(period);
    }

    private void onTick(MarketData tick) {
        bars.computeIfAbsent(tick.instrumentID(), _ -> new BarSeries()).onTick(tick);
        notify(l -> l.onTick(tick));
    }

    // ---- 交易 ----

    public Order submit(OrderRequest request) {
        Objects.requireNonNull(request.instrumentId(), "instrumentId");
        Instrument instrument = instrument(request.instrumentId());
        OrderRequest.OrderType type = Objects.requireNonNullElse(request.orderType(), OrderRequest.OrderType.LIMIT);
        Order.OrderBuilder order = Order.builder()
            .instrumentID(instrument.instrumentID())
            .exchangeID(instrument.exchangeID())
            .direction(request.direction())
            .offsetFlag(request.offsetFlag())
            .limitPrice(request.price())
            .volume(request.volume());
        switch (type) {
            case LIMIT -> order.priceType(DataType.OrderPriceType.LimitPrice).timeCondition(DataType.TimeCondition.GFD)
                .volumeCondition(DataType.VolumeCondition.AV);
            case FAK -> order.priceType(DataType.OrderPriceType.LimitPrice).timeCondition(DataType.TimeCondition.IOC)
                .volumeCondition(DataType.VolumeCondition.AV);
            case FOK -> order.priceType(DataType.OrderPriceType.LimitPrice).timeCondition(DataType.TimeCondition.IOC)
                .volumeCondition(DataType.VolumeCondition.CV);
            case MARKET -> order.priceType(DataType.OrderPriceType.AnyPrice).timeCondition(DataType.TimeCondition.IOC)
                .volumeCondition(DataType.VolumeCondition.AV).limitPrice(0);
        }
        if (type != OrderRequest.OrderType.MARKET && !(request.price() > 0)) {
            throw new IllegalArgumentException("价格须大于 0");
        }
        Order submitted = td.submitOrder(order.build());
        // 回报可能先于返回到达（CTP 回调线程），已有更新的状态时不能用返回值覆盖
        orders.putIfAbsent(submitted.orderId(), submitted);
        return orders.get(submitted.orderId());
    }

    public Order cancel(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("未知报单 " + orderId);
        }
        // 撤单结果（含柜台拒绝）经回报监听更新，这里不写入，避免旧状态覆盖新回报
        td.cancelOrder(order);
        return orders.get(orderId);
    }

    public List<Order> orders() {
        return orders.values().stream()
            .sorted(Comparator.comparing(Order::insertTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Order::systemTimestamp, Comparator.reverseOrder()))
            .toList();
    }

    public List<Trade> trades() {
        return trades.values().stream()
            .sorted(Comparator.comparing(Trade::tradeTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Trade::systemTimestamp, Comparator.reverseOrder()))
            .toList();
    }

    /**
     * 查询结算单（柜台查询，可能耗时数秒）
     *
     * @param tradingDay 交易日，为 null 时查询最近一次结算单
     */
    public Optional<SettlementInfo> settlement(LocalDate tradingDay) {
        return td.querySettlementInfo(tradingDay);
    }

    public synchronized Account account() {
        Cached<Account> cached = account;
        if (cached == null || !cached.fresh()) {
            cached = new Cached<>(td.queryAccount(), System.currentTimeMillis());
            account = cached;
        }
        return cached.value();
    }

    public synchronized List<PositionView> positions() {
        Cached<List<PositionView>> cached = positions;
        if (cached == null || !cached.fresh()) {
            cached = new Cached<>(aggregate(td.queryPositions(), id -> instrumentOrNull(id)),
                System.currentTimeMillis());
            positions = cached;
        }
        return cached.value();
    }

    /**
     * 合并同一合约、方向、投保的持仓记录。剩余昨仓 = 持仓 - 今仓，对单条与今昨分条两种口径都成立。
     */
    static List<PositionView> aggregate(List<Position> rows, Function<String, Instrument> instruments) {
        Map<String, List<Position>> groups = rows.stream()
            .filter(p -> p.position() > 0)
            .collect(Collectors.groupingBy(p -> p.instrumentID() + "|" + p.direction() + "|" + p.hedgeFlag(),
                LinkedHashMap::new, Collectors.toList()));
        List<PositionView> result = new ArrayList<>();
        for (List<Position> group : groups.values()) {
            Position first = group.getFirst();
            Instrument instrument = instruments.apply(first.instrumentID());
            int multiple = instrument == null ? 1 : Math.max((int) instrument.volumeMultiple(), 1);
            int position = group.stream().mapToInt(Position::position).sum();
            int today = group.stream().mapToInt(Position::todayPosition).sum();
            double openCost = sum(group, Position::openCost);
            double positionCost = sum(group, Position::positionCost);
            result.add(PositionView.builder()
                .instrumentID(first.instrumentID())
                .exchangeID(first.exchangeID())
                .direction(first.direction())
                .hedgeFlag(first.hedgeFlag())
                .position(position)
                .ydPosition(position - today)
                .todayPosition(today)
                .longFrozen(group.stream().mapToInt(Position::longFrozen).sum())
                .shortFrozen(group.stream().mapToInt(Position::shortFrozen).sum())
                .openVolume(group.stream().mapToInt(Position::openVolume).sum())
                .closeVolume(group.stream().mapToInt(Position::closeVolume).sum())
                .combPosition(group.stream().mapToInt(Position::combPosition).sum())
                .strikeFrozen(group.stream().mapToInt(Position::strikeFrozen).sum())
                .abandonFrozen(group.stream().mapToInt(Position::abandonFrozen).sum())
                .tasPosition(group.stream().mapToInt(Position::tasPosition).sum())
                .openPrice(openCost / position / multiple)
                .positionPrice(positionCost / position / multiple)
                .openCost(openCost)
                .positionCost(positionCost)
                .openAmount(sum(group, Position::openAmount))
                .closeAmount(sum(group, Position::closeAmount))
                .useMargin(sum(group, Position::useMargin))
                .preMargin(sum(group, Position::preMargin))
                .exchangeMargin(sum(group, Position::exchangeMargin))
                .frozenMargin(sum(group, Position::frozenMargin))
                .frozenCash(sum(group, Position::frozenCash))
                .frozenCommission(sum(group, Position::frozenCommission))
                .positionProfit(sum(group, Position::positionProfit))
                .closeProfit(sum(group, Position::closeProfit))
                .closeProfitByDate(sum(group, Position::closeProfitByDate))
                .closeProfitByTrade(sum(group, Position::closeProfitByTrade))
                .commission(sum(group, Position::commission))
                .preSettlementPrice(firstValid(group, Position::preSettlementPrice))
                .settlementPrice(firstValid(group, Position::settlementPrice))
                .marginRateByMoney(firstValid(group, Position::marginRateByMoney))
                .marginRateByVolume(firstValid(group, Position::marginRateByVolume))
                .volumeMultiple(multiple)
                .build());
        }
        result.sort(Comparator.comparing(PositionView::instrumentID).thenComparing(PositionView::direction));
        return result;
    }

    /**
     * 持仓汇总需要合约乘数；未加载的合约单独查询，查询失败时按乘数 1 处理。
     */
    private Instrument instrumentOrNull(String instrumentId) {
        try {
            return catalog.instrument(instrumentId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("{} 合约 {} 查询失败: {}", userId, instrumentId, e.getMessage());
            return null;
        }
    }

    private static double sum(List<Position> group, ToDoubleFunction<Position> field) {
        return group.stream().mapToDouble(field).sum();
    }

    /**
     * 今昨仓记录的价格、比率相同，取首个有效值（非 NaN 且非 0）；都无效时为 NaN。
     */
    private static double firstValid(List<Position> group, ToDoubleFunction<Position> field) {
        return group.stream().mapToDouble(field).filter(v -> !Double.isNaN(v) && v != 0).findFirst().orElse(Double.NaN);
    }

    private void onOrder(Order order) {
        orders.put(order.orderId(), order);
        notify(l -> l.onOrder(order));
    }

    private void onTrade(Trade trade) {
        trades.put(trade.id(), trade);
        positions = null;
        account = null;
        notify(l -> l.onTrade(trade));
    }

    private void notify(Consumer<GatewayListener> event) {
        for (GatewayListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                log.warn("网关事件处理异常", e);
            }
        }
    }
}
