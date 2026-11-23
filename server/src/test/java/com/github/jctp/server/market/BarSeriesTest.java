package com.github.jctp.server.market;

import com.github.jctp.server.FakeBrokers;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BarSeriesTest {

    private static long at(int day, int hour, int minute, int second) {
        return LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(BarPeriod.ZONE).toInstant().toEpochMilli();
    }

    @Test
    void ticksMergeIntoMinuteBars() {
        BarSeries series = new BarSeries();
        series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 0, 1), 3100, 0));
        series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 0, 30), 3105, 5));
        series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 0, 59), 3098, 3));
        series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 1, 0), 3101, 2));
        // 时间倒退、无效价格均忽略
        assertNull(series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 0, 40), 9999, 1)));
        assertNull(series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 1, 5), Double.NaN, 1)));

        List<Bar> bars = series.bars(BarPeriod.M1);
        assertEquals(2, bars.size());
        Bar first = bars.getFirst();
        assertEquals(at(16, 9, 0, 0), first.time());
        assertEquals(3100, first.open());
        assertEquals(3105, first.high());
        assertEquals(3098, first.low());
        assertEquals(3098, first.close());
        assertEquals(8, first.volume());
        assertEquals(at(16, 9, 1, 0), bars.get(1).time());
    }

    @Test
    void aggregatePeriods() {
        BarSeries series = new BarSeries();
        // 夜盘 21:00-21:09 与日盘 09:00，同属交易日 11-16
        for (int m = 0; m < 10; m++) {
            series.onTick(FakeBrokers.tick("rb2601", at(13, 21, m, 0), 3100 + m, 1));
        }
        series.onTick(FakeBrokers.tick("rb2601", at(16, 9, 0, 0), 3050, 4));

        List<Bar> m5 = series.bars(BarPeriod.M5);
        assertEquals(3, m5.size());
        assertEquals(at(13, 21, 0, 0), m5.getFirst().time());
        assertEquals(3100, m5.getFirst().open());
        assertEquals(3104, m5.getFirst().close());
        assertEquals(5, m5.getFirst().volume());
        assertEquals(at(13, 21, 5, 0), m5.get(1).time());

        List<Bar> h1 = series.bars(BarPeriod.H1);
        assertEquals(2, h1.size());
        assertEquals(at(13, 21, 0, 0), h1.getFirst().time());

        List<Bar> d1 = series.bars(BarPeriod.D1);
        assertEquals(1, d1.size());
        Bar day = d1.getFirst();
        assertEquals(FakeBrokers.TRADING_DAY.atStartOfDay(BarPeriod.ZONE).toInstant().toEpochMilli(), day.time());
        assertEquals(3100, day.open());
        assertEquals(3109, day.high());
        assertEquals(3050, day.low());
        assertEquals(3050, day.close());
        assertEquals(14, day.volume());
    }

    @Test
    void parsePeriod() {
        assertEquals(BarPeriod.M15, BarPeriod.parse("m15"));
        assertThrows(IllegalArgumentException.class, () -> BarPeriod.parse("M7"));
    }
}
