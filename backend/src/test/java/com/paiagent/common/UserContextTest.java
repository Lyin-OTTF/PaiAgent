package com.paiagent.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 迭代7（评审报告 A1/A2）：用户归属上下文的语义与兜底规则。
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("未设置时 currentOwner 兜底为 system（写操作不产生 NULL 归属）")
    void defaultsToSystemOwner() {
        assertNull(UserContext.getCurrent());
        assertEquals(UserContext.SYSTEM_OWNER, UserContext.currentOwner());
    }

    @Test
    @DisplayName("设置后返回登录用户名，并裁剪首尾空白")
    void returnsTrimmedUsername() {
        UserContext.setCurrent("  alice  ");
        assertEquals("alice", UserContext.getCurrent());
        assertEquals("alice", UserContext.currentOwner());
    }

    @Test
    @DisplayName("写入空白等同于清除，回到 system 兜底")
    void blankResetsToSystem() {
        UserContext.setCurrent("alice");
        UserContext.setCurrent("   ");
        assertNull(UserContext.getCurrent());
        assertEquals(UserContext.SYSTEM_OWNER, UserContext.currentOwner());
    }

    @Test
    @DisplayName("clear 后回到 system 兜底（拦截器 afterCompletion 依赖此行为防串扰）")
    void clearResets() {
        UserContext.setCurrent("bob");
        UserContext.clear();
        assertNull(UserContext.getCurrent());
        assertEquals(UserContext.SYSTEM_OWNER, UserContext.currentOwner());
    }

    @Test
    @DisplayName("线程隔离：子线程读不到父线程的登录身份")
    void isThreadLocal() throws Exception {
        UserContext.setCurrent("alice");
        String[] seenInChild = new String[1];
        Thread child = new Thread(() -> seenInChild[0] = UserContext.getCurrent());
        child.start();
        child.join();

        assertNull(seenInChild[0], "ThreadLocal 不继承，子线程需显式恢复上下文");
        assertEquals("alice", UserContext.getCurrent(), "父线程自身不受影响");
    }
}
