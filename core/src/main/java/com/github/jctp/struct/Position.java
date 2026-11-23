package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;

/**
 * 投资者持仓。CTP 按合约、多空、投保、持仓日期分别汇总：上期所、能源中心的今仓与昨仓各一条，其余交易所只有一条。
 */
@Builder(toBuilder = true)
public record Position(
    String instrumentID,                                     // 合约代码
    String exchangeID,                                       // 交易所代码
    DataType.PosiDirection direction,                        // 持仓多空方向
    DataType.HedgeFlag hedgeFlag,                            // 投机套保标志
    DataType.PositionDate positionDate,                      // 持仓日期
    LocalDate tradingDay,                                    // 交易日

    int position,                                            // 今日持仓
    int ydPosition,                                          // 上日持仓（结算时的昨仓，盘中不变）
    int todayPosition,                                       // 今日新开仓
    int longFrozen,                                          // 多头冻结
    int shortFrozen,                                         // 空头冻结
    int openVolume,                                          // 开仓量
    int closeVolume,                                         // 平仓量
    int tasPosition,                                         // TAS 持仓手数

    double positionCost,                                     // 持仓成本
    double openCost,                                         // 开仓成本
    double useMargin,                                        // 占用的保证金
    double frozenMargin,                                     // 冻结的保证金
    double commission,                                       // 手续费
    double closeProfit,                                      // 平仓盈亏
    double positionProfit,                                   // 持仓盈亏
    double preSettlementPrice,                               // 上次结算价
    double settlementPrice,                                  // 本次结算价
    double tasPositionCost,                                  // TAS 持仓成本

    double longFrozenAmount,                                 // 多头开仓冻结金额
    double shortFrozenAmount,                                // 空头开仓冻结金额
    double openAmount,                                       // 开仓金额
    double closeAmount,                                      // 平仓金额
    double preMargin,                                        // 上次占用的保证金
    double exchangeMargin,                                   // 交易所保证金
    double frozenCash,                                       // 冻结的资金
    double frozenCommission,                                 // 冻结的手续费
    double cashIn,                                           // 资金差额
    double closeProfitByDate,                                // 逐日盯市平仓盈亏
    double closeProfitByTrade,                               // 逐笔对冲平仓盈亏
    double marginRateByMoney,                                // 保证金率（按金额）
    double marginRateByVolume,                               // 保证金率（按手数）
    int combPosition,                                        // 组合成交形成的持仓
    int combLongFrozen,                                      // 组合多头冻结
    int combShortFrozen,                                     // 组合空头冻结
    int strikeFrozen,                                        // 执行冻结
    double strikeFrozenAmount,                               // 执行冻结金额
    int abandonFrozen,                                       // 放弃执行冻结
    int ydStrikeFrozen,                                      // 执行冻结的昨仓
    double positionCostOffset,                               // 持仓成本差值（仅大商所）
    int settlementID,                                        // 结算编号
    String investUnitID                                      // 投资单元代码
) {
}
