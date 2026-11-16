# 宠物档案（Pet Profile）模块 — 设计文档

> 对应 docs/01 §B、docs/02 §2.1、docs/04 §4.2（pets 表）、docs/05 §5.2。
> 本模块只做「自己养宠」最简路径（`source_type=OWNED`），是后续 `pet_posts`/申请/交接模块的数据地基。

## 1. 意图与目标

让登录用户发布自己的宠物档案（物种、品种、健康描述、模糊位置），并能编辑、发布、归档、软删除。核心是**跑通 `pets` 表 + 位置隐私三层模型里的第一层**（精确坐标落库、对外只给模糊 geohash）。

- **目标**：`POST /pets`、`PATCH /pets/{id}`、`POST /pets/{id}/publish`、`GET /pets/{id}`、`POST /pets/{id}/archive`、`POST /pets/{id}/delete` 可跑通；精确坐标不外发；归属校验在后端。
- **非目标**（本轮不做）：图片上传（`pet_images`）、精确位置授权/审计（`location_access_grants`/`address_visibility_logs`）、处置授权关系（`pet_authorizations`）、`RESCUED`/`FOUND`/`STRAY` 来源、地图附近列表、内容审核、「我的档案列表」。
- **反目标**（做成即失败）：把 `lng/lat` 精确坐标放进任何响应；让非本人编辑/归档/删除他人档案；客户端直接传 `status`/`source_type`/`ownership_status`/`authorization_status`；为 geohash 引入新依赖。

## 2. 范围边界

**范围内**
- 仅 `source_type=OWNED`：`publisher_id = owner_id = current_custodian_id = 当前登录用户`，`ownership_status=OWNED`、`authorization_status=SELF` 由后端固定写入，客户端不可传。
- 6 个端点（见 §5），状态流转见 §4。
- 位置：客户端可传精确经纬度 `lng/lat` + `city/district`；后端存 `location`（geometry）并本地算 `geo_hash`；对外只返回 `geo_hash` + `city/district`。

**范围外**：见 §1 非目标。发现/列表能力统一留给后续 `pet_posts` 模块。

## 3. 技术决策

### 3.1 已确认（与你对齐）
1. 只做 OWNED/SELF 路径，三方合一。
2. 状态先落 `DRAFT`，流转严格按 docs/02 §2.1。
3. 精确坐标照存，geohash 对外模糊化，为地图功能预留。
4. 归档与软删除一并纳入本轮。

### 3.2 我的 call（实现细节，可调整）
| # | 决策 | 理由 |
|---|---|---|
| C1 | **补 `POST /pets/{id}/publish` 端点** | docs/05 §5.2 没有发布端点，但状态机有 `DRAFT→ACTIVE`；`POST /pets` 只落 `DRAFT` |
| C2 | **geohash 精度取 6 位**（≈1.2km×0.6km），非之前口头说的 5 位 | 对齐 docs/06 §6.3「~200m~1km 截断」；5 位≈5km 过粗，7 位≈150m 偏细 |
| C3 | **不引入 geohash/geometry 依赖**：`location` 用 `ST_SetSRID(ST_MakePoint(lng,lat),4326)`，`geo_hash` 用 `ST_GeoHash(geom,6)` 在 Mapper SQL 内计算；Java 实体**不持有** geometry 对象，`lng/lat` 仅作输入用瞬态字段 | 避免新增 PostGIS JDBC 类型处理器/第三方 geohash 库 |
| C4 | **`lng/lat` 可选**；二者必须成对（只传其一 → 400）；为空则 `location`/`geo_hash` 均为 NULL | 宠物主人可自愿选择是否暴露模糊位置 |
| C5 | **手动校验**（`BadRequestException`），不引入 `spring-boot-starter-validation` | 现有代码未用 jakarta validation（auth 也是手动 `validatePhone`），保持一致 |
| C6 | **新增 `ForbiddenException`(40300) 与 `ConflictException`(40900)** 到 `common/exception` | 越权与状态冲突需要，docs/05 §5.0 已定义这两个错误码 |
| C7 | **`GET /pets/{id}` 公开但仅 `ACTIVE`**：`DRAFT`/`ARCHIVED`/`DELETED` 对外一律 404；需在 `SecurityConfig` 放行 `GET /api/v1/pets/{id}`，Mapper 拆两条查询（公开 `status='ACTIVE'`、内部无状态过滤） | docs/01 权限矩阵游客可浏览详情，但「详情」指已发布内容，草稿/归档不公开 |
| C8 | **`PetResponse` 不含 `created_at/updated_at`**（与 `UserProfileResponse` 一致），也不含 `lng/lat` | 减小时区/精度处理面 |
| C9 | 字段校验（枚举白名单、长度、lng/lat 范围）放 **Service 层**，Controller 保持薄 | 对齐 backend-package-architecture「controller 薄层 + service 承载业务规则」 |
| C10 | **`PATCH /pets/{id}` 全量替换**（对齐现有 `PATCH /users/me`）：更新时 `species` 仍必填；`lng/lat` 省略或为 null 即清空位置 | 与现有 `UserServiceImpl.updateProfile` 一致；因 `species` 不可置空，「必填项缺失」在 publish 时不可达 |
| C11 | **`delete` 返回 `{code:0,data:null}`**（软删后 `findById` 过滤 DELETED，无法回查）；`publish`/`archive` 返回含新 `status` 的 `PetResponse` | 与 `POST /auth/sms-code` 一致的空返回约定 |

## 4. 状态流转契约（docs/02 §2.1，仅 pets.status）

| 转移 | 端点 | 执行者 | 失败条件（→ 错误） | 幂等 |
|---|---|---|---|---|
| 创建 → `DRAFT` | `POST /pets` | 登录用户 | 必填字段缺失/枚举非法（400） | 否 |
| `DRAFT → ACTIVE` | `POST /pets/{id}/publish` | 本人 | 非本人（403）、非 DRAFT（409） | 否 |
| `ACTIVE → ARCHIVED` | `POST /pets/{id}/archive` | 本人 | 非本人（403）、非 ACTIVE（409） | 否 |
| `ACTIVE → DELETED` | `POST /pets/{id}/delete` | 本人 | 非本人（403）、非 ACTIVE（409） | 否 |

- `DRAFT`/`ACTIVE` 允许编辑（`PATCH`）；`ARCHIVED`/`DELETED` 不可编辑。
- `DELETED` 为软删除：`status='DELETED'` + `deleted_at=now()`；`GET` 不再返回（`WHERE status <> 'DELETED'`）。
- 本模块各转移均为**单条 UPDATE**，无需跨表事务；用「条件更新」做原子守卫（见 §6）。

## 5. API 契约

统一前缀 `/api/v1`，鉴权 JWT，返回 `{code, message, data}`。所有写操作在 Service 层显式校验归属。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | /pets | 登录 | 创建档案，落 `DRAFT` |
| GET | /pets/{id} | 公开 | 详情，**仅 `ACTIVE`**（模糊位置）；`DRAFT`/`ARCHIVED`/`DELETED` 对外 404 |
| PATCH | /pets/{id} | 本人 | 全量替换编辑（DRAFT/ACTIVE） |
| POST | /pets/{id}/publish | 本人 | `DRAFT→ACTIVE` |
| POST | /pets/{id}/archive | 本人 | `ACTIVE→ARCHIVED` |
| POST | /pets/{id}/delete | 本人 | `ACTIVE→DELETED`（软删除） |

### 5.1 请求 DTO

```java
// 创建与编辑共用字段
record CreatePetRequest(String species, String breed, String age, String gender,
                        Boolean sterilized, Boolean vaccinated, String healthDesc,
                        Double lng, Double lat, String city, String district) {}
record UpdatePetRequest(String species, String breed, String age, String gender,
                        Boolean sterilized, Boolean vaccinated, String healthDesc,
                        Double lng, Double lat, String city, String district) {}
```

校验（Service 层，非法 → 400）：
- `species` 必填，∈ `{CAT,DOG,OTHER}`。
- `gender` 可选，若给 ∈ `{MALE,FEMALE,UNKNOWN}`。
- `breed`≤64、`age`≤32、`city`≤64、`district`≤64。
- `lng`/`lat` 成对出现：只给一个 → 400；`lng∈[-180,180]`、`lat∈[-90,90]`。

### 5.2 响应 DTO

```java
record PetResponse(Long id, String species, String breed, String age, String gender,
                   Boolean sterilized, Boolean vaccinated, String healthDesc, String status,
                   String sourceType, String ownershipStatus, String authorizationStatus,
                   String geoHash, String city, String district) {}
```

- **绝不包含 `lng/lat` 或精确坐标**，只返回 `geoHash`（6 位）+ `city`/`district`。
- `sourceType`/`ownershipStatus`/`authorizationStatus` 本轮固定为 `OWNED`/`OWNED`/`SELF`，仍返回（语义透明，供前端展示）。

### 5.3 示例

```
# 创建（自己养宠）
POST /api/v1/pets
Authorization: Bearer <jwt>
{ "species": "CAT", "breed": "中华田园猫", "age": "2岁", "gender": "FEMALE",
  "sterilized": true, "vaccinated": true, "healthDesc": "健康，已绝育",
  "lng": 116.397, "lat": 39.908, "city": "北京", "district": "朝阳区" }

→ 200
{ "code": 0, "data": { "id": 1, "species": "CAT", ..., "status": "DRAFT",
    "sourceType": "OWNED", "ownershipStatus": "OWNED", "authorizationStatus": "SELF",
    "geoHash": "wx4g0e", "city": "北京", "district": "朝阳区" } }

# 发布
POST /api/v1/pets/1/publish  → { "code": 0, "data": { "id": 1, "status": "ACTIVE", ... } }

# 越权（他人编辑）
PATCH /api/v1/pets/1  → 403 { "code": 40300, "message": "仅档案发布者可操作" }

# 状态非法（重复发布）
POST /api/v1/pets/1/publish  → 409 { "code": 40900, "message": "当前状态不可发布" }

# 参数错误（只传 lng）
POST /api/v1/pets  → 400 { "code": 40000, "message": "经纬度需成对提供" }
```

## 6. 数据与 SQL 要点

- **归属固定**：insert 时 `publisher_id=owner_id=current_custodian_id=#{currentUserId}`，`source_type='OWNED'`、`ownership_status='OWNED'`、`authorization_status='SELF'`、`status='DRAFT'`。
- **位置**：`location = ST_SetSRID(ST_MakePoint(#{lng},#{lat}),4326)`，`geo_hash = ST_GeoHash(ST_SetSRID(ST_MakePoint(#{lng},#{lat}),4326), 6)`；`lng/lat` 为 NULL 时二者均落 NULL（Mapper SQL 内用 CASE 或 `jdbcType` 判空）。
- **两条查询路径**（公开可见性）：
  - `findByIdActive`（公开 `GET` 用）：`WHERE id=#{id} AND status='ACTIVE'`。
  - `findById`（Service 内部用，供 publish/archive/delete/update 做归属与状态校验）：`WHERE id=#{id} AND status <> 'DELETED'`。
- **编辑为全量替换**：`UPDATE pets SET species=…, breed=…, age=…, gender=…, sterilized=…, vaccinated=…, health_desc=…, location=…, geo_hash=…, city=…, district=…, version=version+1 WHERE id=#{id} AND publisher_id=#{publisherId} AND status IN ('DRAFT','ACTIVE')`。
- **条件更新（原子守卫，配合 Service 层显式校验）**：
  - `publish`：`UPDATE pets SET status='ACTIVE', version=version+1 WHERE id=#{id} AND publisher_id=#{publisherId} AND status='DRAFT'`
  - `archive`：同上 `... AND status='ACTIVE'`
  - `softDelete`：`UPDATE pets SET status='DELETED', deleted_at=now(), version=version+1 WHERE id=#{id} AND publisher_id=#{publisherId} AND status='ACTIVE'`
  - 影响行数 == 0 → `409`（并发下状态已变）。

## 7. 分层与文件清单（backend-package-architecture）

新增业务模块 `com.pethelp.pet`：

```
com.pethelp.pet/
├── controller/PetController.java          # @RestController，薄层
├── service/PetService.java                # 接口
│   └── impl/PetServiceImpl.java           # @Service
├── dto/CreatePetRequest.java
├── dto/UpdatePetRequest.java
├── dto/PetResponse.java
├── entity/Pet.java                        # 无 geometry；含瞬态 lng/lat
└── mapper/PetMapper.java                  # @Mapper

src/main/resources/mapper/PetMapper.xml     # namespace=com.pethelp.pet.mapper.PetMapper
```

- `common/exception/` 新增 `ForbiddenException`(40300)、`ConflictException`(40900)。
- `security/SecurityConfig` 增加：`requestMatchers(HttpMethod.GET, "/api/v1/pets/*").permitAll()`（公开详情）。

跨模块依赖：`PetController` 依赖 `PetService` + `CurrentUser` + `ApiResponse`；`PetServiceImpl` 依赖 `PetMapper` + 异常 + `PetResponse` 映射。无跨模块业务依赖。

## 8. 权限与安全校验（Service 层，禁止 assert）

- 写操作（publish/archive/delete/update）：`pet.publisherId.equals(currentUserId)`，否则 `ForbiddenException`。
- 状态前置校验：目标转移的源状态不匹配 → `ConflictException`。
- `status`/`source_type`/`ownership_status`/`authorization_status` 一律后端推导，客户端不传。
- 可见性：公开 `GET` 仅返回 `ACTIVE`；`DRAFT`/`ARCHIVED`/`DELETED` 对外 404。
- 精确坐标不出现在任何响应；两条查询（`findById`/`findByIdActive`）的 SELECT 均不查 `location` 列。

## 9. 测试方案

### 9.1 单元测试（`com.pethelp.pet`，`<Class>Test`，Mockito mock PetMapper）

- `PetServiceImplTest`：创建固定字段、非法 species/gender 抛 400、只传 lng 抛 400、越权 403、状态非法 409、发布/归档/删除的源状态校验。

### 9.2 集成测试（`com.pethelp.PetFlowIntegrationTest`，Testcontainers postgis + redis，MockMvc）

1. 未登录 `GET /pets/{id}`（已 `ACTIVE`）→ 200，响应不含 `lng/lat`、含 6 位 `geoHash`。
2. 未登录 `GET /pets/{id}`（`DRAFT`）→ 404（草稿不公开）。
3. 登录 `POST /pets` → 200，`status=DRAFT`，DB 中 `source_type/ownership_status/authorization_status` = `OWNED/OWNED/SELF`，`publisher_id=owner_id=current_custodian_id`。
4. `POST /pets` 非法 species → 400；只传 lng → 400。
5. `POST /pets` 带 lng/lat → DB `location` 非空、`geo_hash` 长度 6。
6. `PATCH /pets/{id}` 本人 → 200 字段更新；他人 → 403。
7. `POST /pets/{id}/publish` 本人 → `ACTIVE`；再次 publish → 409。
8. `POST /pets/{id}/archive`（DRAFT）→ 409；publish 后 archive → `ARCHIVED`。
9. `POST /pets/{id}/delete` → `DELETED` 且 `deleted_at` 非空；随后 `GET` → 404。

> 遵循 docs/09：用真实 PostGIS 镜像（`postgis/postgis:16-3.4`），不用 H2（不支持 PostGIS/ST_GeoHash）。

## 10. 新增依赖与异常类

- **无新增 Maven 依赖**（PostGIS 函数由既有 PostgreSQL JDBC + 镜像提供；校验手动做）。
- 新增异常类：`ForbiddenException`(40300)、`ConflictException`(40900)。

## 11. 留给 builder 的自由度

- `PetMapper.xml` 里 CASE/`jdbcType` 判空的具体写法、`ST_GeoHash` 调用细节。
- `PetServiceImpl` 内部校验顺序与错误文案。
- `Pet` 实体是否用 `lng/lat` 瞬态字段还是独立参数，只要不把精确坐标带进响应即可。
- 集成测试里如何构造「登录用户 + JWT」复用现有 `AuthFlowIntegrationTest` 的既有方式。

## 12. 与既有文档的差异/缺口修正

| 项 | 说明 |
|---|---|
| 新增 `POST /pets/{id}/publish` | docs/05 §5.2 缺失发布端点，状态机 §2.1 有 `DRAFT→ACTIVE`，本设计补齐 |
| geohash 精度 6 位 | 修正此前口头「5 位≈5km」，对齐 docs/06 §6.3「~200m~1km」 |
| 公开详情需改 SecurityConfig | docs/01 权限矩阵游客可浏览详情，当前 `anyRequest().authenticated()` 需放行 `GET /pets/{id}`；且公开仅对 `ACTIVE`，故 Mapper 拆 `findByIdActive` / `findById` 两条查询 |
