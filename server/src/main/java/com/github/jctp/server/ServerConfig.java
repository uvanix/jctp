package com.github.jctp.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 服务端配置，JSON 格式。查找顺序：启动参数指定的文件 → 工作目录下的 jctp-server.json → classpath 内置默认配置。
 *
 * @param port        HTTP 端口
 * @param webDir      前端构建产物目录（web/dist），存在时由服务端直接托管；不存在时托管打包进 jar 的前端
 * @param flowPath    CTP 流文件根目录
 * @param ctpTimeout  CTP 登录、查询的等待上限
 * @param counters    可选柜台
 */
public record ServerConfig(
    int port,
    String webDir,
    String flowPath,
    Duration ctpTimeout,
    List<Counter> counters
) {

    public static final String FILE_NAME = "jctp-server.json";

    public ServerConfig {
        if (port <= 0) {
            port = 8080;
        }
        if (flowPath == null || flowPath.isBlank()) {
            flowPath = "ctp-flow";
        }
        if (ctpTimeout == null) {
            ctpTimeout = Duration.ofSeconds(30);
        }
        counters = counters == null ? List.of() : List.copyOf(counters);
    }

    /**
     * 柜台（期货公司交易前置）。
     *
     * @param id              柜台标识，登录时选择
     * @param name            显示名称
     * @param brokerId        期货公司代码
     * @param tdFront         交易前置
     * @param mdFront         行情前置
     * @param appId           穿透式监管 AppID，为空则不认证
     * @param authCode        授权码
     * @param userProductInfo 产品信息
     * @param simulated       本地模拟柜台：不连 CTP，模拟行情与撮合，任意账号密码可登录，前置等参数不用填
     */
    public record Counter(
        String id,
        String name,
        String brokerId,
        String tdFront,
        String mdFront,
        String appId,
        String authCode,
        String userProductInfo,
        boolean simulated
    ) {

        public Counter(String id, String name, String brokerId, String tdFront, String mdFront, String appId,
                       String authCode, String userProductInfo) {
            this(id, name, brokerId, tdFront, mdFront, appId, authCode, userProductInfo, false);
        }
    }

    public Counter counter(String id) {
        return counters.stream()
            .filter(c -> c.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未知柜台 " + id));
    }

    public static ServerConfig load(ObjectMapper mapper, String[] args) {
        Path file = args.length > 0 ? Path.of(args[0]) : Path.of(FILE_NAME);
        try {
            if (Files.exists(file)) {
                return mapper.readValue(file.toFile(), ServerConfig.class);
            }
            if (args.length > 0) {
                throw new IllegalArgumentException("配置文件不存在 " + file.toAbsolutePath());
            }
            try (InputStream in = ServerConfig.class.getResourceAsStream("/" + FILE_NAME)) {
                return mapper.readValue(in, ServerConfig.class);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败 " + file, e);
        }
    }
}
