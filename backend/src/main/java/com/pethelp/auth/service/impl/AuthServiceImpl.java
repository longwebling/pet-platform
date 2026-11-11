package com.pethelp.auth.service.impl;

import com.pethelp.auth.dto.LoginRequest;
import com.pethelp.auth.dto.LoginResponse;
import com.pethelp.auth.service.AuthService;
import com.pethelp.auth.sms.SmsCodeService;
import com.pethelp.auth.token.JwtService;
import com.pethelp.common.exception.InvalidSmsCodeException;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final SmsCodeService smsCodeService;
    private final JwtService jwtService;

    public AuthServiceImpl(UserMapper userMapper, SmsCodeService smsCodeService, JwtService jwtService) {
        this.userMapper = userMapper;
        this.smsCodeService = smsCodeService;
        this.jwtService = jwtService;
    }

    @Override
    public void sendCode(String phone) {
        smsCodeService.sendCode(phone);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        if (!smsCodeService.verify(request.phone(), request.code())) {
            throw new InvalidSmsCodeException("验证码错误或已过期");
        }

        User user = userMapper.findByPhone(request.phone());
        if (user == null) {
            user = new User();
            user.setPhone(request.phone());
            user.setRoles(List.of("user"));
            userMapper.insert(user);
        }

        List<String> roles = user.getRoles() != null ? user.getRoles() : List.of("user");
        String token = jwtService.generateToken(user.getId(), roles);
        return new LoginResponse(token, user.getId());
    }
}
