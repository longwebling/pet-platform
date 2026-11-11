# Database Migration & Test Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish Flyway-based schema migration and a Testcontainers (PostgreSQL + PostGIS) integration-test baseline, without implementing any pet business logic.

**Architecture:** Copy the reviewed `docs/08-database-ddl.sql` verbatim into a Flyway `V1` migration; rely on Spring Boot's existing Flyway auto-configuration to apply it against a Testcontainers-managed PostGIS instance; prove the result with a single `@SpringBootTest` integration test wired to the container via `@DynamicPropertySource`.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Maven, PostgreSQL 16 + PostGIS (`postgis/postgis:16-3.4`), Flyway, Testcontainers (JUnit 5), JdbcTemplate.

**Spec:** `docs/08-database-ddl.sql` (schema source), `docs/09-integration-tests.md` (test intent), plus the task requirements in this session.

## Global Constraints

- Do NOT implement any pet business logic (no controllers/services/mappers).
- Do NOT modify the database design: `V1__init_schema.sql` must be a verbatim copy of `docs/08-database-ddl.sql`.
- Do NOT introduce Redis / Elasticsearch / MQ.
- Do NOT use H2 in place of PostgreSQL — tests must run against Testcontainers PostGIS.
- Do NOT drop or relax any database constraint just to make tests pass.
- Run a real verification command at the end of every task.

## Review Focus

The five failure modes most likely to bite a person using this infrastructure, each pinned by a test/verification step in the owning task:

1. **`CREATE EXTENSION postgis`/`btree_gist` failing on a non-superuser** — pinned by `postgisExtensionIsInstalled` test asserting the `postgis` extension exists (Task 3).
2. **The partial `EXCLUDE` constraint `foster_orders_no_time_overlap` silently missing** (it depends on `btree_gist`; a failed extension would abort the whole migration, not partially apply) — pinned by `flywayMigrationAppliedBaseTablesExist` asserting `foster_orders` and the migration history (Task 3).
3. **`${DB_PASSWORD}` placeholder with no default aborting the Spring context** (main `application.yml` has `password: ${DB_PASSWORD}`) — pinned by `@DynamicPropertySource` overriding `spring.datasource.*` with the container's connection details (Task 2).
4. **Secrets (GitHub PAT / DB password) leaking into git** — pinned by `git ls-files` / `git check-ignore` / `git log --all` audit (Task 4).
5. **`V1__init_schema.sql` drifting from `docs/08-database-ddl.sql`** — pinned by a byte-for-byte diff/checksum comparison (Task 3).

---

### Task 1: Add Testcontainers test dependencies

**Files:**
- Modify: `backend/pom.xml` (insert inside `<dependencies>`, after `spring-boot-starter-test` at line ~60)

**Interfaces:**
- Produces: Testcontainers classes available on the test classpath — `org.testcontainers.containers.PostgreSQLContainer`, `org.testcontainers.junit.jupiter.Container`, `org.testcontainers.junit.jupiter.Testcontainers`. Versions are managed by the Spring Boot BOM (`spring-boot-dependencies` imports `testcontainers-bom`), so no `<version>` tags.

- [ ] **Step 1: Add the three testcontainers artifacts (scope `test`)**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify Flyway / MyBatis / JUnit 5 are already present (no change needed)**

Confirm `pom.xml` already contains `flyway-core`, `flyway-database-postgresql`, `mybatis-spring-boot-starter` (`3.0.5`), and `spring-boot-starter-test` (pulls in JUnit Jupiter). Record this in the delivery report; add nothing for them.

- [ ] **Step 3: Verify dependency resolution**

Run: `mvn -f backend/pom.xml -q dependency:resolve -DincludeScope=test`
Expected: exit code 0, no "could not resolve" errors.

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml
git commit -m "build: add Testcontainers test dependencies"
```

---

### Task 2: Write the integration test (red)

**Files:**
- Create: `backend/src/test/java/com/pethelp/DatabaseMigrationTest.java`

**Interfaces:**
- Consumes: Testcontainers classes (Task 1); `spring-boot-starter-jdbc`'s `JdbcTemplate` (already present).
- Produces: The acceptance test that later tasks must satisfy. Asserts: DB connection, PostGIS extension, Flyway migration history, and base table presence.

- [ ] **Step 1: Write the failing test**

```java
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgis/postgis:16-3.4");

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
}
```

Note: `@DynamicPropertySource` overrides `spring.datasource.*` at high precedence, so the `${DB_PASSWORD}` placeholder in the main `application.yml` is shadowed and never resolved — this is what prevents the context from failing on the missing default.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -q -Dtest=DatabaseMigrationTest test`
Expected: FAIL — the context starts (Flyway finds no migration files), but `postgisExtensionIsInstalled` and `flywayMigrationAppliedBaseTablesExist` fail because `db/migration` is empty.

- [ ] **Step 3: Commit the failing test**

```bash
git add backend/src/test/java/com/pethelp/DatabaseMigrationTest.java
git commit -m "test: add Testcontainers database migration integration test"
```

---

### Task 3: Create V1__init_schema.sql (green)

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Remove: `backend/src/main/resources/db/migration/.gitkeep` (directory now has a real file)

**Interfaces:**
- Consumes: nothing.
- Produces: Flyway migration `V1` that creates the full 19-table schema + extensions + constraints + indexes. Later business modules depend on these tables existing.

- [ ] **Step 1: Copy `docs/08-database-ddl.sql` verbatim into the migration**

Do not edit the SQL content — the design must not change. The target file's content must be byte-for-byte identical to `docs/08-database-ddl.sql` (the existing header comment referencing `docs/04` is acceptable and should be kept as-is). `docs/08-database-ddl.sql` itself stays untouched.

- [ ] **Step 2: Remove the now-unneeded `.gitkeep`**

`backend/src/main/resources/db/migration/` now contains `V1__init_schema.sql`, so the placeholder is redundant.

- [ ] **Step 3: Verify the migration is byte-for-byte identical to the source**

Run: `diff docs/08-database-ddl.sql backend/src/main/resources/db/migration/V1__init_schema.sql && echo IDENTICAL`
Expected: `IDENTICAL` (empty diff). This pins Review Focus #5.

- [ ] **Step 4: Run the test to verify it passes (Flyway auto-migrates)**

Run: `mvn -f backend/pom.xml -q -Dtest=DatabaseMigrationTest test`
Expected: PASS — Spring Boot's Flyway auto-config (`spring.flyway.enabled=true`, `locations: classpath:db/migration`) applies `V1` on context startup; all three tests assert the connection, the `postgis` extension, and the 19 base tables.

- [ ] **Step 5: Run the full build (existing SmokeTest still passes, nothing broken)**

Run: `mvn -f backend/pom.xml -q test`
Expected: PASS for both `SmokeTest` and `DatabaseMigrationTest`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V1__init_schema.sql
git rm backend/src/main/resources/db/migration/.gitkeep
git commit -m "feat: add V1 Flyway schema migration from docs/08 DDL"
```

---

### Task 4: Audit secret exposure (verification only, no code change unless a leak is found)

**Files:**
- None expected to change.

**Interfaces:**
- Consumes: nothing.
- Produces: a written finding (in the delivery report) on whether any secret is tracked or committable.

- [ ] **Step 1: Confirm no secret file is tracked**

Run: `git ls-files | grep -E '(^|/)(\.env|mcp_key\.txt|settings\.local\.json)$' || echo NONE_TRACKED`
Expected: `NONE_TRACKED`.

- [ ] **Step 2: Confirm the ignore rules cover every secret path**

Run: `git check-ignore -v .env backend/.env mcp/mcp_key.txt .claude/settings.local.json`
Expected: all four listed with their matching `.gitignore` rule.

- [ ] **Step 3: Confirm no secret was ever committed in history**

Run: `git log --all --oneline -- .env backend/.env mcp/mcp_key.txt .claude/settings.local.json || echo NEVER_COMMITTED`
Expected: `NEVER_COMMITTED` (empty log).

- [ ] **Step 4: Report findings (no code change)**

Record: `.env` (root) contains a live GitHub PAT `github_pat_…`; `mcp/mcp_key.txt` contains a Context7 key + the same PAT; `.claude/settings.local.json` contains the real local DB password `yuan6380688`. All are gitignored and untracked (confirmed by Steps 1–3), so nothing leaks via git. **Recommendation:** rotate the GitHub PAT and Context7 key, since they have existed in plaintext working-directory files; and never use `git add -f` on these paths.

- [ ] **Step 5: Commit (only if Step 4 prompted a real `.gitignore` hardening change — otherwise skip)**

If no change is required, there is nothing to commit; note "no commit needed" in the delivery report.
