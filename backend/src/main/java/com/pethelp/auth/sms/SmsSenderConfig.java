package com.pethelp.auth.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsSenderConfig {

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender devSmsSender() {
        return new DevSmsSender();
    }
}
