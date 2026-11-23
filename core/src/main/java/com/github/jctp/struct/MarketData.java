package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;

@Builder(toBuilder = true)
public record MarketData(
    String instrumentID,           // 合约代码
    String exchangeId,             // 交易所代码
    String exchangeInstID,         // 合约在交易所的代码

    long systemTimestamp,          // 服务器接收时间
    long actionTimestamp,          // 行情归属时间戳
    LocalTime actionTime,          // 行情归属时间
    LocalDate actionDay,           // 行情归属日
    LocalDate tradingDay,          // 交易日

    double lastPrice,              // 最新价
    double preOpenInterest,        // 昨持仓
    double preClosePrice,          // 昨收盘价
    double preSettlementPrice,     // 昨结算价

    double openPrice,              // 今开盘
    double highestPrice,           // 最高价
    double lowestPrice,            // 最低价
    double closePrice,             // 今收盘
    double upperLimitPrice,        // 涨停价
    double lowerLimitPrice,        // 跌停价
    double settlementPrice,        // 结算价
    double preDelta,               // 昨虚实度
    double currDelta,              // 今虚实度
    double averagePrice,           // 当日均价

    int volume,                    // 当天成交总量
    int volumeDelta,               // 成交量变化/现手
    double turnover,               // 当天成交总额
    double turnoverDelta,          // 当天成交总额变化
    double openInterest,           // 总持仓量
    double openInterestDelta,      // 持仓量变化/增仓

    double bidPrice1,              // 申买价一
    int bidVolume1,                // 申买量一
    double askPrice1,              // 申卖价一
    int askVolume1,                // 申卖量一

    double bidPrice2,              // 申买价二
    int bidVolume2,                // 申买量二
    double askPrice2,              // 申卖价二
    int askVolume2,                // 申卖量二

    double bidPrice3,              // 申买价三
    int bidVolume3,                // 申买量三
    double askPrice3,              // 申卖价三
    int askVolume3,                // 申卖量三

    double bidPrice4,              // 申买价四
    int bidVolume4,                // 申买量四
    double askPrice4,              // 申卖价四
    int askVolume4,                // 申卖量四

    double bidPrice5,              // 申买价五
    int bidVolume5,                // 申买量五
    double askPrice5,              // 申卖价五
    int askVolume5                 // 申卖量五
) {
}
