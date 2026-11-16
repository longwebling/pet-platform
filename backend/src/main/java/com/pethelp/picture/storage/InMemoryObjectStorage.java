package com.pethelp.picture.storage;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content, String contentType) {
        store.put(key, content);
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        return "memory://" + key;
    }
}
