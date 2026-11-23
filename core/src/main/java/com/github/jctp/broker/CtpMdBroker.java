package com.github.jctp.broker;

import com.github.jctp.struct.MarketData;

import java.util.Optional;
import java.util.function.Consumer;

public interface CtpMdBroker extends CtpBroker {

    boolean subscribe(String instrumentId);

    boolean unsubscribe(String instrumentId);

    Optional<MarketData> lastMarketData(String instrumentId);

    /**
     * 行情推送回调。可能在柜台的 I/O 线程上调用，接收方需自行切换线程。
     */
    void setMarketDataListener(Consumer<MarketData> marketDataConsumer);
}
