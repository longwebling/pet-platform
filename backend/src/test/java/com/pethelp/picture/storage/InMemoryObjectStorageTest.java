package com.pethelp.picture.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryObjectStorageTest {

    @Test
    void put_thenSignedUrl_returnsMemoryUrl() {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();

        storage.put("pets/1/a.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(storage.signedUrl("pets/1/a.jpg", Duration.ofHours(1)))
                .isEqualTo("memory://pets/1/a.jpg");
    }
}
