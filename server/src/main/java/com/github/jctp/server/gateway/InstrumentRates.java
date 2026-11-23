package com.github.jctp.server.gateway;

import com.github.jctp.struct.CommissionRate;
import com.github.jctp.struct.MarginRate;

/**
 * 合约手续费率与保证金率，柜台未返回时为 null。
 */
public record InstrumentRates(CommissionRate commission, MarginRate margin) {
}
