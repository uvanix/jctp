package com.github.jctp.server;

import com.github.jctp.server.gateway.GatewayFactory;
import com.github.jctp.server.gateway.SessionManager;
import com.github.jctp.server.web.ApiServer;
import io.javalin.Javalin;

import java.util.List;

/**
 * 离线演示：只提供本地模拟柜台（{@link com.github.jctp.server.sim.SimExchange}）启动完整的 server，便于不连 CTP 调试网页端。
 * <p>
 * 运行：先 {@code mvn -pl server -am test-compile}，再以 test classpath 启动本类；任意账号密码登录。
 * 正式服务的默认配置中同样有本地模拟柜台。
 */
public final class DemoServer {

    private DemoServer() {
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String webDir = args.length > 1 ? args[1] : "../web/dist";
        ServerConfig.Counter counter = new ServerConfig.Counter("local-sim", "本地模拟柜台（模拟行情与撮合）", "9999",
            null, null, null, null, null, true);
        ServerConfig config = new ServerConfig(port, webDir, null, null, List.of(counter));
        SessionManager sessions = new SessionManager(config, GatewayFactory.ctp(config));
        Javalin app = ApiServer.create(config, sessions).start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            sessions.close();
        }));
        System.out.println("演示服务已启动 http://localhost:" + port);
    }
}
