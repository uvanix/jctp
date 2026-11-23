package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;

@Builder(toBuilder = true)
public record Investor(
    String investorID,                                       // 投资者代码
    String brokerID,                                         // 经纪公司代码
    String investorGroupID,                                  // 投资者分组代码
    String investorName,                                     // 投资者名称
    char identifiedCardType,                                 // 证件类型
    String identifiedCardNo,                                 // 证件号码
    boolean active,                                          // 是否活跃
    String telephone,                                        // 联系电话
    String address,                                          // 通讯地址
    LocalDate openDate,                                      // 开户日期
    String mobile,                                           // 手机
    String commModelID,                                      // 手续费率模板代码
    String marginModelID                                     // 保证金率模板代码
) {
}
