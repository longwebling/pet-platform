# 开发进度与后续目标（Onboarding）

> 本文件是「当前进度快照 + 推荐下一步」的活文档，方便下一次快速接手。设计细节以 `docs/` 下各编号文档为准；每次里程碑完成后更新本文件。
>
> 更新日期：2026-11-16

## 1. 一句话现状

后端已完成 **用户认证 + 用户资料 + 宠物档案（含图片上传）** 三大块，跑通「注册登录 → 发布宠物档案 → 上传图片 → 发布档案」这条链路；尚未进入「发布领养信息 / 申请 / 交接」等核心业务闭环。全量测试 **67/67 通过**。

当前分支 `feature/pet-profile`，领先 `main` 13 个提交、领先远程 `origin/feature/pet-profile` 7 个提交（远程推送因网络未完成）。

## 2. 已完成功能

| 模块 | 路径 | 端点 | 说明 |
|---|---|---|---|
| 认证 | `com.pethelp.auth` + `com.pethelp.security` | `POST /auth/sms-code`、`POST /auth/login` | 手机验证码登录（首次自动注册）、JWT 无状态认证、限流（60s/1 次、日 10 次） |
| 用户 | `com.pethelp.user` | `GET /users/me`、`PATCH /users/me`、`GET /users/{id}` | 当前用户信息、改昵称/头像/简介、他人公开资料 |
| 宠物档案 | `com.pethelp.pet` | `POST /pets`、`GET /pets/{id}`、`PATCH /pets/{id}`、`POST /pets/{id}/publish`、`/archive`、`/delete` | 档案 CRUD + 状态机（DRAFT→ACTIVE→ARCHIVED/DELETED），位置存 PostGIS（`location` + `geo_hash`），公开详情只回模糊位置 |
| 图片 | `com.pethelp.picture` + `com.pethelp.pet` | `POST /pets/{id}/images`、`GET /pets/{id}/images` | 上传（魔数嗅探、数量/大小/归属/状态校验、自动过审）+ 列表（1 小时签名 URL）。存储/审核经 `ObjectStorage` / `ImageReviewService` 适配器隔离（本地内存实现，生产 OSS 待接） |

**数据库**：`V1__init_schema.sql`（19 张表全量 DDL）、`V2__add_pets_deleted_at.sql`（软删除字段）。Flyway 管理。

**基础设施**：Testcontainers（PostGIS + Redis）集成测试、MockMvc 流程测试、`backend/docker-compose.yml`（postgis + redis）。

## 3. 关键设计与约定（快速回忆）

- 根包 `com.pethelp`，顶层按业务模块分：`auth` / `user` / `pet` / `picture` / `security` / `common`。模块内 `controller / service(+impl) / dto / entity / mapper` 分层，跨模块只依赖接口。详见 `.claude/skills/backend-package-architecture/SKILL.md`。
- 统一响应 `ApiResponse { code, message, data }`；错误码与 HTTP 对齐（`40000`/`40100`/`40300`/`40400`/`40900`/`42900`/`50000`）。
- 业务规则全在后端，前端不推进状态转移；归属与状态转移服务端校验。
- 外部集成（短信、对象存储、图片审核）一律走 SPI/适配器 + `@ConditionalOnMissingBean` / `@ConditionalOnProperty`，本地/测试用内存或 dev 实现。
- 测试：单元测试与模块同包；Testcontainers 集成测试放 `com.pethelp` 根包，命名 `*FlowIntegrationTest`。
- 设计/计划文档在 `docs/superpowers/specs/` 与 `docs/superpowers/plans/`；功能说明文档在 `docs/archive/`（新功能可让 `api-doc-archive` skill 自动生成）。

## 4. 待开发（对照 `05-api.md` 与 `07-mvp-plan.md`）

尚未实现的核心能力，按 API 文档章节：

- **5.2 位置隐私（剩余）**：`GET /pets/{id}/exact-location`、`POST /pets/{id}/location-grants`、`POST /location-grants/{id}/revoke` —— 精确位置需 `location_access_grants` 授权 + 审计，公开只回模糊位置。
- **5.3 发布信息**：`pet-posts`（`post_type` = FINDING / ADOPTION / FOSTER）的发布/列表/详情/编辑/下架/寻主转领养/取消。
- **5.4 申请与审核**：`adoption-applications` / `foster-applications` 的提交、撤回、通过（**同事务批量拒绝其余**）、拒绝。
- **5.5 交接与安排**：`adoption-arrangements` / `foster-orders` 的交接确认、归还确认、延期、取消、异常终止；`handover_records` 预创建 + 确认位幂等。
- **5.6 消息**：`conversations` / `messages`（会话、发送、已读）。
- **5.7 运营与举报**：`reports`、`/admin/*`（举报处理、下架、封禁、精确位置特殊访问）。

图片模块的 **deferred 项**（本轮明确不做，后续补）：封面图、删除图片、发布需 ≥1 张图门槛、真实/异步审核、OSS 真实接入。

## 5. 推荐下一步

**立即收尾**：网络恢复后 `git push -u origin feature/pet-profile`，开 PR 到 `main` 或本地合并。

**下一里程碑 —— 完成 v0.1 领养闭环**（按依赖顺序推进）：

1. **位置授权（5.2 剩余）** —— 交接/领养流程的前置；`pets` 表已有 `location`/`geo_hash`，只需补授权表与三个端点 + 审计。
2. **发布信息 `pet-posts`（5.3）** —— 领养闭环核心，先做 FINDING + ADOPTION（FOSTER 留 v0.2）。
3. **领养申请与审核（5.4）** —— 含「通过 → 创建安排 + 同事务批量拒绝其余」这一并发关键点。
4. **交接安排与确认（5.5）** —— `handover_records` 预创建、确认位幂等。

**随后（v0.2 照护 + 治理）**：照护申请/工单/归还（含时段冲突 EXCLUDE 约束）→ 消息 → 举报/运营后台。

**图片收尾（可穿插）**：封面图、删除图片、真实审核、OSS 接入。

> 建议：每完成一个里程碑，用 `api-doc-archive` skill 在 `docs/archive/` 补一份说明文档，并回写本文件第 2 节的完成表。

## 6. 快速上手（开发与验证）

```bash
# 1. 启动基础设施（postgis + redis）
cd backend && docker compose up -d && docker compose ps   # 等待 healthy

# 2. 环境变量：DB_PASSWORD 必填（compose 与后端共用）

# 3. 运行后端（端口默认 8080）
mvn spring-boot:run                                        # 在 backend/ 下

# 4. 全量测试（在 backend/ 下）
mvn test                                                   # 或从仓库根：mvn -f backend/pom.xml test
#    当前 67 个测试全绿；集成测试依赖 Docker
```

- 健康检查：`curl http://localhost:8080/actuator/health`。
- 登录联调步骤见 `docs/archive/login-flow.md`；图片上传联调见 `docs/archive/image-upload.md`。
- 分支/提交历史：`git log --oneline main..feature/pet-profile` 可看到本轮全部工作。
