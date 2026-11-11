package com.pethelp.auth.controller;

import com.pethelp.auth.dto.LoginRequest;
import com.pethelp.auth.dto.LoginResponse;
import com.pethelp.auth.dto.SendCodeRequest;
import com.pethelp.auth.service.AuthService;
import com.pethelp.common.ApiResponse;
import com.pethelp.common.exception.BadRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sms-code")
    public ApiResponse<Void> sendCode(@RequestBody SendCodeRequest request) {
        validatePhone(request.phone());
        authService.sendCode(request.phone());
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        validatePhone(request.phone());
        LoginResponse response = authService.login(request);
        return ApiResponse.ok(response);
    }

    private void validatePhone(String phone) {
        if (phone == null || !phone.matches("\\d{11}")) {
            throw new BadRequestException("手机号格式不正确");
        }
    }
}
