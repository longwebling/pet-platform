package com.pethelp.security;

import java.util.List;

public record CurrentUser(Long id, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isOps() {
        return hasRole("ops") || hasRole("admin");
    }

    public boolean isAdmin() {
        return hasRole("admin");
    }
}
