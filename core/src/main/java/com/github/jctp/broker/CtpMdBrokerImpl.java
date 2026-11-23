package com.github.jctp.broker;

import ch.qos.logback.core.util.StringUtil;
import com.github.jctp.CtpConfig;
import com.github.jctp.ffm.*;
import com.github.jctp.struct.MarketData;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.github.jctp.broker.BrokerSupport.*;

public class CtpMdBrokerImpl implements CtpMdBroker {

    private final Logger log;
    private final CtpConfig config;
    private final CtpStructs structs;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requestId = new AtomicInteger();
    private final Map<String, MarketData> lastMarketData = new ConcurrentHashMap<>();

    private volatile boolean loggedIn;
    private volatile LocalDate tradingDay;
    private volatile CompletableFuture<Void> login;
    /**
     * 连接阶段：登录请求已发出、尚未收到应答
     */
    private volatile boolean loginPending;
    private final AtomicInteger loginDisconnects = new AtomicInteger();
    private volatile String version;
    private volatile Consumer<MarketData> marketDataConsumer = _ -> {
    };
    private volatile CtpMdApi api;

    public CtpMdBrokerImpl(ILoggerFactory loggerFactory, CtpConfig config) {
        this.log = loggerFactory.getLogger(getClass().getName());
        this.config = config;
        this.structs = config.version().structs();
    }

    public CtpMdBrokerImpl(CtpConfig config) {
        this(LoggerFactory.getILoggerFactory(), config);
    }

    /**
     * 连接并登录，阻塞至登录成功；超时或登录失败时释放连接并抛出异常。
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
        version = library.mdApiVersion();
        api = new CtpMdApi(library, flowDir(config, "md", version));
        api.registerSpi(new Spi());
        api.registerFront(config.mdFront());
        api.init();
        log.info("CTP {} 行情连接 {}", version, config.mdFront());
        await(login, config.timeout(), "行情登录", this::disconnect);
    }

    @Override
    public synchronized void disconnect() {
        if (api == null) {
            return;
        }
        loggedIn = false;
        api.close();
        api = null;
        log.info("CTP 行情已断开 {}", config.mdFront());
    }

    @Override
    public LocalDate tradingDay() {
        return tradingDay;
    }

    /**
     * 登记订阅；已登录则立即发送，否则在登录后统一发送。
     *
     * @return 订阅请求已发送或已登记
     */
    @Override
    public boolean subscribe(String instrumentId) {
        subscribed.add(instrumentId);
        CtpMdApi current = api;
        if (!loggedIn || current == null) {
            return true;
        }
        int rc = current.subscribeMarketData(instrumentId);
        if (rc != 0) {
            log.warn("{} 订阅请求发送失败 rc={}", instrumentId, rc);
        }
        return rc == 0;
    }

    @Override
    public boolean unsubscribe(String instrumentId) {
        boolean removed = subscribed.remove(instrumentId);
        CtpMdApi current = api;
        if (loggedIn && current != null) {
            current.unSubscribeMarketData(instrumentId);
        }
        return removed;
    }

    @Override
    public Optional<MarketData> lastMarketData(String instrumentId) {
        return Optional.ofNullable(lastMarketData.get(instrumentId));
    }

    @Override
    public void setMarketDataListener(Consumer<MarketData> marketDataConsumer) {
        this.marketDataConsumer = marketDataConsumer;
    }

    private class Spi implements MdSpi {

        @Override
        public void onFrontConnected() {
            log.info("CTP 行情前置已连接，登录 {}", config.userId());
            int rc = api.reqUserLogin(req -> {
                structs.reqUserLogin.BrokerID.set(req, config.brokerId());
                structs.reqUserLogin.UserID.set(req, config.userId());
                structs.reqUserLogin.Password.set(req, config.password());
            }, requestId.incrementAndGet());
            loginPending = rc == 0 && !login.isDone();
            if (rc != 0) {
                login.completeExceptionally(new IllegalStateException("行情登录请求发送失败 rc=" + rc));
            }
        }

        @Override
        public void onFrontDisconnected(int reason) {
            loggedIn = false;
            if (loginPending && !login.isDone()) {
                loginPending = false;
                int times = loginDisconnects.incrementAndGet();
                log.warn("CTP 行情登录请求发出后前置断开 {}（第 {} 次）", disconnectReason(reason), times);
                if (times >= MAX_LOGIN_DISCONNECTS) {
                    login.completeExceptionally(loginDisconnected("行情登录", times, reason, version));
                }
                return;
            }
            log.warn("CTP 行情前置断开 {}，等待自动重连", disconnectReason(reason));
        }

        @Override
        public void onRspUserLogin(MemorySegment rsp, MemorySegment rspInfo, int requestId, boolean isLast) {
            loginPending = false;
            loginDisconnects.set(0);
            String error = error(structs, rspInfo);
            if (error != null) {
                log.error("CTP 行情登录失败 {}", error);
                login.completeExceptionally(new IllegalStateException("行情登录失败 " + error));
                return;
            }
            String tradingDayStr = structs.rspUserLogin.TradingDay.getString(rsp);
            log.info("CTP 行情登录成功 交易日 {}", tradingDayStr);
            tradingDay = StringUtil.isNullOrEmpty(tradingDayStr) ? LocalDate.now() : LocalDate.parse(tradingDayStr, DateTimeFormatter.BASIC_ISO_DATE);
            loggedIn = true;
            if (!subscribed.isEmpty()) {
                int rc = api.subscribeMarketData(subscribed.toArray(String[]::new));
                if (rc != 0) {
                    log.warn("恢复订阅 {} 发送失败 rc={}", subscribed, rc);
                }
            }
            login.complete(null);
        }

        @Override
        public void onRspError(MemorySegment rspInfo, int requestId, boolean isLast) {
            String error = error(structs, rspInfo);
            log.error("CTP 行情错误应答 {}", error);
            if (!loggedIn) {
                login.completeExceptionally(new IllegalStateException("CTP 行情登录过程出错 " + error));
            }
        }

        @Override
        public void onRspSubMarketData(MemorySegment instrument, MemorySegment rspInfo, int requestId, boolean isLast) {
            String instrumentId = instrument == null ? "" : structs.specificInstrument.InstrumentID.getString(instrument);
            String error = error(structs, rspInfo);
            if (error != null) {
                log.error("{} 订阅失败 {}", instrumentId, error);
            } else {
                log.info("{} 订阅成功", instrumentId);
            }
        }

        @Override
        public void onRtnDepthMarketData(MemorySegment data) {
            String instrumentID = structs.depthMarketData.InstrumentID.getString(data);
            MarketData marketData = CtpStructConvert.toMarketData(structs, instrumentID, data, lastMarketData.get(instrumentID), System.currentTimeMillis());
            lastMarketData.put(instrumentID, marketData);
            if (log.isDebugEnabled()) {
                log.debug("深度行情 {}", marketData);
            }
            marketDataConsumer.accept(marketData);
        }
    }
}
