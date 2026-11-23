package com.github.jctp.broker;

import com.github.jctp.CtpConfig;
import com.github.jctp.ffm.CtpStructs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行情、交易网关内部共用的连接、应答与等待工具。
 */
final class BrokerSupport {

    /**
     * 连接阶段登录请求发出后、应答到达前前置断开的容忍次数。柜台不接受客户端（如 API 版本过旧）时
     * 会在收到登录请求后直接断开而不返回错误，CTP 又会无限自动重连，因此超过次数即判定连接失败。
     */
    static final int MAX_LOGIN_DISCONNECTS = 3;

    private BrokerSupport() {
    }

    static IllegalStateException loginDisconnected(String what, int times, int reason, String version) {
        return new IllegalStateException("CTP %s请求发出后前置连续断开 %d 次（%s），柜台可能不接受当前 API 版本 %s"
            .formatted(what, times, disconnectReason(reason), version));
    }

    /**
     * CTP 请求返回码的说明
     */
    static String requestError(int rc) {
        return switch (rc) {
            case -1 -> "rc=-1 网络连接失败";
            case -2 -> "rc=-2 未处理请求超过许可数";
            case -3 -> "rc=-3 每秒发送请求数超过许可数";
            default -> "rc=" + rc;
        };
    }

    /**
     * OnFrontDisconnected 的断开原因，如 "0x2001 接收心跳超时"。
     */
    static String disconnectReason(int reason) {
        String text = switch (reason) {
            case 0x1001 -> "网络读失败";
            case 0x1002 -> "网络写失败";
            case 0x2001 -> "接收心跳超时";
            case 0x2002 -> "发送心跳失败";
            case 0x2003 -> "收到错误报文";
            default -> "未知原因";
        };
        return "0x" + Integer.toHexString(reason) + " " + text;
    }

    /**
     * RspInfo 为空或 ErrorID 为 0 表示成功，返回 null；否则返回 "[ErrorID]ErrorMsg"。
     */
    static String error(CtpStructs structs, MemorySegment rspInfo) {
        if (rspInfo == null || structs.rspInfo.ErrorID.getInt(rspInfo) == 0) {
            return null;
        }
        return "[" + structs.rspInfo.ErrorID.getInt(rspInfo) + "]" + structs.rspInfo.ErrorMsg.getString(rspInfo);
    }

    /**
     * 行情与交易的流文件同名（DialogRsp.con 等），必须分目录；不同 CTP 版本的流文件也不混用。
     * 版本串可能带编译时间（如 "v6.3.15_20190220 20:39:53"），只取首段并替换路径非法字符。
     */
    static Path flowDir(CtpConfig config, String kind, String version) {
        String versionDir = version.strip().split("\\s+")[0].replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = config.flowPath().resolve(kind).resolve(versionDir)
            .resolve(config.brokerId() + "_" + config.userId());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建 CTP 流文件目录失败 " + dir, e);
        }
        return dir;
    }

    /**
     * 等待 CTP 异步应答；失败或超时时先执行 onFailure 再抛出。
     */
    static <T> T await(CompletableFuture<T> future, Duration timeout, String what, Runnable onFailure) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure.run();
            throw new IllegalStateException(what + "被中断", e);
        } catch (ExecutionException e) {
            onFailure.run();
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            onFailure.run();
            throw new IllegalStateException(what + "超时 " + timeout, e);
        }
    }
}
