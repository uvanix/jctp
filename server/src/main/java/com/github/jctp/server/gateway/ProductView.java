package com.github.jctp.server.gateway;

import com.github.jctp.struct.DataType;

/**
 * 品种（合约列表的分组）。
 *
 * @param count   可交易合约数，合约尚未加载时为 null
 * @param loading 尚未加载且后台正在（排队）加载；为 false 的未加载品种打开时按需查询
 */
public record ProductView(
    String productId,
    String exchangeId,
    DataType.ProductClass productClass,
    String name,
    Integer count,
    boolean loading
) {
}
