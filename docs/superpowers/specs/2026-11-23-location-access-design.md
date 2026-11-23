# 位置授权（Location Access Grant）模块 — 设计文档

> 对应 docs/05 §5.2（剩余三项）、docs/04 §4.2（`location_access_grants` / `address_visibility_logs`）、docs/06 §6.1 ⑥ 与 §6.3。
> 本模块只做「精确位置授权」最小闭环：授权 → 授权方查看 → 撤销，是后续 `pet_posts`/申请/交接模块（授权领养人/照护人看精确位置）的数据地基。

## 1. 意图与目标

让宠物档案 owner 把精确位置（`pets.location` 坐标）授权给指定用户查看；被授权方可查看精确经纬度；owner 可撤销授权。核心是**位置隐私三层模型里「精确位置」这一层的授权 + 访问审计**。

- **目标**：`POST /pets/{id}/location-grants`、`GET /pets/{id}/exact-location`、`POST /location-grants/{id}/revoke` 可跑通；未授权者拿不到精确坐标；每次授权访问落一条 `address_visibility_logs`。
- **非目标**（本轮不做）：授权过期时间（`expires_at`）、运营特殊访问（`/admin/*`，且 `GET /exact-location` 本轮不放行 `admin`/`operator` 角色）、操作审计（`audit_logs`）、`location_type=AGREED`（约定交接位置，属交接模块）、授权列表查询、本人以外的 owner 直接查看规则（owner 本人直接放行，见 §3）。
- **反目标**（做成即失败）：把精确坐标放进公开 `GET /pets/{id}` 响应；让非授权者查看精确位置；授权/撤销不校验归属（越权可操作）；客户端直接传 `status`/`location_type`。

## 2. 范围边界

**范围内**
- 3 个端点（见 §5）。
- 授权状态机：`ACTIVE → REVOKED`（无 `EXPIRED`，因为无过期）。
- `location_type` 恒为 `EXACT`（精确坐标），后端固定写入，客户端不可传。
- 精确坐标从 `pets.location` 用 `ST_X`/`ST_Y` 提取，`GET /exact-location` 返回 `lng/lat`。
- 访问审计：被授权方每次成功查看精确位置，写一条 `address_visibility_logs`（`address_type='EXACT'`，`post_id=NULL`）。

**范围外**：见 §1 非目标。

## 3. 技术决策

### 3.1 已确认（与你对齐）
1. 只做「授权 → 授权方查看 → 撤销」最小闭环，不做本人直接查看以外的边角。
2. **访问审计要写**：被授权方查看精确位置落 `address_visibility_logs`。
3. **owner 本人直接放行**：`pet.publisherId == currentUser` 直接返回精确坐标，不查授权表、不写审计。
4. **本轮排除运营角色**：`GET /exact-location` 只对本人 + 被授权方开放，不放行 `admin`/`operator`（05 §5.2 虽标注「被授权者/运营」，但运营特殊访问归后续 5.7 `/admin/*` 模块）。
5. **防重复授权加唯一索引**：新增 V3 migration（`(pet_id, grantee_id)` 局部唯一索引）+ `@Transactional` 兜底，不依赖纯 Service 先查后插。

### 3.2 我的 call（实现细节，可调整）

| # | 决策 | 理由 |
|---|---|---|
| C1 | **模块归属 `com.pethelp.pet`**，不新建 `location` 模块 | 三个端点两个挂 `/pets/{id}` 下；`address_visibility_logs.post_id` 本轮恒 NULL，当前只有 pet 一个消费方；与 `PetImage` 同属 pet 子资源，避免过早抽象 |
| C2 | **`grant` 请求体仅 `{ granteeId }`**，无 `expiresAt`/`locationType` | 无过期、固定 EXACT，客户端无需传 |
| C3 | **授权状态仅 `ACTIVE`/`REVOKED`，`expires_at` 恒 NULL** | 本轮无过期；查询校验 `status='ACTIVE'` 即可，不查时间 |
| C4 | **防重复授权**：`(pet_id, grantee_id)` 局部唯一索引 `WHERE status='ACTIVE'`，grant 走 `@Transactional`，唯一约束冲突捕获转 `409` | 06 §6.2「不能只靠先查后插」；与现有防重复申请唯一索引一致 |
| C5 | **`granteeId` 必须存在且不等于授权者本人**：用户不存在 → `404`，等于本人 → `400` | 用 `UserMapper.findById` 校验存在；本人已直接放行，给自己授权无意义 |
| C6 | **`GET /exact-location` 用 `findById`（仅过滤 DELETED）而非 `findByIdActive`** | owner 本人在 `DRAFT` 阶段也能核对位置；授权方只要 pet 非 DELETED 且有授权即可看 |
| C7 | **`pets.location` 为 NULL 时返回 `404「该宠物未设置位置」`** | owner 发布时可选择不填位置，此时无可返回坐标 |
| C8 | **`revoke` 非 `ACTIVE` → `409`**；`grant`/`revoke` 影响行数 == 0 → `409`（并发守卫） | 与 `PetServiceImpl` 的条件更新原子守卫一致 |
| C9 | **精确坐标提取用 `Map<String,Object>` 返回 `ST_X`/`ST_Y`**，Service 内转 `Double` | 避免为一条坐标新建 POJO/TypeHandler；Pet 实体的 `findById` 继续不查 `location` 列，保持「精确坐标不进入实体」的既有约定 |
| C10 | **审计只在「被授权方」查看时写，owner 本人查看不写** | 审计意图是追踪「谁看了我的精确位置」，owner 是数据拥有者无需审计 |
| C11 | **三个端点放一个 `LocationAccessController`（`@RequestMapping("/api/v1")`）** | 三个端点同属「位置授权」功能，逻辑内聚；避免为 `revoke`（不在 `/pets` 前缀下）单开 controller |

## 4. 状态流转契约（仅 `location_access_grants.status`）

| 转移 | 端点 | 执行者 | 失败条件（→ 错误） | 幂等 |
|---|---|---|---|---|
| 创建 → `ACTIVE` | `POST /pets/{id}/location-grants` | owner/发布者 | 非 owner（403）、grantee 不存在（404）、授权给自己（400）、重复授权（409，唯一索引兜底） | 否 |
| `ACTIVE → REVOKED` | `POST /location-grants/{id}/revoke` | 授权者（grantor） | 非 grantor（403）、非 ACTIVE（409） | 否 |

- 无 `EXPIRED` 状态；`expires_at` 恒 NULL。
- 均单条 UPDATE/INSERT，用条件更新做原子守卫。

## 5. API 契约

统一前缀 `/api/v1`，鉴权 JWT，返回 `{code, message, data}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | /pets/{id}/location-grants | owner/发布者 | 授予某用户精确位置访问（`location_type=EXACT`） |
| GET | /pets/{id}/exact-location | 本人/被授权者 | 精确坐标（本人直接放行，被授权者需 ACTIVE 授权，落访问审计） |
| POST | /location-grants/{id}/revoke | 授权者 | 撤销授权（`ACTIVE→REVOKED`） |

### 5.1 请求/响应 DTO

```java
// grant 请求
record GrantLocationAccessRequest(Long granteeId) {}

// 授权结果
record LocationGrantResponse(Long id, Long petId, Long granteeId, String locationType, String status) {}

// 精确位置响应（绝不包含在公开 /pets/{id} 中）
record ExactLocationResponse(Double lng, Double lat) {}
```

### 5.2 示例

```
# owner 授权给用户 2
POST /api/v1/pets/1/location-grants
Authorization: Bearer <owner-jwt>
{ "granteeId": 2 }

→ 200
{ "code": 0, "data": { "id": 10, "petId": 1, "granteeId": 2, "locationType": "EXACT", "status": "ACTIVE" } }

→ 非 owner 403 { "code": 40300, "message": "仅档案发布者可授权" }
→ grantee 不存在 404 { "code": 40400, "message": "用户不存在" }
→ 授权给自己 400 { "code": 40000, "message": "不能授权给自己" }
→ 已存在 ACTIVE 授权 409 { "code": 40900, "message": "已授权该用户" }

# 被授权方查看精确位置
GET /api/v1/pets/1/exact-location
Authorization: Bearer <grantee-jwt>

→ 200
{ "code": 0, "data": { "lng": 116.397, "lat": 39.908 } }

→ 未授权 403 { "code": 40300, "message": "无权查看精确位置" }
→ 未设置位置 404 { "code": 40400, "message": "该宠物未设置位置" }

# 授权者撤销
POST /api/v1/location-grants/10/revoke
Authorization: Bearer <owner-jwt>

→ 200
{ "code": 0, "data": { "id": 10, "petId": 1, "granteeId": 2, "locationType": "EXACT", "status": "REVOKED" } }

→ 非 grantor 403 { "code": 40300, "message": "仅授权者可撤销" }
→ 已撤销 409 { "code": 40900, "message": "授权已撤销" }
```

## 6. 数据与 SQL 要点

- **`location_access_grants` 表已存在**（V1），字段：`id, grantor_id, grantee_id, pet_id, location_type, status, granted_at, expires_at, revoked_at, created_at`。
- **`address_visibility_logs` 表已存在**（V1），字段：`id, viewer_id, pet_id, post_id, address_type, viewed_at`。
- 新增 1 个迁移 `V3__add_location_grant_unique_index.sql`：`CREATE UNIQUE INDEX uq_location_grant_active ON location_access_grants (pet_id, grantee_id) WHERE status='ACTIVE';`。
- grant insert：`location_type='EXACT'`、`status='ACTIVE'`，`grantor_id`/`grantee_id`/`pet_id` 由后端写入，`expires_at` 留 NULL；在 `@Transactional` 内执行，捕获唯一约束冲突（`DuplicateKeyException`）转 `409`。
- 精确坐标提取（`PetMapper` 新增，唯一查 `location` 列的地方）：
  ```sql
  SELECT ST_X(location) AS lng, ST_Y(location) AS lat
  FROM pets WHERE id = #{id} AND location IS NOT NULL
  ```
  返回空（或 null）→ `404「该宠物未设置位置」`。
- 友好预检（可选，唯一索引仍兜底）：insert 前查 `WHERE pet_id=#{petId} AND grantee_id=#{granteeId} AND status='ACTIVE'`，命中提前 `409`。
- revoke 条件更新：
  ```sql
  UPDATE location_access_grants SET status='REVOKED', revoked_at=now()
  WHERE id=#{id} AND grantor_id=#{grantorId} AND status='ACTIVE'
  ```
  影响行数 == 0 → `409`。

## 7. 分层与文件清单（backend-package-architecture）

全部在 `com.pethelp.pet`（pet 子资源）：

```
com.pethelp.pet/
├── controller/LocationAccessController.java   # @RequestMapping("/api/v1")
├── service/LocationAccessService.java
│   └── impl/LocationAccessServiceImpl.java
├── dto/GrantLocationAccessRequest.java
├── dto/LocationGrantResponse.java
├── dto/ExactLocationResponse.java
├── entity/LocationAccessGrant.java
├── entity/AddressVisibilityLog.java
└── mapper/LocationAccessGrantMapper.java
└── mapper/AddressVisibilityLogMapper.java

com.pethelp.pet/mapper/PetMapper.java          # 增 findExactLocation
src/main/resources/mapper/LocationAccessGrantMapper.xml
src/main/resources/mapper/AddressVisibilityLogMapper.xml
src/main/resources/mapper/PetMapper.xml        # 增 findExactLocation SQL
```

- 跨模块依赖：`LocationAccessServiceImpl` 依赖本模块 `PetMapper`/`LocationAccessGrantMapper`/`AddressVisibilityLogMapper` + `com.pethelp.user.mapper.UserMapper`（校验 grantee 存在）+ `common` 异常。
- 无需改动 `SecurityConfig`（三个端点均要求登录，走 `anyRequest().authenticated()`）。

## 8. 权限与安全校验（Service 层，禁止 assert）

- **grant**：`pet.publisherId == currentUserId`（否则 403）；`granteeId` 存在（否则 404）；`granteeId != currentUserId`（否则 400）；重复授权（预检或唯一索引冲突 → 409）。
- **exact-location**：`pet.publisherId == currentUserId` → 直接返回（不写审计）；否则须有 ACTIVE 授权（`grantee_id=currentUser AND pet_id=petId AND status='ACTIVE'`）→ 返回 + 写 `address_visibility_logs`；否则 403。
- **revoke**：`grant.grantorId == currentUserId`（否则 403）；`status='ACTIVE'`（否则 409）。
- `location_type`/`status` 一律后端推导，客户端不传。
- 精确坐标只在 `GET /exact-location` 返回；公开 `GET /pets/{id}` 与 `PetResponse` 绝不含 `lng/lat`。

## 9. 测试方案

### 9.1 单元测试（`com.pethelp.pet`，`LocationAccessServiceImplTest`，Mockito mock 各 mapper）

- grant：owner 成功、非 owner 403、grantee 不存在 404、授权给自己 400、重复授权 409。
- exact-location：本人放行不写审计、授权方查看成功并写审计、未授权 403、未设位置 404。
- revoke：grantor 成功、非 grantor 403、非 ACTIVE 409。

### 9.2 集成测试（`com.pethelp.LocationAccessFlowIntegrationTest`，Testcontainers postgis + redis，MockMvc）

1. owner 登录建 pet（带 lng/lat）→ grant 给 B → B `GET /exact-location` 200 拿到 lng/lat，且 `address_visibility_logs` 落一条。
2. 未授权用户 C `GET /exact-location` → 403，无审计记录。
3. owner 本人 `GET /exact-location` → 200，无审计记录。
4. owner 撤销后 B 再 `GET /exact-location` → 403。
5. 非 owner grant → 403；授权给自己 → 400；grantee 不存在 → 404。
6. 重复 grant（同一 grantee 两次）→ 409，DB 中仅一条 ACTIVE 记录（唯一索引生效）。
7. 无位置 pet（不带 lng/lat）`GET /exact-location`（owner 本人）→ 404。

> 遵循 docs/09：真实 PostGIS 镜像，不用 H2（依赖 `ST_X`/`ST_Y`）。

## 10. 新增依赖与异常类

- 无新增 Maven 依赖、无新增异常类（复用 `ForbiddenException`/`ConflictException`/`NotFoundException`/`BadRequestException`）。
- 新增 1 个数据库迁移：`V3__add_location_grant_unique_index.sql`。

## 11. 留给 builder 的自由度

- `findExactLocation` 返回类型：`Map<String,Object>`（C9）或自定义 POJO，只要精确坐标不进 `Pet` 实体且不泄漏到公开响应。
- Service 内校验顺序与错误文案。
- 集成测试如何复用现有 JWT 登录方式（参照 `PetFlowIntegrationTest`）。
