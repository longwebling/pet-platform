package com.pethelp.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DevSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(DevSmsSender.class);

    @Override
    public void send(String phone, String code) {
        log.warn("[DEV SMS] phone={} code={}", phone, code);
    }
}
