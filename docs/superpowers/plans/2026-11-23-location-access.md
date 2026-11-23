# 位置授权（Location Access Grant）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为宠物档案增加「精确位置授权」最小闭环：owner 授权指定用户查看精确坐标、被授权方查看（落访问审计）、owner 撤销授权。

**Architecture:** 全部在 `com.pethelp.pet` 模块（pet 子资源，同 PetImage 模式），复用已建好的 `location_access_grants` / `address_visibility_logs` 两张表；新增 V3 唯一索引迁移防并发重复授权；精确坐标用 `ST_X/ST_Y` 从 `pets.location` 提取，不进入 `Pet` 实体。

**Tech Stack:** Java 17、Spring Boot 3.5、MyBatis、PostgreSQL/PostGIS、Spring Security（JWT）、Mockito + AssertJ（单测）、Testcontainers（集成测试）。

**Spec:** `docs/superpowers/specs/2026-11-23-location-access-design.md`

## Global Constraints

- 统一响应 `ApiResponse { code, message, data }`，错误码：40000/40300/40400/40900。
- 所有写操作在 Service 层显式校验归属；客户端不传 `status`/`location_type`。
- `location_type` 恒 `EXACT`；`status` 仅 `ACTIVE`/`REVOKED`；`expires_at` 恒 NULL。
- 精确坐标只出现在 `GET /exact-location`，绝不进公开 `GET /pets/{id}` 或 `PetResponse`。
- 模块 `com.pethelp.pet` 内分层 controller / service(+impl) / dto / entity / mapper。
- 复用现有异常类，不新增异常类、不新增 Maven 依赖。

## Review Focus

1. 精确坐标不得泄漏进公开 `GET /pets/{id}`（新增 `findExactLocation` 后仍须成立）→ 集成测试断言公开详情 `lng`/`lat` 不存在。
2. 未授权用户查看精确位置必须 403 且不写审计 → 集成测试。
3. owner 本人查看不写审计，被授权方查看才写 → 集成测试用 `jdbcTemplate` 查 `address_visibility_logs` 条数。
4. 并发/重复 grant 只产生一条 ACTIVE 授权（唯一索引兜底）→ 集成测试重复 grant 得 409。
5. `granteeId` 非法：不存在=404、等于本人=400 → 单测 + 集成测试。

---

### Task 1: 数据库唯一索引迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__add_location_grant_unique_index.sql`

**Interfaces:**
- Produces: 唯一索引 `uq_location_grant_active`，后续 grant 的 `@Transactional` + 唯一约束捕获依赖它。

- [ ] **Step 1: 写迁移文件**

```sql
-- 同一宠物对同一被授权者，同时只允许一条 ACTIVE 授权
CREATE UNIQUE INDEX uq_location_grant_active
    ON location_access_grants (pet_id, grantee_id)
    WHERE status = 'ACTIVE';
```

- [ ] **Step 2: 运行现有全量测试验证迁移不破坏**

Run: `cd backend && mvn test`
Expected: 现有 67 个测试全绿（迁移由 Flyway 在 Testcontainers 启动时应用）。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/resources/db/migration/V3__add_location_grant_unique_index.sql
git commit -m "feat: add unique index on active location grants"
```

---

### Task 2: 实体、DTO、Mapper（数据访问层）

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/entity/LocationAccessGrant.java`
- Create: `backend/src/main/java/com/pethelp/pet/entity/AddressVisibilityLog.java`
- Create: `backend/src/main/java/com/pethelp/pet/dto/GrantLocationAccessRequest.java`
- Create: `backend/src/main/java/com/pethelp/pet/dto/LocationGrantResponse.java`
- Create: `backend/src/main/java/com/pethelp/pet/dto/ExactLocationResponse.java`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/LocationAccessGrantMapper.java`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/AddressVisibilityLogMapper.java`
- Create: `backend/src/main/resources/mapper/LocationAccessGrantMapper.xml`
- Create: `backend/src/main/resources/mapper/AddressVisibilityLogMapper.xml`
- Modify: `backend/src/main/java/com/pethelp/pet/mapper/PetMapper.java`（加 `findExactLocation`）
- Modify: `backend/src/main/resources/mapper/PetMapper.xml`（加 SQL）

**Interfaces:**
- Consumes: 无（本计划第一个 Java 任务）。
- Produces: 以下类型与签名，Task 3 Service 依赖。

```java
// entity
public class LocationAccessGrant {
    private Long id; private Long grantorId; private Long granteeId;
    private Long petId; private String locationType; private String status;
    // getters/setters
}
public class AddressVisibilityLog {
    private Long viewerId; private Long petId; private Long postId; private String addressType;
    // getters/setters
}

// dto
public record GrantLocationAccessRequest(Long granteeId) {}
public record LocationGrantResponse(Long id, Long petId, Long granteeId, String locationType, String status) {}
public record ExactLocationResponse(Double lng, Double lat) {}

// mapper
@Mapper public interface LocationAccessGrantMapper {
    int insert(LocationAccessGrant grant);
    LocationAccessGrant findById(@Param("id") Long id);
    LocationAccessGrant findActiveByGranteeAndPet(@Param("granteeId") Long granteeId, @Param("petId") Long petId);
    int revoke(@Param("id") Long id, @Param("grantorId") Long grantorId);
}
@Mapper public interface AddressVisibilityLogMapper {
    int insert(AddressVisibilityLog log);
}
// PetMapper 新增（import java.util.Map）
Map<String, Object> findExactLocation(@Param("id") Long id);
```

- [ ] **Step 1: 写两个 entity**（纯 POJO + getter/setter，字段如上；`AddressVisibilityLog` 无 `id` 字段）

- [ ] **Step 2: 写三个 DTO**（record，一行一个，字段与顺序如上）

- [ ] **Step 3: 写 `LocationAccessGrantMapper` 接口与 XML**

XML（namespace `com.pethelp.pet.mapper.LocationAccessGrantMapper`）：
- `insert`（`useGeneratedKeys="true" keyProperty="id"`）：列 `grantor_id, grantee_id, pet_id, location_type, status`。
- `findById`：`SELECT id, grantor_id, grantee_id, pet_id, location_type, status FROM location_access_grants WHERE id = #{id}`。
- `findActiveByGranteeAndPet`：同上加 `WHERE grantee_id = #{granteeId} AND pet_id = #{petId} AND status = 'ACTIVE' LIMIT 1`。
- `revoke`：`UPDATE location_access_grants SET status = 'REVOKED', revoked_at = now() WHERE id = #{id} AND grantor_id = #{grantorId} AND status = 'ACTIVE'`。

- [ ] **Step 4: 写 `AddressVisibilityLogMapper` 接口与 XML**

XML：`insert` 列 `viewer_id, pet_id, post_id, address_type`，值 `#{viewerId}, #{petId}, #{postId}, #{addressType}`。

- [ ] **Step 5: 修改 `PetMapper` 加 `findExactLocation` 与 XML**

XML：
```xml
<select id="findExactLocation" resultType="map">
    SELECT ST_X(location) AS lng, ST_Y(location) AS lat
    FROM pets WHERE id = #{id} AND location IS NOT NULL
</select>
```

- [ ] **Step 6: 编译验证**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet/entity backend/src/main/java/com/pethelp/pet/dto backend/src/main/java/com/pethelp/pet/mapper backend/src/main/resources/mapper
git commit -m "feat: add location access grant entity, mapper, and DTOs"
```

---

### Task 3: Service 层（TDD，单元测试先行）

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/service/LocationAccessService.java`
- Create: `backend/src/main/java/com/pethelp/pet/service/impl/LocationAccessServiceImpl.java`
- Test: `backend/src/test/java/com/pethelp/pet/LocationAccessServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 的 entity/dto/mapper（`LocationAccessGrantMapper`、`AddressVisibilityLogMapper`、`PetMapper.findExactLocation`）、`com.pethelp.user.mapper.UserMapper.findById`。
- Produces:

```java
public interface LocationAccessService {
    LocationGrantResponse grant(Long petId, Long currentUserId, GrantLocationAccessRequest request);
    ExactLocationResponse getExactLocation(Long petId, Long currentUserId);
    LocationGrantResponse revoke(Long grantId, Long currentUserId);
}
```

`LocationAccessServiceImpl` 构造器注入四个依赖：`PetMapper petMapper, LocationAccessGrantMapper grantMapper, AddressVisibilityLogMapper visibilityLogMapper, UserMapper userMapper`。

业务规则（实现须满足，校验顺序）：
- `grant`：`petMapper.findById(petId)` null → 404「宠物不存在」；`!publisherId.equals(currentUserId)` → 403「仅档案发布者可授权」；`granteeId` null → 400「被授权用户不能为空」；`granteeId.equals(currentUserId)` → 400「不能授权给自己」；`userMapper.findById(granteeId)` null → 404「用户不存在」；`grantMapper.findActiveByGranteeAndPet` 非 null → 409「已授权该用户」；否则 insert（`locationType="EXACT"`, `status="ACTIVE"`）后返回 `LocationGrantResponse`。insert 用 `@Transactional` 并在 `catch (org.springframework.dao.DuplicateKeyException)` 时抛 `ConflictException("已授权该用户")`。
- `getExactLocation`：`petMapper.findById` null → 404；`publisherId.equals(currentUserId)` 直接返回坐标（**不写审计**）；否则 `findActiveByGranteeAndPet(currentUserId, petId)` null → 403「无权查看精确位置」；非 null 则先 `visibilityLogMapper.insert`（`viewerId=currentUserId, petId, postId=null, addressType="EXACT"`）再返回。坐标提取：`petMapper.findExactLocation(petId)` 为 null/空 → 404「该宠物未设置位置」；否则 `((Number) map.get("lng")).doubleValue()` / `((Number) map.get("lat")).doubleValue()`。
- `revoke`：`grantMapper.findById` null → 404「授权不存在」；`!grantorId.equals(currentUserId)` → 403「仅授权者可撤销」；`!"ACTIVE".equals(status)` → 409「授权已撤销」；`grantMapper.revoke` 返回 0 → 409「授权已撤销」；否则返回 `status="REVOKED"` 的 `LocationGrantResponse`。

- [ ] **Step 1: 写 `LocationAccessService` 接口**（三个方法签名如上）

- [ ] **Step 2: 写失败的单元测试 `LocationAccessServiceImplTest`**

参照 `PetServiceImplTest`：`mock` 四个依赖，`@BeforeEach` 里 `new LocationAccessServiceImpl(...)`。覆盖以下断言（`assertThatThrownBy(...).isInstanceOf(...)`）：

```java
// grant
grant_notOwner_throws403
grant_granteeNotFound_throws404
grant_granteeSelf_throws400
grant_duplicateActive_throws409
grant_success_returnsActive

// getExactLocation
getExactLocation_owner_noAuditWritten   // verify(visibilityLogMapper, never()).insert(any())
getExactLocation_grantee_writesAudit    // verify(visibilityLogMapper).insert(any())
getExactLocation_unAuthorized_throws403
getExactLocation_noLocation_throws404   // findExactLocation 返回 null

// revoke
revoke_notGrantor_throws403
revoke_notActive_throws409
revoke_success_returnsRevoked
```

- [ ] **Step 3: 运行单测确认失败**

Run: `cd backend && mvn -q -Dtest=LocationAccessServiceImplTest test`
Expected: 编译失败（`LocationAccessServiceImpl` 尚未创建）→ FAIL。

- [ ] **Step 4: 实现 `LocationAccessServiceImpl`**（按 Interfaces 块里的业务规则写）

- [ ] **Step 5: 运行单测确认通过**

Run: `cd backend && mvn -q -Dtest=LocationAccessServiceImplTest test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet/service backend/src/test/java/com/pethelp/pet/LocationAccessServiceImplTest.java
git commit -m "feat: add location access grant service"
```

---

### Task 4: Controller 层

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/controller/LocationAccessController.java`

**Interfaces:**
- Consumes: Task 3 的 `LocationAccessService`、`com.pethelp.security.CurrentUser`、`com.pethelp.common.ApiResponse`。
- Produces: 三个 HTTP 端点（`/api/v1` 前缀，鉴权 JWT，走既有 `anyRequest().authenticated()`）：

```java
@RestController
@RequestMapping("/api/v1")
public class LocationAccessController {
    // POST /pets/{id}/location-grants        -> ApiResponse<LocationGrantResponse>
    // GET  /pets/{id}/exact-location         -> ApiResponse<ExactLocationResponse>
    // POST /location-grants/{id}/revoke      -> ApiResponse<LocationGrantResponse>
}
```

- [ ] **Step 1: 写 `LocationAccessController`**

三个方法均用 `@AuthenticationPrincipal CurrentUser currentUser` 取 `currentUser.id()`，薄层转发到 service，返回 `ApiResponse.ok(...)`。

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet/controller/LocationAccessController.java
git commit -m "feat: add location access grant endpoints"
```

---


### Task 5: 集成测试（端到端）

**Files:**
- Test: `backend/src/test/java/com/pethelp/LocationAccessFlowIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 迁移 + Task 4 端点。复用 `PetFlowIntegrationTest` 的 `loginAndGetToken(phone)` / `createPet(token)` 辅助方法模式（复制到本类）。

- [ ] **Step 1: 写 `LocationAccessFlowIntegrationTest`**

参照 `PetFlowIntegrationTest` 的容器/`@DynamicPropertySource`/MockMvc 结构。用 `jdbcTemplate` 断言审计与唯一索引。测试用例：

```java
// 1. grant + 授权方查看 + 审计落库
grantAndView_writesAudit
   // owner 建带 lng/lat 的 pet、publish；owner grant 给 B
   // B GET /exact-location -> 200, data.lng/lat 非空
   // jdbcTemplate 查 address_visibility_logs 该 pet 有 1 条、address_type='EXACT'

// 2. 未授权 403 且无审计
unauthorizedView_403_noAudit

// 3. owner 本人查看 200 且无审计
ownerView_noAudit

// 4. 撤销后 403
revoke_thenView_403

// 5. 越权/非法 grantee 校验
grant_notOwner_403 / grant_self_400 / granteeNotFound_404

// 6. 重复 grant 409 且仅一条 ACTIVE
duplicateGrant_409_singleActiveRow
   // jdbcTemplate 查 count(*) where pet_id=? and grantee_id=? and status='ACTIVE' == 1

// 7. 无位置 pet 查看 404
noLocation_ownerView_404
```

- [ ] **Step 2: 运行集成测试**

Run: `cd backend && mvn -q -Dtest=LocationAccessFlowIntegrationTest test`
Expected: PASS（依赖本地 Docker）。

- [ ] **Step 3: 运行全量测试确认无回归**

Run: `cd backend && mvn test`
Expected: 全部通过（原 67 + 新增）。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/com/pethelp/LocationAccessFlowIntegrationTest.java
git commit -m "test: add location access flow integration tests"
```

---

## Self-Review 记录

- **Spec 覆盖**：§5 三端点 → Task 4；§6 唯一索引/坐标提取/revoke 条件更新 → Task 1/2/3；§7 文件清单 → Task 2/3/4；§8 权限 → Task 3；§9 测试 → Task 3/5。无遗漏。
- **类型一致**：`LocationAccessService` 三方法签名在 Task 3 Interfaces 定义、Task 4 消费一致；mapper 方法签名在 Task 2 定义、Task 3 消费一致。
- **Review Focus**：5 条均有对应集成测试（Task 5 用例 1/2/3/6 + grantee 校验）。
- **比例**：plan 篇幅与 spec 相当，未复写代码体。
