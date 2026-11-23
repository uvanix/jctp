package com.github.jctp.struct;

import lombok.Builder;

/**
 * 合约手续费率。手续费 = 成交金额 × ByMoney + 手数 × ByVolume。
 * 柜台可能按品种返回（instrumentID 为品种代码）。
 */
@Builder(toBuilder = true)
public record CommissionRate(
    String instrumentID,                                     // 合约或品种代码
    String exchangeID,                                       // 交易所代码
    double openRatioByMoney,                                 // 开仓手续费率
    double openRatioByVolume,                                // 开仓手续费
    double closeRatioByMoney,                                // 平仓手续费率
    double closeRatioByVolume,                               // 平仓手续费
    double closeTodayRatioByMoney,                           // 平今手续费率
    double closeTodayRatioByVolume                           // 平今手续费
) {
}
