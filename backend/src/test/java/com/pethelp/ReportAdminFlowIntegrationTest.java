package com.pethelp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethelp.auth.token.JwtService;
import com.pethelp.user.entity.User;
import com.pethelp.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReportAdminFlowIntegrationTest {

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

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserMapper userMapper;
    @Autowired JwtService jwtService;

    private record Credentials(String token, long userId) {}

    @Test
    void fullReportAdminFlow() throws Exception {
        Credentials user = createUser("13900138300", "user");
        Credentials ops = createUser("13900138301", "ops");

        long petId = createPet(user.token());
        publish(user.token(), petId);
        long postId = createPost(user.token(), petId);

        mockMvc.perform(get("/api/v1/admin/posts").header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/v1/admin/posts/" + postId + "/reject")
                        .header("Authorization", "Bearer " + ops.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"违规内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/admin/posts/" + postId + "/verify")
                        .header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void imageReviewFlow() throws Exception {
        Credentials user = createUser("13900138310", "user");
        Credentials ops = createUser("13900138311", "ops");

        long petId = createPet(user.token());
        long imageId = uploadImage(user.token(), petId);

        mockMvc.perform(get("/api/v1/admin/images").header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/v1/admin/images/" + imageId + "/reject")
                        .header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"));

        mockMvc.perform(post("/api/v1/admin/images/" + imageId + "/approve")
                        .header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));
    }

    @Test
    void reportFlow() throws Exception {
        Credentials user = createUser("13900138320", "user");
        Credentials ops = createUser("13900138321", "ops");

        long petId = createPet(user.token());
        publish(user.token(), petId);
        long postId = createPost(user.token(), petId);

        MvcResult created = mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"垃圾信息\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        long reportId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/v1/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + ops.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));
    }

    @Test
    void banFlow_loginBlocked() throws Exception {
        String phone = "13900138330";
        Credentials user = createUser(phone, "user");
        Credentials admin = createUser("13900138331", "admin");

        mockMvc.perform(post("/api/v1/admin/users/" + user.userId() + "/ban")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BANNED"));

        mockMvc.perform(post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isOk());
        String code = redis.opsForValue().get("sms:code:" + phone);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void exactLocationOpsFlow() throws Exception {
        Credentials user = createUser("13900138340", "user");
        Credentials ops = createUser("13900138341", "ops");

        long petId = createPetWithLocation(user.token(), 116.397, 39.908);

        mockMvc.perform(post("/api/v1/admin/pets/" + petId + "/exact-location")
                        .header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lng").value(116.397))
                .andExpect(jsonPath("$.data.lat").value(39.908));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address_visibility_logs WHERE pet_id = ? AND viewer_id = ? AND address_type = 'EXACT'",
                Integer.class, petId, ops.userId());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void idor_plainUserCallsAdmin_403() throws Exception {
        String plainToken = createUser("13900138360", "user").token();

        mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/v1/admin/posts/1/take-down")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/v1/admin/users/1/ban")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private Credentials createUser(String phone, String... roles) {
        User u = new User();
        u.setPhone(phone);
        u.setRoles(List.of(roles));
        userMapper.insert(u);
        String token = jwtService.generateToken(u.getId(), List.of(roles));
        return new Credentials(token, u.getId());
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

    private long createPost(String token, long petId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pet-posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":" + petId + ",\"postType\":\"FINDING\",\"title\":\"寻宠\",\"description\":\"走失猫咪\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long uploadImage(String token, long petId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);
        MvcResult result = mockMvc.perform(multipart("/api/v1/pets/" + petId + "/images")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }
}
