package com.pethelp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：仅验证 JUnit 5 装配可用，不启动 Spring 上下文、不依赖数据库。
 * 真正的集成测试将在接入 Testcontainers 后补充。
 */
class SmokeTest {

    @Test
    void junit5IsConfigured() {
        assertTrue(true);
    }
}
