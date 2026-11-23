package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 报单。下单时只需填写合约、方向、开平、价格、数量，其余条件缺省为限价、当日有效、任意数量、投机；
 * 柜台回报后由 {@link #orderId()} 关联同一笔报单。
 */
@Builder(toBuilder = true)
public record Order(
    String instrumentID,                                     // 合约代码
    String exchangeID,                                       // 交易所代码，为空时按合约补全

    DataType.Direction direction,                            // 买卖方向
    DataType.OffsetFlag offsetFlag,                          // 开平标志
    DataType.HedgeFlag hedgeFlag,                            // 投机套保标志
    DataType.OrderPriceType priceType,                       // 报单价格条件
    DataType.TimeCondition timeCondition,                    // 有效期类型
    DataType.VolumeCondition volumeCondition,                // 成交量类型
    double limitPrice,                                       // 价格
    int volume,                                              // 报单数量
    int minVolume,                                           // 最小成交量（VolumeCondition 为 MV 时有效）

    int frontID,                                             // 前置编号
    int sessionID,                                           // 会话编号
    String orderRef,                                         // 报单引用
    String orderSysID,                                       // 交易所报单编号（原样，上期所等含前导空格）

    DataType.OrderSubmitStatus submitStatus,                 // 报单提交状态
    DataType.OrderStatus status,                             // 报单状态
    String statusMsg,                                        // 状态信息
    int volumeTraded,                                        // 已成交数量
    int volumeTotal,                                         // 剩余数量

    LocalDate tradingDay,                                    // 交易日
    LocalDate insertDate,                                    // 报单日期（大商所夜盘为交易日）
    LocalTime insertTime,                                    // 委托时间
    LocalTime updateTime,                                    // 最后修改时间
    LocalTime cancelTime,                                    // 撤销时间

    String investorID,                                       // 投资者代码
    String userID,                                           // 用户代码
    String accountID,                                        // 资金账号
    String currencyID,                                       // 币种代码
    LocalDate gtdDate,                                       // GTD 日期
    DataType.ContingentCondition contingentCondition,        // 触发条件
    double stopPrice,                                        // 止损价
    DataType.ForceCloseReason forceCloseReason,              // 强平原因
    boolean autoSuspend,                                     // 自动挂起标志
    boolean userForceClose,                                  // 用户强平标志
    boolean swapOrder,                                       // 互换单标志
    DataType.OrderSource orderSource,                        // 报单来源
    DataType.OrderType orderType,                            // 报单类型
    LocalTime activeTime,                                    // 激活时间
    LocalTime suspendTime,                                   // 挂起时间
    int requestID,                                           // 请求编号
    String orderLocalID,                                     // 本地报单编号
    int brokerOrderSeq,                                      // 经纪公司报单编号
    String relativeOrderSysID,                               // 相关报单
    int zceTotalTradedVolume,                                // 郑商所成交数量
    String participantID,                                    // 会员代码
    String clientID,                                         // 客户代码（交易编码）
    String traderID,                                         // 交易所交易员代码
    String activeTraderID,                                   // 最后修改交易所交易员代码
    String clearingPartID,                                   // 结算会员编号
    int installID,                                           // 安装编号
    int notifySequence,                                      // 报单提示序号
    int sequenceNo,                                          // 序号
    int settlementID,                                        // 结算编号
    String activeUserID,                                     // 操作用户代码
    String userProductInfo,                                  // 用户端产品信息
    String businessUnit,                                     // 业务单元
    String branchID,                                         // 营业部编号
    String investUnitID,                                     // 投资单元代码
    String ipAddress,                                        // IP 地址
    String macAddress,                                       // Mac 地址
    long systemTimestamp                                     // 本地最后更新时间
) {

    /**
     * 报单唯一标识：FrontID_SessionID_OrderRef。
     */
    public String orderId() {
        return orderId(frontID, sessionID, orderRef);
    }

    public static String orderId(int frontID, int sessionID, String orderRef) {
        return frontID + "_" + sessionID + "_" + orderRef;
    }

    /**
     * 报单已终结（全部成交、已撤销、被拒绝），不会再有状态变化。
     */
    public boolean done() {
        return submitStatus == DataType.OrderSubmitStatus.InsertRejected
            || status == DataType.OrderStatus.AllTraded
            || status == DataType.OrderStatus.Canceled
            || status == DataType.OrderStatus.PartTradedNotQueueing
            || status == DataType.OrderStatus.NoTradeNotQueueing;
    }
}
