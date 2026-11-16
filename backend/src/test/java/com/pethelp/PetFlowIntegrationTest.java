package com.pethelp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PetFlowIntegrationTest {

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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void publicDetail_onlyActiveVisible() throws Exception {
        String phone = "13900138010";
        String token = loginAndGetToken(phone);

        MvcResult createResult = mockMvc.perform(post("/api/v1/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"species\":\"CAT\",\"breed\":\"中华田园猫\",\"lng\":116.397,\"lat\":39.908,\"city\":\"北京\",\"district\":\"朝阳区\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long petId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // DRAFT 不公开
        mockMvc.perform(get("/api/v1/pets/" + petId))
                .andExpect(status().isNotFound());

        // 发布 → ACTIVE
        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        MvcResult detail = mockMvc.perform(get("/api/v1/pets/" + petId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.lng").doesNotExist())
                .andExpect(jsonPath("$.data.lat").doesNotExist())
                .andReturn();

        String geoHash = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("geoHash").asText();
        assertThat(geoHash).hasSize(6);
    }

    @Test
    void createPet_returnsDraftWithFixedFields() throws Exception {
        String phone = "13900138011";
        String token = loginAndGetToken(phone);

        MvcResult createResult = mockMvc.perform(post("/api/v1/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"species\":\"DOG\",\"breed\":\"柯基\",\"age\":\"3岁\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sourceType").value("OWNED"))
                .andReturn();

        long petId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT source_type, ownership_status, authorization_status, publisher_id, owner_id, current_custodian_id FROM pets WHERE id = ?",
                petId);
        assertThat(row.get("source_type")).isEqualTo("OWNED");
        assertThat(row.get("ownership_status")).isEqualTo("OWNED");
        assertThat(row.get("authorization_status")).isEqualTo("SELF");
        assertThat(row.get("publisher_id")).isEqualTo(row.get("owner_id"));
        assertThat(row.get("owner_id")).isEqualTo(row.get("current_custodian_id"));
    }

    @Test
    void createPet_withLocation_storesGeometryAndGeoHash() throws Exception {
        String phone = "13900138012";
        String token = loginAndGetToken(phone);

        MvcResult createResult = mockMvc.perform(post("/api/v1/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"species\":\"DOG\",\"lng\":116.397,\"lat\":39.908}"))
                .andExpect(status().isOk())
                .andReturn();

        long petId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT (location IS NOT NULL) AS has_location, geo_hash FROM pets WHERE id = ?", petId);
        assertThat(row.get("has_location")).isEqualTo(true);
        assertThat((String) row.get("geo_hash")).hasSize(6);
    }

    @Test
    void stateTransitions_guardInvalidTransitions() throws Exception {
        String phone = "13900138013";
        String token = loginAndGetToken(phone);
        long petId = createPet(token);

        // 归档 DRAFT → 409
        mockMvc.perform(post("/api/v1/pets/" + petId + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 发布 → ACTIVE
        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 重复发布 → 409
        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 归档 → ARCHIVED
        mockMvc.perform(post("/api/v1/pets/" + petId + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        // 归档后公开 GET → 404
        mockMvc.perform(get("/api/v1/pets/" + petId))
                .andExpect(status().isNotFound());

        // 第二个宠物：删除 → DELETED 且公开 GET → 404
        long petId2 = createPet(token);
        mockMvc.perform(post("/api/v1/pets/" + petId2 + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pets/" + petId2 + "/delete")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/v1/pets/" + petId2))
                .andExpect(status().isNotFound());
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
