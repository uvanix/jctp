package com.github.jctp.broker;

import java.time.LocalDate;

public interface CtpBroker {

    void connect();

    void disconnect();

    /**
     * 登录时柜台返回的交易日。
     */
    LocalDate tradingDay();
}
