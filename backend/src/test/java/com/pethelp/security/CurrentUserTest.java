package com.pethelp.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    @Test
    void isOps_opsOrAdmin_true() {
        assertThat(new CurrentUser(1L, List.of("ops")).isOps()).isTrue();
        assertThat(new CurrentUser(2L, List.of("admin")).isOps()).isTrue();
        assertThat(new CurrentUser(3L, List.of("user", "ops")).isOps()).isTrue();
    }

    @Test
    void isOps_plainUser_false() {
        assertThat(new CurrentUser(1L, List.of("user")).isOps()).isFalse();
    }

    @Test
    void isAdmin_admin_true() {
        assertThat(new CurrentUser(1L, List.of("admin")).isAdmin()).isTrue();
    }

    @Test
    void isAdmin_opsOnly_false() {
        assertThat(new CurrentUser(1L, List.of("ops")).isAdmin()).isFalse();
    }

    @Test
    void hasRole_nullRoles_false() {
        assertThat(new CurrentUser(1L, null).hasRole("ops")).isFalse();
        assertThat(new CurrentUser(1L, null).isOps()).isFalse();
        assertThat(new CurrentUser(1L, null).isAdmin()).isFalse();
    }
}
