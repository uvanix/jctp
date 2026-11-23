package com.github.jctp.server.gateway;

import com.github.jctp.ffm.CtpVersion;
import com.github.jctp.server.FakeBrokers;
import com.github.jctp.server.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private static final ServerConfig.Counter COUNTER =
        new ServerConfig.Counter("sim", "模拟柜台", "9999", "tcp://td", "tcp://md", null, null, null);

    private final AtomicInteger created = new AtomicInteger();
    private final SessionManager sessions = new SessionManager(new ServerConfig(0, null, null, null, List.of(COUNTER)),
        (counter, userId, password, apiVersion) -> {
            created.incrementAndGet();
            return new Gateway(counter, userId, password, apiVersion, new FakeBrokers.Td(FakeBrokers.future("rb2601", "螺纹钢2601", "rb", "SHFE")), new FakeBrokers.Md());
        }, Duration.ofMillis(400));

    @AfterEach
    void close() {
        sessions.close();
    }

    private SessionManager.Session login(String apiVersion) throws Exception {
        SessionManager.LoginTask task = sessions.startLogin("sim", "000001", "pwd", apiVersion);
        SessionManager.Session session = task.session().get(10, TimeUnit.SECONDS);
        sessions.claimLoginTask(task.id());
        return session;
    }

    @Test
    void idleSessionsExpireAndReleaseGateway() throws Exception {
        SessionManager.Session active = login("6.5.1");
        SessionManager.Session orphan = login("6.5.1");
        assertSame(active.gateway(), orphan.gateway());
        assertEquals(1, created.get());

        // 持续有请求的会话保留，无人使用的会话过期（查询会话即记为活跃，只在最后查一次）；网关仍有会话不释放
        for (int i = 0; i < 30; i++) {
            assertTrue(sessions.session(active.token()).isPresent());
            Thread.sleep(50);
        }
        assertTrue(sessions.session(orphan.token()).isEmpty(), "空闲会话未过期");
        assertThrows(IllegalArgumentException.class, () -> sessions.startLogin("sim", "000001", "pwd", "6.3.15"),
            "网关仍在使用，不能换版本");

        // 最后一个会话过期后网关释放，可以换版本登录
        Thread.sleep(1500);
        assertTrue(sessions.session(active.token()).isEmpty());
        SessionManager.Session other = login("6.3.15");
        assertEquals(CtpVersion.V6_3_15, other.gateway().getApiVersion());
        assertEquals(2, created.get());
    }
}
