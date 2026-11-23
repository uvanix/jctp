package com.github.jctp.struct;

import lombok.Builder;

/**
 * 合约保证金率。保证金 = 成交金额 × ByMoney + 手数 × ByVolume。
 */
@Builder(toBuilder = true)
public record MarginRate(
    String instrumentID,                                     // 合约代码
    String exchangeID,                                       // 交易所代码
    DataType.HedgeFlag hedgeFlag,                            // 投机套保标志
    double longMarginRatioByMoney,                           // 多头保证金率
    double longMarginRatioByVolume,                          // 多头保证金费
    double shortMarginRatioByMoney,                          // 空头保证金率
    double shortMarginRatioByVolume,                         // 空头保证金费
    boolean relative                                         // 是否相对交易所收取
) {
}
