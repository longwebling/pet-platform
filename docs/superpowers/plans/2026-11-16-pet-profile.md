# 宠物档案（Pet Profile）模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让登录用户发布/编辑/发布/归档/软删除自己的宠物档案（`source_type=OWNED`），精确坐标落库但对外只暴露 6 位 geohash。

**Architecture:** 模块化单体，新增业务模块 `com.pethelp.pet`（controller/service/service.impl/dto/entity/mapper）。位置用 PostGIS 的 `ST_MakePoint`/`ST_GeoHash` 在 Mapper SQL 内计算，Java 实体不持有 geometry。状态转移用「条件更新 + Service 层显式归属/状态校验」的 CAS 模式。

**Tech Stack:** Java 17、Spring Boot 3.5.16、MyBatis 3.0.5、PostgreSQL 16 + PostGIS 3.4、JWT（已有）。

**Spec:** `docs/superpowers/specs/2026-11-16-pet-profile-design.md`（本计划从该 spec 推演，实现时两份文档一起读）。

## Global Constraints

- Java 17；Spring Boot 3.5.16（parent）；MyBatis `mybatis-spring-boot-starter` 3.0.5。**不新增任何 Maven 依赖**。
- 统一返回 `{code, message, data}`；错误码 5 位且前 3 位 = HTTP 状态：`40000` 参数错误、`40300` 越权、`40400` 不存在、`40900` 状态冲突。
- 客户端**不得**传 `status`/`source_type`/`ownership_status`/`authorization_status`，一律后端推导。
- 精确坐标（`lng`/`lat`/`location`）**绝不**出现在任何响应；对外只返回 `geo_hash`（6 位）+ `city`/`district`。
- 归属校验在 Service 层显式做，**禁止 Java `assert`**；越权 → `ForbiddenException`(40300)。
- 手动校验（`BadRequestException`），不引入 jakarta validation。
- 模块内分层：`controller`（薄）→ `service` 接口 → `service/impl` 实现 → `mapper` 接口 + `resources/mapper/*.xml`。
- 状态流转严格按 docs/02 §2.1：`DRAFT → ACTIVE → ARCHIVED/DELETED`；`DELETED` 软删（`status='DELETED'` + `deleted_at=now()`）。

## Review Focus

以下是最可能「咬了使用者一口」的输入/条件，spec 没明说、但合理使用者会期待这样行为。每条都在对应任务里补了测试钉住它。

1. **`species` 为 null/空串**：应返回 400，而不是 DB `NOT NULL` 约束抛出的 500。→ Task 1 单测。
2. **只传 `lng` 不传 `lat`（或反之）**：应返回 400「经纬度需成对提供」，不能落库一个悬空的坐标。→ Task 1 单测。
3. **`lng/lat` 越界（如 `lng=181`）**：应返回 400，不能写出非法坐标。→ Task 1 单测。
4. **`geo_hash` 精度**：必须正好 6 位（≈1.2km），不能因漏传精度参数而退化成 PostGIS 默认的 20 位（≈厘米级，等于没模糊）。→ Task 1 集成测试断言长度 6。
5. **公开详情泄露草稿**：未登录 `GET /pets/{id}` 对 `DRAFT` 档案应返回 404，只对 `ACTIVE` 返回 200。→ Task 1 集成测试。

（另：软删后的档案再被操作应 404 而非 409、归档后公开不可见——分别由 Task 2/3 的测试自然覆盖。）

---

## Task 1: 创建 + 公开详情（`POST /pets`、`GET /pets/{id}`）

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/entity/Pet.java`
- Create: `backend/src/main/java/com/pethelp/pet/dto/CreatePetRequest.java`
- Create: `backend/src/main/java/com/pethelp/pet/dto/PetResponse.java`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/PetMapper.java`
- Create: `backend/src/main/resources/mapper/PetMapper.xml`
- Create: `backend/src/main/java/com/pethelp/pet/service/PetService.java`
- Create: `backend/src/main/java/com/pethelp/pet/service/impl/PetServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/pet/controller/PetController.java`
- Modify: `backend/src/main/java/com/pethelp/security/SecurityConfig.java`（放行 `GET /api/v1/pets/{id}`）
- Test: `backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java`
- Test: `backend/src/test/java/com/pethelp/PetFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `common/ApiResponse`、`common/exception/BadRequestException`、`common/exception/NotFoundException`、`security/CurrentUser`。
- Produces:
  - `Pet`（可变 POJO，getter/setter）：`Long id, Long publisherId, Long ownerId, Long currentCustodianId, String sourceType, String ownershipStatus, String authorizationStatus, String species, String breed, String age, String gender, Boolean sterilized, Boolean vaccinated, String healthDesc, String status, Double lng, Double lat, String geoHash, String city, String district`（`lng/lat` 仅作输入，不参与查询结果映射；`version`/时间戳不进入 Java 实体，由 SQL 维护）。
  - `PetMapper`：`int insert(Pet pet)`、`Pet findById(@Param("id") Long id)`、`Pet findByIdActive(@Param("id") Long id)`。
  - `PetService`：`PetResponse create(Long currentUserId, CreatePetRequest request)`、`PetResponse getById(Long petId)`。
  - `CreatePetRequest(String species, String breed, String age, String gender, Boolean sterilized, Boolean vaccinated, String healthDesc, Double lng, Double lat, String city, String district)`。
  - `PetResponse(Long id, String species, String breed, String age, String gender, Boolean sterilized, Boolean vaccinated, String healthDesc, String status, String sourceType, String ownershipStatus, String authorizationStatus, String geoHash, String city, String district)`。

- [ ] **Step 1: 写失败的单元测试 `PetServiceImplTest`**

`backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java`，Mockito mock `PetMapper`，`new PetServiceImpl(petMapper)`。用例：

```java
// create: 固定 OWNED/OWNED/SELF + DRAFT，返回 PetResponse
@Test void create_fixedOwnedFields_insertsDraft()
//   doAnswer 给 insert 设 id=1；findById(1) 返回 status=DRAFT 的 Pet；断言 resp.status()=="DRAFT"、resp.sourceType()=="OWNED"

// create: species 空串 → BadRequestException
@Test void create_blankSpecies_throws400()

// create: 只传 lng 不传 lat → BadRequestException
@Test void create_onlyLng_throws400()

// create: lng=181 越界 → BadRequestException
@Test void create_lngOutOfRange_throws400()

// getById: findByIdActive 返回 null → NotFoundException
@Test void getById_notFound_throws404()
```

- [ ] **Step 2: 运行单测确认失败**

Run: `cd backend && mvn -q -Dtest=PetServiceImplTest test`
Expected: 编译失败（`PetServiceImpl` 等类尚不存在）。

- [ ] **Step 3: 写失败的集成测试 `PetFlowIntegrationTest`**

`backend/src/test/java/com/pethelp/PetFlowIntegrationTest.java`，镜像 `AuthFlowIntegrationTest` 的容器/JWT 装配（PostGIS + Redis Testcontainers、`@AutoConfigureMockMvc`、`ObjectMapper`、`StringRedisTemplate`），并加一个私有 helper `loginAndGetToken(String phone)`（发码 → 读 Redis 验证码 → 登录 → 返回 token）。用例：

```java
// 公开详情：DRAFT 不可见，ACTIVE 可见且 geoHash 6 位
@Test void publicDetail_onlyActiveVisible()
//   1) 建一个 DRAFT 档案 → 未登录 GET /api/v1/pets/{id} → 404
//   2) 用 loginAndGetToken 拿 token → POST /pets 建档案 → publish（本任务尚未实现 publish，先用 JdbcTemplate 直改 status='ACTIVE'）
//   3) 未登录 GET → 200，jsonPath $.data.geoHash 长度 6，且无 lng/lat 字段

// 创建：落 DRAFT，三方合一，字段固定
@Test void createPet_returnsDraftWithFixedFields()
//   登录 → POST /pets → 200，$.data.status == "DRAFT"、$.data.sourceType == "OWNED"
//   用 JdbcTemplate 查 pets 表：source_type/ownership_status/authorization_status = OWNED/OWNED/SELF，
//   publisher_id == owner_id == current_custodian_id

// 创建带 lng/lat：location 与 geo_hash 落库
@Test void createPet_withLocation_storesGeometryAndGeoHash()
//   POST /pets (lng=116.397,lat=39.908) → 用 JdbcTemplate 查 location IS NOT NULL、geo_hash 长度 6
```

> 注：本任务尚未有 `publish` 端点，集成测试里把 `DRAFT→ACTIVE` 用 `JdbcTemplate` 直改，避免 Task 3 之前的循环依赖；Task 3 再改回走真实端点。

- [ ] **Step 4: 运行集成测试确认失败**

Run: `cd backend && mvn -q -Dtest=PetFlowIntegrationTest test`
Expected: 404/编译失败（`/api/v1/pets` 端点尚不存在）。

- [ ] **Step 5: 实现 `Pet`、`CreatePetRequest`、`PetResponse`**

按 Interfaces 里的字段定义三个类。`Pet` 用与 `user/entity/User.java` 相同的可变 POJO 风格（显式 getter/setter，无 Lombok）。

- [ ] **Step 6: 实现 `PetMapper` 接口 + `PetMapper.xml`**

`PetMapper.java`：`@Mapper` 接口，三个方法（签名见 Interfaces）。

`PetMapper.xml`（`namespace="com.pethelp.pet.mapper.PetMapper"`，`resultType="com.pethelp.pet.entity.Pet"` 依赖 `map-underscore-to-camel-case: true`）：

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
  INSERT INTO pets (publisher_id, owner_id, current_custodian_id, source_type, ownership_status,
                    authorization_status, species, breed, age, gender, sterilized, vaccinated,
                    health_desc, status, location, geo_hash, city, district)
  VALUES (#{publisherId}, #{ownerId}, #{currentCustodianId}, #{sourceType}, #{ownershipStatus},
          #{authorizationStatus}, #{species}, #{breed}, #{age}, #{gender}, #{sterilized}, #{vaccinated},
          #{healthDesc}, #{status},
          CASE WHEN #{lng} IS NULL OR #{lat} IS NULL THEN NULL
               ELSE ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326) END,
          CASE WHEN #{lng} IS NULL OR #{lat} IS NULL THEN NULL
               ELSE ST_GeoHash(ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326), 6) END,
          #{city}, #{district})
</insert>

<select id="findById" resultType="com.pethelp.pet.entity.Pet">
  SELECT id, publisher_id, owner_id, current_custodian_id, source_type, ownership_status,
         authorization_status, species, breed, age, gender, sterilized, vaccinated,
         health_desc, status, geo_hash, city, district
  FROM pets WHERE id = #{id} AND status != 'DELETED'
</select>

<select id="findByIdActive" resultType="com.pethelp.pet.entity.Pet">
  SELECT id, publisher_id, owner_id, current_custodian_id, source_type, ownership_status,
         authorization_status, species, breed, age, gender, sterilized, vaccinated,
         health_desc, status, geo_hash, city, district
  FROM pets WHERE id = #{id} AND status = 'ACTIVE'
</select>
```

注意：`!=` 替代 `<>` 避免 XML 转义；两个 SELECT **不查 `location` 列**。

- [ ] **Step 7: 实现 `PetService` 接口 + `PetServiceImpl`**

`PetService`（接口）与 `PetServiceImpl`（`@Service`，构造注入 `PetMapper`）。

`create` 流程：`validate(request)` → 构造 `Pet`（`publisherId=ownerId=currentCustodianId=currentUserId`，`sourceType="OWNED"`、`ownershipStatus="OWNED"`、`authorizationStatus="SELF"`、`status="DRAFT"`，其余字段从 request 拷贝）→ `insert(pet)` → `findById(pet.getId())` → `toResponse`。

`getById` 流程：`findByIdActive(petId)` → null 则 `NotFoundException("宠物不存在")` → `toResponse`。

`validate`（私有，`create` 与后续 `update` 共用）：`species` 非空且 ∈ `{CAT,DOG,OTHER}`；`gender` 若给 ∈ `{MALE,FEMALE,UNKNOWN}`；`breed`≤64、`age`≤32、`city`≤64、`district`≤64；`lng/lat` 成对（只给其一 → `BadRequestException("经纬度需成对提供")`）、`lng∈[-180,180]`、`lat∈[-90,90]`。任一违反抛 `BadRequestException`（文案自定）。

`toResponse(Pet)`：按 `PetResponse` 字段顺序映射（**不含** `lng/lat`）。

- [ ] **Step 8: 实现 `PetController`**

`@RestController @RequestMapping("/api/v1/pets")`，构造注入 `PetService`：
- `@PostMapping` `create(@AuthenticationPrincipal CurrentUser u, @RequestBody CreatePetRequest r)` → `ApiResponse.ok(petService.create(u.id(), r))`
- `@GetMapping("/{id}")` `getById(@PathVariable Long id)`（无 `CurrentUser`，公开）→ `ApiResponse.ok(petService.getById(id))`

- [ ] **Step 9: 修改 `SecurityConfig`**

在 `authorizeHttpRequests` 里 `anyRequest().authenticated()` 之前加：`.requestMatchers(HttpMethod.GET, "/api/v1/pets/*").permitAll()`（需 `import org.springframework.http.HttpMethod`）。

- [ ] **Step 10: 运行测试确认通过**

Run: `cd backend && mvn -q test`
Expected: 全绿（`PetServiceImplTest` + `PetFlowIntegrationTest` + 既有 `AuthFlowIntegrationTest`/`DatabaseMigrationTest`/`SmokeTest`）。

- [ ] **Step 11: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet backend/src/main/resources/mapper/PetMapper.xml backend/src/main/java/com/pethelp/security/SecurityConfig.java backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java backend/src/test/java/com/pethelp/PetFlowIntegrationTest.java
git commit -m "feat: add pet create and public detail endpoints"
```

---

## Task 2: 编辑档案（`PATCH /pets/{id}`，全量替换）

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/dto/UpdatePetRequest.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/ForbiddenException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/ConflictException.java`
- Modify: `backend/src/main/java/com/pethelp/pet/mapper/PetMapper.java`（加 `int update(Pet pet)`）
- Modify: `backend/src/main/resources/mapper/PetMapper.xml`（加 `<update id="update">`）
- Modify: `backend/src/main/java/com/pethelp/pet/service/PetService.java`（加 `update`）
- Modify: `backend/src/main/java/com/pethelp/pet/service/impl/PetServiceImpl.java`
- Modify: `backend/src/main/java/com/pethelp/pet/controller/PetController.java`（加 `@PatchMapping("/{id}")`）
- Modify: `backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Pet`、`PetMapper.findById`、`PetResponse`。
- Produces:
  - `PetMapper.update(Pet pet)`：`int`，全量替换，`WHERE id=#{id} AND publisher_id=#{publisherId} AND status IN ('DRAFT','ACTIVE')`。
  - `PetService.update(Long petId, Long currentUserId, UpdatePetRequest request)`：`PetResponse`。
  - `ForbiddenException`(40300)、`ConflictException`(40900)：`extends BusinessException`，构造同 `NotFoundException` 模式。
  - `UpdatePetRequest`：字段与 `CreatePetRequest` 完全一致。

- [ ] **Step 1: 写失败的单元测试**

在 `PetServiceImplTest` 增加：

```java
// update: 非本人 → ForbiddenException（findById 返回 publisherId=99，currentUserId=5）
@Test void update_notOwner_throws403()

// update: 状态非 DRAFT/ACTIVE（如 ARCHIVED）→ ConflictException
@Test void update_wrongStatus_throws409()

// update: 全量替换成功 → 返回更新后的 PetResponse（验证 findById 后再 update、再 findById 回读）
@Test void update_fullReplace_returnsUpdated()
```

- [ ] **Step 2: 运行单测确认失败**

Run: `cd backend && mvn -q -Dtest=PetServiceImplTest test`
Expected: 失败（`PetService.update` 与 `ForbiddenException`/`ConflictException` 尚不存在）。

- [ ] **Step 3: 实现 `ForbiddenException`、`ConflictException`、`UpdatePetRequest`**

两个异常类 `extends BusinessException`，`ForbiddenException` 用 `super(40300, message, HttpStatus.FORBIDDEN)`，`ConflictException` 用 `super(40900, message, HttpStatus.CONFLICT)`。`UpdatePetRequest` 字段与 `CreatePetRequest` 相同。

- [ ] **Step 4: 实现 `PetMapper.update` 与 XML**

```xml
<update id="update">
  UPDATE pets SET
    species = #{species}, breed = #{breed}, age = #{age}, gender = #{gender},
    sterilized = #{sterilized}, vaccinated = #{vaccinated}, health_desc = #{healthDesc},
    location = CASE WHEN #{lng} IS NULL OR #{lat} IS NULL THEN NULL
                    ELSE ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326) END,
    geo_hash = CASE WHEN #{lng} IS NULL OR #{lat} IS NULL THEN NULL
                    ELSE ST_GeoHash(ST_SetSRID(ST_MakePoint(#{lng}, #{lat}), 4326), 6) END,
    city = #{city}, district = #{district},
    version = version + 1
  WHERE id = #{id} AND publisher_id = #{publisherId} AND status IN ('DRAFT','ACTIVE')
</update>
```

- [ ] **Step 5: 实现 `PetService.update`**

流程：`validate(request)`（复用 Task 1 的私有 `validate`）→ `findById(petId)`（null → 404）→ `publisherId != currentUserId` → 403 → `status` 非 `DRAFT/ACTIVE` → 409 → 把 request 字段覆盖到 entity → `update(pet)`（0 行 → 409「当前状态不可编辑」）→ `findById(petId)` → `toResponse`。

- [ ] **Step 6: 实现 Controller 的 `@PatchMapping("/{id}")`**

`update(@PathVariable Long id, @AuthenticationPrincipal CurrentUser u, @RequestBody UpdatePetRequest r)` → `ApiResponse.ok(petService.update(id, u.id(), r))`。

- [ ] **Step 7: 运行测试确认通过**

Run: `cd backend && mvn -q test`
Expected: 全绿。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet/dto/UpdatePetRequest.java backend/src/main/java/com/pethelp/common/exception/ForbiddenException.java backend/src/main/java/com/pethelp/common/exception/ConflictException.java backend/src/main/java/com/pethelp/pet backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java
git commit -m "feat: add pet edit endpoint with full-replace semantics"
```

---

## Task 3: 状态转移（`publish` / `archive` / `delete`）

**Files:**
- Modify: `backend/src/main/java/com/pethelp/pet/mapper/PetMapper.java`（加 `publish`/`archive`/`softDelete`）
- Modify: `backend/src/main/resources/mapper/PetMapper.xml`
- Modify: `backend/src/main/java/com/pethelp/pet/service/PetService.java`
- Modify: `backend/src/main/java/com/pethelp/pet/service/impl/PetServiceImpl.java`
- Modify: `backend/src/main/java/com/pethelp/pet/controller/PetController.java`
- Modify: `backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java`
- Modify: `backend/src/test/java/com/pethelp/PetFlowIntegrationTest.java`（把 Task 1 里用 `JdbcTemplate` 直改 `status='ACTIVE'` 的地方改走真实 `publish` 端点）

**Interfaces:**
- Consumes: Task 1 的 `PetMapper.findById`、Task 2 的 `ForbiddenException`/`ConflictException`。
- Produces:
  - `PetMapper.publish(@Param("id") Long id, @Param("publisherId") Long publisherId)`：`int`，`SET status='ACTIVE', version=version+1 WHERE id AND publisher_id AND status='DRAFT'`。
  - `PetMapper.archive(...)`：同上，`status='ARCHIVED'`，源 `status='ACTIVE'`。
  - `PetMapper.softDelete(...)`：`SET status='DELETED', deleted_at=now(), version=version+1`，源 `status='ACTIVE'`。
  - `PetService.publish(Long petId, Long currentUserId)`：`PetResponse`；`PetService.archive(...)`：`PetResponse`；`PetService.delete(Long petId, Long currentUserId)`：`void`。

- [ ] **Step 1: 写失败的单元测试**

在 `PetServiceImplTest` 增加：

```java
// publish: 非本人 → 403；非 DRAFT（如 ACTIVE）→ 409；成功 → 返回 status=ACTIVE
@Test void publish_notOwner_throws403()
@Test void publish_notDraft_throws409()
@Test void publish_success_returnsActive()

// archive: 非 ACTIVE（如 DRAFT）→ 409；成功 → ARCHIVED
@Test void archive_notActive_throws409()

// delete: 成功 → 不抛异常且不调用 findByIdActive（delete 返回 void）
@Test void delete_success_softDeletes()

// 软删后再编辑：findById 返回 null（DELETED 被过滤）→ NotFoundException 404
@Test void update_onDeleted_throws404()
```

- [ ] **Step 2: 运行单测确认失败**

Run: `cd backend && mvn -q -Dtest=PetServiceImplTest test`
Expected: 失败（`publish`/`archive`/`delete` 尚不存在）。

- [ ] **Step 3: 实现 `PetMapper` 三个转移方法 + XML**

```xml
<update id="publish">
  UPDATE pets SET status = 'ACTIVE', version = version + 1
  WHERE id = #{id} AND publisher_id = #{publisherId} AND status = 'DRAFT'
</update>
<update id="archive">
  UPDATE pets SET status = 'ARCHIVED', version = version + 1
  WHERE id = #{id} AND publisher_id = #{publisherId} AND status = 'ACTIVE'
</update>
<update id="softDelete">
  UPDATE pets SET status = 'DELETED', deleted_at = now(), version = version + 1
  WHERE id = #{id} AND publisher_id = #{publisherId} AND status = 'ACTIVE'
</update>
```

- [ ] **Step 4: 实现 `PetService` 三个方法**

三个方法共用同一模式（`publish` 示例，`archive`/`delete` 同理，`delete` 结尾 return 不回读）：

```java
public PetResponse publish(Long petId, Long currentUserId) {
    Pet pet = petMapper.findById(petId);
    if (pet == null) throw new NotFoundException("宠物不存在");
    if (!pet.getPublisherId().equals(currentUserId)) throw new ForbiddenException("仅档案发布者可操作");
    if (!"DRAFT".equals(pet.getStatus())) throw new ConflictException("当前状态不可发布");
    if (petMapper.publish(petId, currentUserId) == 0) throw new ConflictException("当前状态不可发布");
    return toResponse(petMapper.findById(petId));
}
```

`archive` 源状态校验 `"ACTIVE"`；`delete` 源状态校验 `"ACTIVE"`，末尾不 `toResponse`、返回 `void`。

- [ ] **Step 5: 实现 Controller 三个 `@PostMapping`**

`@PostMapping("/{id}/publish")`、`@PostMapping("/{id}/archive")` 返回 `ApiResponse<PetResponse>`；`@PostMapping("/{id}/delete")` 返回 `ApiResponse<Void>`（`petService.delete(...)` 后 `return ApiResponse.ok()`）。

- [ ] **Step 6: 更新集成测试走真实 `publish` 端点**

把 `PetFlowIntegrationTest` 里 Task 1 用 `JdbcTemplate` 直改 `status='ACTIVE'` 的地方，改成登录后调 `POST /api/v1/pets/{id}/publish`。补充用例：

```java
// 发布后重复发布 → 409；归档 DRAFT → 409；删除 → DELETED 且随后公开 GET → 404；归档后公开 GET → 404
@Test void stateTransitions_guardInvalidTransitions()
```

- [ ] **Step 7: 运行测试确认通过**

Run: `cd backend && mvn -q test`
Expected: 全绿。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/pethelp/pet backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java backend/src/test/java/com/pethelp/PetFlowIntegrationTest.java
git commit -m "feat: add pet publish/archive/delete state transitions"
```

---

## Self-Review 记录

- **Spec 覆盖**：spec §5 的 6 个端点 → Task 1（POST/GET）、Task 2（PATCH）、Task 3（publish/archive/delete）；§6 的两条查询、条件更新、软删 → Task 1/2/3 的 Mapper 步骤；§8 归属/状态校验 → 三个 Task 的 Service 流程；§9 测试场景 → 单测 + 集成测试步骤均已对应。
- **类型一致性**：`PetMapper` 方法名（`insert`/`findById`/`findByIdActive`/`update`/`publish`/`archive`/`softDelete`）、`PetService` 方法名、`PetResponse`/`CreatePetRequest`/`UpdatePetRequest` 字段在三个 Task 的 Interfaces 间一致。
- **Review Focus**：5 条均已落到 Task 1（species 空串/只传 lng/lng 越界/geo_hash 长度 6/DRAFT 不可见）；软删后 404 由 Task 3 的 `update_onDeleted_throws404` 与集成测试覆盖。
- **比例**：计划聚焦签名、SQL、测试断言；生产方法体（校验顺序、toResponse 映射、DTO 拷贝）由实现者从签名与测试直接写出，未逐行誊写代码。
