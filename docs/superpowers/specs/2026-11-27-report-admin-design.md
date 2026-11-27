# 举报与运营模块设计（report + admin + 审核）

> 日期：2026-11-27
> 范围：补齐 v0.1 的「举报与运营 + 图片/帖子审核」能力，对应 `05-api.md` 5.7、`07-mvp-plan.md` 第 13/14 项，以及 `docs/archive/*` 中 deferred 的「真实运营核实/审核」。

## 1. 意图与背景

v0.1 领养闭环已上线，但缺治理闭环：用户举报违规内容，运营（`ops`）处理举报、下架/驳回内容、审核图片与帖子、特殊查看精确位置，管理员（`admin`）封禁用户。

**审核原则（已确认）**：图片上传 / 帖子发布**保持自动过审**（`APPROVED` / `OPEN`），不引入 `PENDING` 门槛、不改现有领养闭环。运营审核接口是**事后处置**能力：对已自动过审但被举报 / 违规的内容，运营可驳回（`REJECTED`）或下架（`CLOSED`），也可恢复（`APPROVED` / `OPEN`）。

## 2. 现状（recon 结论）

- **无新增 DDL 迁移**：`V1` 已含 `reports`、`audit_logs`、`address_visibility_logs`；`users.roles`（`user/ops/admin`）、`users.status`（`ACTIVE/BANNED`）；`pet_images.review_status`（`PENDING/APPROVED/REJECTED`）；`pet_posts.status`（含 `reject_reason`、`verified_at` 列）。
- 角色链路已通：JWT → `CurrentUser(id, roles)`，但**无任何角色校验**，只有服务层「本人」校验。
- 自动过审现状：`PetImageServiceImpl.upload` 调 `AutoApproveImageReviewService`（恒 APPROVED）；`PostServiceImpl.create` 直接 `status=OPEN, verified_at=now()`。

## 3. 目标 / 非目标 / 反目标

**目标**
- 用户举报 + 运营处理 + 内容下架/驳回/封禁 + 精确位置特殊访问 + 图片/帖子审核（事后处置）。
- 服务层角色校验：`ops`（处理举报/下架/驳回/审核/精确位置），`admin`（封禁），`admin` 蕴含 `ops`。
- 封禁 = 置 `BANNED` + 登录拦截；已发 token 到 TTL 自然失效。

**非目标（本轮不做）**
- 不写 `audit_logs`（精确位置审计走已有 `address_visibility_logs`；下架/封禁/驳回暂不落审计）。
- 不做举报去重、举报自动仲裁、运营工作台 UI、`notification` 通知、`PENDING_VERIFY` 人工审核门槛、真实机器审核（OSS/内容安全 SPI 接外部）。
- 不改现有领养闭环的自动过审行为（除新增「运营可驳回/下架」外，原有状态流转不变）。

**反目标（做了算失败）**
- 客户端能直接改状态；越权可访问（普通用户冒充 ops/admin，IDOR）。

## 4. 约束

- 遵循 `backend-package-architecture`：模块化单体，模块间只依赖接口。
- 错误码：`40300` 越权、`40400` 不存在、`40900` 状态冲突、`40000` 参数错误。
- 角色校验放 service 层；`/admin/**` 在 `SecurityConfig` 加 `hasAnyRole("ops","admin")` 兜底。

## 5. Approaches（已选）

| 方案 | 取舍 | 结论 |
|---|---|---|
| A. report + admin 两模块 | report 管举报全生命周期；admin 管跨域运营动作（下架/驳回/审核/封禁/精确位置） | **采用** |
| B. 单 admin 统一 | 所有 `/admin/*` 集中，admin 同时依赖 4 模块 | 弃 |
| C. 分散到各域 | admin 端点散落，难统一治理 | 弃 |
| 审核：改 PENDING 门槛 | 需改现有闭环 + 更新 174 测试 | 弃（用户选保持自动过审） |
| 审核：保持自动过审 + 事后处置 | 不改现有闭环，运营可驳回/下架/恢复 | **采用** |

## 6. 端点清单（最终）

### 6.1 report 模块（`com.pethelp.report`）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/reports` | 登录 | 举报（targetType ∈ PET/POST/USER） |
| GET | `/api/v1/admin/reports` | ops/admin | 举报列表（status + 分页） |
| POST | `/api/v1/admin/reports/{id}/handle` | ops/admin | 处理举报（`{action: RESOLVED\|DISMISSED}`） |

### 6.2 admin 模块（`com.pethelp.admin`）

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/v1/admin/posts/{id}/take-down` | ops/admin | 帖子下架 → `CLOSED` |
| POST | `/api/v1/admin/posts/{id}/reject` | ops/admin | 帖子核实驳回 → `REJECTED` + `reject_reason` |
| POST | `/api/v1/admin/posts/{id}/verify` | ops/admin | 帖子恢复/核实通过 → `OPEN` + `verified_at` |
| GET | `/api/v1/admin/posts` | ops/admin | 帖子列表（status + 分页，供审核） |
| POST | `/api/v1/admin/images/{id}/approve` | ops/admin | 图片通过 → `APPROVED` |
| POST | `/api/v1/admin/images/{id}/reject` | ops/admin | 图片驳回 → `REJECTED` |
| GET | `/api/v1/admin/images` | ops/admin | 图片列表（reviewStatus + 分页，供审核） |
| POST | `/api/v1/admin/users/{id}/ban` | admin | 封禁 → `BANNED` |
| POST | `/api/v1/admin/pets/{id}/exact-location` | ops/admin | 精确位置特殊访问（审计） |

> 审核语义：自动过审下 `verify`/`approve` 为「恢复」操作（驳回后重新上线），`reject` 为「驳回/隐藏」核心动作，`take-down` 为 5.7 的「下架」（CLOSED，中性下架，与 `REJECTED` 语义区分）。

## 7. 模块与跨模块改动

### 7.1 report 模块（新增）

- `entity/Report`：id、reporterId、targetType、targetId、reason、status、handledBy、createdAt。
- `mapper/ReportMapper` + `resources/mapper/ReportMapper.xml`：`insert`、`findById`、`list(status, offset, limit)`、`handle(id, status, handledBy)`（`WHERE status='PENDING'` CAS）。
- `dto`：`CreateReportRequest(targetType, targetId, reason)`、`ReportResponse`、`HandleReportRequest(action)`。
- `service/ReportService` + `impl`：`create`（校验 targetType、目标存在、reason 非空）、`list`、`handle`（`requireOps`）。
- `controller/ReportController`：见 6.1。

### 7.2 admin 模块（新增，无自有表）

- `service/AdminService` + `impl`（依赖跨模块接口），`controller/AdminController`（`@RequestMapping("/api/v1/admin")`）、`dto/AdminImageResponse`。
- 各动作：`requireOps` 或 `requireAdmin`（封禁）。

### 7.3 跨模块改动（最小侵入）

- `post/mapper/PostMapper` + XML 新增：
  - `int takeDown(Long id)`：`SET status='CLOSED' WHERE id=#{id} AND status IN ('OPEN','RESERVED')`。
  - `int reject(Long id, String reason)`：`SET status='REJECTED', reject_reason=#{reason} WHERE id=#{id} AND status IN ('OPEN','RESERVED')`。
  - `int verify(Long id)`：`SET status='OPEN', verified_at=now(), reject_reason=NULL WHERE id=#{id} AND status IN ('REJECTED')`。
  - `List<PetPost> listForAdmin(status, offset, limit)`（带 status 过滤）。
- `pet/mapper/PetImageMapper` + XML 新增：`findById`、`list(reviewStatus, offset, limit)`、`updateReviewStatus(id, status)`。
- `user/mapper/UserMapper` 新增：`int updateStatus(id, status)`。
- `pet/service/LocationAccessService` 新增：`ExactLocationResponse getExactLocationForOps(Long petId, Long viewerId)`（查 pet + `resolveExactLocation` + 写 `address_visibility_logs` EXACT，**不做 grant 校验**）。
- `security/CurrentUser` 新增实例方法：`hasRole(String)`、`isOps()`（ops/admin）、`isAdmin()`。
- `auth/service/impl/AuthServiceImpl.login`：`findByPhone` 后若 `BANNED` 抛 `ForbiddenException("账号已被封禁")`。
- `security/SecurityConfig`：`.requestMatchers("/api/v1/admin/**").hasAnyRole("ops","admin")`。

## 8. 状态机扩展说明

- 帖子新增可达转移（运营执行，`admin` 模块）：`OPEN/RESERVED → REJECTED`（驳回）、`REJECTED → OPEN`（恢复/核实通过）、`OPEN/RESERVED → CLOSED`（下架，5.7）。均通过 `PostMapper` 条件 UPDATE + 影响行数校验，沿用现有乐观锁约定。
- 图片 `review_status`：`APPROVED ↔ REJECTED`（运营驳回/恢复），`PENDING` 值保留给未来真实审核但本轮不产出。

## 9. 测试

- 单元测试（模块同包）：`ReportServiceImplTest`、`AdminServiceImplTest`（各端点状态机 + 越权）。
- 集成测试（`com.pethelp` 根包）：`ReportAdminFlowIntegrationTest` —— 造 `user/ops/admin` 三角色：举报 → ops 列表/处理 → admin 下架/驳回帖子 → 审核图片 → 精确位置 + 审计 → 封禁 + 登录被拒 → 普通用户调 `/admin/**` 被拒（IDOR）。
- 全量 `mvn test` 绿（现有 174 + 新增）。

## 10. 留给实现者

- DTO 字段命名、`ApiResponse` 包装沿用现有 record 风格。
- `ReportMapper.xml` / `PostMapper` / `PetImageMapper` SQL 细节。
- 完成后用 `api-doc-archive` skill 在 `docs/archive/` 补说明，回写 `10-development-progress.md`。
