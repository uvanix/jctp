package com.github.jctp.server.gateway;

import com.github.jctp.struct.DataType;

/**
 * 网页端下单请求。
 *
 * @param orderType LIMIT 限价（缺省）、FAK 立即成交剩余撤销、FOK 全部成交否则撤销、MARKET 市价（上期所、能源中心不支持）
 */
public record OrderRequest(
    String instrumentId,
    DataType.Direction direction,
    DataType.OffsetFlag offsetFlag,
    double price,
    int volume,
    OrderType orderType
) {

    public enum OrderType {LIMIT, FAK, FOK, MARKET}
}
