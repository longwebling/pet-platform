package com.pethelp.picture.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssObjectStorageTest {

    @Test
    void put_delegatesToOssPutObject() {
        OSS oss = mock(OSS.class);
        OssObjectStorage storage = new OssObjectStorage(oss, "bucket");

        byte[] content = {1, 2, 3};
        storage.put("pets/1/a.jpg", content, "image/jpeg");

        verify(oss).putObject(eq("bucket"), eq("pets/1/a.jpg"),
                any(InputStream.class), any(ObjectMetadata.class));
    }

    @Test
    void signedUrl_usesOneHourTtl() throws Exception {
        OSS oss = mock(OSS.class);
        when(oss.generatePresignedUrl(eq("bucket"), eq("k"),
                any(Date.class), eq(HttpMethod.GET)))
                .thenReturn(new java.net.URL("https://oss.example/bucket/k"));
        OssObjectStorage storage = new OssObjectStorage(oss, "bucket");

        String url = storage.signedUrl("k", Duration.ofHours(1));

        assertThat(url).contains("oss.example");
        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(oss).generatePresignedUrl(eq("bucket"), eq("k"), captor.capture(), eq(HttpMethod.GET));
        long delta = captor.getValue().getTime() - System.currentTimeMillis();
        assertThat(delta).isBetween(3_500_000L, 3_650_000L);
    }

    @Test
    void delete_delegatesToOssDeleteObject() {
        OSS oss = mock(OSS.class);
        OssObjectStorage storage = new OssObjectStorage(oss, "bucket");

        storage.delete("pets/1/a.jpg");

        verify(oss).deleteObject(eq("bucket"), eq("pets/1/a.jpg"));
    }
}
