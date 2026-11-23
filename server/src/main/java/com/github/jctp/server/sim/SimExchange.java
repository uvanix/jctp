package com.github.jctp.server.sim;

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

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 本地模拟柜台的一个账号，不连 CTP，任意账号密码即可登录。
 * <p>
 * 合约与行情来自所有账号共享的 {@link SimMarket}；报单、成交、持仓、资金按账号独立。
 * 挂单价优于对手价即按对手价全部成交，初始权益 100 万。网关断开时退出共享行情。
 */
public final class SimExchange {

    private static final ZoneId ZONE = SimMarket.ZONE;
    private static final Charset GBK = Charset.forName("GBK");

    private final SimMarket market = SimMarket.acquire();
    private final AtomicBoolean released = new AtomicBoolean();
    private final Consumer<MarketData> subscriber = this::publish;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Trade> trades = new ConcurrentHashMap<>();
    private final Map<String, double[]> positions = new ConcurrentHashMap<>(); // key 合约|方向 → [手数, 开仓成本, 今仓]
    private final AtomicInteger orderRef = new AtomicInteger();
    private final AtomicInteger tradeSeq = new AtomicInteger();
    private double closeProfit;
    private volatile Consumer<Order> orderListener = _ -> {
    };
    private volatile Consumer<Trade> tradeListener = _ -> {
    };
    private volatile Consumer<MarketData> tickListener = _ -> {
    };

    private final String brokerId;
    private final String userId;
    private final LocalDate tradingDay = market.tradingDay;

    public final Td td = new Td();
    public final Md md = new Md();

    public SimExchange(String brokerId, String userId) {
        this.brokerId = brokerId;
        this.userId = userId;
    }

    /**
     * 退出共享行情，可重复调用
     */
    private void release() {
        if (released.compareAndSet(false, true)) {
            market.unsubscribeAll(subscriber);
            market.release();
        }
    }

    private void publish(MarketData tick) {
        tickListener.accept(tick);
        match(tick);
    }

    /**
     * 挂单价优于对手价即按对手价全部成交
     */
    private synchronized void match(MarketData tick) {
        for (Order o : List.copyOf(orders.values())) {
            if (o.done() || !o.instrumentID().equals(tick.instrumentID())) {
                continue;
            }
            boolean buy = o.direction() == DataType.Direction.Buy;
            double price = buy ? tick.askPrice1() : tick.bidPrice1();
            boolean market = o.priceType() == DataType.OrderPriceType.AnyPrice;
            if (market || (buy ? o.limitPrice() >= price : o.limitPrice() <= price)) {
                fill(o, price);
            } else if (o.timeCondition() == DataType.TimeCondition.IOC) {
                updateOrder(o.toBuilder().status(DataType.OrderStatus.Canceled).volumeTotal(0).statusMsg("已撤单(FAK/FOK未成交)").build());
            }
        }
    }

    private void fill(Order o, double price) {
        Instrument instrument = market.instrument(o.instrumentID()).orElseThrow();
        int volume = o.volumeTotal();
        boolean open = o.offsetFlag() == DataType.OffsetFlag.Open;
        boolean buy = o.direction() == DataType.Direction.Buy;
        String key = o.instrumentID() + "|" + ((buy == open) ? "Long" : "Short");
        double[] p = positions.computeIfAbsent(key, _ -> new double[3]);
        if (open) {
            p[0] += volume;
            p[1] += price * volume * instrument.volumeMultiple();
            p[2] += volume;
        } else {
            int closing = (int) Math.min(volume, p[0]);
            double avg = p[0] == 0 ? price : p[1] / p[0] / instrument.volumeMultiple();
            closeProfit += (price - avg) * closing * instrument.volumeMultiple() * (buy ? -1 : 1);
            p[1] -= avg * closing * instrument.volumeMultiple();
            p[0] -= closing;
            p[2] = Math.clamp(p[2], 0, p[0]);
        }
        updateOrder(o.toBuilder().status(DataType.OrderStatus.AllTraded).volumeTraded(o.volume()).volumeTotal(0)
            .statusMsg("全部成交").updateTime(LocalTime.now(ZONE).withNano(0)).build());
        Trade trade = Trade.builder().instrumentID(o.instrumentID()).exchangeID(o.exchangeID())
            .tradeID(String.valueOf(100000 + tradeSeq.incrementAndGet())).orderRef(o.orderRef()).orderSysID(o.orderSysID())
            .direction(o.direction()).offsetFlag(o.offsetFlag()).hedgeFlag(DataType.HedgeFlag.Speculation)
            .tradeType(DataType.TradeType.Common).price(price).volume(volume).tradingDay(tradingDay)
            .tradeDate(LocalDate.now(ZONE)).tradeTime(LocalTime.now(ZONE).withNano(0)).systemTimestamp(System.currentTimeMillis())
            .build();
        trades.put(trade.id(), trade);
        tradeListener.accept(trade);
    }

    /**
     * 仿照期货公司结算单的定宽文本。按 GBK 字节宽度对齐（汉字占两格），与 CTP 返回的结算单一致
     */
    private String settlementText() {
        Account a = td.queryAccount();
        String line = "-".repeat(96);
        StringBuilder sb = new StringBuilder();
        sb.append(pad("", 36)).append("模拟结算单(盯市) Settlement Statement(MTM)\n");
        sb.append(pad("交易日期 Date：" + tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE), 48))
            .append("客户号 Client ID：").append(userId).append('\n');
        sb.append(line).append('\n').append(pad("", 40)).append("资金状况 币种：人民币\n").append(line).append('\n');
        sb.append(pad(money("上日结存 Balance b/f：", a.preBalance()), 48)).append(money("客户权益 Client Equity：", a.balance())).append('\n');
        sb.append(pad(money("平仓盈亏 Realized P/L：", a.closeProfit()), 48)).append(money("可用资金 Fund Avail.：", a.available())).append('\n');
        sb.append(pad(money("持仓盯市盈亏 MTM P/L：", a.positionProfit()), 48)).append(money("保证金占用 Margin Occupied：", a.currMargin())).append('\n');
        sb.append(line).append('\n').append(pad("", 36)).append("成交记录 Transaction Record\n").append(line).append('\n');
        sb.append(row("成交日期", "合约", "买/卖", "开平", "成交价", "手数", "成交额")).append('\n');
        trades.values().stream().sorted(Comparator.comparing(Trade::tradeTime)).forEach(t -> {
            Instrument i = market.instrument(t.instrumentID()).orElseThrow();
            sb.append(row(t.tradeDate().format(DateTimeFormatter.BASIC_ISO_DATE), t.instrumentID(),
                t.direction() == DataType.Direction.Buy ? "买" : "卖", t.offsetFlag() == DataType.OffsetFlag.Open ? "开" : "平",
                "%.2f".formatted(t.price()), String.valueOf(t.volume()),
                "%,.2f".formatted(t.price() * t.volume() * i.volumeMultiple()))).append('\n');
        });
        sb.append(line).append('\n').append(pad("", 36)).append("持仓汇总 Positions\n").append(line).append('\n');
        sb.append(row("合约", "多空", "持仓", "开仓成本", "盯市盈亏", "保证金", "")).append('\n');
        td.queryPositions().forEach(p -> sb.append(row(p.instrumentID(), p.direction() == DataType.PosiDirection.Long ? "多" : "空",
            String.valueOf(p.position()), "%,.2f".formatted(p.openCost()), "%,.2f".formatted(p.positionProfit()),
            "%,.2f".formatted(p.useMargin()), "")).append('\n'));
        sb.append(line).append('\n');
        return sb.toString();
    }

    private static String money(String label, double value) {
        return label + "%,16.2f".formatted(value);
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder("|");
        for (String c : cells) {
            sb.append(pad(c, 13)).append('|');
        }
        return sb.toString();
    }

    /**
     * 右补空格到 width 个 GBK 字节宽（汉字算两格）
     */
    private static String pad(String text, int width) {
        int w = text.getBytes(GBK).length;
        return w >= width ? text : text + " ".repeat(width - w);
    }

    private void updateOrder(Order order) {
        orders.put(order.orderId(), order);
        orderListener.accept(order);
    }

    public final class Td implements CtpTdBroker {

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
            release();
        }

        @Override
        public LocalDate tradingDay() {
            return tradingDay;
        }

        @Override
        public Optional<Instrument> instrument(String instrumentId) {
            return market.instrument(instrumentId);
        }

        @Override
        public Collection<Instrument> instruments() {
            return market.instruments();
        }

        @Override
        public Collection<Product> products() {
            return market.products();
        }

        @Override
        public List<Product> queryProducts() {
            return market.products();
        }

        @Override
        public List<Instrument> queryInstruments(IntConsumer progress) {
            List<Instrument> all = List.copyOf(market.instruments());
            progress.accept(all.size());
            return all;
        }

        @Override
        public List<Instrument> queryInstruments(String exchangeId, String productId) {
            return market.instruments().stream()
                .filter(i -> i.exchangeID().equals(exchangeId) && i.productID().equals(productId))
                .toList();
        }

        @Override
        public Optional<Instrument> queryInstrument(String instrumentId) {
            return market.instrument(instrumentId);
        }

        @Override
        public void setConnectListener(Consumer<String> stageListener) {
        }

        @Override
        public Investor investor() {
            return Investor.builder().investorID(userId).brokerID(brokerId).investorName("模拟投资者").active(true).build();
        }

        @Override
        public Order submitOrder(Order order) {
            Order submitted = order.toBuilder().frontID(1).sessionID(1).orderRef(String.valueOf(orderRef.incrementAndGet()))
                .orderSysID(String.format("%12d", 500000 + orderRef.get()).trim())
                .hedgeFlag(DataType.HedgeFlag.Speculation)
                .submitStatus(DataType.OrderSubmitStatus.Accepted).status(DataType.OrderStatus.NoTradeQueueing)
                .statusMsg("未成交").volumeTotal(order.volume()).tradingDay(tradingDay)
                .insertTime(LocalTime.now(ZONE).withNano(0)).systemTimestamp(System.currentTimeMillis()).build();
            if (order.offsetFlag() != DataType.OffsetFlag.Open) {
                boolean buy = order.direction() == DataType.Direction.Buy;
                double[] p = positions.get(order.instrumentID() + "|" + (buy ? "Short" : "Long"));
                if (p == null || p[0] < order.volume()) {
                    Order rejected = submitted.toBuilder().submitStatus(DataType.OrderSubmitStatus.InsertRejected)
                        .status(DataType.OrderStatus.Canceled).volumeTotal(0).statusMsg("CTP:平仓量超过持仓量").build();
                    updateOrder(rejected);
                    return rejected;
                }
            }
            updateOrder(submitted);
            market.quote(order.instrumentID()).ifPresent(SimExchange.this::match);
            return submitted;
        }

        @Override
        public Order cancelOrder(Order order) {
            Order known = orders.get(order.orderId());
            if (known != null && !known.done()) {
                updateOrder(known.toBuilder().status(DataType.OrderStatus.Canceled).volumeTotal(0).statusMsg("已撤单")
                    .cancelTime(LocalTime.now(ZONE).withNano(0)).build());
            }
            return known;
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
            return List.copyOf(trades.values());
        }

        @Override
        public Account queryAccount() {
            double margin = 0;
            double positionProfit = 0;
            for (Position p : queryPositions()) {
                margin += p.useMargin();
                positionProfit += p.positionProfit();
            }
            double balance = 1_000_000 + closeProfit + positionProfit;
            return Account.builder().accountID(userId).currencyID("CNY").tradingDay(tradingDay).preBalance(1_000_000)
                .balance(balance).available(balance - margin).currMargin(margin).exchangeMargin(margin)
                .closeProfit(closeProfit).positionProfit(positionProfit).withdrawQuota(balance - margin).build();
        }

        @Override
        public List<Position> queryPositions() {
            List<Position> list = new ArrayList<>();
            positions.forEach((key, p) -> {
                if (p[0] <= 0) {
                    return;
                }
                String[] parts = key.split("\\|");
                Instrument instrument = market.instrument(parts[0]).orElseThrow();
                boolean longSide = parts[1].equals("Long");
                double last = market.lastPrice(parts[0]);
                double value = last * p[0] * instrument.volumeMultiple();
                list.add(Position.builder().instrumentID(parts[0]).exchangeID(instrument.exchangeID())
                    .direction(longSide ? DataType.PosiDirection.Long : DataType.PosiDirection.Short)
                    .hedgeFlag(DataType.HedgeFlag.Speculation).positionDate(DataType.PositionDate.Today)
                    .tradingDay(tradingDay).position((int) p[0]).todayPosition((int) p[2]).openCost(p[1]).positionCost(p[1])
                    .useMargin(value * 0.1).positionProfit((value - p[1]) * (longSide ? 1 : -1)).build());
            });
            return list;
        }

        @Override
        public List<Order> queryOrders() {
            return List.copyOf(orders.values());
        }

        @Override
        public List<Trade> queryTrades() {
            return List.copyOf(trades.values());
        }

        @Override
        public Optional<CommissionRate> queryCommissionRate(String instrumentId) {
            return Optional.of(CommissionRate.builder().instrumentID(instrumentId).openRatioByMoney(0.0001)
                .closeRatioByMoney(0.0001).closeTodayRatioByMoney(0.0003).build());
        }

        /**
         * 模拟柜台只有当日（以当前资金、持仓、成交生成的）结算单，其他交易日没有
         */
        @Override
        public Optional<SettlementInfo> querySettlementInfo(LocalDate day) {
            if (day != null && !day.equals(tradingDay)) {
                return Optional.empty();
            }
            return Optional.of(SettlementInfo.builder().tradingDay(tradingDay).settlementID(1).brokerID(brokerId)
                .investorID(userId).accountID(userId).currencyID("CNY").content(settlementText()).build());
        }

        @Override
        public Optional<MarginRate> queryMarginRate(String instrumentId) {
            return Optional.of(MarginRate.builder().instrumentID(instrumentId).hedgeFlag(DataType.HedgeFlag.Speculation)
                .longMarginRatioByMoney(0.12).shortMarginRatioByMoney(0.12).build());
        }

        @Override
        public void setOrderListener(Consumer<Order> orderConsumer) {
            orderListener = orderConsumer;
        }

        @Override
        public void setTradeListener(Consumer<Trade> tradeConsumer) {
            tradeListener = tradeConsumer;
        }
    }

    public final class Md implements CtpMdBroker {

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
            release();
        }

        @Override
        public LocalDate tradingDay() {
            return tradingDay;
        }

        /**
         * 订阅时回放共享行情的历史，K 线一打开就有数据
         */
        @Override
        public boolean subscribe(String instrumentId) {
            return market.subscribe(instrumentId, subscriber, tick -> tickListener.accept(tick));
        }

        @Override
        public boolean unsubscribe(String instrumentId) {
            market.unsubscribe(instrumentId, subscriber);
            return true;
        }

        @Override
        public Optional<MarketData> lastMarketData(String instrumentId) {
            return market.quote(instrumentId);
        }

        @Override
        public void setMarketDataListener(Consumer<MarketData> marketDataConsumer) {
            tickListener = marketDataConsumer;
        }
    }
}
