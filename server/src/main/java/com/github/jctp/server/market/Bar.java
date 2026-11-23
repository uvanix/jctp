package com.github.jctp.server.market;

import java.time.LocalDate;

/**
 * K 线。time 为周期起始时刻（epoch 毫秒）；日线取交易日零点。
 */
public record Bar(
    long time,
    LocalDate tradingDay,
    double open,
    double high,
    double low,
    double close,
    long volume,
    double turnover,
    double openInterest
) {

    Bar merge(Bar next) {
        return new Bar(time, tradingDay, open, Math.max(high, next.high), Math.min(low, next.low), next.close,
            volume + next.volume, turnover + next.turnover, next.openInterest);
    }

    Bar withTime(long newTime) {
        return new Bar(newTime, tradingDay, open, high, low, close, volume, turnover, openInterest);
    }
}
