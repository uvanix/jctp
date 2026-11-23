package com.github.jctp.struct;

import lombok.Builder;

@Builder(toBuilder = true)
public record Product(
    String productID,                                                 // 产品代码
    String productName,                                               // 产品名称
    String exchangeID,                                                // 交易所代码
    String exchangeProductID,                                         // 交易所产品代码
    DataType.ProductClass productClass,                               // 产品类型

    int volumeMultiple,                                               // 合约数量乘数
    double priceTick,                                                 // 最小变动价位
    double underlyingMultiple,                                        // 合约基础商品乘数
    int maxMarketOrderVolume,                                         // 市价单最大下单量
    int minMarketOrderVolume,                                         // 市价单最小下单量
    int maxLimitOrderVolume,                                          // 限价单最大下单量
    int minLimitOrderVolume,                                          // 限价单最小下单量

    DataType.PositionType positionType,                               // 持仓类型
    char positionDateType,                                            // 持仓日期类型：1 使用历史持仓，2 不使用历史持仓
    char closeDealType,                                               // 平仓处理类型：0 正常，1 投机平仓优先
    String tradeCurrencyID,                                           // 交易币种类型
    char mortgageFundUseRange                                         // 质押资金可用范围
) {
}
