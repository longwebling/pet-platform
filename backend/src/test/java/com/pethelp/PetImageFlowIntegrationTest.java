package com.pethelp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PetImageFlowIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void uploadImage_returnsApprovedId() throws Exception {
        String token = loginAndGetToken("13900139031");
        long petId = createPet(token);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);

        upload(token, petId, file)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.signedUrl").value(nullValue()));
    }

    @Test
    void listImages_draftHidesThenActiveShows() throws Exception {
        String token = loginAndGetToken("13900139032");
        long petId = createPet(token);

        upload(token, petId, new MockMultipartFile("file", "a.png", "image/png", PNG))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/pets/" + petId + "/images"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/pets/" + petId + "/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].signedUrl", startsWith("memory://")));
    }

    @Test
    void uploadImage_notOwner_403() throws Exception {
        String tokenA = loginAndGetToken("13900139033");
        long petId = createPet(tokenA);

        String tokenB = loginAndGetToken("13900139034");

        upload(tokenB, petId, new MockMultipartFile("file", "a.png", "image/png", PNG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void uploadImage_invalidFormat_400() throws Exception {
        String token = loginAndGetToken("13900139035");
        long petId = createPet(token);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "hello".getBytes());

        upload(token, petId, file)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void uploadImage_missingPet_404() throws Exception {
        String token = loginAndGetToken("13900139036");

        upload(token, 999999L, new MockMultipartFile("file", "a.png", "image/png", PNG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void uploadImage_missingFilePart_400() throws Exception {
        String token = loginAndGetToken("13900139037");
        long petId = createPet(token);

        mockMvc.perform(multipart("/api/v1/pets/" + petId + "/images")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void uploadImage_deletedPet_404() throws Exception {
        String token = loginAndGetToken("13900139038");
        long petId = createPet(token);

        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pets/" + petId + "/delete")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        upload(token, petId, new MockMultipartFile("file", "a.png", "image/png", PNG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private ResultActions upload(String token, long petId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/pets/" + petId + "/images")
                .file(file)
                .header("Authorization", "Bearer " + token));
    }

    private long createPet(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"species\":\"CAT\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private String loginAndGetToken(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isOk());

        String code = redis.opsForValue().get("sms:code:" + phone);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
