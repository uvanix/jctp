package com.github.jctp.server;

import com.github.jctp.server.gateway.GatewayFactory;
import com.github.jctp.server.gateway.SessionManager;
import com.github.jctp.server.web.ApiServer;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动：{@code java --enable-native-access=ALL-UNNAMED -jar server.jar [jctp-server.json]}
 */
@Slf4j
public final class ServerApp {

    private ServerApp() {
    }

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.load(Json.MAPPER, args);
        SessionManager sessions = new SessionManager(config, GatewayFactory.ctp(config));
        Javalin app = ApiServer.create(config, sessions).start(config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            sessions.close();
        }, "shutdown"));
        log.info("jctp server 已启动 http://localhost:{} 柜台 {} 个", config.port(), config.counters().size());
    }
}
