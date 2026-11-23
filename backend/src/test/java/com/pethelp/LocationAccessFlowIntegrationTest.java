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
class LocationAccessFlowIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private record Credentials(String token, long userId) {}

    @Test
    void grantAndView_writesAudit() throws Exception {
        Credentials owner = login("13900138100");
        Credentials grantee = login("13900138101");

        long petId = createPetWithLocation(owner.token(), 116.397, 39.908);
        publish(owner.token(), petId);

        grant(owner.token(), petId, grantee.userId());

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + grantee.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.lng").value(116.397))
                .andExpect(jsonPath("$.data.lat").value(39.908));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address_visibility_logs WHERE pet_id = ? AND viewer_id = ? AND address_type = 'EXACT'",
                Integer.class, petId, grantee.userId());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void unauthorizedView_403_noAudit() throws Exception {
        Credentials owner = login("13900138110");
        Credentials intruder = login("13900138111");

        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);
        publish(owner.token(), petId);

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + intruder.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address_visibility_logs WHERE pet_id = ?", Integer.class, petId);
        assertThat(auditCount).isZero();
    }

    @Test
    void ownerView_noAudit() throws Exception {
        Credentials owner = login("13900138120");

        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lng").value(116.4));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address_visibility_logs WHERE pet_id = ?", Integer.class, petId);
        assertThat(auditCount).isZero();
    }

    @Test
    void revoke_thenView_403() throws Exception {
        Credentials owner = login("13900138130");
        Credentials grantee = login("13900138131");

        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);
        publish(owner.token(), petId);

        long grantId = grant(owner.token(), petId, grantee.userId());

        mockMvc.perform(post("/api/v1/location-grants/" + grantId + "/revoke")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + grantee.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void grant_notOwner_403() throws Exception {
        Credentials owner = login("13900138140");
        Credentials other = login("13900138141");
        Credentials grantee = login("13900138142");

        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);

        mockMvc.perform(post("/api/v1/pets/" + petId + "/location-grants")
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("granteeId", grantee.userId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void grant_self_400() throws Exception {
        Credentials owner = login("13900138150");
        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);

        mockMvc.perform(post("/api/v1/pets/" + petId + "/location-grants")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("granteeId", owner.userId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void granteeNotFound_404() throws Exception {
        Credentials owner = login("13900138160");
        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);

        mockMvc.perform(post("/api/v1/pets/" + petId + "/location-grants")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("granteeId", 999999L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void duplicateGrant_409_singleActiveRow() throws Exception {
        Credentials owner = login("13900138170");
        Credentials grantee = login("13900138171");

        long petId = createPetWithLocation(owner.token(), 116.4, 39.9);
        publish(owner.token(), petId);

        grant(owner.token(), petId, grantee.userId());

        mockMvc.perform(post("/api/v1/pets/" + petId + "/location-grants")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("granteeId", grantee.userId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        Integer active = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM location_access_grants WHERE pet_id = ? AND grantee_id = ? AND status = 'ACTIVE'",
                Integer.class, petId, grantee.userId());
        assertThat(active).isEqualTo(1);
    }

    @Test
    void noLocation_ownerView_404() throws Exception {
        Credentials owner = login("13900138180");
        long petId = createPet(owner.token());

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void granteeView_noLocation_404_noAudit() throws Exception {
        Credentials owner = login("13900138190");
        Credentials grantee = login("13900138191");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        grant(owner.token(), petId, grantee.userId());

        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + grantee.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address_visibility_logs WHERE pet_id = ?", Integer.class, petId);
        assertThat(auditCount).isZero();
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

    private long createPetWithLocation(String token, double lng, double lat) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"species\":\"DOG\",\"lng\":" + lng + ",\"lat\":" + lat + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void publish(String token, long petId) throws Exception {
        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private long grant(String token, long petId, long granteeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pets/" + petId + "/location-grants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("granteeId", granteeId))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private Credentials login(String phone) throws Exception {
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

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
        long userId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("userId").asLong();
        return new Credentials(token, userId);
    }
}
