package com.github.jctp.server.market;

import java.time.ZoneId;
import java.time.ZoneOffset;

public enum BarPeriod {
    M1(1), M5(5), M15(15), M30(30), H1(60), D1(0);

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long OFFSET = ZoneOffset.ofHours(8).getTotalSeconds() * 1000L;

    /**
     * 分钟数，日线为 0
     */
    public final int minutes;

    BarPeriod(int minutes) {
        this.minutes = minutes;
    }

    /**
     * 按北京时间对齐的周期起始时刻。日线按交易日聚合，不走这里。
     */
    long bucket(long epochMillis) {
        long size = minutes * 60_000L;
        long local = epochMillis + OFFSET;
        return local - Math.floorMod(local, size) - OFFSET;
    }

    public static BarPeriod parse(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不支持的K线周期 " + value);
        }
    }
}
