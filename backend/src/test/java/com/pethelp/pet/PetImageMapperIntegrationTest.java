package com.pethelp.pet;

import com.pethelp.pet.entity.PetImage;
import com.pethelp.pet.mapper.PetImageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PetImageMapperIntegrationTest {

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
    PetImageMapper petImageMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void insert_count_find_roundTrip() {
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (phone) VALUES (?) RETURNING id", Long.class, "13900139021");
        long petId = jdbcTemplate.queryForObject(
                "INSERT INTO pets (publisher_id, source_type, ownership_status, authorization_status, species, status) " +
                        "VALUES (?, 'OWNED', 'OWNED', 'SELF', 'CAT', 'DRAFT') RETURNING id", Long.class, userId);

        PetImage approved = image(petId, userId, "pets/1/a.jpg", "PHOTO", "APPROVED");
        PetImage rejected = image(petId, userId, "pets/1/b.jpg", "PHOTO", "REJECTED");
        petImageMapper.insert(approved);
        petImageMapper.insert(rejected);

        assertThat(approved.getId()).isNotNull();
        assertThat(petImageMapper.countByPetIdAndType(petId, "PHOTO")).isEqualTo(2);

        List<PetImage> list = petImageMapper.findByPetIdPublicApproved(petId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getFileKey()).isEqualTo("pets/1/a.jpg");
        assertThat(list.get(0).getReviewStatus()).isEqualTo("APPROVED");
    }

    private PetImage image(Long petId, Long uploaderId, String key, String type, String status) {
        PetImage img = new PetImage();
        img.setPetId(petId);
        img.setUploaderId(uploaderId);
        img.setFileKey(key);
        img.setFileType(type);
        img.setReviewStatus(status);
        return img;
    }
}
