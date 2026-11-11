package com.pethelp.auth.token;

import java.util.List;

public interface JwtService {

    String generateToken(Long userId, List<String> roles);

    JwtClaims parseToken(String token);
}
