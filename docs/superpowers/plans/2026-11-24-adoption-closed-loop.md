# v0.1 领养闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 v0.1 免费领养闭环：发布领养信息（ADOPTION/FINDING）→ 申请 → 审核（同事务批量拒绝其余）→ 交接（双确认幂等）→ 完成联动更新宠物所有权。

**Architecture:** 模块化单体，新增三个业务模块 `post`/`application`/`arrangement`，处置授权与所有权历史留在 `pet` 模块。跨模块只依赖接口。approve 与 confirm-handover 用单事务 + 行锁 + 条件更新（CAS）+ 唯一索引兜底。创建帖子即 `OPEN`（自动过审），无运营核实。

**Tech Stack:** Java 17 + Spring Boot 3.5 + MyBatis + Spring Security + PostgreSQL 16/PostGIS 3.4 + Redis（Testcontainers 集成测试）。

**Spec:** `docs/superpowers/specs/2026-11-24-adoption-closed-loop-design.md`（业务规格见 docs/02、04、05、06）。

## Global Constraints

- 状态字段 `varchar(32)` 大写蛇形枚举，禁用 PostgreSQL enum / smallint。
- 无任何资金/支付字段或表。
- 统一响应 `ApiResponse{code,message,data}`；错误码 `40000/40100/40300/40400/40900/42900/50000`，HTTP 状态与 code 前三位对齐。
- 客户端不得传业务状态，状态转移全部服务端推导校验；状态非法 → `ConflictException`(40900)、越权 → `ForbiddenException`(40300)、不存在 → `NotFoundException`(40400)。
- 跨模块只引用 `service`/`mapper` 接口与 `dto`/`entity`，禁止引用其他模块 `impl`。
- 禁止用 Java `assert` 做权限校验；显式 `if` + 抛业务异常。
- 关键表 `version` 乐观锁，更新 `version = version + 1`。
- 精确坐标绝不外发，公开接口只回 `geo_hash` 模糊位置。
- 交接/归还确认以 `biz_id + event + user` 幂等；`handover_records` 预创建、confirm 只更新确认位。
- 测试：单元测试与模块同包（Mockito mock mapper）；集成测试放 `com.pethelp` 根包（Testcontainers postgis + redis，真实 PostGIS 镜像，不用 H2）。
- 无新增 Maven 依赖；无新增数据库迁移（V1 已含全部表与索引）。

## Review Focus

1. **重复/并发 approve**（两个申请人或同一申请 approve 两次）：第二个必须 409，且不能留下「帖子已 RESERVED 但安排未创建」中间态 → Task 4 集成测试 + 单元测试 `approve_reserveCasFails_throws409`。
2. **同一申请人重复提交申请**：`uq_adoption_application_pending` 唯一索引兜底，重复 → 409，DB 仅一条 PENDING → Task 4 集成测试。
3. **confirm-handover 重复/并发**：同一方重复确认 no-op，不重复写库、不重复推进 → Task 3 单元测试 `confirmHandover_alreadyConfirmed_noop` + 集成测试。
4. **非参与者 confirm-handover**：`currentUser ∉ {adopter_id, custodian_id}` → 403，不置确认位 → Task 3 单元测试。
5. **领养完成后再次领养同一 pet_id**：复用档案（不归档/不新建），旧 `COMPLETED` 安排不阻塞新的 `PENDING_HANDOVER`（唯一索引只约束 PENDING_HANDOVER）→ Task 5 集成测试。
6. **精确位置不因交接自动放行**：申请通过/交接完成后，adopter 仍需 `location_access_grants` 授权才能看精确位置 → Task 5 集成测试。

---

### Task 1: pet 模块地基（处置授权 + 所有权转移）

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/entity/PetAuthorization.java`
- Create: `backend/src/main/java/com/pethelp/pet/entity/PetOwnershipHistory.java`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/PetAuthorizationMapper.java`
- Create: `backend/src/main/resources/mapper/PetAuthorizationMapper.xml`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/PetOwnershipHistoryMapper.java`
- Create: `backend/src/main/resources/mapper/PetOwnershipHistoryMapper.xml`
- Create: `backend/src/main/java/com/pethelp/pet/service/DispositionAuthorizationService.java`
- Create: `backend/src/main/java/com/pethelp/pet/service/impl/DispositionAuthorizationServiceImpl.java`
- Modify: `backend/src/main/java/com/pethelp/pet/service/PetService.java`（增 `transferOwnershipToAdopter`）
- Modify: `backend/src/main/java/com/pethelp/pet/service/impl/PetServiceImpl.java`
- Modify: `backend/src/main/java/com/pethelp/pet/mapper/PetMapper.java`（增 `transferOwnership`）
- Modify: `backend/src/main/resources/mapper/PetMapper.xml`
- Test: `backend/src/test/java/com/pethelp/pet/DispositionAuthorizationServiceImplTest.java`
- Test: `backend/src/test/java/com/pethelp/pet/PetServiceImplTest.java`（增 transfer 用例）

**Interfaces:**
- Consumes: `PetMapper.findById(Long) -> Pet`（已有）、`PetMapper.transferOwnership(...)`（本任务新增）。
- Produces:
  - `boolean DispositionAuthorizationService.canPublishAdoption(Long petId, Long userId)`
  - `boolean DispositionAuthorizationService.canApproveAdoption(Long petId, Long userId)`
  - `void PetService.transferOwnershipToAdopter(Long petId, Long adopterId, Long arrangementId, Long operatorId)`
  - `PetAuthorizationMapper.findActiveByPetAndGranteeAndType(Long petId, Long granteeId, String authType) -> PetAuthorization`
  - `int PetOwnershipHistoryMapper.insert(PetOwnershipHistory history)`

**实体字段**（`PetAuthorization`）：`id, petId, grantorId, granteeId, authType, status, grantedAt, expiresAt, revokedAt, createdAt`（对应 `pet_authorizations` 表）。`PetOwnershipHistory`：`id, petId, fromOwnerId, toOwnerId, changeType, arrangementId, orderId, changedBy, reason, createdAt`（对应 `pet_ownership_history` 表）。用普通 POJO getter/setter，与 `Pet` 一致。

- [ ] **Step 1: 写失败测试** `DispositionAuthorizationServiceImplTest`

  - `canApproveAdoption_ownerSelf_returnsTrue`：`petMapper.findById(1L)` 返回 ownerId=5 的 pet，`service.canApproveAdoption(1L, 5L)` 为 true（不查授权表）。
  - `canApproveAdoption_activeGrant_returnsTrue`：pet.ownerId=9，`petAuthorizationMapper.findActiveByPetAndGranteeAndType(1L, 5L, "ADOPTION_APPROVAL")` 返回非 null，`canApproveAdoption(1L, 5L)` 为 true。
  - `canApproveAdoption_noGrant_returnsFalse`：ownerId=9 且授权查询返回 null，返回 false。
  - `canPublishAdoption_ownerSelf_returnsTrue` / `canPublishAdoption_activePubGrant_returnsTrue`（`authType="PUBLISH"`）/ `canPublishAdoption_noGrant_returnsFalse`。
  - `transferOwnershipToAdopter_updatesPetAndWritesHistory`：`petMapper.findById(1L)` 返回 ownerId=5 的 pet；调用 `service.transferOwnershipToAdopter(1L, 7L, 100L, 5L)` 后，验证 `petMapper.transferOwnership(1L, 7L, 5L)` 被调用一次，且 `petOwnershipHistoryMapper.insert` 捕获的 history 满足 `toOwnerId==7 && fromOwnerId==5 && changeType=="ADOPTION" && arrangementId==100 && changedBy==5`。

- [ ] **Step 2: 运行测试确认失败**

  Run: `mvn -q -Dtest=DispositionAuthorizationServiceImplTest test`
  Expected: 编译失败（`DispositionAuthorizationService` 未定义）。

- [ ] **Step 3: 实现 `DispositionAuthorizationService` 与 `transferOwnershipToAdopter`**

  `DispositionAuthorizationServiceImpl`：构造注入 `PetMapper` + `PetAuthorizationMapper`。
  - `canPublishAdoption(petId, userId)`：`Pet pet = petMapper.findById(petId)`；`pet != null && (userId.equals(pet.getOwnerId()) || petAuthorizationMapper.findActiveByPetAndGranteeAndType(petId, userId, "PUBLISH") != null)`。
  - `canApproveAdoption` 同理，`authType="ADOPTION_APPROVAL"`。

  `PetServiceImpl.transferOwnershipToAdopter`：构造注入 `PetOwnershipHistoryMapper`（新增字段）。读 `petMapper.findById(petId)` 拿旧 `ownerId`，调 `petMapper.transferOwnership(petId, adopterId, oldOwnerId)`，再 `petOwnershipHistoryMapper.insert(...)`（`changeType="ADOPTION"`、`arrangementId`、`changedBy=operatorId`、`reason="领养交接完成"`）。该方法加 `@Transactional`。

  SQL：
  ```sql
  -- PetMapper.xml
  UPDATE pets SET owner_id = #{newOwnerId}, current_custodian_id = #{newOwnerId},
         ownership_status = 'OWNED', version = version + 1
  WHERE id = #{petId};
  ```
  ```sql
  -- PetAuthorizationMapper.xml
  SELECT ... FROM pet_authorizations
  WHERE pet_id = #{petId} AND grantee_id = #{granteeId} AND auth_type = #{authType} AND status = 'ACTIVE'
  LIMIT 1;
  ```
  ```sql
  -- PetOwnershipHistoryMapper.xml
  INSERT INTO pet_ownership_history (pet_id, from_owner_id, to_owner_id, change_type, arrangement_id, changed_by, reason)
  VALUES (#{petId}, #{fromOwnerId}, #{toOwnerId}, #{changeType}, #{arrangementId}, #{changedBy}, #{reason});
  ```

- [ ] **Step 4: 运行测试确认通过**

  Run: `mvn -q -Dtest=DispositionAuthorizationServiceImplTest,PetServiceImplTest test`
  Expected: 全绿。

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/pethelp/pet backend/src/main/resources/mapper backend/src/test/java/com/pethelp/pet
  git commit -m "feat: add disposition authorization and ownership transfer to pet module"
  ```

---

### Task 2: post 模块（pet_posts 发布信息）

**Files:**
- Create: `backend/src/main/java/com/pethelp/post/entity/PetPost.java`
- Create: `backend/src/main/java/com/pethelp/post/mapper/PostMapper.java`
- Create: `backend/src/main/resources/mapper/PostMapper.xml`
- Create: `backend/src/main/java/com/pethelp/post/dto/CreatePostRequest.java`
- Create: `backend/src/main/java/com/pethelp/post/dto/UpdatePostRequest.java`
- Create: `backend/src/main/java/com/pethelp/post/dto/PostResponse.java`
- Create: `backend/src/main/java/com/pethelp/post/service/PostService.java`
- Create: `backend/src/main/java/com/pethelp/post/service/impl/PostServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/post/controller/PostController.java`
- Modify: `backend/src/main/java/com/pethelp/security/SecurityConfig.java`（GET /pet-posts 公开）
- Test: `backend/src/test/java/com/pethelp/post/PostServiceImplTest.java`

**Interfaces:**
- Consumes: `PetMapper.findById(Long)`（pet 模块）；`DispositionAuthorizationService.canPublishAdoption(Long, Long)`。
- Produces:
  - `PostResponse PostService.create(Long currentUserId, CreatePostRequest request)`
  - `PostResponse PostService.getById(Long postId)`
  - `List<PostResponse> PostService.list(String postType, String species, int page, int size)`
  - `PostResponse PostService.update(Long postId, Long currentUserId, UpdatePostRequest request)`
  - `PostResponse PostService.close(Long postId, Long currentUserId)`
  - `PostResponse PostService.cancel(Long postId, Long currentUserId)`
  - `PostResponse PostService.convertToAdoption(Long postId, Long currentUserId)`
  - `PostMapper.findByIdForUpdate(Long) -> PetPost`（Task 4 用）、`PostMapper.reserve(Long id, Long applicationId) -> int`（Task 4 用）、`PostMapper.reopen(Long id) -> int`、`PostMapper.markCompleted(Long id) -> int`（Task 3/4 用）。

**DTO（record）：**
```java
record CreatePostRequest(Long petId, String postType, String title, String description) {}
record UpdatePostRequest(String title, String description) {}
record PostResponse(Long id, Long petId, Long publisherId, String postType, String title,
                    String description, String status, String species, String geoHash,
                    String city, String district) {}
```

**实体 `PetPost`**：字段 = `pet_posts` 全部列（`id, petId, publisherId, postType, title, description, careType, careStartTime, careEndTime, status, reservedApplicationId, convertedToPostId, rejectReason, verifiedAt, completedAt, version`）+ 展示字段 `species, geoHash, city, district`（由 `findById`/`list` 的 `LEFT JOIN pets` 填充）。用 POJO getter/setter。

- [ ] **Step 1: 写失败测试** `PostServiceImplTest`（mock `PetMapper` + `PostMapper` + `DispositionAuthorizationService`）

  - `create_adoption_autoOpen`：`petMapper.findById` 返回 ACTIVE pet；`canPublishAdoption` true；`postMapper.insert` 设 id；断言返回 `status=="OPEN"`，且 insert 捕获的 post `status=="OPEN"`、`verifiedAt != null`。
  - `create_adoption_noAuthorization_throws403`：`canPublishAdoption` false → `ForbiddenException`。
  - `create_petNotActive_throws409`：pet.status=DRAFT → `ConflictException`。
  - `create_blankTitle_throws400`：title 空 → `BadRequestException`。
  - `create_invalidPostType_throws400`：postType="FOSTER" → `BadRequestException`。
  - `update_notOwner_throws403`、`update_reserved_throws409`（post.status=RESERVED）、`close_notOwner_throws403`、`cancel_completed_throws409`。
  - `convertToAdoption_notVerified_throws409`：FINDING post + pet `ownershipStatus=OWNED` → `ConflictException`（v0.1 恒拒）。

- [ ] **Step 2: 运行测试确认失败**

  Run: `mvn -q -Dtest=PostServiceImplTest test`
  Expected: 编译失败（`PostService` 未定义）。

- [ ] **Step 3: 实现 post 模块**

  `PostServiceImpl` 校验顺序（`create`）：`petMapper.findById(petId)` null→404、`status!="ACTIVE"`→409；`postType ∈ {FINDING,ADOPTION}`；`title` 非空；ADOPTION 走 `canPublishAdoption`（false→403）、FINDING 走 `pet.publisherId==userId || pet.ownerId==userId`（否则 403）；`insert`（status=`OPEN`、`verifiedAt=now()`）。`update`/`close`/`cancel` 校验 `publisherId==userId`（否则 403）+ 状态前置（锁定/终态→409）。`convertToAdoption`：post 为 FINDING 且 OPEN 且本人；`petMapper.findById` 校验 `ownershipStatus=="UNOWNED" && authorizationStatus=="AUTHORIZED"`，否则 409；满足则新建 ADOPTION post + `markFindingConverted`（v0.1 恒走 409 分支，happy-path 保留）。

  关键 SQL（`PostMapper.xml`）：
  ```sql
  <!-- insert: 由服务层传 status='OPEN', verified_at=now() -->
  <!-- findById / list: LEFT JOIN pets p ON pp.pet_id = p.id，选 p.species, p.geo_hash, p.city, p.district -->
  <!-- findByIdForUpdate: 同上 + FOR UPDATE（供 Task4 锁帖） -->
  <!-- reserve (CAS): UPDATE pet_posts SET status='RESERVED', reserved_application_id=#{applicationId},
        version=version+1 WHERE id=#{id} AND status='OPEN' -->
  <!-- reopen: UPDATE pet_posts SET status='OPEN', reserved_application_id=NULL, version=version+1
        WHERE id=#{id} AND status='RESERVED' -->
  <!-- markCompleted: UPDATE pet_posts SET status='COMPLETED', completed_at=now(), version=version+1
        WHERE id=#{id} AND status='RESERVED' -->
  <!-- close: UPDATE ... SET status='CLOSED' WHERE id=#{id} AND publisher_id=#{publisherId} AND status IN ('OPEN','RESERVED') -->
  <!-- cancel: UPDATE ... SET status='CANCELLED' WHERE id=#{id} AND publisher_id=#{publisherId} AND status IN ('OPEN','RESERVED') -->
  <!-- update: UPDATE ... SET title, description, version=version+1 WHERE id=#{id} AND publisher_id=#{publisherId} AND status IN ('OPEN') -->
  <!-- markFindingConverted: UPDATE pet_posts SET status='CLOSED', converted_to_post_id=#{toPostId} WHERE id=#{id} AND status='OPEN' -->
  ```

  `PostController`（`@RequestMapping("/api/v1/pet-posts")`）：`POST ""`、`GET ""`（`@RequestParam(required=false) postType/species + page/size`，page/size 默认 1/20）、`GET "/{id}"`、`PATCH "/{id}"`、`POST "/{id}/close"`、`POST "/{id}/convert-to-adoption"`、`POST "/{id}/cancel"`。

  `SecurityConfig` 在 `permitAll` 处增加：`requestMatchers(HttpMethod.GET, "/api/v1/pet-posts", "/api/v1/pet-posts/*").permitAll()`。

- [ ] **Step 4: 运行测试确认通过**

  Run: `mvn -q -Dtest=PostServiceImplTest test`
  Expected: 全绿。

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/pethelp/post backend/src/main/resources/mapper/PostMapper.xml backend/src/main/java/com/pethelp/security/SecurityConfig.java backend/src/test/java/com/pethelp/post
  git commit -m "feat: add pet-posts module (FINDING/ADOPTION, auto-verify to OPEN)"
  ```

---

### Task 3: arrangement 模块（领养安排 + 交接记录）

**Files:**
- Create: `backend/src/main/java/com/pethelp/arrangement/entity/AdoptionArrangement.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/entity/HandoverRecord.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/mapper/AdoptionArrangementMapper.java`
- Create: `backend/src/main/resources/mapper/AdoptionArrangementMapper.xml`
- Create: `backend/src/main/java/com/pethelp/arrangement/mapper/HandoverRecordMapper.java`
- Create: `backend/src/main/resources/mapper/HandoverRecordMapper.xml`
- Create: `backend/src/main/java/com/pethelp/arrangement/dto/ArrangementResponse.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/dto/HandoverConfirmResponse.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/service/ArrangementService.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/service/impl/ArrangementServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/arrangement/controller/AdoptionArrangementController.java`
- Test: `backend/src/test/java/com/pethelp/arrangement/ArrangementServiceImplTest.java`

**Interfaces:**
- Consumes: `PostMapper.markCompleted(Long)`、`PostMapper.reopen(Long)`、`PetService.transferOwnershipToAdopter(Long,Long,Long,Long)`（Task 1/2）。
- Produces:
  - `Long ArrangementService.create(Long petId, Long postId, Long applicationId, Long adopterId, Long custodianId)`
  - `HandoverConfirmResponse ArrangementService.confirmHandover(Long arrangementId, Long currentUserId)`
  - `ArrangementResponse ArrangementService.cancel(Long arrangementId, Long currentUserId)`
  - `ArrangementResponse ArrangementService.abort(Long arrangementId, Long currentUserId, String reason)`
  - `HandoverRecordMapper.confirmByFrom(Long id) -> int`、`HandoverRecordMapper.confirmByTo(Long id) -> int`

**DTO（record）：**
```java
record ArrangementResponse(Long id, Long petId, Long postId, Long applicationId,
                           Long adopterId, Long custodianId, String status) {}
record HandoverConfirmResponse(String status, boolean fromConfirmed, boolean toConfirmed) {}
```

- [ ] **Step 1: 写失败测试** `ArrangementServiceImplTest`（mock `AdoptionArrangementMapper` + `HandoverRecordMapper` + `PostMapper` + `PetService`）

  - `create_insertsArrangementAndPrecreatesHandover`：`create(...)` 后验证 arrangement insert 状态 `PENDING_HANDOVER`，且 handover insert 一条 `event=="HANDOVER"`、`bizType=="ADOPTION"`、`fromUserId==custodianId`、`toUserId==adopterId`、两确认位 false。
  - `confirmHandover_notParticipant_throws403`：arrangement `adopterId=7, custodianId=8`，currentUserId=9 → `ForbiddenException`。
  - `confirmHandover_fromSideFirst_setsFromFlag`：currentUserId==custodianId，`confirmByFrom` 返回 1、`confirmByTo` 未调；返回 status=`PENDING_HANDOVER`、fromConfirmed=true。
  - `confirmHandover_bothConfirmed_completesAndTransfers`：双方均已确认后（from/to 均 true）→ `arrangementMapper.complete` 调用、`postMapper.markCompleted` 调用、`petService.transferOwnershipToAdopter` 调用一次。
  - `confirmHandover_alreadyConfirmed_noop`：本侧确认位已 true，`confirmBy*` 返回 0 → 不重复 complete/transfer，返回当前状态。
  - `cancel_reservedArrangement_reopensPost`：`cancel` 后 `arrangementMapper.cancel` + `postMapper.reopen` 各调用一次。
  - `abort_reservedArrangement_reopensPost`：同 cancel，另验证 `abort` 懒创建 `event=="ABORT"` 记录（`handoverRecordMapper.insert` 被调）。

- [ ] **Step 2: 运行测试确认失败**

  Run: `mvn -q -Dtest=ArrangementServiceImplTest test`
  Expected: 编译失败（`ArrangementService` 未定义）。

- [ ] **Step 3: 实现 arrangement 模块**

  `create`（`@Transactional`）：`adoptionArrangementMapper.insert`（status=`PENDING_HANDOVER`）+ `handoverRecordMapper.insert`（`bizType="ADOPTION"`、`bizId=arrangementId`、`event="HANDOVER"`、`fromUserId=custodianId`、`toUserId=adopterId`、`location=null`、`locationType=null`、确认位 false）。返回 arrangement id。

  `confirmHandover`（`@Transactional`）：`adoptionArrangementMapper.findByIdForUpdate(id)`（null→404）；`status=="PENDING_HANDOVER"` 否则 409；按 `currentUserId` 判定是 `custodianId`(from) 还是 `adopterId`(to)，否则 403；取 `handoverRecordMapper.findByBizAndEvent("ADOPTION", id, "HANDOVER")`，调对应 `confirmByFrom`/`confirmByTo`；重读记录判两确认位：均 true → `complete` + `postMapper.markCompleted(postId)` + `petService.transferOwnershipToAdopter(petId, adopterId, id, currentUserId)`；返回 `HandoverConfirmResponse(status, fromConfirmed, toConfirmed)`。

  `cancel`/`abort`（`@Transactional`）：`findById`（null→404）；`status=="PENDING_HANDOVER"` 否则 409；`cancel`→`adoptionArrangementMapper.cancel` + `postMapper.reopen(postId)`；`abort`→`adoptionArrangementMapper.abort(id, reason)` + `postMapper.reopen(postId)` + 懒建 `event="ABORT"` 记录（`findByBizAndEvent("ADOPTION",id,"ABORT")` 为 null 才 insert，`ON CONFLICT` 语义由应用层判空近似）。

  关键 SQL：`AdoptionArrangementMapper.xml` 的 `insert/findById/findByIdForUpdate(FOR UPDATE)/complete/cancel/abort`；`HandoverRecordMapper.xml` 的 `insert/findByBizAndEvent/confirmByFrom/confirmByTo`（confirm 用 `WHERE id=#{id} AND confirmed_*_false`，影响行数即幂等结果）。

  `AdoptionArrangementController`（`@RequestMapping("/api/v1")`）：`POST "/adoption-arrangements/{id}/confirm-handover"`、`POST "/adoption-arrangements/{id}/cancel"`、`POST "/adoption-arrangements/{id}/abort"`。

- [ ] **Step 4: 运行测试确认通过**

  Run: `mvn -q -Dtest=ArrangementServiceImplTest test`
  Expected: 全绿。

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/pethelp/arrangement backend/src/main/resources/mapper/AdoptionArrangementMapper.xml backend/src/main/resources/mapper/HandoverRecordMapper.xml backend/src/test/java/com/pethelp/arrangement
  git commit -m "feat: add adoption-arrangement module with idempotent handover confirm"
  ```

---

### Task 4: application 模块（领养申请 + approve 单事务）

**Files:**
- Create: `backend/src/main/java/com/pethelp/application/entity/AdoptionApplication.java`
- Create: `backend/src/main/java/com/pethelp/application/mapper/AdoptionApplicationMapper.java`
- Create: `backend/src/main/resources/mapper/AdoptionApplicationMapper.xml`
- Create: `backend/src/main/java/com/pethelp/application/dto/SubmitApplicationRequest.java`
- Create: `backend/src/main/java/com/pethelp/application/dto/ApplicationResponse.java`
- Create: `backend/src/main/java/com/pethelp/application/dto/ApproveResponse.java`
- Create: `backend/src/main/java/com/pethelp/application/service/AdoptionApplicationService.java`
- Create: `backend/src/main/java/com/pethelp/application/service/impl/AdoptionApplicationServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/application/controller/AdoptionApplicationController.java`
- Test: `backend/src/test/java/com/pethelp/application/AdoptionApplicationServiceImplTest.java`

**Interfaces:**
- Consumes: `PostMapper.findByIdForUpdate(Long)`、`PostMapper.reserve(Long, Long)`（Task 2）；`DispositionAuthorizationService.canApproveAdoption(Long, Long)`（Task 1）；`ArrangementService.create(Long,Long,Long,Long,Long)`（Task 3）；`PetMapper.findById(Long)`（拿 `current_custodian_id`）。
- Produces:
  - `ApplicationResponse AdoptionApplicationService.submit(Long postId, Long currentUserId, SubmitApplicationRequest request)`
  - `ApplicationResponse AdoptionApplicationService.withdraw(Long applicationId, Long currentUserId)`
  - `ApplicationResponse AdoptionApplicationService.reject(Long applicationId, Long currentUserId)`
  - `ApproveResponse AdoptionApplicationService.approve(Long applicationId, Long currentUserId)`

**DTO（record）：**
```java
record SubmitApplicationRequest(String message) {}
record ApplicationResponse(Long id, Long postId, Long adopterId, String message, String status, String rejectReason) {}
record ApproveResponse(Long arrangementId, String postStatus, int rejectedOthers) {}
```

- [ ] **Step 1: 写失败测试** `AdoptionApplicationServiceImplTest`（mock `PostMapper` + `AdoptionApplicationMapper` + `DispositionAuthorizationService` + `ArrangementService` + `PetMapper`）

  - `submit_postNotOpen_throws409`：post.status=RESERVED → `ConflictException`。
  - `submit_postNotAdoption_throws409`：post.postType=FINDING → `ConflictException`。
  - `submit_success_insertsPending`：post OPEN、postType=ADOPTION → insert status=`PENDING`。
  - `withdraw_notOwner_throws403`、`reject_publisherOnly_throws403`。
  - `approve_notPublisher_throws403`：post.publisherId != currentUser → `ForbiddenException`。
  - `approve_noAuthorization_throws403`：publisher 本人但 `canApproveAdoption` false → `ForbiddenException`。
  - `approve_reserveCasFails_throws409`：`postMapper.reserve` 返回 0 → `ConflictException`，且 `arrangementService.create` 未调用。
  - `approve_success_createsArrangementAndRejectsOthers`：reserve 返回 1、pet 有 `currentCustodianId`；验证 `arrangementService.create(petId, postId, appId, adopterId, custodianId)` 调用一次，`applicationMapper.rejectOthers(postId, appId, "该宠物已被其他申请人匹配")` 调用一次，返回 `postStatus=="RESERVED"`。

- [ ] **Step 2: 运行测试确认失败**

  Run: `mvn -q -Dtest=AdoptionApplicationServiceImplTest test`
  Expected: 编译失败（`AdoptionApplicationService` 未定义）。

- [ ] **Step 3: 实现 application 模块**

  `submit`：`postMapper.findById(postId)` null→404；`postType!="ADOPTION" || status!="OPEN"`→409；insert（status=`PENDING`）；捕获 `DuplicateKeyException` → 409（`uq_adoption_application_pending`）。

  `approve`（`@Transactional`）：`applicationMapper.findById(applicationId)` null→404；`status=="PENDING"` 否则 409；`postMapper.findByIdForUpdate(postId)`（FOR UPDATE 锁帖）null→404；`post.publisherId==currentUser` 否则 403；`canApproveAdoption(petId, currentUser)` false→403；`post.status=="OPEN"` 否则 409；`postMapper.reserve(postId, applicationId)` != 1 → 409；`petMapper.findById(petId)` 拿 `currentCustodianId`；`arrangementService.create(petId, postId, applicationId, adopterId, custodianId)`；`applicationMapper.rejectOthers(postId, applicationId, "该宠物已被其他申请人匹配")`（返回受影响行数为 `rejectedOthers`）；`applicationMapper.approve(applicationId)`。返回 `ApproveResponse(arrangementId, "RESERVED", rejectedOthers)`。

  关键 SQL（`AdoptionApplicationMapper.xml`）：
  ```sql
  <!-- rejectOthers: UPDATE adoption_applications SET status='REJECTED', reject_reason=#{reason},
        version=version+1 WHERE post_id=#{postId} AND status='PENDING' AND id <> #{approvedId} -->
  <!-- withdraw: UPDATE ... SET status='WITHDRAWN' WHERE id=#{id} AND adopter_id=#{adopterId} AND status='PENDING' -->
  <!-- reject: UPDATE ... SET status='REJECTED', reject_reason=#{reason} WHERE id=#{id} AND status='PENDING' -->
  <!-- approve: UPDATE ... SET status='APPROVED' WHERE id=#{id} AND status='PENDING' -->
  ```

  `AdoptionApplicationController`（`@RequestMapping("/api/v1")`）：`POST "/adoption-posts/{id}/applications"`（submit，`{id}` 为 post id）、`POST "/adoption-applications/{id}/withdraw"`、`POST "/adoption-applications/{id}/approve"`、`POST "/adoption-applications/{id}/reject"`。

- [ ] **Step 4: 运行测试确认通过**

  Run: `mvn -q -Dtest=AdoptionApplicationServiceImplTest test`
  Expected: 全绿。

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/pethelp/application backend/src/main/resources/mapper/AdoptionApplicationMapper.xml backend/src/test/java/com/pethelp/application
  git commit -m "feat: add adoption-application module with single-transaction approve"
  ```

---

### Task 5: 全链路集成测试

**Files:**
- Create: `backend/src/test/java/com/pethelp/AdoptionFlowIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1-4 全部端点 + 既有 `auth`（登录）、`pet`（建 pet、publish、location-grants）。

- [ ] **Step 1: 写集成测试**（Testcontainers postgis+redis + MockMvc，参照 `LocationAccessFlowIntegrationTest` 的登录/建 pet helper）

  用例（每条覆盖 Review Focus 之一）：
  1. `fullAdoptionFlow_updatesOwnership`：owner 建 pet→publish→发 ADOPTION 帖子→adopter 申请→owner approve（断言 `arrangementId>0`、`postStatus=="RESERVED"`、`rejectedOthers==0`）→ 双方 `confirm-handover` → 断言帖子 `COMPLETED`、`pets.owner_id/current_custodian_id=adopterId`、`ownership_status='OWNED'`、`pet_ownership_history` 落一条 `change_type='ADOPTION'`。
  2. `approve_rejectsOtherPendingApplications`：两名 adopter 申请同一帖子，owner approve 其一 → 另一条 `status='REJECTED'` 且 `reject_reason` 匹配；`rejectedOthers==1`。
  3. `duplicateApplication_conflict_singlePending`：同一 adopter 重复申请 → 第二次 409，DB 仅一条 PENDING。
  4. `approve_notPublisher_403`、`confirmHandover_notParticipant_403`。
  5. `confirmHandover_repeat_noop`：同一方重复确认 → 200 no-op，`handover_records` 确认位不变、无重复 `pet_ownership_history`。
  6. `reAdoption_samePetId_notBlocked`：领养完成后（COMPLETED），同一 pet 再次发 ADOPTION 帖子并 approve → 新 `PENDING_HANDOVER` 安排创建成功（不被旧 COMPLETED 阻塞）。
  7. `exactLocation_stillRequiresGrant_afterHandover`：交接完成后 adopter `GET /pets/{id}/exact-location` 未授权 → 403。

- [ ] **Step 2: 运行测试确认失败或缺失**

  Run: `mvn -q -Dtest=AdoptionFlowIntegrationTest test`
  Expected: 编译通过后部分用例失败（若某端点未就绪）——以当前分支应全绿，若失败说明某任务漏实现，回退对应任务修复。

- [ ] **Step 3: 运行全量测试**

  Run: `mvn -q test`
  Expected: 全绿（原 90 + 新增全通过；集成测试依赖 Docker 已启动）。

- [ ] **Step 4: 回写进度文档**

  Modify: `docs/10-development-progress.md` 第 2 节补「发布信息 / 领养申请 / 交接安排」三行完成表；第 4 节待开发移除 5.3/5.4/5.5 领养部分；第 5 节「下一里程碑」改为 v0.2 照护 + 治理。

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/test/java/com/pethelp/AdoptionFlowIntegrationTest.java docs/10-development-progress.md
  git commit -m "test: add adoption closed-loop integration test; docs: update progress"
  ```

---

## Self-Review Notes

- **Spec coverage**：5.3 ADOPTION（Task 2，含 close/cancel/convert）+ FINDING（Task 2）、5.4（Task 4）、5.5（Task 3）；自动过审贯穿 Task 2；批量拒绝（Task 4 `rejectOthers`）；交接幂等 + 所有权联动（Task 3 + Task 1 `transferOwnershipToAdopter`）。FOSTER/消息/举报/真实核实按设计留 v0.2。
- **Type consistency**：`PostMapper.reserve/reopen/markCompleted`、`ArrangementService.create`、`PetService.transferOwnershipToAdopter` 签名在各任务 Interfaces 一致；`ApproveResponse.rejectedOthers` 取 `rejectOthers` 返回行数。
- **Review Focus**：6 条分别映射 Task 4（#1/#2）、Task 3（#3/#4）、Task 5（#5/#6）。
