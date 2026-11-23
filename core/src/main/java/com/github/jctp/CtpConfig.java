package com.github.jctp;

import com.github.jctp.ffm.CtpVersion;
import lombok.Builder;

import java.nio.file.Path;
import java.time.Duration;

/**
 * CTP 连接参数。
 *
 * @param brokerId        期货公司代码
 * @param userId          用户代码
 * @param investorId      投资者代码，为空时同 userId
 * @param password        密码
 * @param appId           穿透式监管 AppID，为空则跳过看穿式认证
 * @param authCode        穿透式监管授权码
 * @param userProductInfo 用户端产品信息
 * @param mdFront         行情前置，如 tcp://182.254.243.31:30011
 * @param tdFront         交易前置，如 tcp://182.254.243.31:30001
 * @param flowPath        流文件根目录，行情/交易各用一个子目录
 * @param version         CTP API 版本，须与柜台要求一致，为空时 v6.3.15
 * @param libraryDir      CTP 原生库目录（须与 version 一致），为空时从 classpath 解压
 * @param timeout         连接登录的等待上限；查询为连续无应答的等待上限（大批量查询只要应答还在到达就继续等）
 */
@Builder(toBuilder = true)
public record CtpConfig(
    String brokerId,
    String userId,
    String investorId,
    String password,
    String appId,
    String authCode,
    String userProductInfo,
    String mdFront,
    String tdFront,
    Path flowPath,
    CtpVersion version,
    Path libraryDir,
    Duration timeout
) {

    public CtpConfig {
        if (investorId == null || investorId.isBlank()) {
            investorId = userId;
        }
        if (flowPath == null) {
            flowPath = Path.of("ctp-flow");
        }
        if (version == null) {
            version = CtpVersion.V6_3_15;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (userProductInfo == null) {
            userProductInfo = "";
        }
    }

    /**
     * SimNow 仿真环境（BrokerID 9999，统一的测试 AppID/授权码）。前置地址以 SimNow 官网公布为准。
     */
    public static CtpConfig simnow(SimNow env, String userId, String password) {
        return CtpConfig.builder()
            .brokerId("9999")
            .userId(userId)
            .password(password)
            .appId("simnow_client_test")
            .authCode("0000000000000000")
            .mdFront(env.mdFront)
            .tdFront(env.tdFront)
            .build();
    }

    public enum SimNow {
        /**
         * 与实盘同步的交易时段，第一组
         */
        GROUP1("tcp://182.254.243.31:30001", "tcp://182.254.243.31:30011"),
        GROUP2("tcp://182.254.243.31:30002", "tcp://182.254.243.31:30012"),
        GROUP3("tcp://182.254.243.31:30003", "tcp://182.254.243.31:30013"),
        /**
         * 7x24 环境：非交易时段回放行情，便于联调
         */
        SEVEN_BY_24("tcp://182.254.243.31:40001", "tcp://182.254.243.31:40011"),
        ;

        public final String tdFront;
        public final String mdFront;

        SimNow(String tdFront, String mdFront) {
            this.tdFront = tdFront;
            this.mdFront = mdFront;
        }
    }
}
