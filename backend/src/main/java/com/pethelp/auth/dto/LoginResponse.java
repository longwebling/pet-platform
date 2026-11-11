package com.pethelp.auth.dto;

public record LoginResponse(String token, Long userId) {
}
