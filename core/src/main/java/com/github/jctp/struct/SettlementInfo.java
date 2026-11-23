package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;

/**
 * 投资者结算单。CTP 分多段返回，已按序号拼接为完整文本。
 */
@Builder(toBuilder = true)
public record SettlementInfo(
    LocalDate tradingDay,                                    // 交易日
    int settlementID,                                        // 结算编号
    String brokerID,                                         // 经纪公司代码
    String investorID,                                       // 投资者代码
    String accountID,                                        // 投资者帐号
    String currencyID,                                       // 币种代码
    String content                                           // 结算单全文
) {
}
