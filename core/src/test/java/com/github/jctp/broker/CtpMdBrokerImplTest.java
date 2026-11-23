package com.github.jctp.broker;

import com.github.jctp.CtpConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CtpMdBrokerImplTest {

    /**
     * 未连接时订阅只登记，登录后统一发送。
     */
    @Test
    void subscribeBeforeConnect() {
        CtpMdBroker broker = new CtpMdBrokerImpl(CtpConfig.simnow(CtpConfig.SimNow.GROUP1, "user", "password"));
        assertTrue(broker.subscribe("rb2601"));
        assertTrue(broker.unsubscribe("rb2601"));
        assertFalse(broker.unsubscribe("rb2601"));
        assertTrue(broker.lastMarketData("rb2601").isEmpty());
        assertDoesNotThrow(broker::disconnect);
    }
}
