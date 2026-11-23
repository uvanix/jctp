package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;

@Builder(toBuilder = true)
public record Instrument(
    String instrumentID,                                              // 合约代码
    String exchangeID,                                                // 交易所代码
    String instrumentName,                                            // 合约名称
    String exchangeInstID,                                            // 合约在交易所的代码
    String productID,                                                 // 产品代码
    DataType.ProductClass productClass,                               // 产品类型
    DataType.InstLifePhase instLifePhase,                             // 合约生命周期状态
    boolean isTrading,                                                // 当前是否交易

    double priceTick,                                                 // 最小变动价位
    double volumeMultiple,                                            // 合约数量乘数
    double underlyingMultiple,                                        // 合约基础商品乘数
    String underlyingInstrID,                                         // 基础商品代码
    double strikePrice,                                               // 期权执行价
    DataType.OptionsType optionsType,                                 // 期权类型

    LocalDate createDate,                                             // 创建日
    LocalDate openDate,                                               // 上市日
    LocalDate expireDate,                                             // 到期日
    int deliveryYear,                                                 // 交割年份
    int deliveryMonth,                                                // 交割月
    LocalDate startDelivDate,                                         // 开始交割日
    LocalDate endDelivDate,                                           // 结束交割日

    double longMarginRatio,                                           // 多头保证金率
    double shortMarginRatio,                                          // 空头保证金率
    double maxMarketOrderVolume,                                      // 市价单最大下单量
    double minMarketOrderVolume,                                      // 限价单最小下单量
    double maxLimitOrderVolume,                                       // 限价单最大下单量
    double minLimitOrderVolume,                                       // 限价单最小下单量

    DataType.MaxMarginSideAlgorithm maxMarginSideAlgorithm,           // 是否使用大额单边保证金算法
    DataType.PositionType positionType,                               // 持仓类型
    DataType.CombinationType combinationType                          // 组合类型
) {
}
