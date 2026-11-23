# 开发进度与后续目标（Onboarding）

> 本文件是「当前进度快照 + 推荐下一步」的活文档，方便下一次快速接手。设计细节以 `docs/` 下各编号文档为准；每次里程碑完成后更新本文件。
>
> 更新日期：2026-11-23

## 1. 一句话现状

后端已完成 **用户认证 + 用户资料 + 宠物档案（含图片上传）+ 精确位置授权** 四大块，跑通「注册登录 → 发布宠物档案 → 上传图片 → 发布档案 → 授权他人查看精确位置」这条链路；尚未进入「发布领养信息 / 申请 / 交接」等核心业务闭环。全量测试 **90/90 通过**。

当前分支 `feature/pet-profile`，领先 `main` 21 个提交、领先远程 `origin/feature/pet-profile` 8 个提交（远程推送因网络未完成）。

## 2. 已完成功能

| 模块 | 路径 | 端点 | 说明 |
|---|---|---|---|
| 认证 | `com.pethelp.auth` + `com.pethelp.security` | `POST /auth/sms-code`、`POST /auth/login` | 手机验证码登录（首次自动注册）、JWT 无状态认证、限流（60s/1 次、日 10 次） |
| 用户 | `com.pethelp.user` | `GET /users/me`、`PATCH /users/me`、`GET /users/{id}` | 当前用户信息、改昵称/头像/简介、他人公开资料 |
| 宠物档案 | `com.pethelp.pet` | `POST /pets`、`GET /pets/{id}`、`PATCH /pets/{id}`、`POST /pets/{id}/publish`、`/archive`、`/delete` | 档案 CRUD + 状态机（DRAFT→ACTIVE→ARCHIVED/DELETED），位置存 PostGIS（`location` + `geo_hash`），公开详情只回模糊位置 |
| 图片 | `com.pethelp.picture` + `com.pethelp.pet` | `POST /pets/{id}/images`、`GET /pets/{id}/images` | 上传（魔数嗅探、数量/大小/归属/状态校验、自动过审）+ 列表（1 小时签名 URL）。存储/审核经 `ObjectStorage` / `ImageReviewService` 适配器隔离（本地内存实现，生产 OSS 待接） |
| 位置授权 | `com.pethelp.pet` | `POST /pets/{id}/location-grants`、`GET /pets/{id}/exact-location`、`POST /location-grants/{id}/revoke` | 精确位置授权最小闭环：owner 授权指定用户查看精确坐标、被授权方查看（落 `address_visibility_logs` 审计）、owner 撤销。坐标用 `ST_X`/`ST_Y` 提取、不进实体；`(pet_id, grantee_id)` 局部唯一索引防重复授权 |

**数据库**：`V1__init_schema.sql`（19 张表全量 DDL）、`V2__add_pets_deleted_at.sql`（软删除字段）、`V3__add_location_grant_unique_index.sql`（精确位置授权 ACTIVE 局部唯一索引）。Flyway 管理。

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

- **5.2 位置隐私**：✅ 已完成（授权/查看/撤销三端点，见第 2 节「位置授权」行）。`location_type=AGREED`（约定交接位置）留待交接模块。
- **5.3 发布信息**：`pet-posts`（`post_type` = FINDING / ADOPTION / FOSTER）的发布/列表/详情/编辑/下架/寻主转领养/取消。
- **5.4 申请与审核**：`adoption-applications` / `foster-applications` 的提交、撤回、通过（**同事务批量拒绝其余**）、拒绝。
- **5.5 交接与安排**：`adoption-arrangements` / `foster-orders` 的交接确认、归还确认、延期、取消、异常终止；`handover_records` 预创建 + 确认位幂等。
- **5.6 消息**：`conversations` / `messages`（会话、发送、已读）。
- **5.7 运营与举报**：`reports`、`/admin/*`（举报处理、下架、封禁、精确位置特殊访问）。

图片模块的 **deferred 项**（本轮明确不做，后续补）：封面图、删除图片、发布需 ≥1 张图门槛、真实/异步审核、OSS 真实接入。

## 5. 推荐下一步

**立即收尾**：网络恢复后 `git push -u origin feature/pet-profile`，开 PR 到 `main` 或本地合并。

**下一里程碑 —— 完成 v0.1 领养闭环**（按依赖顺序推进）：

1. **发布信息 `pet-posts`（5.3）** —— 领养闭环核心，先做 FINDING + ADOPTION（FOSTER 留 v0.2）。位置授权已完成，`pet-posts` 可引用精确位置授权作为「授权领养人看精确位置」的地基。
2. **领养申请与审核（5.4）** —— 含「通过 → 创建安排 + 同事务批量拒绝其余」这一并发关键点。
3. **交接安排与确认（5.5）** —— `handover_records` 预创建、确认位幂等。

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
#    当前 90 个测试全绿；集成测试依赖 Docker
```

- 健康检查：`curl http://localhost:8080/actuator/health`。
- 登录联调步骤见 `docs/archive/login-flow.md`；图片上传联调见 `docs/archive/image-upload.md`；位置授权联调见 `docs/archive/location-access.md`。
- 分支/提交历史：`git log --oneline main..feature/pet-profile` 可看到本轮全部工作。
