package com.pethelp.picture.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObjectStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "oss.enabled", havingValue = "false", matchIfMissing = true)
    public ObjectStorage inMemoryObjectStorage() {
        return new InMemoryObjectStorage();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "oss.enabled", havingValue = "true")
    public OSS ossClient(@Value("${oss.endpoint}") String endpoint,
                         @Value("${oss.access-key-id}") String accessKeyId,
                         @Value("${oss.access-key-secret}") String accessKeySecret) {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Bean
    @ConditionalOnProperty(name = "oss.enabled", havingValue = "true")
    public ObjectStorage ossObjectStorage(OSS ossClient, @Value("${oss.bucket}") String bucket) {
        return new OssObjectStorage(ossClient, bucket);
    }
}
