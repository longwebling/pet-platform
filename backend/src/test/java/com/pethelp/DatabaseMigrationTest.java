package com.pethelp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void databaseConnectionWorks() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }

    @Test
    void postgisExtensionIsInstalled() {
        List<String> extensions = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'postgis'", String.class);
        assertThat(extensions).contains("postgis");
    }

    @Test
    void flywayMigrationAppliedBaseTablesExist() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains(
                "users", "pets", "pet_images", "pet_posts", "pet_authorizations",
                "pet_ownership_history", "adoption_applications", "foster_applications",
                "adoption_arrangements", "foster_orders", "handover_records",
                "conversations", "conversation_participants", "messages",
                "location_access_grants", "notifications", "reports",
                "audit_logs", "address_visibility_logs");
    }

    @Test
    void v2MigrationAddsPetsDeletedAtNullByDefault() {
        Integer v2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class);
        assertThat(v2).isEqualTo(1);

        Map<String, Object> column = jdbcTemplate.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'pets' AND column_name = 'deleted_at'");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("column_default")).isNull();

        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (phone) VALUES (?) RETURNING id", Long.class, "13800000001");
        Long petId = jdbcTemplate.queryForObject(
                "INSERT INTO pets (publisher_id, source_type, ownership_status, authorization_status, species) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, userId, "OWNED", "OWNED", "SELF", "CAT");
        Timestamp deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM pets WHERE id = ?", Timestamp.class, petId);
        assertThat(deletedAt).isNull();
    }
}
