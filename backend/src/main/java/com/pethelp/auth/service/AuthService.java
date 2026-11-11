package com.pethelp.auth.service;

import com.pethelp.auth.dto.LoginRequest;
import com.pethelp.auth.dto.LoginResponse;

public interface AuthService {

    void sendCode(String phone);

    LoginResponse login(LoginRequest request);
}
