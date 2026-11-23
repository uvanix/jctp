package com.github.jctp.struct;

import lombok.Builder;

import java.time.LocalDate;

@Builder(toBuilder = true)
public record Account(
    String accountID,                                        // 投资者帐号
    String currencyID,                                       // 币种代码
    LocalDate tradingDay,                                    // 交易日

    double preBalance,                                       // 上次结算准备金
    double balance,                                          // 期货结算准备金（动态权益）
    double available,                                        // 可用资金
    double withdrawQuota,                                    // 可取资金
    double deposit,                                          // 入金金额
    double withdraw,                                         // 出金金额

    double currMargin,                                       // 当前保证金总额
    double exchangeMargin,                                   // 交易所保证金
    double frozenMargin,                                     // 冻结的保证金
    double frozenCash,                                       // 冻结的资金
    double frozenCommission,                                 // 冻结的手续费
    double commission,                                       // 手续费
    double closeProfit,                                      // 平仓盈亏
    double positionProfit,                                   // 持仓盈亏
    double credit,                                           // 信用额度
    double mortgage,                                         // 质押金额

    double preMargin,                                        // 上次占用的保证金
    double preCredit,                                        // 上次信用额度
    double preMortgage,                                      // 上次质押金额
    double preDeposit,                                       // 上次存款额
    double interestBase,                                     // 利息基数
    double interest,                                         // 利息收入
    double cashIn,                                           // 资金差额
    double reserve,                                          // 基本准备金
    double reserveBalance,                                   // 保底期货结算准备金
    double deliveryMargin,                                   // 投资者交割保证金
    double exchangeDeliveryMargin,                           // 交易所交割保证金
    double preFundMortgageIn,                                // 上次货币质入金额
    double preFundMortgageOut,                               // 上次货币质出金额
    double fundMortgageIn,                                   // 货币质入金额
    double fundMortgageOut,                                  // 货币质出金额
    double fundMortgageAvailable,                            // 货币质押余额
    double mortgageableFund,                                 // 可质押货币金额
    double specProductMargin,                                // 特殊产品占用保证金
    double specProductFrozenMargin,                          // 特殊产品冻结保证金
    double specProductCommission,                            // 特殊产品手续费
    double specProductFrozenCommission,                      // 特殊产品冻结手续费
    double specProductPositionProfit,                        // 特殊产品持仓盈亏
    double specProductCloseProfit,                           // 特殊产品平仓盈亏
    double specProductPositionProfitByAlg,                   // 特殊产品持仓盈亏（按持仓盈亏算法）
    double specProductExchangeMargin,                        // 特殊产品交易所保证金
    double frozenSwap,                                       // 延时换汇冻结金额
    double remainSwap,                                       // 剩余换汇额度
    int settlementID                                         // 结算编号
) {
}
