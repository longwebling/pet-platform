package com.pethelp.auth.token;

import java.util.List;

public record JwtClaims(Long userId, List<String> roles) {
}
