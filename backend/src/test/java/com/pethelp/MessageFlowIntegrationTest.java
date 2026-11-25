package com.pethelp;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MessageFlowIntegrationTest {

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

    private record Seed(long conversationId, long postId) {}

    @Test
    void sendMessage_andListMessages_ok() throws Exception {
        Credentials owner = login("13900139000");
        Credentials adopter = login("13900139001");
        Seed seed = seedConversation(owner, adopter);

        mockMvc.perform(post("/api/v1/conversations/" + seed.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + adopter.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "你好，想了解一下交接时间"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value("你好，想了解一下交接时间"))
                .andExpect(jsonPath("$.data.senderId").value(adopter.userId()));

        mockMvc.perform(get("/api/v1/conversations/" + seed.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + adopter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").value("你好，想了解一下交接时间"));
    }

    @Test
    void listMessages_cursorPagination() throws Exception {
        Credentials owner = login("13900139010");
        Credentials adopter = login("13900139011");
        Seed seed = seedConversation(owner, adopter);

        for (int i = 1; i <= 25; i++) {
            jdbcTemplate.update("INSERT INTO messages (conversation_id, sender_id, content) VALUES (?, ?, ?)",
                    seed.conversationId(), adopter.userId(), "msg-" + i);
        }

        List<Long> allIds = jdbcTemplate.queryForList(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id", Long.class, seed.conversationId());
        assertThat(allIds).hasSize(25);

        List<Long> latest = listMessageIds(adopter.token(), seed.conversationId(), "?limit=10");
        assertThat(latest).containsExactlyElementsOf(allIds.subList(15, 25));

        List<Long> older = listMessageIds(adopter.token(), seed.conversationId(),
                "?limit=10&before_id=" + allIds.get(15));
        assertThat(older).containsExactlyElementsOf(allIds.subList(5, 15));
    }

    @Test
    void sendMessage_notParticipant_403() throws Exception {
        Credentials owner = login("13900139020");
        Credentials adopter = login("13900139021");
        Credentials stranger = login("13900139022");
        Seed seed = seedConversation(owner, adopter);

        mockMvc.perform(post("/api/v1/conversations/" + seed.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hi"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void sendMessage_afterPostCompleted_ok() throws Exception {
        Credentials owner = login("13900139030");
        Credentials adopter = login("13900139031");
        Seed seed = seedConversation(owner, adopter);

        jdbcTemplate.update("UPDATE pet_posts SET status = 'COMPLETED' WHERE id = ?", seed.postId());

        mockMvc.perform(post("/api/v1/conversations/" + seed.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + adopter.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "交接后还能联系吗"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void listConversations_returnsUnreadSummary() throws Exception {
        Credentials owner = login("13900139040");
        Credentials adopter = login("13900139041");
        Credentials stranger = login("13900139042");
        Seed seed = seedConversation(owner, adopter);

        mockMvc.perform(post("/api/v1/conversations/" + seed.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + adopter.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "hi"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(seed.conversationId()))
                .andExpect(jsonPath("$.data[0].peer.id").value(adopter.userId()))
                .andExpect(jsonPath("$.data[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data[0].firstUnreadMessageId").isNumber())
                .andExpect(jsonPath("$.data[0].lastMessage.content").value("hi"));

        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private Seed seedConversation(Credentials owner, Credentials adopter) throws Exception {
        long petId = createPet(owner.token());
        publish(owner.token(), petId);
        long postId = createAdoptionPost(owner.token(), petId);

        Long conversationId = jdbcTemplate.queryForObject(
                "INSERT INTO conversations (post_id) VALUES (?) RETURNING id", Long.class, postId);
        jdbcTemplate.update("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)",
                conversationId, owner.userId());
        jdbcTemplate.update("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)",
                conversationId, adopter.userId());
        return new Seed(conversationId, postId);
    }

    private List<Long> listMessageIds(String token, long conversationId, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        List<Long> ids = new ArrayList<>();
        for (JsonNode node : data) {
            ids.add(node.path("id").asLong());
        }
        return ids;
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

    private long createAdoptionPost(String token, long petId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pet-posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"petId\":" + petId + ",\"postType\":\"ADOPTION\",\"title\":\"寻领养\",\"description\":\"可爱猫咪\"}"))
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
