package com.pethelp.auth.sms;

public interface SmsCodeService {

    void sendCode(String phone);

    boolean verify(String phone, String code);
}
