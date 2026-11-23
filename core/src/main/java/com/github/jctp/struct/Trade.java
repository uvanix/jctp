package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;

@Builder(toBuilder = true)
public record Trade(
    String instrumentID,                                     // 合约代码
    String exchangeID,                                       // 交易所代码
    String tradeID,                                          // 成交编号（交易所内唯一）
    String orderRef,                                         // 报单引用
    String orderSysID,                                       // 交易所报单编号（原样，上期所等含前导空格）

    DataType.Direction direction,                            // 买卖方向
    DataType.OffsetFlag offsetFlag,                          // 开平标志
    DataType.HedgeFlag hedgeFlag,                            // 投机套保标志
    DataType.TradeType tradeType,                            // 成交类型
    double price,                                            // 成交价
    int volume,                                              // 成交数量

    LocalDate tradingDay,                                    // 交易日
    LocalDate tradeDate,                                     // 成交日期（大商所夜盘为交易日）
    LocalTime tradeTime,                                     // 成交时间

    String investorID,                                       // 投资者代码
    String userID,                                           // 用户代码
    DataType.TradingRole tradingRole,                        // 交易角色
    DataType.PriceSource priceSource,                        // 成交价来源
    DataType.TradeSource tradeSource,                        // 成交来源
    String orderLocalID,                                     // 本地报单编号
    int brokerOrderSeq,                                      // 经纪公司报单编号
    String participantID,                                    // 会员代码
    String clientID,                                         // 客户代码（交易编码）
    String traderID,                                         // 交易所交易员代码
    String clearingPartID,                                   // 结算会员编号
    int sequenceNo,                                          // 序号
    int settlementID,                                        // 结算编号
    String businessUnit,                                     // 业务单元
    String investUnitID,                                     // 投资单元代码
    long systemTimestamp                                     // 本地接收时间
) {

    /**
     * 成交唯一标识：ExchangeID_TradeID_Direction。成交编号只在交易所内唯一，自成交时买卖两笔同号。
     */
    public String id() {
        return exchangeID + "_" + tradeID + "_" + (direction == null ? "" : direction.code());
    }
}
