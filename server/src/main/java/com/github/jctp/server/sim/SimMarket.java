package com.github.jctp.server.sim;

import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Product;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 本地模拟柜台的行情，所有模拟账号共享一份：同一合约人人看到的价格一致。
 * <p>
 * 按当前日期生成近月合约。合约首次被订阅时生成 4 小时历史行情，之后每 500ms 随机游走一笔，只推送有人订阅的合约。
 * 历史按分钟保留开高低收 4 笔（成交量记在该分钟最后一笔），晚订阅的账号回放后得到与他人完全一致的 K 线。
 * 首个账号使用时创建，最后一个账号释放时停止；跨日后新登录的账号使用新交易日的行情。
 */
final class SimMarket {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long TICK_MILLIS = 500;
    private static final long HISTORY_MILLIS = 4 * 3600_000L;
    private static final long HISTORY_STEP_MILLIS = 15_000;
    private static final long MINUTE_MILLIS = 60_000;

    private static SimMarket current;

    /**
     * 取得当前交易日的行情并登记一个使用者，用完须 {@link #release()}
     */
    static synchronized SimMarket acquire() {
        LocalDate today = LocalDate.now(ZONE);
        if (current == null || !current.tradingDay.equals(today)) {
            current = new SimMarket(today);
        }
        current.users++;
        return current;
    }

    void release() {
        synchronized (SimMarket.class) {
            if (--users > 0) {
                return;
            }
            timer.shutdownNow();
            if (current == this) {
                current = null;
            }
        }
    }

    /**
     * 期货品种。consecutive 为当月起的连续月合约（如股指），否则从下月起取 months 中的月份
     */
    private record Spec(String id, String name, String exchange, double tick, int multiple, double price,
                        Set<Integer> months, boolean consecutive, int count, boolean options) {
    }

    private static final Set<Integer> EVEN_MONTHS = Set.of(2, 4, 6, 8, 10, 12);
    private static final Set<Integer> MAIN_MONTHS = Set.of(1, 5, 9);
    private static final List<Spec> SPECS = List.of(
        new Spec("au", "黄金", "SHFE", 0.02, 1000, 960, EVEN_MONTHS, false, 3, false),
        new Spec("ag", "白银", "SHFE", 1, 15, 11800, EVEN_MONTHS, false, 3, false),
        new Spec("rb", "螺纹钢", "SHFE", 1, 10, 3150, Set.of(1, 5, 10), false, 3, false),
        new Spec("IF", "沪深300股指", "CFFEX", 0.2, 300, 4000, Set.of(), true, 2, false),
        new Spec("m", "豆粕", "DCE", 1, 10, 2900, MAIN_MONTHS, false, 2, true),
        new Spec("TA", "PTA", "CZCE", 2, 5, 4700, MAIN_MONTHS, false, 2, false));

    final LocalDate tradingDay;
    private final Random random = new Random();
    private final List<Product> products = new ArrayList<>();
    private final Map<String, Book> books = new LinkedHashMap<>();
    private final ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sim-market").factory());
    private int users;

    private SimMarket(LocalDate tradingDay) {
        this.tradingDay = tradingDay;
        YearMonth month = YearMonth.from(tradingDay);
        for (Spec spec : SPECS) {
            products.add(product(spec.id(), spec.name(), spec.exchange(), DataType.ProductClass.Futures));
            List<YearMonth> deliveries = Stream.iterate(spec.consecutive() ? month : month.plusMonths(1), m -> m.plusMonths(1))
                .filter(m -> spec.consecutive() || spec.months().contains(m.getMonthValue()))
                .limit(spec.count()).toList();
            for (int i = 0; i < deliveries.size(); i++) {
                Instrument future = future(spec, deliveries.get(i));
                double price = round(spec.price() * (1 + 0.004 * i), spec.tick());
                add(future, price);
                if (i == 0 && spec.options()) {
                    addOptions(spec, future, price);
                }
            }
        }
        timer.scheduleAtFixedRate(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    List<Product> products() {
        return List.copyOf(products);
    }

    Collection<Instrument> instruments() {
        return books.values().stream().map(b -> b.instrument).toList();
    }

    Optional<Instrument> instrument(String instrumentId) {
        return Optional.ofNullable(books.get(instrumentId)).map(b -> b.instrument);
    }

    Optional<MarketData> quote(String instrumentId) {
        return Optional.ofNullable(books.get(instrumentId)).map(b -> b.quote);
    }

    /**
     * 最新价，未有行情时为昨结算价
     */
    double lastPrice(String instrumentId) {
        return books.get(instrumentId).last;
    }

    /**
     * 订阅：先把历史交给 replay，再向 subscriber 推送实时行情
     *
     * @return 合约不存在时 false
     */
    boolean subscribe(String instrumentId, Consumer<MarketData> subscriber, Consumer<MarketData> replay) {
        Book book = books.get(instrumentId);
        if (book == null) {
            return false;
        }
        synchronized (book) {
            if (book.minute < 0) {
                long now = System.currentTimeMillis();
                for (long t = now - HISTORY_MILLIS; t < now - 1000; t += HISTORY_STEP_MILLIS) {
                    book.record(book.step(t));
                }
            }
            if (book.subscribers.add(subscriber)) {
                book.history.forEach(replay);
                book.minuteTicks().forEach(replay);
            }
        }
        return true;
    }

    void unsubscribe(String instrumentId, Consumer<MarketData> subscriber) {
        Book book = books.get(instrumentId);
        if (book != null) {
            book.subscribers.remove(subscriber);
        }
    }

    void unsubscribeAll(Consumer<MarketData> subscriber) {
        books.values().forEach(b -> b.subscribers.remove(subscriber));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Book book : books.values()) {
            if (book.subscribers.isEmpty()) {
                continue;
            }
            synchronized (book) {
                MarketData tick = book.step(now);
                book.record(tick);
                book.subscribers.forEach(s -> s.accept(tick));
            }
        }
    }

    private void add(Instrument instrument, double price) {
        books.put(instrument.instrumentID(), new Book(instrument, price));
    }

    private static double round(double price, double tick) {
        return Math.round(price / tick) * tick;
    }

    /**
     * 合约代码：郑商所为品种 + 年份末位 + 月份（TA705），其余为品种 + 两位年份 + 月份（au2706）
     */
    private static String code(Spec spec, YearMonth month) {
        int year = spec.exchange().equals("CZCE") ? month.getYear() % 10 : month.getYear() % 100;
        return spec.id() + year + "%02d".formatted(month.getMonthValue());
    }

    private static Instrument future(Spec spec, YearMonth month) {
        String id = code(spec, month);
        return Instrument.builder().instrumentID(id).instrumentName(spec.name() + id.substring(spec.id().length()))
            .productID(spec.id()).exchangeID(spec.exchange())
            .productClass(DataType.ProductClass.Futures).isTrading(true).instLifePhase(DataType.InstLifePhase.Started)
            .priceTick(spec.tick()).volumeMultiple(spec.multiple()).longMarginRatio(0.1).shortMarginRatio(0.1)
            .minLimitOrderVolume(1).maxLimitOrderVolume(500).minMarketOrderVolume(1).maxMarketOrderVolume(30)
            .openDate(month.minusYears(1).atDay(15)).expireDate(month.atDay(15))
            .deliveryYear(month.getYear()).deliveryMonth(month.getMonthValue())
            .build();
    }

    /**
     * 首个合约的期权：平值上下各 4 档行权价
     */
    private void addOptions(Spec spec, Instrument underlying, double underlyingPrice) {
        String productId = spec.id() + "_o";
        products.add(product(productId, spec.name() + "期权", spec.exchange(), DataType.ProductClass.Options));
        int step = 50;
        long atm = Math.round(underlyingPrice / step) * step;
        LocalDate expire = YearMonth.of(underlying.deliveryYear(), underlying.deliveryMonth()).minusMonths(1).atDay(7);
        for (long strike = atm - 4L * step; strike <= atm + 4L * step; strike += step) {
            for (DataType.OptionsType type : DataType.OptionsType.values()) {
                boolean call = type == DataType.OptionsType.Call;
                String id = underlying.instrumentID() + "-" + (call ? "C" : "P") + "-" + strike;
                Instrument option = Instrument.builder().instrumentID(id).instrumentName(id).productID(productId)
                    .exchangeID(spec.exchange()).productClass(DataType.ProductClass.Options).isTrading(true)
                    .instLifePhase(DataType.InstLifePhase.Started).underlyingInstrID(underlying.instrumentID())
                    .optionsType(type).strikePrice(strike).priceTick(0.5).volumeMultiple(spec.multiple())
                    .minLimitOrderVolume(1).maxLimitOrderVolume(1000).expireDate(expire)
                    .build();
                double intrinsic = call ? underlyingPrice - strike : strike - underlyingPrice;
                add(option, round(Math.max(intrinsic, 0) + 40, 0.5));
            }
        }
    }

    private static Product product(String id, String name, String exchange, DataType.ProductClass productClass) {
        return Product.builder().productID(id).productName(name).exchangeID(exchange).productClass(productClass).build();
    }

    /**
     * 单个合约的随机游走行情。step/record 在 synchronized(book) 内调用
     */
    private final class Book {
        final Instrument instrument;
        final double preSettlement;
        final Set<Consumer<MarketData>> subscribers = new CopyOnWriteArraySet<>();
        /**
         * 已结束各分钟的开高低收行情，见 {@link #minuteTicks()}
         */
        final List<MarketData> history = new ArrayList<>();
        /**
         * 当前分钟（epoch 分钟数）及其开、高、低、收行情与累计成交，未有行情时为 -1
         */
        long minute = -1;
        MarketData first;
        MarketData highest;
        MarketData lowest;
        MarketData latest;
        int minuteVolume;
        double minuteTurnover;
        volatile double last;
        volatile MarketData quote;
        double open;
        double high;
        double low;
        int volume;
        double turnover;
        double openInterest;

        Book(Instrument instrument, double base) {
            this.instrument = instrument;
            this.preSettlement = base;
            this.last = base;
            this.open = round(base * (1 + (random.nextDouble() - 0.5) * 0.004));
            this.high = Math.max(open, base);
            this.low = Math.min(open, base);
            this.openInterest = 50_000 + random.nextInt(200_000);
        }

        double round(double price) {
            return SimMarket.round(price, instrument.priceTick());
        }

        void record(MarketData tick) {
            long m = tick.systemTimestamp() / MINUTE_MILLIS;
            if (m != minute) {
                history.addAll(minuteTicks());
                minute = m;
                first = highest = lowest = tick;
                minuteVolume = 0;
                minuteTurnover = 0;
            }
            if (tick.lastPrice() > highest.lastPrice()) {
                highest = tick;
            }
            if (tick.lastPrice() < lowest.lastPrice()) {
                lowest = tick;
            }
            latest = tick;
            minuteVolume += tick.volumeDelta();
            minuteTurnover += tick.turnoverDelta();
        }

        /**
         * 当前分钟按时间排列的开、高、低、收行情：K 线由这几笔即可还原，成交量、成交额全部记在最后一笔
         */
        List<MarketData> minuteTicks() {
            if (minute < 0) {
                return List.of();
            }
            List<MarketData> ticks = Stream.of(first, highest, lowest, latest).distinct()
                .sorted(Comparator.comparingLong(MarketData::systemTimestamp)).toList();
            List<MarketData> out = new ArrayList<>(ticks.size());
            for (MarketData t : ticks) {
                boolean last = t == latest;
                out.add(t.toBuilder().volumeDelta(last ? minuteVolume : 0).turnoverDelta(last ? minuteTurnover : 0).build());
            }
            return out;
        }

        MarketData step(long time) {
            double tick = instrument.priceTick();
            double price = round(Math.max(tick, last + (random.nextInt(5) - 2) * tick));
            price = Math.clamp(price, round(preSettlement * 0.93), round(preSettlement * 1.07));
            last = price;
            high = Math.max(high, price);
            low = Math.min(low, price);
            int delta = 1 + random.nextInt(30);
            volume += delta;
            turnover += delta * price * instrument.volumeMultiple();
            openInterest += random.nextInt(21) - 10;
            quote = MarketData.builder()
                .instrumentID(instrument.instrumentID()).exchangeId(instrument.exchangeID())
                .systemTimestamp(time).actionTimestamp(time)
                .actionTime(LocalTime.ofInstant(Instant.ofEpochMilli(time), ZONE))
                .actionDay(LocalDate.ofInstant(Instant.ofEpochMilli(time), ZONE)).tradingDay(tradingDay)
                .lastPrice(price).preSettlementPrice(preSettlement).preClosePrice(preSettlement)
                .preOpenInterest(openInterest - 1000).openPrice(open).highestPrice(high).lowestPrice(low)
                .upperLimitPrice(round(preSettlement * 1.07)).lowerLimitPrice(round(preSettlement * 0.93))
                .settlementPrice(Double.NaN).closePrice(Double.NaN).averagePrice(turnover / Math.max(volume, 1) / instrument.volumeMultiple())
                .volume(volume).volumeDelta(delta).turnover(turnover).turnoverDelta(delta * price * instrument.volumeMultiple())
                .openInterest(openInterest)
                .bidPrice1(price - tick).bidVolume1(1 + random.nextInt(200)).askPrice1(price + tick).askVolume1(1 + random.nextInt(200))
                .bidPrice2(price - 2 * tick).bidVolume2(1 + random.nextInt(300)).askPrice2(price + 2 * tick).askVolume2(1 + random.nextInt(300))
                .bidPrice3(price - 3 * tick).bidVolume3(1 + random.nextInt(300)).askPrice3(price + 3 * tick).askVolume3(1 + random.nextInt(300))
                .bidPrice4(price - 4 * tick).bidVolume4(1 + random.nextInt(300)).askPrice4(price + 4 * tick).askVolume4(1 + random.nextInt(300))
                .bidPrice5(price - 5 * tick).bidVolume5(1 + random.nextInt(300)).askPrice5(price + 5 * tick).askVolume5(1 + random.nextInt(300))
                .build();
            return quote;
        }
    }
}
