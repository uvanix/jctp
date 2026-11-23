package com.github.jctp.server.gateway;

import com.github.jctp.CtpConfig;
import com.github.jctp.broker.CtpMdBrokerImpl;
import com.github.jctp.broker.CtpTdBrokerImpl;
import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.ServerConfig;
import com.github.jctp.server.sim.SimExchange;

import java.nio.file.Path;

/**
 * 创建（未连接的）网关。测试中可替换为假柜台。
 */
@FunctionalInterface
public interface GatewayFactory {

    Gateway create(ServerConfig.Counter counter, String userId, String password, CtpVersion apiVersion);

    static GatewayFactory ctp(ServerConfig config) {
        return (counter, userId, password, apiVersion) -> {
            if (counter.simulated()) {
                SimExchange exchange = new SimExchange(counter.brokerId(), userId);
                return new Gateway(counter, userId, password, apiVersion, exchange.td, exchange.md);
            }
            CtpConfig ctp = CtpConfig.builder()
                .brokerId(counter.brokerId())
                .userId(userId)
                .password(password)
                .appId(counter.appId())
                .authCode(counter.authCode())
                .userProductInfo(counter.userProductInfo())
                .tdFront(counter.tdFront())
                .mdFront(counter.mdFront())
                .version(apiVersion)
                .flowPath(Path.of(config.flowPath()))
                .timeout(config.ctpTimeout())
                .build();
            return new Gateway(counter, userId, password, apiVersion, new CtpTdBrokerImpl(ctp), new CtpMdBrokerImpl(ctp),
                Path.of(config.flowPath(), "instrument-cache"));
        };
    }
}
