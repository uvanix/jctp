package com.github.jctp.server.gateway;

import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Trade;

/**
 * 网关事件。在 CTP 回调线程上调用，实现不得阻塞。
 */
public interface GatewayListener {

    default void onTick(MarketData tick) {
    }

    default void onOrder(Order order) {
    }

    default void onTrade(Trade trade) {
    }

    /**
     * 网关已关闭（账号登出）
     */
    default void onClosed() {
    }
}
