package com.pethelp.picture.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Date;

public class OssObjectStorage implements ObjectStorage {

    private final OSS oss;
    private final String bucket;

    public OssObjectStorage(OSS oss, String bucket) {
        this.oss = oss;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        oss.putObject(bucket, key, new ByteArrayInputStream(content), metadata);
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        Date expiration = new Date(System.currentTimeMillis() + ttl.toMillis());
        return oss.generatePresignedUrl(bucket, key, expiration, HttpMethod.GET).toString();
    }

    @Override
    public void delete(String key) {
        oss.deleteObject(bucket, key);
    }
}
