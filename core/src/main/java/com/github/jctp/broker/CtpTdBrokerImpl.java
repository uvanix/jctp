package com.github.jctp.broker;

import ch.qos.logback.core.util.StringUtil;
import com.github.jctp.CtpConfig;
import com.github.jctp.ffm.*;
import com.github.jctp.struct.*;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static com.github.jctp.broker.BrokerSupport.*;

public class CtpTdBrokerImpl implements CtpTdBroker {

    private final Logger log;
    private final CtpConfig config;
    private final CtpStructs structs;

    private final AtomicInteger requestId = new AtomicInteger();
    private final AtomicInteger orderRefSeq = new AtomicInteger();
    private final Map<String, Instrument> instrumentMap = new ConcurrentHashMap<>();
    /**
     * 交易所代码|产品代码 → 产品
     */
    private final Map<String, Product> productMap = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Trade> trades = new ConcurrentHashMap<>();
    private final CtpQueryChannel<CtpTraderApi> queries;
    /**
     * OrderRef 须按发送顺序递增，分配与发送放在同一临界区
     */
    private final Object orderInsertLock = new Object();

    private volatile boolean ready;
    private volatile int frontId;
    private volatile int sessionId;
    private volatile LocalDate tradingDay;
    private volatile Investor investor;
    private volatile CompletableFuture<Void> login;
    /**
     * 连接阶段：登录请求已发出、尚未收到应答
     */
    private volatile boolean loginPending;
    private final AtomicInteger loginDisconnects = new AtomicInteger();
    private volatile String version;
    private volatile Consumer<Order> orderConsumer = _ -> {
    };
    private volatile Consumer<Trade> tradeConsumer = _ -> {
    };
    private volatile Consumer<String> connectListener = _ -> {
    };
    private volatile CtpTraderApi api;

    public CtpTdBrokerImpl(ILoggerFactory loggerFactory, CtpConfig config) {
        this.log = loggerFactory.getLogger(getClass().getName());
        this.config = config;
        this.structs = config.version().structs();
        this.queries = new CtpQueryChannel<>("交易", config.timeout(), requestId::incrementAndGet,
            new CtpQueryChannel.Readiness<>() {
                @Override
                public CtpTraderApi readyApi() {
                    CtpTraderApi current = api;
                    return ready ? current : null;
                }

                @Override
                public boolean closed() {
                    return api == null;
                }
            }, log);
    }

    public CtpTdBrokerImpl(CtpConfig config) {
        this(LoggerFactory.getILoggerFactory(), config);
    }

    private void stage(String stage) {
        try {
            connectListener.accept(stage);
        } catch (RuntimeException e) {
            log.warn("连接进度监听器处理异常 {}", stage, e);
        }
    }

    /**
     * 连接、认证、登录、查询投资者信息、确认结算单，阻塞至就绪；失败或超时时释放连接并抛出异常。
     * 不加载合约，需要时调用 {@link #queryInstruments()}。
     */
    @Override
    public synchronized void connect() {
        if (api != null) {
            return;
        }
        login = new CompletableFuture<>();
        loginPending = false;
        loginDisconnects.set(0);
        CtpLibrary library = CtpLibrary.load(config.version(), config.libraryDir());
        version = library.traderApiVersion();
        api = new CtpTraderApi(library, flowDir(config, "td", version));
        api.registerSpi(new Spi());
        api.subscribePrivateTopic(CtpTraderApi.ResumeType.RESUME);
        api.subscribePublicTopic(CtpTraderApi.ResumeType.QUICK);
        api.registerFront(config.tdFront());
        stage("连接交易前置");
        api.init();
        log.info("CTP {} 交易连接 {}", version, config.tdFront());
        await(login, config.timeout(), "交易登录", this::disconnect);
    }

    @Override
    public synchronized void disconnect() {
        if (api == null) {
            return;
        }
        ready = false;
        investor = null;
        api.close();
        api = null;
        queries.close("CTP 交易已断开");
        log.info("CTP 交易已断开 {}", config.tdFront());
    }

    @Override
    public LocalDate tradingDay() {
        return tradingDay;
    }

    @Override
    public Investor investor() {
        return investor;
    }

    @Override
    public List<Product> queryProducts() {
        List<Product> list = queries.query("产品查询", s -> CtpStructConvert.toProduct(structs, s),
            (td, id) -> td.reqQryProduct(_ -> {
            }, id)
        );
        Map<String, Product> loaded = new HashMap<>();
        list.forEach(p -> loaded.put(p.exchangeID() + "|" + p.productID(), p));
        productMap.putAll(loaded);
        productMap.keySet().retainAll(loaded.keySet());
        log.info("CTP 产品加载完成 {} 个", list.size());
        return list;
    }

    @Override
    public Collection<Product> products() {
        return List.copyOf(productMap.values());
    }

    @Override
    public List<Instrument> queryInstruments(IntConsumer progress) {
        List<Instrument> list = queries.query("合约查询", s -> CtpStructConvert.toInstrument(structs, s),
            (td, id) -> td.reqQryInstrument(_ -> {
            }, id), progress);
        Set<String> ids = new HashSet<>();
        list.forEach(i -> {
            instrumentMap.put(i.instrumentID(), i);
            ids.add(i.instrumentID());
        });
        // 去掉已下市的合约
        instrumentMap.keySet().retainAll(ids);
        log.info("CTP 合约加载完成 {} 个", list.size());
        return list;
    }

    @Override
    public List<Instrument> queryInstruments(String exchangeId, String productId) {
        List<Instrument> list = queries.query("合约查询 " + productId, s -> CtpStructConvert.toInstrument(structs, s),
            (td, id) -> td.reqQryInstrument(req -> {
                structs.qryInstrument.ExchangeID.set(req, exchangeId);
                structs.qryInstrument.ProductID.set(req, productId);
            }, id));
        instrumentMap.values().removeIf(i -> exchangeId.equals(i.exchangeID()) && productId.equals(i.productID()));
        list.forEach(i -> instrumentMap.put(i.instrumentID(), i));
        return list;
    }

    @Override
    public Optional<Instrument> queryInstrument(String instrumentId) {
        Optional<Instrument> found = queries.query("合约查询 " + instrumentId, s -> CtpStructConvert.toInstrument(structs, s),
                (td, id) -> td.reqQryInstrument(req -> structs.qryInstrument.InstrumentID.set(req, instrumentId), id))
            .stream().filter(i -> instrumentId.equals(i.instrumentID())).findFirst();
        found.ifPresent(i -> instrumentMap.put(i.instrumentID(), i));
        return found;
    }

    @Override
    public Optional<Instrument> instrument(String instrumentId) {
        return Optional.ofNullable(instrumentMap.get(instrumentId));
    }

    @Override
    public Collection<Instrument> instruments() {
        return List.copyOf(instrumentMap.values());
    }

    @Override
    public Order submitOrder(Order order) {
        Objects.requireNonNull(order.instrumentID(), "instrumentID");
        Objects.requireNonNull(order.direction(), "direction");
        Objects.requireNonNull(order.offsetFlag(), "offsetFlag");
        if (order.volume() <= 0) {
            throw new IllegalArgumentException("报单数量须大于 0: " + order.volume());
        }
        CtpTraderApi current = readyApi();
        Order submitted;
        int rc;
        synchronized (orderInsertLock) {
            submitted = order.toBuilder()
                .exchangeID(exchangeId(order.instrumentID(), order.exchangeID()))
                .hedgeFlag(Objects.requireNonNullElse(order.hedgeFlag(), DataType.HedgeFlag.Speculation))
                .priceType(Objects.requireNonNullElse(order.priceType(), DataType.OrderPriceType.LimitPrice))
                .timeCondition(Objects.requireNonNullElse(order.timeCondition(), DataType.TimeCondition.GFD))
                .volumeCondition(Objects.requireNonNullElse(order.volumeCondition(), DataType.VolumeCondition.AV))
                .minVolume(Math.max(order.minVolume(), 1))
                .frontID(frontId)
                .sessionID(sessionId)
                .orderRef(String.valueOf(orderRefSeq.incrementAndGet()))
                .orderSysID("")
                .submitStatus(DataType.OrderSubmitStatus.InsertSubmitted)
                .status(DataType.OrderStatus.Unknown)
                .statusMsg("")
                .volumeTraded(0)
                .volumeTotal(order.volume())
                .tradingDay(tradingDay)
                .systemTimestamp(System.currentTimeMillis())
                .build();
            // 先登记再发送：回报可能在 ReqOrderInsert 返回前到达
            orders.put(submitted.orderId(), submitted);
            Order o = submitted;
            rc = current.reqOrderInsert(req -> fillInputOrder(req, o), requestId.incrementAndGet());
        }
        if (rc != 0) {
            log.warn("CTP 报单请求发送失败 {} {}", submitted.orderId(), requestError(rc));
            return rejected(submitted, DataType.OrderSubmitStatus.InsertRejected, "报单请求发送失败 " + requestError(rc));
        }
        log.info("CTP 报单 {} {} {} {} {}@{}", submitted.orderId(), submitted.instrumentID(), submitted.direction().label(),
            submitted.offsetFlag().label(), submitted.volume(), submitted.limitPrice());
        return submitted;
    }

    /**
     * 有报单引用时按 FrontID+SessionID+OrderRef 撤单（交易所接受前即可撤），否则按交易所报单编号撤单。
     * {@link Order#orderSysID()} 保留了上期所等交易所右对齐的前导空格，可原样回填。
     */
    @Override
    public Order cancelOrder(Order order) {
        CtpTraderApi current = readyApi();
        Order target = findOrder(order).orElse(order);
        if (target.done()) {
            log.info("CTP 报单 {} 已终结 {}，不再撤单", target.orderId(), target.status());
            return target;
        }
        boolean byRef = target.orderRef() != null && !target.orderRef().isBlank();
        if (!byRef && (target.orderSysID() == null || target.orderSysID().isBlank())) {
            throw new IllegalArgumentException("撤单须提供 OrderRef 或 OrderSysID: " + order);
        }
        String exchangeId = exchangeId(target.instrumentID(), target.exchangeID());
        int rc = current.reqOrderAction(req -> {
            structs.inputOrderAction.BrokerID.set(req, config.brokerId());
            structs.inputOrderAction.InvestorID.set(req, config.investorId());
            structs.inputOrderAction.UserID.set(req, config.userId());
            structs.inputOrderAction.InstrumentID.set(req, target.instrumentID());
            structs.inputOrderAction.ExchangeID.set(req, exchangeId);
            structs.inputOrderAction.ActionFlag.set(req, '0');
            if (byRef) {
                structs.inputOrderAction.FrontID.set(req, target.frontID());
                structs.inputOrderAction.SessionID.set(req, target.sessionID());
                structs.inputOrderAction.OrderRef.set(req, target.orderRef());
            } else {
                structs.inputOrderAction.OrderSysID.set(req, target.orderSysID());
            }
        }, requestId.incrementAndGet());
        if (rc != 0) {
            log.warn("CTP 撤单请求发送失败 {} {}", target.orderId(), requestError(rc));
            return rejected(target, DataType.OrderSubmitStatus.CancelRejected, "撤单请求发送失败 " + requestError(rc));
        }
        log.info("CTP 撤单 {} {}", target.orderId(), target.instrumentID());
        return target;
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
        List<Account> accounts = queries.query("资金查询", s -> CtpStructConvert.toAccount(structs, s),
            (td, id) -> td.reqQryTradingAccount(req -> {
                structs.qryTradingAccount.BrokerID.set(req, config.brokerId());
                structs.qryTradingAccount.InvestorID.set(req, config.investorId());
            }, id));
        if (accounts.isEmpty()) {
            throw new IllegalStateException("CTP 资金查询无结果");
        }
        return accounts.getFirst();
    }

    @Override
    public List<Position> queryPositions() {
        return queries.query("持仓查询", s -> CtpStructConvert.toPosition(structs, s),
            (td, id) -> td.reqQryInvestorPosition(req -> {
                structs.qryInvestorPosition.BrokerID.set(req, config.brokerId());
                structs.qryInvestorPosition.InvestorID.set(req, config.investorId());
            }, id));
    }

    @Override
    public List<Order> queryOrders() {
        return queries.query("报单查询", seg -> CtpStructConvert.toOrder(structs, seg, System.currentTimeMillis()),
            (td, id) -> td.reqQryOrder(req -> {
                structs.qryOrder.BrokerID.set(req, config.brokerId());
                structs.qryOrder.InvestorID.set(req, config.investorId());
            }, id));
    }

    @Override
    public List<Trade> queryTrades() {
        return queries.query("成交查询", seg -> CtpStructConvert.toTrade(structs, seg, System.currentTimeMillis()),
            (td, id) -> td.reqQryTrade(req -> {
                structs.qryTrade.BrokerID.set(req, config.brokerId());
                structs.qryTrade.InvestorID.set(req, config.investorId());
            }, id));
    }

    @Override
    public Optional<CommissionRate> queryCommissionRate(String instrumentId) {
        return queries.query("手续费率查询", s -> CtpStructConvert.toCommissionRate(structs, s),
            (td, id) -> td.reqQryInstrumentCommissionRate(req -> {
                structs.qryInstrumentCommissionRate.BrokerID.set(req, config.brokerId());
                structs.qryInstrumentCommissionRate.InvestorID.set(req, config.investorId());
                structs.qryInstrumentCommissionRate.InstrumentID.set(req, instrumentId);
            }, id)).stream().findFirst();
    }

    @Override
    public Optional<SettlementInfo> querySettlementInfo(LocalDate tradingDay) {
        String day = tradingDay == null ? "" : tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE);
        return CtpStructConvert.mergeSettlement(queries.query("结算单查询", s -> CtpStructConvert.toSettlementPart(structs, s),
            (td, id) -> td.reqQrySettlementInfo(req -> {
                structs.qrySettlementInfo.BrokerID.set(req, config.brokerId());
                structs.qrySettlementInfo.InvestorID.set(req, config.investorId());
                structs.qrySettlementInfo.TradingDay.set(req, day);
            }, id)));
    }

    @Override
    public Optional<MarginRate> queryMarginRate(String instrumentId) {
        return queries.query("保证金率查询", s -> CtpStructConvert.toMarginRate(structs, s),
            (td, id) -> td.reqQryInstrumentMarginRate(req -> {
                structs.qryInstrumentMarginRate.BrokerID.set(req, config.brokerId());
                structs.qryInstrumentMarginRate.InvestorID.set(req, config.investorId());
                structs.qryInstrumentMarginRate.InstrumentID.set(req, instrumentId);
                structs.qryInstrumentMarginRate.HedgeFlag.set(req, DataType.HedgeFlag.Speculation.code());
            }, id)).stream().findFirst();
    }

    @Override
    public void setConnectListener(Consumer<String> stageListener) {
        this.connectListener = stageListener;
    }

    @Override
    public void setOrderListener(Consumer<Order> orderConsumer) {
        this.orderConsumer = orderConsumer;
    }

    @Override
    public void setTradeListener(Consumer<Trade> tradeConsumer) {
        this.tradeConsumer = tradeConsumer;
    }

    private CtpTraderApi readyApi() {
        CtpTraderApi current = api;
        if (current == null || !ready) {
            throw new IllegalStateException("CTP 交易未就绪");
        }
        return current;
    }

    private String exchangeId(String instrumentId, String exchangeId) {
        if (exchangeId != null && !exchangeId.isBlank()) {
            return exchangeId;
        }
        Instrument instrument = instrumentId == null ? null : instrumentMap.get(instrumentId);
        return instrument == null ? "" : instrument.exchangeID();
    }

    private void fillInputOrder(MemorySegment req, Order o) {
        structs.inputOrder.BrokerID.set(req, config.brokerId());
        structs.inputOrder.InvestorID.set(req, config.investorId());
        structs.inputOrder.UserID.set(req, config.userId());
        structs.inputOrder.InstrumentID.set(req, o.instrumentID());
        structs.inputOrder.ExchangeID.set(req, o.exchangeID());
        structs.inputOrder.OrderRef.set(req, o.orderRef());
        structs.inputOrder.OrderPriceType.set(req, o.priceType().code());
        structs.inputOrder.Direction.set(req, o.direction().code());
        structs.inputOrder.CombOffsetFlag.set(req, String.valueOf(o.offsetFlag().code()));
        structs.inputOrder.CombHedgeFlag.set(req, String.valueOf(o.hedgeFlag().code()));
        structs.inputOrder.LimitPrice.set(req, o.limitPrice());
        structs.inputOrder.VolumeTotalOriginal.set(req, o.volume());
        structs.inputOrder.TimeCondition.set(req, o.timeCondition().code());
        structs.inputOrder.VolumeCondition.set(req, o.volumeCondition().code());
        structs.inputOrder.MinVolume.set(req, o.minVolume());
        // THOST_FTDC_CC_Immediately / THOST_FTDC_FCC_NotForceClose
        structs.inputOrder.ContingentCondition.set(req, '1');
        structs.inputOrder.ForceCloseReason.set(req, '0');
        structs.inputOrder.IsAutoSuspend.set(req, 0);
        structs.inputOrder.UserForceClose.set(req, 0);
    }

    private Optional<Order> findOrder(Order order) {
        if (order.orderRef() != null && !order.orderRef().isBlank()) {
            Order known = orders.get(order.orderId());
            if (known != null) {
                return Optional.of(known);
            }
        }
        return findOrder(order.exchangeID(), order.orderSysID());
    }

    /**
     * 按交易所报单编号查找。比较时忽略前导空格：外部传入或撤单应答中的编号可能已去掉空格
     */
    private Optional<Order> findOrder(String exchangeId, String orderSysId) {
        if (orderSysId == null || orderSysId.isBlank()) {
            return Optional.empty();
        }
        String id = orderSysId.strip();
        return orders.values().stream()
            .filter(o -> id.equals(o.orderSysID().strip()))
            .filter(o -> exchangeId == null || exchangeId.isBlank() || exchangeId.equals(o.exchangeID()))
            .findFirst();
    }

    /**
     * 柜台拒绝或请求未发出：本地更新状态并推送。报单被拒视同撤销，与交易所拒单时的回报一致。
     */
    private Order rejected(Order order, DataType.OrderSubmitStatus submitStatus, String msg) {
        Order.OrderBuilder builder = order.toBuilder()
            .submitStatus(submitStatus)
            .statusMsg(msg)
            .systemTimestamp(System.currentTimeMillis());
        if (submitStatus == DataType.OrderSubmitStatus.InsertRejected) {
            builder.status(DataType.OrderStatus.Canceled).volumeTotal(0);
        }
        Order updated = builder.build();
        publish(updated);
        return updated;
    }

    private void publish(Order order) {
        orders.put(order.orderId(), order);
        try {
            orderConsumer.accept(order);
        } catch (RuntimeException e) {
            log.error("报单监听器处理异常 {}", order.orderId(), e);
        }
    }

    private class Spi implements TdSpi {

        private void check(int rc, String step) {
            if (rc != 0) {
                login.completeExceptionally(new IllegalStateException("CTP " + step + "请求发送失败 " + requestError(rc)));
            }
        }

        private boolean fail(String step, MemorySegment rspInfo) {
            String error = error(structs, rspInfo);
            if (error == null) {
                return false;
            }
            log.error("CTP {}失败 {}", step, error);
            login.completeExceptionally(new IllegalStateException("CTP " + step + "失败 " + error));
            return true;
        }

        private void reqUserLogin() {
            stage("登录");
            int rc = api.reqUserLogin(req -> {
                structs.reqUserLogin.BrokerID.set(req, config.brokerId());
                structs.reqUserLogin.UserID.set(req, config.userId());
                structs.reqUserLogin.Password.set(req, config.password());
                structs.reqUserLogin.UserProductInfo.set(req, config.userProductInfo());
            }, requestId.incrementAndGet());
            loginPending = rc == 0 && !login.isDone();
            check(rc, "登录");
        }

        private void reqQryInvestor() {
            String what = "查询投资者";
            stage(what);
            queries.send(what, s -> CtpStructConvert.toInvestor(structs, s),
                    api, (td, id) -> td.reqQryInvestor(req -> {
                        structs.qryInvestor.BrokerID.set(req, config.brokerId());
                        structs.qryInvestor.InvestorID.set(req, config.investorId());
                    }, id))
                .whenComplete((list, e) -> {
                    if (e instanceof CtpQueryChannel.FrontDisconnected) {
                        // 自动重连后会重走认证、登录流程，连接总时长仍受 timeout 限制
                        log.warn("CTP {}途中{}，等待重连", what, e.getMessage());
                        return;
                    }
                    if (e != null) {
                        log.error("CTP {}异常 {}", what, e.getMessage());
                        login.completeExceptionally(e);
                        return;
                    }
                    if (list.isEmpty()) {
                        login.completeExceptionally(new IllegalStateException("CTP 投资者查询无结果 " + config.investorId()));
                        return;
                    }
                    investor = list.getFirst();
                    log.info("CTP 投资者 {} {}", investor.investorID(), investor.investorName());
                    reqSettlementInfoConfirm();
                });
        }

        private void reqSettlementInfoConfirm() {
            stage("确认结算单");
            check(api.reqSettlementInfoConfirm(req -> {
                structs.settlementInfoConfirm.BrokerID.set(req, config.brokerId());
                structs.settlementInfoConfirm.InvestorID.set(req, config.investorId());
            }, CtpTdBrokerImpl.this.requestId.incrementAndGet()), "结算单确认");
        }

        private void onReady() {
            ready = true;
            stage("交易就绪");
            login.complete(null);
        }

        /**
         * 把查询应答交给查询通道
         */
        private void collect(int requestId, MemorySegment data, MemorySegment rspInfo, boolean isLast) {
            queries.onResponse(requestId, data, error(structs, rspInfo), isLast);
        }

        @Override
        public void onFrontConnected() {
            log.info("CTP 交易前置已连接");
            if (config.appId() == null || config.appId().isBlank()) {
                reqUserLogin();
                return;
            }
            stage("认证");
            check(api.reqAuthenticate(req -> {
                structs.reqAuthenticate.BrokerID.set(req, config.brokerId());
                structs.reqAuthenticate.UserID.set(req, config.userId());
                structs.reqAuthenticate.UserProductInfo.set(req, config.userProductInfo());
                structs.reqAuthenticate.AuthCode.set(req, config.authCode());
                structs.reqAuthenticate.AppID.set(req, config.appId());
            }, requestId.incrementAndGet()), "认证");
        }

        @Override
        public void onFrontDisconnected(int reason) {
            ready = false;
            queries.onDisconnected("前置断开 " + disconnectReason(reason));
            if (loginPending && !login.isDone()) {
                loginPending = false;
                int times = loginDisconnects.incrementAndGet();
                log.warn("CTP 交易登录请求发出后前置断开 {}（第 {} 次）", disconnectReason(reason), times);
                if (times >= MAX_LOGIN_DISCONNECTS) {
                    login.completeExceptionally(loginDisconnected("交易登录", times, reason, version));
                }
                return;
            }
            log.warn("CTP 交易前置断开 {}，等待自动重连", disconnectReason(reason));
        }

        @Override
        public void onHeartBeatWarning(int timeLapse) {
            log.warn("CTP 交易心跳超时警告 距上次接收报文 {}s", timeLapse);
        }

        @Override
        public void onRspAuthenticate(MemorySegment rspInfo, int requestId, boolean isLast) {
            if (fail("认证", rspInfo)) {
                return;
            }
            log.info("CTP 认证成功 {}", config.appId());
            reqUserLogin();
        }

        @Override
        public void onRspUserLogin(MemorySegment rsp, MemorySegment rspInfo, int requestId, boolean isLast) {
            loginPending = false;
            loginDisconnects.set(0);
            if (fail("登录", rspInfo)) {
                return;
            }
            frontId = structs.rspUserLogin.FrontID.getInt(rsp);
            sessionId = structs.rspUserLogin.SessionID.getInt(rsp);
            String tradingDayStr = structs.rspUserLogin.TradingDay.getString(rsp);
            tradingDay = StringUtil.isNullOrEmpty(tradingDayStr) ? LocalDate.now() : LocalDate.parse(tradingDayStr, DateTimeFormatter.BASIC_ISO_DATE);
            String maxOrderRef = structs.rspUserLogin.MaxOrderRef.getString(rsp);
            if (!maxOrderRef.isEmpty()) {
                int max = Integer.parseInt(maxOrderRef);
                orderRefSeq.accumulateAndGet(max, Math::max);
            }
            log.info("CTP 交易登录成功 交易日 {} FrontID {} SessionID {} MaxOrderRef {}", tradingDay, frontId, sessionId, maxOrderRef);
            reqQryInvestor();
        }

        @Override
        public void onRspUserLogout(MemorySegment userLogout, MemorySegment rspInfo, int requestId, boolean isLast) {
            ready = false;
            log.info("CTP 交易已登出 {}", Objects.requireNonNullElse(error(structs, rspInfo), ""));
        }

        @Override
        public void onRspError(MemorySegment rspInfo, int requestId, boolean isLast) {
            String error = error(structs, rspInfo);
            log.error("CTP 交易错误应答 requestId={} {}", requestId, error);
            collect(requestId, null, rspInfo, true);
            if (!ready) {
                login.completeExceptionally(new IllegalStateException("CTP 交易登录过程出错 " + error));
            }
        }

        @Override
        public void onRspSettlementInfoConfirm(MemorySegment confirm, MemorySegment rspInfo, int requestId, boolean isLast) {
            String error = error(structs, rspInfo);
            if (error != null) {
                log.warn("CTP 结算单确认失败 {}", error);
            } else {
                log.info("CTP 结算单已确认");
            }
            onReady();
        }

        @Override
        public void onRspOrderInsert(MemorySegment inputOrder, MemorySegment rspInfo, int requestId, boolean isLast) {
            insertRejected(inputOrder, rspInfo, "柜台");
        }

        @Override
        public void onErrRtnOrderInsert(MemorySegment inputOrder, MemorySegment rspInfo) {
            insertRejected(inputOrder, rspInfo, "交易所");
        }

        /**
         * 柜台拒单时 OnRspOrderInsert 与 OnErrRtnOrderInsert 都会到达，只推送一次。
         */
        private void insertRejected(MemorySegment inputOrder, MemorySegment rspInfo, String by) {
            String error = error(structs, rspInfo);
            if (inputOrder == null || error == null) {
                return;
            }
            String orderRef = structs.inputOrder.OrderRef.getString(inputOrder);
            String orderId = Order.orderId(frontId, sessionId, orderRef);
            log.warn("CTP {}拒绝报单 {} {} {}", by, orderId, structs.inputOrder.InstrumentID.getString(inputOrder), error);
            Order order = orders.get(orderId);
            if (order != null && order.submitStatus() != DataType.OrderSubmitStatus.InsertRejected) {
                rejected(order, DataType.OrderSubmitStatus.InsertRejected, error);
            }
        }

        @Override
        public void onRspOrderAction(MemorySegment action, MemorySegment rspInfo, int requestId, boolean isLast) {
            if (action == null) {
                return;
            }
            cancelRejected(structs.inputOrderAction.FrontID.getInt(action),
                structs.inputOrderAction.SessionID.getInt(action),
                structs.inputOrderAction.OrderRef.getString(action),
                structs.inputOrderAction.ExchangeID.getString(action),
                structs.inputOrderAction.OrderSysID.getString(action), rspInfo, "柜台");
        }

        @Override
        public void onErrRtnOrderAction(MemorySegment action, MemorySegment rspInfo) {
            if (action == null) {
                return;
            }
            cancelRejected(structs.orderAction.FrontID.getInt(action),
                structs.orderAction.SessionID.getInt(action),
                structs.orderAction.OrderRef.getString(action),
                structs.orderAction.ExchangeID.getString(action),
                structs.orderAction.OrderSysID.getString(action), rspInfo, "交易所");
        }

        private void cancelRejected(int front, int session, String orderRef, String exchangeId, String orderSysId,
                                    MemorySegment rspInfo, String by) {
            String error = error(structs, rspInfo);
            if (error == null) {
                return;
            }
            Optional<Order> order = orderRef.isEmpty()
                ? findOrder(exchangeId, orderSysId)
                : Optional.ofNullable(orders.get(Order.orderId(front, session, orderRef)));
            log.warn("CTP {}拒绝撤单 {} {}", by, order.map(Order::orderId).orElse(exchangeId + " " + orderSysId), error);
            order.filter(o -> !o.done())
                .ifPresent(o -> rejected(o, DataType.OrderSubmitStatus.CancelRejected, error));
        }

        @Override
        public void onRtnOrder(MemorySegment data) {
            Order order = CtpStructConvert.toOrder(structs, data, System.currentTimeMillis());
            log.info("CTP 报单回报 {} {} {} {} 成交 {}/{} {}", order.orderId(), order.instrumentID(), order.submitStatus(),
                order.status(), order.volumeTraded(), order.volume(), order.statusMsg());
            publish(order);
        }

        @Override
        public void onRtnTrade(MemorySegment data) {
            Trade trade = CtpStructConvert.toTrade(structs, data, System.currentTimeMillis());
            if (trades.putIfAbsent(trade.id(), trade) != null) {
                return;
            }
            log.info("CTP 成交回报 {} {} {} {} {}@{}", trade.tradeID(), trade.instrumentID(), trade.direction(),
                trade.offsetFlag(), trade.volume(), trade.price());
            try {
                tradeConsumer.accept(trade);
            } catch (RuntimeException e) {
                log.error("成交监听器处理异常 {}", trade.id(), e);
            }
        }

        @Override
        public void onRspQryOrder(MemorySegment order, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, order, rspInfo, isLast);
        }

        @Override
        public void onRspQryTrade(MemorySegment trade, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, trade, rspInfo, isLast);
        }

        @Override
        public void onRspQryInvestorPosition(MemorySegment position, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, position, rspInfo, isLast);
        }

        @Override
        public void onRspQryTradingAccount(MemorySegment account, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, account, rspInfo, isLast);
        }

        @Override
        public void onRspQryInvestor(MemorySegment investor, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, investor, rspInfo, isLast);
        }

        @Override
        public void onRspQrySettlementInfo(MemorySegment settlementInfo, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, settlementInfo, rspInfo, isLast);
        }

        @Override
        public void onRspQryInstrumentMarginRate(MemorySegment rate, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, rate, rspInfo, isLast);
        }

        @Override
        public void onRspQryInstrumentCommissionRate(MemorySegment rate, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, rate, rspInfo, isLast);
        }

        @Override
        public void onRspQryProduct(MemorySegment product, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, product, rspInfo, isLast);
        }

        @Override
        public void onRspQryInstrument(MemorySegment instrument, MemorySegment rspInfo, int requestId, boolean isLast) {
            collect(requestId, instrument, rspInfo, isLast);
        }

        @Override
        public void onRtnInstrumentStatus(MemorySegment status) {
            if (log.isDebugEnabled()) {
                log.debug("CTP 合约状态 {} {} {}", structs.instrumentStatus.ExchangeID.getString(status),
                    structs.instrumentStatus.InstrumentID.getString(status),
                    DataType.of(DataType.InstrumentStatus.class, structs.instrumentStatus.InstrumentStatus.getChar(status)));
            }
        }

        @Override
        public void onRtnTradingNotice(MemorySegment notice) {
            log.info("CTP 交易通知 {}", structs.tradingNoticeInfo.FieldContent.getString(notice));
        }
    }
}
