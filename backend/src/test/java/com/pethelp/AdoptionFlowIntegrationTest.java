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
class AdoptionFlowIntegrationTest {

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

    private record ApproveData(long arrangementId, String postStatus, int rejectedOthers) {}

    @Test
    void fullAdoptionFlow_updatesOwnership() throws Exception {
        Credentials owner = login("13900138200");
        Credentials adopter = login("13900138201");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);

        long applicationId = submitApplication(adopter.token(), postId);

        ApproveData approved = approve(owner.token(), applicationId);
        assertThat(approved.arrangementId()).isPositive();
        assertThat(approved.postStatus()).isEqualTo("RESERVED");
        assertThat(approved.rejectedOthers()).isZero();

        // owner (custodian) confirms first, then adopter completes
        mockMvc.perform(post("/api/v1/adoption-arrangements/" + approved.arrangementId() + "/confirm-handover")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_HANDOVER"))
                .andExpect(jsonPath("$.data.fromConfirmed").value(true))
                .andExpect(jsonPath("$.data.toConfirmed").value(false));

        mockMvc.perform(post("/api/v1/adoption-arrangements/" + approved.arrangementId() + "/confirm-handover")
                        .header("Authorization", "Bearer " + adopter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.fromConfirmed").value(true))
                .andExpect(jsonPath("$.data.toConfirmed").value(true));

        String postStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM pet_posts WHERE id = ?", String.class, postId);
        assertThat(postStatus).isEqualTo("COMPLETED");

        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM pets WHERE id = ?", Long.class, petId);
        Long custodianId = jdbcTemplate.queryForObject(
                "SELECT current_custodian_id FROM pets WHERE id = ?", Long.class, petId);
        String ownershipStatus = jdbcTemplate.queryForObject(
                "SELECT ownership_status FROM pets WHERE id = ?", String.class, petId);
        assertThat(ownerId).isEqualTo(adopter.userId());
        assertThat(custodianId).isEqualTo(adopter.userId());
        assertThat(ownershipStatus).isEqualTo("OWNED");

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pet_ownership_history WHERE pet_id = ? AND change_type = 'ADOPTION' "
                        + "AND from_owner_id = ? AND to_owner_id = ? AND arrangement_id = ?",
                Integer.class, petId, owner.userId(), adopter.userId(), approved.arrangementId());
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    void approve_rejectsOtherPendingApplications() throws Exception {
        Credentials owner = login("13900138210");
        Credentials adopter1 = login("13900138211");
        Credentials adopter2 = login("13900138212");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);

        long app1 = submitApplication(adopter1.token(), postId);
        long app2 = submitApplication(adopter2.token(), postId);

        ApproveData approved = approve(owner.token(), app1);
        assertThat(approved.postStatus()).isEqualTo("RESERVED");
        assertThat(approved.rejectedOthers()).isEqualTo(1);

        String app1Status = jdbcTemplate.queryForObject(
                "SELECT status FROM adoption_applications WHERE id = ?", String.class, app1);
        assertThat(app1Status).isEqualTo("APPROVED");

        String app2Status = jdbcTemplate.queryForObject(
                "SELECT status FROM adoption_applications WHERE id = ?", String.class, app2);
        String app2Reason = jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM adoption_applications WHERE id = ?", String.class, app2);
        assertThat(app2Status).isEqualTo("REJECTED");
        assertThat(app2Reason).isEqualTo("该宠物已被其他申请人匹配");
    }

    @Test
    void duplicateApplication_conflict_singlePending() throws Exception {
        Credentials owner = login("13900138220");
        Credentials adopter = login("13900138221");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);

        submitApplication(adopter.token(), postId);

        mockMvc.perform(post("/api/v1/adoption-posts/" + postId + "/applications")
                        .header("Authorization", "Bearer " + adopter.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "again"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM adoption_applications WHERE post_id = ? AND adopter_id = ? AND status = 'PENDING'",
                Integer.class, postId, adopter.userId());
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void approve_notPublisher_403() throws Exception {
        Credentials owner = login("13900138230");
        Credentials adopter = login("13900138231");
        Credentials stranger = login("13900138232");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);
        long applicationId = submitApplication(adopter.token(), postId);

        mockMvc.perform(post("/api/v1/adoption-applications/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void confirmHandover_notParticipant_403() throws Exception {
        Credentials owner = login("13900138240");
        Credentials adopter = login("13900138241");
        Credentials stranger = login("13900138242");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);
        long applicationId = submitApplication(adopter.token(), postId);
        long arrangementId = approve(owner.token(), applicationId).arrangementId();

        mockMvc.perform(post("/api/v1/adoption-arrangements/" + arrangementId + "/confirm-handover")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void confirmHandover_repeat_noop() throws Exception {
        Credentials owner = login("13900138250");
        Credentials adopter = login("13900138251");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);
        long applicationId = submitApplication(adopter.token(), postId);
        long arrangementId = approve(owner.token(), applicationId).arrangementId();

        mockMvc.perform(post("/api/v1/adoption-arrangements/" + arrangementId + "/confirm-handover")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_HANDOVER"))
                .andExpect(jsonPath("$.data.fromConfirmed").value(true))
                .andExpect(jsonPath("$.data.toConfirmed").value(false));

        // same party repeats -> 200 no-op, confirmation bits unchanged
        mockMvc.perform(post("/api/v1/adoption-arrangements/" + arrangementId + "/confirm-handover")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_HANDOVER"))
                .andExpect(jsonPath("$.data.fromConfirmed").value(true))
                .andExpect(jsonPath("$.data.toConfirmed").value(false));

        Boolean fromConfirmed = jdbcTemplate.queryForObject(
                "SELECT confirmed_by_from FROM handover_records WHERE biz_type = 'ADOPTION' AND biz_id = ? AND event = 'HANDOVER'",
                Boolean.class, arrangementId);
        Boolean toConfirmed = jdbcTemplate.queryForObject(
                "SELECT confirmed_by_to FROM handover_records WHERE biz_type = 'ADOPTION' AND biz_id = ? AND event = 'HANDOVER'",
                Boolean.class, arrangementId);
        assertThat(fromConfirmed).isTrue();
        assertThat(toConfirmed).isFalse();

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pet_ownership_history WHERE pet_id = ?", Integer.class, petId);
        assertThat(historyCount).isZero();
    }

    @Test
    void reAdoption_samePetId_notBlocked() throws Exception {
        Credentials owner = login("13900138260");
        Credentials adopter1 = login("13900138261");
        Credentials adopter2 = login("13900138262");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);

        long post1 = createAdoptionPost(owner.token(), petId);
        long app1 = submitApplication(adopter1.token(), post1);
        long arrangement1 = approve(owner.token(), app1).arrangementId();
        confirmHandover(owner.token(), arrangement1);
        confirmHandover(adopter1.token(), arrangement1);

        String firstStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM adoption_arrangements WHERE id = ?", String.class, arrangement1);
        assertThat(firstStatus).isEqualTo("COMPLETED");

        // adopter1 is now owner; creates a NEW adoption post on the SAME pet
        long post2 = createAdoptionPost(adopter1.token(), petId);
        long app2 = submitApplication(adopter2.token(), post2);
        ApproveData approved = approve(adopter1.token(), app2);
        assertThat(approved.arrangementId()).isPositive();
        assertThat(approved.postStatus()).isEqualTo("RESERVED");
        assertThat(approved.arrangementId()).isNotEqualTo(arrangement1);

        String secondStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM adoption_arrangements WHERE id = ?", String.class, approved.arrangementId());
        assertThat(secondStatus).isEqualTo("PENDING_HANDOVER");
    }

    @Test
    void exactLocation_stillRequiresGrant_afterHandover() throws Exception {
        Credentials owner = login("13900138270");
        Credentials adopter = login("13900138271");

        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);
        long applicationId = submitApplication(adopter.token(), postId);
        long arrangementId = approve(owner.token(), applicationId).arrangementId();
        confirmHandover(owner.token(), arrangementId);
        confirmHandover(adopter.token(), arrangementId);

        // adopter becomes owner_id but is NOT publisher_id -> still needs an explicit grant
        mockMvc.perform(get("/api/v1/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + adopter.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private long createAdoptionPost(String token, long petId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pet-posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":" + petId + ",\"postType\":\"ADOPTION\",\"title\":\"寻领养\",\"description\":\"可爱猫咪\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long submitApplication(String token, long postId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/adoption-posts/" + postId + "/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "我想领养"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private ApproveData approve(String token, long applicationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/adoption-applications/" + applicationId + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new ApproveData(data.path("arrangementId").asLong(),
                data.path("postStatus").asText(),
                data.path("rejectedOthers").asInt());
    }

    private void confirmHandover(String token, long arrangementId) throws Exception {
        mockMvc.perform(post("/api/v1/adoption-arrangements/" + arrangementId + "/confirm-handover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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

    private void publish(String token, long petId) throws Exception {
        mockMvc.perform(post("/api/v1/pets/" + petId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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
