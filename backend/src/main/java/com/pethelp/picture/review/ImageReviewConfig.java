package com.pethelp.picture.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageReviewConfig {

    @Bean
    @ConditionalOnMissingBean(ImageReviewService.class)
    public ImageReviewService autoApproveImageReviewService() {
        return new AutoApproveImageReviewService();
    }
}
