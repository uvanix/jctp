package com.github.jctp.server.market;

import com.github.jctp.struct.MarketData;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个合约的 1 分钟 K 线，由实时 tick 合成，仅保存在内存。其他周期按需从 1 分钟线聚合。
 */
public final class BarSeries {

    /**
     * 每个合约最多保留的 1 分钟 K 线数（约两周的日夜盘）
     */
    static final int MAX_BARS = 10_000;

    private final List<Bar> bars = new ArrayList<>();

    /**
     * @return 本 tick 更新后的 1 分钟 K 线；tick 无效或时间倒退时返回 null
     */
    public synchronized Bar onTick(MarketData tick) {
        double price = tick.lastPrice();
        if (Double.isNaN(price) || price <= 0) {
            return null;
        }
        long time = BarPeriod.M1.bucket(tick.actionTimestamp());
        Bar last = bars.isEmpty() ? null : bars.getLast();
        Bar bar = new Bar(time, tick.tradingDay(), price, price, price, price, Math.max(tick.volumeDelta(), 0),
            Math.max(tick.turnoverDelta(), 0), tick.openInterest());
        if (last == null || time > last.time()) {
            bars.add(bar);
            if (bars.size() > MAX_BARS) {
                bars.removeFirst();
            }
            return bar;
        }
        if (time < last.time()) {
            return null;
        }
        Bar merged = last.merge(bar);
        bars.set(bars.size() - 1, merged);
        return merged;
    }

    public List<Bar> bars(BarPeriod period) {
        List<Bar> minutes;
        synchronized (this) {
            minutes = List.copyOf(bars);
        }
        return aggregate(minutes, period);
    }

    static List<Bar> aggregate(List<Bar> minutes, BarPeriod period) {
        if (period == BarPeriod.M1) {
            return minutes;
        }
        List<Bar> result = new ArrayList<>();
        Bar current = null;
        for (Bar m : minutes) {
            long time = period == BarPeriod.D1 ? dayTime(m) : period.bucket(m.time());
            if (current != null && current.time() == time) {
                current = current.merge(m);
            } else {
                if (current != null) {
                    result.add(current);
                }
                current = m.withTime(time);
            }
        }
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /**
     * 日线按交易日聚合；行情缺交易日时退回自然日。
     */
    private static long dayTime(Bar bar) {
        LocalDate day = bar.tradingDay() != null
            ? bar.tradingDay()
            : LocalDate.ofInstant(Instant.ofEpochMilli(bar.time()), BarPeriod.ZONE);
        return day.atStartOfDay(BarPeriod.ZONE).toInstant().toEpochMilli();
    }
}
