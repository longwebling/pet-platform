# 举报与运营模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 v0.1 举报与运营（report + admin + 图片/帖子审核）治理闭环，含 1 个用户举报端点 + 11 个 `/admin/**` 端点。

**Architecture:** 新增 `com.pethelp.report`（举报全生命周期）与 `com.pethelp.admin`（跨域运营动作，无自有表）两个业务模块，遵循 modular-monolith；角色校验在 service 层通过 `CurrentUser.isOps()/isAdmin()` 完成，`/admin/**` 在 SecurityConfig 加 `hasAnyRole` 兜底。审核保持自动过审，运营端点做「事后处置」（驳回/下架/恢复）。

**Tech Stack:** Java 17 + Spring Boot 3.5 + MyBatis + PostgreSQL/PostGIS + Spring Security(JWT) + JUnit5/Mockito/AssertJ + Testcontainers。

**Spec:** `docs/superpowers/specs/2026-11-27-report-admin-design.md`

## Global Constraints

- 根包 `com.pethelp`，业务模块内 `controller/service(+impl)/dto/entity/mapper` 分层；模块间只依赖接口（service/mapper/dto/entity），禁止依赖他模块 `impl`。
- 统一响应 `ApiResponse { code, message, data }`；错误码 `40000`/`40100`/`40300`/`40400`/`40900`/`50000` 与 HTTP 对齐（`ForbiddenException`=40300、`NotFoundException`=40400、`ConflictException`=40900、`BadRequestException`=40000）。
- 角色：`ops` = 处理举报/下架/驳回/审核/精确位置；`admin` = 封禁（含 ops）；`isOps()` = `ops` 或 `admin`。
- mapper XML 位于 `backend/src/main/resources/mapper/`，`namespace` 与实体 `type` 用全限定名。
- 状态机由后端推导，条件 UPDATE 校验影响行数，0 行抛 `ConflictException`。
- 提交信息用 conventional commits：`feat(report): ...`、`feat(admin): ...`。
- 单测与模块同包（`com.pethelp.<module>`），集成测试放 `com.pethelp` 根包命名 `*FlowIntegrationTest`。

## Review Focus

1. **越权访问 `/admin/**`**：普通 `user` 角色调任意 `/admin/**` 必须 403（SecurityConfig 兜底 + service 层 `isOps/isAdmin` 双重校验）。
2. **封禁后登录**：`BANNED` 用户下次 `POST /auth/login` 必须 403「账号已被封禁」。
3. **举报目标不存在**：`POST /reports` 的 `targetId` 指向不存在的 PET/POST/USER 必须 404。
4. **重复处理举报**：对已 `RESOLVED/DISMISSED` 的举报再 `handle` 必须 409。
5. **对终态帖子下架/驳回**：对已 `CLOSED`/`COMPLETED`/`REJECTED` 帖子执行 take-down/reject/verify 必须 409（条件 UPDATE 0 行）。

---

### Task 1: `CurrentUser` 角色助手 + SecurityConfig 兜底

**Files:**
- Modify: `backend/src/main/java/com/pethelp/security/CurrentUser.java`
- Modify: `backend/src/main/java/com/pethelp/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/pethelp/security/CurrentUserTest.java`

**Interfaces:**
- Produces: `CurrentUser.hasRole(String) -> boolean`、`CurrentUser.isOps() -> boolean`、`CurrentUser.isAdmin() -> boolean`（`roles == null` 时 `hasRole` 返回 false）。

- [ ] **Step 1: 写失败测试** `CurrentUserTest`：`isOps_opsOrAdmin_true`、`isOps_plainUser_false`、`isAdmin_admin_true`、`isAdmin_opsOnly_false`、`hasRole_nullRoles_false`。断言如 `new CurrentUser(1L, List.of("ops")).isOps()` 为 true。

- [ ] **Step 2: 运行测试确认失败**（编译失败：方法不存在）。Run: `mvn -q -pl . test -Dtest=CurrentUserTest`（在 backend/ 下 `mvn test -Dtest=CurrentUserTest`）。

- [ ] **Step 3: 实现** 在 `CurrentUser` record 内加三个实例方法：`hasRole`（`roles != null && roles.contains(role)`）、`isOps`（`hasRole("ops") || hasRole("admin")`）、`isAdmin`（`hasRole("admin")`）。在 `SecurityConfig` 的 `authorizeHttpRequests` 中，`.anyRequest().authenticated()` 之前插入 `.requestMatchers("/api/v1/admin/**").hasAnyRole("ops","admin")`。

- [ ] **Step 4: 运行测试确认通过** Run: `mvn test -Dtest=CurrentUserTest`，Expected: PASS。

- [ ] **Step 5: Commit** `git add` 上述三文件，`git commit -m "feat(security): add role helpers on CurrentUser and /admin/** gate"`。

---

### Task 2: 封禁基础设施（UserMapper.updateStatus + 登录拦截）

**Files:**
- Modify: `backend/src/main/java/com/pethelp/user/mapper/UserMapper.java`
- Modify: `backend/src/main/resources/mapper/UserMapper.xml`
- Modify: `backend/src/main/java/com/pethelp/auth/service/impl/AuthServiceImpl.java`
- Test: `backend/src/test/java/com/pethelp/auth/AuthServiceImplTest.java`

**Interfaces:**
- Consumes: `CurrentUser`（本任务不直接消费，仅新增 mapper/登录逻辑）。
- Produces: `UserMapper.updateStatus(Long id, String status) -> int`（供 Task 6 封禁调用）。

- [ ] **Step 1: 写失败测试** `AuthServiceImplTest`（mock `UserMapper`/`SmsCodeService`/`JwtService`）：`login_bannedUser_throwsForbidden` —— `when(smsCodeService.verify(phone,code)).thenReturn(true)`、`when(userMapper.findByPhone(phone)).thenReturn(bannedUser(status="BANNED"))`，断言 `assertThatThrownBy(() -> service.login(...)).isInstanceOf(ForbiddenException.class)`。

- [ ] **Step 2: 运行测试确认失败**（登录未拦截 BANNED）。Run: `mvn test -Dtest=AuthServiceImplTest`。

- [ ] **Step 3: 实现** `UserMapper` 加 `int updateStatus(@Param("id") Long id, @Param("status") String status)`；XML 加 `<update id="updateStatus">UPDATE users SET status=#{status}, updated_at=now() WHERE id=#{id}</update>`。`AuthServiceImpl.login` 在 `findByPhone` 后、生成 token 前加：`if (user != null && "BANNED".equals(user.getStatus())) throw new ForbiddenException("账号已被封禁");`（import `ForbiddenException`）。

- [ ] **Step 4: 运行测试确认通过** Run: `mvn test -Dtest=AuthServiceImplTest`，Expected: PASS。

- [ ] **Step 5: Commit** `git commit -m "feat(user): add updateStatus and block BANNED login"`。

---

### Task 3: report 模块（举报全生命周期）

**Files:**
- Create: `backend/src/main/java/com/pethelp/report/entity/Report.java`
- Create: `backend/src/main/java/com/pethelp/report/mapper/ReportMapper.java`
- Create: `backend/src/main/resources/mapper/ReportMapper.xml`
- Create: `backend/src/main/java/com/pethelp/report/dto/CreateReportRequest.java`
- Create: `backend/src/main/java/com/pethelp/report/dto/HandleReportRequest.java`
- Create: `backend/src/main/java/com/pethelp/report/dto/ReportResponse.java`
- Create: `backend/src/main/java/com/pethelp/report/service/ReportService.java`
- Create: `backend/src/main/java/com/pethelp/report/service/impl/ReportServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/report/controller/ReportController.java`
- Test: `backend/src/test/java/com/pethelp/report/ReportServiceImplTest.java`

**Interfaces:**
- Consumes: `CurrentUser`、`PetMapper.findById`、`PostMapper.findById`、`UserMapper.findById`、`ForbiddenException` 等 common 异常。
- Produces: `ReportService.create(CurrentUser, CreateReportRequest) -> ReportResponse`、`list(String status, int page, int size) -> List<ReportResponse>`、`handle(CurrentUser, Long reportId, HandleReportRequest) -> ReportResponse`。

**关键签名/值**
- `Report` 字段：`Long id, Long reporterId, String targetType, Long targetId, String reason, String status, Long handledBy, OffsetDateTime createdAt`。
- `ReportMapper`：`int insert(Report)`（useGeneratedKeys）、`Report findById(Long id)`、`List<Report> list(String status, int offset, int limit)`（`<if status 非空>` 过滤，`ORDER BY id DESC LIMIT/OFFSET`）、`int handle(Long id, String status, Long handledBy)`（`UPDATE reports SET status=#{status}, handled_by=#{handledBy} WHERE id=#{id} AND status='PENDING'`）。
- DTO：`CreateReportRequest(String targetType, Long targetId, String reason)`、`HandleReportRequest(String action)`、`ReportResponse(Long id, Long reporterId, String targetType, Long targetId, String reason, String status, Long handledBy, OffsetDateTime createdAt)`。
- `create` 规则：`targetType ∈ {PET,POST,USER}`（否则 400）；目标存在（PET→petMapper、POST→postMapper、USER→userMapper，null→404）；`reason` 非空（400）；insert `status=PENDING, reporterId=currentUser.id()`。
- `handle` 规则：`!currentUser.isOps()`→403；`findById` null→404；`status != PENDING`→409；`action ∈ {RESOLVED,DISMISSED}`（否则 400）；`mapper.handle(id, action, currentUser.id())`。

- [ ] **Step 1: 写失败测试** `ReportServiceImplTest`（mock `ReportMapper`/`PetMapper`/`PostMapper`/`UserMapper`）：`create_petTarget_ok`、`create_badTargetType_400`、`create_targetNotFound_404`、`create_blankReason_400`、`handle_byOps_pending_ok`、`handle_byPlainUser_403`、`handle_notPending_409`、`handle_badAction_400`、`list_validPagination_ok`。

- [ ] **Step 2: 运行测试确认失败**（类不存在）。Run: `mvn test -Dtest=ReportServiceImplTest`。

- [ ] **Step 3: 实现** 按上述签名/规则实现 entity、mapper(+XML)、dto、service 接口+impl、controller（`@RequestMapping("/api/v1")`，三端点：`POST /reports`、`GET /admin/reports`、`POST /admin/reports/{id}/handle`）。controller 注入 `@AuthenticationPrincipal CurrentUser`。

- [ ] **Step 4: 运行测试确认通过** Run: `mvn test -Dtest=ReportServiceImplTest`，Expected: PASS。

- [ ] **Step 5: Commit** `git commit -m "feat(report): add report create/list/handle"`。

---

### Task 4: PostMapper 运营方法（takeDown/reject/verify/listForAdmin）

**Files:**
- Modify: `backend/src/main/java/com/pethelp/post/mapper/PostMapper.java`
- Modify: `backend/src/main/resources/mapper/PostMapper.xml`

**Interfaces:**
- Produces: `int takeDown(Long id)`、`int reject(Long id, String reason)`、`int verify(Long id)`、`List<PetPost> listForAdmin(String status, int offset, int limit)`（供 Task 6）。

**SQL（条件 UPDATE，沿用 `version=version+1`）**
- `takeDown`：`SET status='CLOSED' ... WHERE id=#{id} AND status IN ('OPEN','RESERVED')`
- `reject`：`SET status='REJECTED', reject_reason=#{reason} ... WHERE id=#{id} AND status IN ('OPEN','RESERVED')`
- `verify`：`SET status='OPEN', verified_at=now(), reject_reason=NULL ... WHERE id=#{id} AND status='REJECTED'`
- `listForAdmin`：复用 `postColumns`，`<if status 非空>` 过滤 `pp.status=#{status}`，`ORDER BY pp.id DESC LIMIT/OFFSET`。

- [ ] **Step 1: 写失败测试**（本任务方法由 Task 6 单测 mock、Task 7 集成测试覆盖 SQL；此处加 mapper 集成测试 `PostMapperAdminMethodsIntegrationTest` 或并入 Task 7。若无独立集成测试，本任务以「编译 + 后续 Task 6 依赖」为验证）。Run: `mvn -q compile`（backend 下）确认编译通过。

- [ ] **Step 2: 实现** 在 `PostMapper` 加 4 个方法签名，XML 加对应 SQL。

- [ ] **Step 3: 验证编译** Run: `mvn -q compile`，Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit** `git commit -m "feat(post): add admin take-down/reject/verify/list mappers"`。

---

### Task 5: PetImageMapper 审核方法 + LocationAccessService.getExactLocationForOps

**Files:**
- Modify: `backend/src/main/java/com/pethelp/pet/mapper/PetImageMapper.java`
- Modify: `backend/src/main/resources/mapper/PetImageMapper.xml`
- Modify: `backend/src/main/java/com/pethelp/pet/service/LocationAccessService.java`
- Modify: `backend/src/main/java/com/pethelp/pet/service/impl/LocationAccessServiceImpl.java`
- Test: `backend/src/test/java/com/pethelp/pet/LocationAccessServiceImplTest.java`（新，覆盖 `getExactLocationForOps`）

**Interfaces:**
- Consumes: `PetImage` 实体、`PetMapper`、`AddressVisibilityLogMapper`。
- Produces: `PetImageMapper.findById(Long) -> PetImage`、`PetImageMapper.list(String reviewStatus, int offset, int limit) -> List<PetImage>`、`PetImageMapper.updateReviewStatus(Long id, String status) -> int`；`LocationAccessService.getExactLocationForOps(Long petId, Long viewerId) -> ExactLocationResponse`（供 Task 6）。

**SQL/规则**
- `findById`：`SELECT id, pet_id, uploader_id, file_key, file_type, review_status FROM pet_images WHERE id=#{id}`。
- `list`：同列，`<if reviewStatus 非空>` `AND review_status=#{reviewStatus}`，`ORDER BY id DESC LIMIT/OFFSET`。
- `updateReviewStatus`：`UPDATE pet_images SET review_status=#{status} WHERE id=#{id}`。
- `getExactLocationForOps`：`petMapper.findById(petId)` null→404；`resolveExactLocation(petId)`（复用私有方法）；写 `AddressVisibilityLog`（viewer=viewerId、petId、`addressType="EXACT"`、postId=null）；**不做 grant 校验**。

- [ ] **Step 1: 写失败测试** `LocationAccessServiceImplTest`：`getExactLocationForOps_petNotFound_404`、`getExactLocationForOps_writesAuditAndReturnsCoords`（mock petMapper 返回 Pet、`findExactLocation` 返回 `{lng:121.5,lat:31.2}`，verify `visibilityLogMapper.insert` 被调一次）。

- [ ] **Step 2: 运行测试确认失败**。Run: `mvn test -Dtest=LocationAccessServiceImplTest`。

- [ ] **Step 3: 实现** 按上述 SQL/规则实现 mapper + XML 与 `LocationAccessService` 接口方法 + impl。

- [ ] **Step 4: 运行测试确认通过**。Run: `mvn test -Dtest=LocationAccessServiceImplTest`。

- [ ] **Step 5: Commit** `git commit -m "feat(pet): add image review mappers and ops exact-location access"`。

---

### Task 6: admin 模块（跨域运营动作 + 角色校验）

**Files:**
- Create: `backend/src/main/java/com/pethelp/admin/dto/AdminImageResponse.java`
- Create: `backend/src/main/java/com/pethelp/admin/dto/RejectPostRequest.java`
- Create: `backend/src/main/java/com/pethelp/admin/dto/UserBanResponse.java`
- Create: `backend/src/main/java/com/pethelp/admin/service/AdminService.java`
- Create: `backend/src/main/java/com/pethelp/admin/service/impl/AdminServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/admin/controller/AdminController.java`
- Test: `backend/src/test/java/com/pethelp/admin/AdminServiceImplTest.java`

**Interfaces:**
- Consumes: Task1 `CurrentUser.isOps()/isAdmin()`、Task2 `UserMapper.updateStatus`、Task4 `PostMapper.takeDown/reject/verify/listForAdmin`、Task5 `PetImageMapper.findById/list/updateReviewStatus` + `LocationAccessService.getExactLocationForOps`、`PetMapper.findById`、`PostResponse`、`PetPost`、`PetImage`。
- Produces: `AdminService` 9 个方法（见下），`AdminController` 9 端点。

**DTO**
- `AdminImageResponse(Long id, Long petId, Long uploaderId, String fileType, String reviewStatus)`。
- `RejectPostRequest(String reason)`。
- `UserBanResponse(Long userId, String status)`。

**AdminService 方法签名**
- `PostResponse takeDownPost(CurrentUser cu, Long postId)`
- `PostResponse rejectPost(CurrentUser cu, Long postId, RejectPostRequest req)`
- `PostResponse verifyPost(CurrentUser cu, Long postId)`
- `List<PostResponse> listPosts(CurrentUser cu, String status, int page, int size)`
- `AdminImageResponse approveImage(CurrentUser cu, Long imageId)`
- `AdminImageResponse rejectImage(CurrentUser cu, Long imageId)`
- `List<AdminImageResponse> listImages(CurrentUser cu, String reviewStatus, int page, int size)`
- `UserBanResponse banUser(CurrentUser cu, Long userId)`
- `ExactLocationResponse getPetExactLocation(CurrentUser cu, Long petId)`

**规则**
- 除 `banUser` 用 `!cu.isAdmin()`→403 外，其余全部先 `!cu.isOps()`→403。
- `takeDownPost/rejectPost/verifyPost`：`postMapper.findById` null→404；对应 `takeDown/reject/verify` 影响 0 行→409；返回 `PostResponse`（从 `PetPost` 映射，字段见 `PostResponse` record）。
- `rejectPost` 需 `req.reason()` 非空→400。
- `approveImage/rejectImage`：`petImageMapper.findById` null→404；`updateReviewStatus(id, "APPROVED"/"REJECTED")`；返回 `AdminImageResponse`。
- `banUser`：`userMapper.findById` null→404；已是 `BANNED`→409；`updateStatus(id,"BANNED")`；返回 `UserBanResponse(userId,"BANNED")`。
- `listPosts/listImages`：`page<1||size<1`→400；`offset=(page-1)*size`。
- `getPetExactLocation`：委托 `locationAccessService.getExactLocationForOps(petId, cu.id())`。

**AdminController**（`@RequestMapping("/api/v1/admin")`）9 端点，路径见 spec §6.2；注入 `@AuthenticationPrincipal CurrentUser`。

- [ ] **Step 1: 写失败测试** `AdminServiceImplTest`（mock 全部依赖）：`takeDownPost_byOps_ok`、`takeDownPost_byPlainUser_403`、`rejectPost_blankReason_400`、`rejectPost_postNotFound_404`、`verifyPost_ok`、`banUser_byAdmin_ok`、`banUser_byOps_403`、`banUser_alreadyBanned_409`、`approveImage_ok`、`rejectImage_ok`、`listPosts_validPagination_ok`、`getPetExactLocation_byOps_ok`。

- [ ] **Step 2: 运行测试确认失败**（类不存在）。Run: `mvn test -Dtest=AdminServiceImplTest`。

- [ ] **Step 3: 实现** 按签名/规则实现 dto、service 接口+impl、controller。

- [ ] **Step 4: 运行测试确认通过**。Run: `mvn test -Dtest=AdminServiceImplTest`。

- [ ] **Step 5: Commit** `git commit -m "feat(admin): add admin moderation/review/ban/exact-location endpoints"`。

---

### Task 7: 端到端集成测试 ReportAdminFlowIntegrationTest

**Files:**
- Test: `backend/src/test/java/com/pethelp/ReportAdminFlowIntegrationTest.java`

**Interfaces:**
- Consumes: 全部 Task 1-6 产物。复用 `AuthFlowIntegrationTest` 的 Testcontainers 夹具（PostGIS + Redis）；注入 `UserMapper`、`JwtService` 用于直接造 `ops`/`admin` 角色用户并签发 token。

**辅助方法**（测试内私有）
- `String createToken(String phone, String... roles)`：`User u=new User(); u.setPhone(phone); u.setRoles(List.of(roles)); userMapper.insert(u); return jwtService.generateToken(u.getId(), List.of(roles));`

**测试用例**
1. `fullReportAdminFlow`：user 发 POST → ops `GET /admin/posts`（见 PENDING？不，帖子自动 OPEN）→ ops `POST /admin/posts/{id}/reject`（→REJECTED）→ ops `POST /admin/posts/{id}/verify`（→OPEN）。
2. `imageReviewFlow`：user 上传图片（自动 APPROVED）→ ops `GET /admin/images` → ops `POST /admin/images/{id}/reject`（→REJECTED）→ ops `POST /admin/images/{id}/approve`（→APPROVED）。
3. `reportFlow`：user `POST /reports`（target=POST）→ ops `GET /admin/reports` → ops `POST /admin/reports/{id}/handle`（RESOLVED）。
4. `banFlow_loginBlocked`：admin `POST /admin/users/{id}/ban` → 该 user 再次 `POST /auth/login` 得 403/40300。
5. `exactLocationOpsFlow`：ops `POST /admin/pets/{id}/exact-location` 得 200 + 坐标；`address_visibility_logs` 新增一行。
6. `idor_plainUserCallsAdmin_403`：普通 user token 调 `GET /admin/reports`、`POST /admin/posts/{id}/take-down` 等，断言 403/40300（覆盖 Review Focus 1/2/3/4/5 的越权与状态冲突场景）。

- [ ] **Step 1: 写测试** 上述用例，用 `MockMvc` + `jsonPath` 断言。

- [ ] **Step 2: 运行确认失败或通过**（依赖 Docker）。Run: `mvn test -Dtest=ReportAdminFlowIntegrationTest`。若某行为未实现则失败，逐条修复对应 Task 代码。

- [ ] **Step 3: 全量测试** Run: `mvn test`，Expected: 全绿（174 + 新增）。

- [ ] **Step 4: Commit** `git commit -m "test(report-admin): add end-to-end flow integration test"`。

---

### Task 8: 归档文档 + 进度回写

**Files:**
- Create: `docs/archive/report-admin.md`（用 `api-doc-archive` skill 生成：Mermaid 时序图 + 错误码表 + curl 调试步骤）
- Modify: `docs/10-development-progress.md`（第 2 节补 report/admin 行、第 5 节更新待办）

- [ ] **Step 1: 生成归档** 用 `api-doc-archive` skill 产出 `docs/archive/report-admin.md`。

- [ ] **Step 2: 回写进度** 更新 `10-development-progress.md`：完成表加「举报与运营」+「图片/帖子审核」，待开发移除 5.7；更新测试数。

- [ ] **Step 3: Commit** `git commit -m "docs: add report-admin archive and update progress"`。

---

## Self-Review Notes

- **Spec coverage**: §6.1（report 3 端点）→ Task 3；§6.2（admin 9 端点）→ Task 6；§7.1/7.2 模块 → Task 3/6；§7.3 跨模块改动 → Task 2/4/5 + Task1(SecurityConfig)；§8 状态机扩展 → Task 4/6；§9 测试 → Task 3/5/6/7；§10 文档 → Task 8。
- **Type consistency**: `PostMapper.reject(Long id, String reason)` 与 `AdminServiceImpl.rejectPost` 一致；`PetImageMapper.updateReviewStatus(Long, String)` 与 `approveImage/rejectImage` 一致；`getExactLocationForOps(Long petId, Long viewerId)` 与 `getPetExactLocation` 委托一致。
- **Review Focus** 每条均有对应测试：1→Task7 用例 6 + SecurityConfig；2→Task2 + Task7 用例 4；3→Task3 `create_targetNotFound_404` + Task7；4→Task3 `handle_notPending_409`；5→Task6 `rejectPost/takeDownPost` 影响 0 行 409 + Task7。
