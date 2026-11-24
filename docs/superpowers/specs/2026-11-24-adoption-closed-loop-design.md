# v0.1 领养闭环（pet-posts + adoption-applications + adoption-arrangements）— 设计文档

> 对应 docs/05 §5.3 / §5.4 / §5.5、docs/02 §2.2/§2.3/§2.5/§2.7、docs/04 §4.2/§4.4/§4.5、docs/06 §6.1 ②⑤ / §6.4。
> 本迭代完成 **v0.1 免费领养闭环**：发布领养信息 → 申请 → 审核（同事务批量拒绝其余）→ 交接 → 双方确认完成 → 宠物所有权更新。业务规格（状态机/表结构/API/并发）以上述编号文档为准，本文只记录本次的决策与实现方案，不重复规格。

## 1. 意图与目标

跑通 `07 §7.3` 的 v0.1 验收链路：发布 → 开放申请 → 申请 → 审核（同事务批量拒绝其余）→ 安排 → 交接 → 双方确认完成 → 宠物档案所有权/照护者/所有权状态更新。

- **目标**：5.3 发布信息（ADOPTION 全量 + FINDING 基础）、5.4 领养申请、5.5 领养安排与交接 三段端点跑通；并发下同一宠物不会被两个申请同时预留成功；越权全部 403；精确位置仍仅授权可见。
- **非目标**（v0.2）：FOSTER 全部（帖子/申请/工单/归还）、真实运营核实（`PENDING_VERIFY → OPEN` 由 admin 触发）、`convert-to-adoption` 的真实触发（需运营核实，见 §3.6）、消息（5.6）、举报与运营后台（5.7）、宠物 `source_type` 语义扩展（FOUND/STRAY 建档）。
- **反目标**（做成即失败）：客户端直接传业务状态；approve 的「锁帖子 → CAS → 建安排 → 批量拒绝」被 `REQUIRES_NEW`/`@Async`/MQ 拆分出中间态；领养完成后把 `pets.status` 置 `ARCHIVED` 或新建档案（应复用同一 `pet_id`）；交接确认插入 `handover_records`（应预创建 + 只更新确认位）。

## 2. 范围边界

**范围内**
- `pet_posts`：ADOPTION 帖子全量（创建/列表/详情/编辑/下架/取消）；FINDING 帖子基础（创建/列表/详情/下架）；`convert-to-adoption` 端点 + 完整校验（v0.1 受核实门槛限制，实际返回 409/403）。
- `adoption_applications`：提交/撤回/通过（含批量拒绝）/拒绝。
- `adoption_arrangements` + `handover_records`：确认交接（双确认幂等）/取消/异常终止；完成联动更新 `pets` 所有权 + 写 `pet_ownership_history`。

**范围外**：见 §1 非目标。FOSTER 相关字段（`care_type`/`care_start_time`/`care_end_time`）在表与实体中保留，但本迭代不提供 FOSTER 帖子创建与任何照护流程。

## 3. 技术决策

### 3.1 已确认（与你对齐）

1. **范围 = 完整领养闭环**（5.3 + 5.4 + 5.5），一步到位，不拆多次迭代。
2. **核实环节 = 自动过审**：帖子创建后直接进入 `OPEN`（写 `verified_at=now()`），保留 `PENDING_VERIFY`/`REJECTED` 状态定义供 v0.2 真实核实使用；本迭代不提供任何 admin 核实端点。

### 3.2 我的 call（实现细节，可调整）

| # | 决策 | 理由 |
|---|---|---|
| C1 | **新建三个业务模块** `com.pethelp.post` / `com.pethelp.application` / `com.pethelp.arrangement`，并在 `com.pethelp.pet` 内补处置授权与所有权历史 | 遵循 `backend-package-architecture` 顶层「业务模块优先」；`pet_authorizations`/`pet_ownership_history` 属 pet 域，留在 pet 模块 |
| C2 | **处置授权判定放 `com.pethelp.pet.service.DispositionAuthorizationService`**（接口 + impl），`post`/`application` 通过接口依赖 | 跨模块只依赖接口；§4.4 规则集中在 pet 域 |
| C3 | **`POST /pet-posts` 创建即 `OPEN`**（自动过审，无 DRAFT→PUBLISH→核实 两步） | 5.3 无独立「发布」端点，创建即发布；与 C1 自动过审一致 |
| C4 | **approve 编排在 `application` 模块**，`@Transactional(REQUIRED)` 单事务，跨模块调 `ArrangementService` 接口 + `PostMapper` + `DispositionAuthorizationService` | API 端点 `/adoption-applications/{id}/approve` 属 application；跨模块调用共享同一 REQUIRED 事务，杜绝中间态（06 §6.4） |
| C5 | **交接完成联动 `pets` 所有权放 `PetService` 新方法**（如 `transferOwnershipToAdopter`），`arrangement` 通过接口调用 | 所有权更新 + `pet_ownership_history` 是 pet 域职责，避免 arrangement 直接写 pet 表 |
| C6 | **`GET /pet-posts` 列表仅支持 `post_type` / `species` 筛选 + 分页**；距离（`ST_DWithin`）筛选留后续 | 距离筛选非闭环验收必需，geo_hash 模糊展示已够 v0.1；避免本迭代引入额外查询复杂度 |
| C7 | **`convert-to-adoption` 实现端点 + §4.4 完整校验**（`ownership_status=UNOWNED` 且 `authorization_status=AUTHORIZED` 且 FINDING 已过公示期） | 端点与校验逻辑就绪；v0.1 无运营核实，条件恒不满足 → 409/403，v0.2 打通 |
| C8 | **`pet_authorizations` / `pet_ownership_history` 本迭代只加 entity + mapper（读取/判定用），不提供授权授予/运营代授端点** | 授予端点属运营（5.7，v0.2）；v0.1 全为 SELF（owner==publisher），授权表为空 |
| C9 | **无需数据库迁移**：V1 已含全部 19 表 + `uq_active_adoption_arrangement_per_pet` / `uq_adoption_application_pending` / `uq_handover_record` / `idx_pet_posts_type_status` | 纯 Java 代码，不动 schema |

## 4. 状态流转契约（本迭代实际实现）

### 4.1 `pet_posts`（ADOPTION，自动过审）

| 转移 | 端点 | 执行者 | 失败条件（→ 错误） | 幂等 |
|---|---|---|---|---|
| 创建 → `OPEN` | `POST /pet-posts` | 发布者 | 无处置授权（403）、必填缺失（400） | 否 |
| `OPEN` → `RESERVED` | `POST /adoption-applications/{id}/approve` | 发布者 | 非 OPEN（409）、非发布者/无授权（403）、宠物已有有效安排（409，唯一索引兜底） | 否（CAS） |
| `RESERVED` → `COMPLETED` | `POST /adoption-arrangements/{id}/confirm-handover`（双方均确认后） | 系统联动 | 安排未 COMPLETED | 是 |
| `RESERVED` → `OPEN` | 安排 `cancel` / `abort` 联动 | 系统联动 | 已 COMPLETED | 否 |
| `OPEN`/`RESERVED` → `CLOSED` | `POST /pet-posts/{id}/close` | 发布者/运营 | 已终态 | 否 |
| 非终态 → `CANCELLED` | `POST /pet-posts/{id}/cancel` | 发布者 | 已 COMPLETED | 否 |

> 创建即 `OPEN`（跳过 `DRAFT`/`PUBLISHED`/`PENDING_VERIFY`）；`REJECTED` 本迭代不出现（无核实）。

### 4.2 `pet_posts`（FINDING）

| 转移 | 端点 | 执行者 | 失败条件 | 幂等 |
|---|---|---|---|---|
| 创建 → `OPEN` | `POST /pet-posts`（post_type=FINDING） | 发布者 | 引用 pet 非 ACTIVE（409） | 否 |
| `OPEN` → `CLOSED` | `POST /pet-posts/{id}/close` | 发布者 | 已终态 | 否 |
| `OPEN` → `CLOSED`（转领养） | `POST /pet-posts/{id}/convert-to-adoption` | 发布者 | §4.4 三条件不满足（409/403） | 否 |

### 4.3 `adoption_applications`

| 转移 | 端点 | 执行者 | 失败条件 | 幂等 |
|---|---|---|---|---|
| 创建 → `PENDING` | `POST /adoption-posts/{id}/applications` | 申请人 | 帖子非 OPEN（409）、已有自己 PENDING（409，唯一索引兜底） | 否 |
| `PENDING` → `APPROVED` | `approve` | 发布者 | 见 §4.1 `RESERVED` | 否 |
| `PENDING` → `REJECTED` | `reject`（人工）或 `approve` 批量（系统） | 发布者/系统 | 已 APPROVED/REJECTED | 否 |
| `PENDING` → `WITHDRAWN` | `withdraw` | 申请人 | 已 APPROVED/REJECTED | 否 |

### 4.4 `adoption_arrangements` + `handover_records`

| 转移 | 端点 | 执行者 | 失败条件 | 幂等 |
|---|---|---|---|---|
| 创建 → `PENDING_HANDOVER` | approve 联动（同事务预创建 1 条 `event=HANDOVER` 交接记录，确认位 false） | 系统 | 宠物已有有效安排（唯一索引） | 否 |
| `PENDING_HANDOVER` → `COMPLETED` | `confirm-handover`（双方各自确认，各自幂等） | 安排双方 | 未双方确认 | 是（双确认幂等） |
| `PENDING_HANDOVER` → `CANCELLED` | `cancel` | 任一方 | 已 COMPLETED | 否 |
| `PENDING_HANDOVER` → `ABORTED` | `abort` | 任一方发起，运营确认 | — | 否 |

## 5. API 契约

统一前缀 `/api/v1`，鉴权 JWT，返回 `{code, message, data}`。路径中的 `adoption-posts`/`finding-posts` 均映射 `pet_posts` 并按 `post_type` 过滤。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | /pet-posts | 普通用户 | 发布信息（post_type=FINDING/ADOPTION），创建即 OPEN |
| GET | /pet-posts | 全部 | 列表（post_type/species 筛选 + 分页） |
| GET | /pet-posts/{id} | 全部 | 详情（模糊位置 geo_hash） |
| PATCH | /pet-posts/{id} | 发布者 | 编辑（未锁定/未完成时） |
| POST | /pet-posts/{id}/close | 发布者/运营 | 下架 |
| POST | /pet-posts/{id}/convert-to-adoption | 发布者 | 寻主转领养（需已核实，v0.1 恒拒） |
| POST | /pet-posts/{id}/cancel | 发布者 | 取消 |
| POST | /adoption-posts/{id}/applications | 普通用户 | 提交领养申请 |
| POST | /adoption-applications/{id}/withdraw | 申请人 | 撤回 |
| POST | /adoption-applications/{id}/approve | 发布者 | 通过 → 建安排 + 批量拒绝其余 |
| POST | /adoption-applications/{id}/reject | 发布者 | 拒绝 |
| POST | /adoption-arrangements/{id}/confirm-handover | 安排双方 | 确认交接（服务端按 JWT 识别 from/to，幂等） |
| POST | /adoption-arrangements/{id}/cancel | 安排双方 | 取消 |
| POST | /adoption-arrangements/{id}/abort | 安排双方/运营 | 异常终止 |

### 5.1 关键 DTO（record）

```java
// post
record CreatePostRequest(Long petId, String postType, String title, String description) {}
record UpdatePostRequest(String title, String description) {}
record PostResponse(Long id, Long petId, Long publisherId, String postType, String title,
                    String description, String status, String species, String geoHash,
                    String city, String district) {}

// application
record SubmitApplicationRequest(String message) {}
record ApplicationResponse(Long id, Long postId, Long adopterId, String message, String status, String rejectReason) {}
record ApproveResponse(Long arrangementId, String postStatus, int rejectedOthers) {}

// arrangement
record ArrangementResponse(Long id, Long petId, Long postId, Long applicationId,
                           Long adopterId, Long custodianId, String status) {}
record HandoverConfirmResponse(String status, boolean fromConfirmed, boolean toConfirmed) {}
```

### 5.2 示例（approve 成功）

```
POST /api/v1/adoption-applications/123/approve
Authorization: Bearer <publisher-jwt>

→ 200
{ "code": 0, "data": { "arrangementId": 456, "postStatus": "RESERVED", "rejectedOthers": 2 } }

→ 宠物已被预留 409 { "code": 40901, "message": "该宠物已被预留" }
→ 越权 403 { "code": 40300, "message": "仅帖子发布者且具备处置授权可审核申请" }
```

## 6. 数据与 SQL 要点

- **无需新迁移**（见 C9）。相关表均已在 V1：`pet_posts`、`adoption_applications`、`adoption_arrangements`、`handover_records`、`pet_authorizations`、`pet_ownership_history`。
- **approve 单事务**（06 §6.4），顺序与影响行数校验：
  1. `SELECT ... FOR UPDATE` 锁帖子行（`PostMapper.findByIdForUpdate`）。
  2. `UPDATE pet_posts SET status='RESERVED', reserved_application_id=:appId, version=version+1 WHERE id=:postId AND status='OPEN'`（CAS，影响行数 != 1 → 409）。
  3. `INSERT adoption_arrangements`（`uq_active_adoption_arrangement_per_pet` 兜底）+ 同事务预创建 `handover_records`（`event='HANDOVER'`，`from_user_id=custodian_id → to_user_id=adopter_id`，确认位 false）。
  4. `UPDATE adoption_applications SET status='REJECTED', reject_reason='该宠物已被其他申请人匹配', version=version+1 WHERE post_id=:postId AND status='PENDING' AND id<>:approvedId`（批量拒绝）。
- **confirm-handover 幂等**（06 §6.1 ⑤）：事务锁 arrangement 行 `FOR UPDATE` → 按 JWT 身份 `UPDATE handover_records SET confirmed_by_from/to=true WHERE ... AND confirmed_*_false` → 两确认位均 true 才推进；重复提交 no-op。完成时同事务：arrangement `COMPLETED` + 帖子 `COMPLETED` + `UPDATE pets SET owner_id=adopter, current_custodian_id=adopter, ownership_status='OWNED'` + `INSERT pet_ownership_history`。
- **cancel/abort** 联动帖子 `RESERVED → OPEN`（清空 `reserved_application_id`）；`abort` 懒创建 `event='ABORT'` 交接记录（`ON CONFLICT DO NOTHING`）。

## 7. 分层与文件清单（backend-package-architecture）

```
com.pethelp.post/
├── controller/PostController.java
├── service/PostService.java + impl/PostServiceImpl.java
├── dto/CreatePostRequest.java / UpdatePostRequest.java / PostResponse.java
├── entity/PetPost.java
└── mapper/PostMapper.java            (+ resources/mapper/PostMapper.xml)

com.pethelp.application/
├── controller/AdoptionApplicationController.java
├── service/AdoptionApplicationService.java + impl/AdoptionApplicationServiceImpl.java
├── dto/SubmitApplicationRequest.java / ApplicationResponse.java / ApproveResponse.java
├── entity/AdoptionApplication.java
└── mapper/AdoptionApplicationMapper.java  (+ AdoptionApplicationMapper.xml)

com.pethelp.arrangement/
├── controller/AdoptionArrangementController.java
├── service/ArrangementService.java + impl/ArrangementServiceImpl.java
├── dto/ArrangementResponse.java / HandoverConfirmResponse.java
├── entity/AdoptionArrangement.java / HandoverRecord.java
└── mapper/AdoptionArrangementMapper.java / HandoverRecordMapper.java  (+ 2 个 xml)

com.pethelp.pet/  （新增，pet 域职责）
├── entity/PetAuthorization.java / PetOwnershipHistory.java
├── mapper/PetAuthorizationMapper.java / PetOwnershipHistoryMapper.java  (+ 2 个 xml)
├── service/DispositionAuthorizationService.java + impl/DispositionAuthorizationServiceImpl.java
└── service/PetService.java（+impl）增 transferOwnershipToAdopter(...)；PetMapper.xml 增对应 SQL
```

- 跨模块依赖（只接口）：`post` → `pet`（`DispositionAuthorizationService`、`PetMapper`）；`application` → `post`（`PostMapper`）+ `pet`（`DispositionAuthorizationService`）+ `arrangement`（`ArrangementService`）；`arrangement` → `post`（`PostMapper`）+ `pet`（`PetService`、`PetMapper`）。
- `SecurityConfig` 需改一处：`GET /api/v1/pet-posts` 与 `GET /api/v1/pet-posts/{id}` 是「公开」访问（docs/05 §5.3），加 `permitAll`；其余端点走 `anyRequest().authenticated()`。

## 8. 权限与安全校验（Service 层，禁止 assert）

- **create post**：`pet` 存在且 `pet.status='ACTIVE'`（否则 404/409）；`post_type ∈ {FINDING, ADOPTION}`；ADOPTION 需 `DispositionAuthorizationService.canPublishAdoption(petId, userId)`（owner 本人 SELF 或有 ACTIVE `PUBLISH` 授权）；FINDING 允许任何该 pet 的发布者/处置方（v0.1 从宽：pet 的 publisher 或 owner）。
- **edit/close/cancel**：`post.publisherId == currentUserId`（否则 403）+ 状态前置（锁定/终态 → 409）。
- **submit application**：帖子 `OPEN`（否则 409）；`post_type=ADOPTION`；防重提交（`uq_adoption_application_pending` 唯一索引兜底 + `DuplicateKeyException` → 409）。
- **approve**：`application.status='PENDING'`；`post.publisherId == currentUserId` 且 `DispositionAuthorizationService.canApproveAdoption(petId, userId)`；`post.status='OPEN'`；CAS + 唯一索引兜底。
- **confirm-handover**：`currentUserId ∈ {adopter_id, custodian_id}`（否则 403）；按身份置对应确认位；状态前置（终态 → 409）。
- **精确位置**：不因申请通过/交接而自动放行；仍走既有 `location_access_grants` 授权（本迭代不改）。

## 9. 测试方案

### 9.1 单元测试（各模块同包，Mockito mock mapper）

- `PostServiceImplTest`：创建（含授权校验、自动 OPEN）、编辑/下架/取消的状态前置、`convert-to-adoption` 条件不满足 → 409。
- `AdoptionApplicationServiceImplTest`：提交（OPEN 校验、防重）、撤回/拒绝状态前置、**approve 单事务路径**（CAS 失败 → 409、批量拒绝调用、建安排调用）。
- `ArrangementServiceImplTest`：确认交接（from/to 身份判定、幂等、双方确认推进、非参与者 403）、cancel/abort 联动。
- `DispositionAuthorizationServiceImplTest`：SELF 放行、ACTIVE 授权放行、无授权拒绝。

### 9.2 集成测试（`com.pethelp.AdoptionFlowIntegrationTest`，Testcontainers postgis+redis，MockMvc）

1. 全链路：owner 建 pet→publish→发 ADOPTION 帖子→adopter 申请→owner approve（返回 arrangementId、postStatus=RESERVED、rejectedOthers=N）→ 双方 confirm-handover → 帖子 COMPLETED、pets 所有权/照护者/ownership_status 更新、`pet_ownership_history` 落一条。
2. 并发预留：两申请人同时申请，owner approve 其一，另一被批量 REJECTED；`uq_active_adoption_arrangement_per_pet` 兜底（模拟重复 approve → 409）。
3. 越权：非发布者 approve → 403；非参与者 confirm-handover → 403；非本人 withdraw → 403。
4. 幂等：同一方重复 confirm-handover → no-op 不重复写库。
5. 防重申请：同一 adopter 对同一帖子重复申请 → 409，DB 仅一条 PENDING。
6. 精确位置隔离：申请通过/交接完成后，adopter 仍需授权才能看精确位置。

> 遵循 docs/09：真实 PostGIS 镜像，不用 H2（依赖 `ST_X`/`ST_Y`/`FOR UPDATE`/唯一部分索引）。

## 10. 新增依赖与异常类

- 无新增 Maven 依赖。
- 异常类：复用 `ForbiddenException`/`ConflictException`/`NotFoundException`/`BadRequestException`。`approve` 的「宠物已被预留」用 `ConflictException`（`code 40901`，或复用 40900 + 文案；如需 40901 再议）。
- 无新增数据库迁移（C9）。

## 11. 留给 builder 的自由度

- `findByIdForUpdate` / `reserve` / `rejectOthers` / `confirmFrom`/`confirmTo` 等 SQL 落在哪个 mapper 及具体命名。
- 各模块 DTO 字段的最终取舍（列表项是否含 `publisherNickname` 等冗余展示字段）。
- `convert-to-adoption` 若实现代价超出预期，可与我对齐后整体 defer 到 v0.2（端点 + 校验整体移除，不留死代码）。
- 集成测试如何复用现有 JWT 登录 helper（参照 `LocationAccessFlowIntegrationTest`）。
