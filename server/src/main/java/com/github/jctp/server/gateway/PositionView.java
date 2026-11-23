package com.github.jctp.server.gateway;

import com.github.jctp.struct.DataType;
import lombok.Builder;

/**
 * 按合约、多空、投保汇总的持仓（上期所、能源中心的今昨仓两条记录合并为一条）。
 * 数量、金额为各记录之和；结算价、保证金率取首个有效值。
 *
 * @param ydPosition     剩余昨仓
 * @param todayPosition  今仓
 * @param openPrice      开仓均价 = 开仓成本 / (持仓 × 合约乘数)
 * @param positionPrice  持仓均价（盯市，昨仓按昨结算价）
 * @param positionProfit 持仓盈亏（盯市，柜台计算）
 */
@Builder
public record PositionView(
    String instrumentID,
    String exchangeID,
    DataType.PosiDirection direction,
    DataType.HedgeFlag hedgeFlag,
    int position,
    int ydPosition,
    int todayPosition,
    int longFrozen,                                          // 多头冻结
    int shortFrozen,                                         // 空头冻结
    int openVolume,                                          // 今日开仓量
    int closeVolume,                                         // 今日平仓量
    int combPosition,                                        // 组合成交形成的持仓
    int strikeFrozen,                                        // 执行冻结
    int abandonFrozen,                                       // 放弃执行冻结
    int tasPosition,                                         // TAS 持仓
    double openPrice,
    double positionPrice,
    double openCost,                                         // 开仓成本
    double positionCost,                                     // 持仓成本
    double openAmount,                                       // 今日开仓金额
    double closeAmount,                                      // 今日平仓金额
    double useMargin,                                        // 占用的保证金
    double preMargin,                                        // 上次占用的保证金
    double exchangeMargin,                                   // 交易所保证金
    double frozenMargin,                                     // 冻结的保证金
    double frozenCash,                                       // 冻结的资金
    double frozenCommission,                                 // 冻结的手续费
    double positionProfit,
    double closeProfit,                                      // 平仓盈亏
    double closeProfitByDate,                                // 逐日盯市平仓盈亏
    double closeProfitByTrade,                               // 逐笔对冲平仓盈亏
    double commission,                                       // 手续费
    double preSettlementPrice,                               // 上次结算价
    double settlementPrice,                                  // 本次结算价
    double marginRateByMoney,                                // 保证金率（按金额）
    double marginRateByVolume,                               // 保证金率（按手数）
    int volumeMultiple
) {
}
