package com.pethelp.picture.storage;

import java.time.Duration;

public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    String signedUrl(String key, Duration ttl);

    void delete(String key);
}
