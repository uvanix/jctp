package com.github.jctp.server;

import com.github.jctp.broker.CtpMdBroker;
import com.github.jctp.broker.CtpTdBroker;
import com.github.jctp.struct.Account;
import com.github.jctp.struct.CommissionRate;
import com.github.jctp.struct.DataType;
import com.github.jctp.struct.Instrument;
import com.github.jctp.struct.Investor;
import com.github.jctp.struct.MarginRate;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Position;
import com.github.jctp.struct.Product;
import com.github.jctp.struct.SettlementInfo;
import com.github.jctp.struct.Trade;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 内存模拟柜台：报单立即挂单，撤单立即撤销。
 */
public final class FakeBrokers {

    public static final LocalDate TRADING_DAY = LocalDate.of(2026, 11, 16);

    private FakeBrokers() {
    }

    public static Instrument future(String id, String name, String product, String exchange) {
        return Instrument.builder()
            .instrumentID(id).instrumentName(name).productID(product).exchangeID(exchange)
            .productClass(DataType.ProductClass.Futures).isTrading(true).instLifePhase(DataType.InstLifePhase.Started)
            .priceTick(1).volumeMultiple(10).longMarginRatio(0.1).shortMarginRatio(0.1)
            .build();
    }

    public static Instrument option(String id, String product, String underlying, DataType.OptionsType type, double strike) {
        return Instrument.builder()
            .instrumentID(id).instrumentName(id).productID(product).exchangeID("SHFE")
            .productClass(DataType.ProductClass.Options).isTrading(true).instLifePhase(DataType.InstLifePhase.Started)
            .underlyingInstrID(underlying).optionsType(type).strikePrice(strike)
            .priceTick(0.5).volumeMultiple(10)
            .build();
    }

    public static MarketData tick(String id, long actionTimestamp, double price, int volumeDelta) {
        return MarketData.builder()
            .instrumentID(id).exchangeId("SHFE").tradingDay(TRADING_DAY)
            .actionTimestamp(actionTimestamp).systemTimestamp(actionTimestamp)
            .lastPrice(price).volumeDelta(volumeDelta).bidPrice1(price - 1).askPrice1(price + 1)
            .upperLimitPrice(price * 1.1).lowerLimitPrice(price * 0.9).settlementPrice(Double.NaN)
            .build();
    }

    public static final class Td implements CtpTdBroker {

        public final Map<String, Instrument> instruments = new ConcurrentHashMap<>();
        /**
         * 交易所|品种 → 产品，构造时按合约生成（名称即品种代码）；测试可增删
         */
        public final Map<String, Product> products = new ConcurrentHashMap<>();
        public final List<Position> positions = new ArrayList<>();
        public final AtomicInteger connects = new AtomicInteger();
        public final AtomicInteger disconnects = new AtomicInteger();
        /**
         * 按品种查询合约的次数（交易所|品种 → 次数）
         */
        public final Map<String, AtomicInteger> productQueries = new ConcurrentHashMap<>();
        /**
         * 查询时抛出超时的品种（交易所|品种）
         */
        public final Set<String> failingProducts = ConcurrentHashMap.newKeySet();
        private final AtomicInteger orderRef = new AtomicInteger();
        private final Map<String, Order> orders = new ConcurrentHashMap<>();
        private volatile Consumer<Order> orderListener = _ -> {
        };
        private volatile Consumer<Trade> tradeListener = _ -> {
        };
        public volatile RuntimeException connectError;
        /**
         * 非空时 connect 阻塞至放行，模拟盘中连接耗时
         */
        public volatile CountDownLatch connectGate;

        public Td(Instrument... instruments) {
            for (Instrument i : instruments) {
                this.instruments.put(i.instrumentID(), i);
                this.products.putIfAbsent(i.exchangeID() + "|" + i.productID(), Product.builder()
                    .productID(i.productID()).exchangeID(i.exchangeID()).productClass(i.productClass())
                    .productName(i.productID()).build());
            }
        }

        @Override
        public void connect() {
            if (connectGate != null) {
                try {
                    connectGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (connectError != null) {
                throw connectError;
            }
            connects.incrementAndGet();
        }

        @Override
        public void disconnect() {
            disconnects.incrementAndGet();
        }

        @Override
        public LocalDate tradingDay() {
            return TRADING_DAY;
        }

        @Override
        public Optional<Instrument> instrument(String instrumentId) {
            return Optional.ofNullable(instruments.get(instrumentId));
        }

        @Override
        public Collection<Instrument> instruments() {
            return List.copyOf(instruments.values());
        }

        @Override
        public Collection<Product> products() {
            return List.copyOf(products.values());
        }

        @Override
        public List<Product> queryProducts() {
            return List.copyOf(products.values());
        }

        @Override
        public List<Instrument> queryInstruments(IntConsumer progress) {
            progress.accept(instruments.size());
            return List.copyOf(instruments.values());
        }

        @Override
        public List<Instrument> queryInstruments(String exchangeId, String productId) {
            productQueries.computeIfAbsent(exchangeId + "|" + productId, _ -> new AtomicInteger()).incrementAndGet();
            if (failingProducts.contains(exchangeId + "|" + productId)) {
                throw new IllegalStateException("CTP 合约查询 " + productId + "超时");
            }
            return instruments.values().stream()
                .filter(i -> i.exchangeID().equals(exchangeId) && i.productID().equals(productId))
                .toList();
        }

        @Override
        public Optional<Instrument> queryInstrument(String instrumentId) {
            return Optional.ofNullable(instruments.get(instrumentId));
        }

        @Override
        public void setConnectListener(Consumer<String> stageListener) {
        }

        @Override
        public Investor investor() {
            return Investor.builder().investorID("000001").brokerID("9999").investorName("模拟投资者").active(true).build();
        }

        @Override
        public Order submitOrder(Order order) {
            Order submitted = order.toBuilder().frontID(1).sessionID(2).orderRef(String.valueOf(orderRef.incrementAndGet()))
                .submitStatus(DataType.OrderSubmitStatus.InsertSubmitted).status(DataType.OrderStatus.Unknown)
                .volumeTotal(order.volume()).insertTime(LocalTime.of(9, 30)).build();
            orders.put(submitted.orderId(), submitted);
            update(submitted.toBuilder().submitStatus(DataType.OrderSubmitStatus.Accepted)
                .status(DataType.OrderStatus.NoTradeQueueing).orderSysID("SYS" + submitted.orderRef()).build());
            return submitted;
        }

        @Override
        public Order cancelOrder(Order order) {
            Order known = orders.get(order.orderId());
            update(known.toBuilder().status(DataType.OrderStatus.Canceled).volumeTotal(0).statusMsg("已撤单").build());
            return known;
        }

        /**
         * 模拟成交回报
         */
        public void fill(Trade trade) {
            tradeListener.accept(trade);
        }

        private void update(Order order) {
            orders.put(order.orderId(), order);
            orderListener.accept(order);
        }

        @Override
        public Optional<Order> order(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public Collection<Order> orders() {
            return List.copyOf(orders.values());
        }

        @Override
        public Collection<Trade> trades() {
            return List.of();
        }

        @Override
        public Account queryAccount() {
            return Account.builder().accountID("000001").tradingDay(TRADING_DAY).balance(1_000_000).available(900_000).build();
        }

        @Override
        public List<Position> queryPositions() {
            return List.copyOf(positions);
        }

        @Override
        public List<Order> queryOrders() {
            return List.of();
        }

        @Override
        public List<Trade> queryTrades() {
            return List.of();
        }

        @Override
        public Optional<CommissionRate> queryCommissionRate(String instrumentId) {
            return Optional.of(CommissionRate.builder().instrumentID(instrumentId).openRatioByMoney(0.0001).build());
        }

        /**
         * 结算单查询返回的内容，为 null 时没有结算单；settlementQueries 记录每次查询的交易日（null 为最近一次）
         */
        public volatile SettlementInfo settlement;
        public final List<Optional<LocalDate>> settlementQueries = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Optional<SettlementInfo> querySettlementInfo(LocalDate day) {
            settlementQueries.add(Optional.ofNullable(day));
            return Optional.ofNullable(settlement);
        }

        @Override
        public Optional<MarginRate> queryMarginRate(String instrumentId) {
            return Optional.of(MarginRate.builder().instrumentID(instrumentId).longMarginRatioByMoney(0.12).build());
        }

        @Override
        public void setOrderListener(Consumer<Order> orderConsumer) {
            this.orderListener = orderConsumer;
        }

        @Override
        public void setTradeListener(Consumer<Trade> tradeConsumer) {
            this.tradeListener = tradeConsumer;
        }
    }

    public static final class Md implements CtpMdBroker {

        public final Set<String> subscribed = ConcurrentHashMap.newKeySet();
        public final AtomicInteger connects = new AtomicInteger();
        public final AtomicInteger disconnects = new AtomicInteger();
        private final Map<String, MarketData> last = new ConcurrentHashMap<>();
        private volatile Consumer<MarketData> listener = _ -> {
        };

        public void push(MarketData tick) {
            last.put(tick.instrumentID(), tick);
            listener.accept(tick);
        }

        @Override
        public void connect() {
            connects.incrementAndGet();
        }

        @Override
        public void disconnect() {
            disconnects.incrementAndGet();
        }

        @Override
        public LocalDate tradingDay() {
            return TRADING_DAY;
        }

        @Override
        public boolean subscribe(String instrumentId) {
            subscribed.add(instrumentId);
            return true;
        }

        @Override
        public boolean unsubscribe(String instrumentId) {
            return subscribed.remove(instrumentId);
        }

        @Override
        public Optional<MarketData> lastMarketData(String instrumentId) {
            return Optional.ofNullable(last.get(instrumentId));
        }

        @Override
        public void setMarketDataListener(Consumer<MarketData> marketDataConsumer) {
            this.listener = marketDataConsumer;
        }
    }
}
