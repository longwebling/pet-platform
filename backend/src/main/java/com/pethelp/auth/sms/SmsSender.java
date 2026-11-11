package com.pethelp.auth.sms;

public interface SmsSender {

    void send(String phone, String code);
}
