package com.github.jctp.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.jctp.server.Json;
import com.github.jctp.server.gateway.Gateway;
import com.github.jctp.server.gateway.GatewayListener;
import com.github.jctp.struct.MarketData;
import com.github.jctp.struct.Order;
import com.github.jctp.struct.Trade;
import io.javalin.websocket.WsContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 一个浏览器 WebSocket 连接。
 * <p>
 * 收到的消息：{@code {"type":"subscribe|unsubscribe","instruments":[...]}}、{@code {"type":"ping"}}；
 * 推送的消息：{@code {"type":"tick|order|trade|pong|closed","data":...}}。
 * 网关事件在 CTP 回调线程上产生，这里只入队，由独立的虚拟线程发送，慢客户端不会拖住 CTP。
 */
@Slf4j
final class WsClient implements GatewayListener {

    /**
     * 积压上限，超过说明客户端已失去响应，断开连接
     */
    private static final int MAX_QUEUE = 20_000;

    private final WsContext ctx;
    private final Gateway gateway;
    private final Set<String> instruments = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final Thread sender;
    private volatile boolean closed;

    WsClient(WsContext ctx, Gateway gateway) {
        this.ctx = ctx;
        this.gateway = gateway;
        this.sender = Thread.ofVirtual().name("ws-" + ctx.sessionId()).start(this::drain);
        gateway.addListener(this);
    }

    void onMessage(String text) {
        JsonNode message;
        try {
            message = Json.MAPPER.readTree(text);
        } catch (Exception e) {
            log.debug("无法解析的 WebSocket 消息 {}", text);
            return;
        }
        String type = message.path("type").asText();
        switch (type) {
            case "subscribe" -> {
                List<String> ids = ids(message);
                // 先订阅再登记：订阅过程中产生的行情不推给本连接，随后统一推一次最新快照
                gateway.subscribe(ids);
                instruments.addAll(ids);
                ids.forEach(id -> gateway.quote(id).ifPresent(this::onTick));
            }
            case "unsubscribe" -> ids(message).forEach(instruments::remove);
            case "ping" -> enqueue("pong", null);
            default -> log.debug("未知的 WebSocket 消息类型 {}", type);
        }
    }

    private static List<String> ids(JsonNode message) {
        List<String> ids = new ArrayList<>();
        message.path("instruments").forEach(n -> {
            if (n.isTextual() && !n.asText().isBlank()) {
                ids.add(n.asText());
            }
        });
        return ids;
    }

    @Override
    public void onTick(MarketData tick) {
        if (instruments.contains(tick.instrumentID())) {
            enqueue("tick", tick);
        }
    }

    @Override
    public void onOrder(Order order) {
        enqueue("order", order);
    }

    @Override
    public void onTrade(Trade trade) {
        enqueue("trade", trade);
    }

    @Override
    public void onClosed() {
        enqueue("closed", null);
    }

    private void enqueue(String type, Object data) {
        if (closed) {
            return;
        }
        Map<String, Object> message = new java.util.HashMap<>();
        message.put("type", type);
        message.put("data", data);
        if (!queue.offer(Json.write(message))) {
            log.warn("WebSocket {} 积压超过 {} 条，断开", ctx.sessionId(), MAX_QUEUE);
            close();
            ctx.closeSession(1011, "backlog");
        }
    }

    private void drain() {
        while (!closed) {
            String message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                ctx.send(message);
            } catch (RuntimeException e) {
                log.debug("WebSocket {} 发送失败 {}", ctx.sessionId(), e.getMessage());
            }
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        gateway.removeListener(this);
        sender.interrupt();
    }
}
