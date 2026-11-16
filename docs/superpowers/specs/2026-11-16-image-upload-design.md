# 宠物图片上传（Pet Image Upload）模块 — 设计文档

> 对应 docs/01 §B、docs/04 §4.1/`pet_images` 表、docs/05 §5.2。
> 本模块做宠物档案的**照片上传 + 图片列表**，为后续「发布页 / 帖子 / 详情页」展示图片提供地基。
> 存储落在阿里云 OSS，但通过 `ObjectStorage` 适配器隔离，测试与本地开发不依赖真实 OSS。

## 1. 意图与目标

让档案发布者给自己名下（`publisher_id = 当前用户`）的宠物上传照片，并能对外列出已过审的照片（带 1 小时有效的签名 URL）。核心是**跑通 `pet_images` 表 + 对象存储私有 key 模型**：文件以私有 key 存入 OSS，DB 只存 key，读取时现签 URL，永不存永久公开 URL。

- **目标**：`POST /pets/{id}/images`（上传，返回 imageId）与 `GET /pets/{id}/images`（列表，返回签名 URL）可跑通；文件私有、URL 短期；归属校验在后端；审核抽象已就位（当前自动通过）。
- **非目标**（本轮不做）：封面图（`is_cover`/排序）、删图、健康证/检疫证（`HEALTH_CERT`/`QUARANTINE`）、图片裁剪/压缩/水印、真实内容审核、用户头像（`users.avatar`）上传。
- **反目标**（做成即失败）：把 OSS 永久公开 URL 落库或下发；让非本人给他人档案传图；信任客户端 `Content-Type` 而不校验文件真实类型；让图片绕过 `review_status` 对公开接口可见。

## 2. 范围边界

**范围内**
- 仅 `file_type=PHOTO`（宠物照片）。
- 2 个端点（见 §5）：上传、列表。
- 审核：一个 `ImageReviewService` 接口，当前实现 `AutoApproveImageReviewService` 同步返回 `APPROVED`；将来运营审核接口设计好后替换实现。
- 存储：`ObjectStorage` 适配器接口，`put`/`signedUrl`；本地开发/测试用内存实现，生产用 OSS 实现。

**范围外**：见 §1 非目标。`pet_posts`（帖子/发布信息）的图片能力后续在其模块内实现。

## 3. 技术决策

### 3.1 已确认（与你对齐）
1. 存储用阿里云 OSS。
2. 只上传宠物照片（`PHOTO`），不做健康证/检疫证。
3. 审核接口先做、内部自动通过，运营接口完成后填细节。
4. 上传是单独接口 `POST /pets/{id}/images`，不与 `POST /pets` 合并。
5. 上传响应**只返回 imageId**（+ 元数据），**不含签名 URL**；另开 `GET /pets/{id}/images` 获取带签名 URL 的图片。
6. 封面图与删图本轮不做。

### 3.2 我的 call（实现细节，可调整）
| # | 决策 | 理由 |
|---|---|---|
| C1 | **后端代理上传**（前端 multipart POST 给后端，后端写 OSS），不做「前端直传 + STS 临时凭证」 | 需先做档案归属校验 + 审核 + 数量限制；直传会把凭证下发暴露桶 |
| C2 | **读取用签名 URL，TTL 1 小时**；DB 只存 `file_key`，永不存公开 URL | docs/04 §4.1 `file_key` 注释「不存永久公开 URL」 |
| C3 | **`ObjectStorage` 适配器**（`put`/`signedUrl`），生产 = OSS SDK，本地/测试 = 内存实现；互斥开关 `oss.enabled` | 对齐 AGENTS.md「把外部集成藏到适配器后」+ 现有 `SmsSender`/`DevSmsSender` 模式；集成测试不连真 OSS |
| C4 | **`ImageReviewService` 适配器**，默认 `AutoApproveImageReviewService`（`@ConditionalOnMissingBean`） | 你要求「审核接口自动通过」，未来替换为真实审核 |
| C5 | **文件真实类型靠魔数嗅探**（JPEG `FF D8 FF` / PNG `89 50 4E 47`），不信任 `Content-Type` 与后缀；由此定扩展名与 `contentType` | 防伪造类型上传；AGENTS.md「校验放后端」 |
| C6 | **限制**：每档案最多 9 张 `PHOTO`、单张 ≤10MB、仅 JPEG/PNG | 数量/大小为应用层软限制（并发下可略超，记为 minor） |
| C7 | **归属**：上传者必须等于 `pets.publisher_id`，否则 403；用 `PetMapper.findById` 读档案（其排除 DELETED），故 `DELETED` → 404、`ARCHIVED` → 409 | 复用 `PetServiceImpl` 同款校验，DELETED 与 update/publish 一致走 404 |
| C8 | **列表可见性与详情对齐**：`GET /pets/{id}/images` 公开，仅 `ACTIVE` 档案且仅 `APPROVED` 图片；非 ACTIVE → 404 | 与 `GET /pets/{id}` 的「公开但仅 ACTIVE」一致；当前自动通过，故图片均 APPROVED |
| C9 | **上传在 Service 层入参为 `byte[]`**（Controller 读 `file.getBytes()` 传入），不把 `MultipartFile` 下沉到 Service | 保持 Controller 薄、Service 可脱离 Web 单测 |
| C10 | **`file_key` 由应用生成**：`pets/{petId}/{uuid}.{ext}`，私有（无 public-read ACL） | key 可预测、可审计；不暴露用户原始文件名 |
| C11 | **新增 `PetImageController`**（`@RequestMapping("/api/v1/pets")`，方法 `/{id}/images`），不改动 `PetController` | 图片是独立关注点（存储 + 审核 + image mapper），按职责拆分 |
| C12 | **DTO 单一 `PetImageResponse`**，`signedUrl` 字段在上传响应为 `null`、列表响应填充 | 避免两个几乎相同的 record；不返回 `created_at`（对齐 `PetResponse`） |

## 4. 审核抽象（当前自动通过）

```java
public interface ImageReviewService {
    /** 返回 review_status 取值：APPROVED / PENDING / REJECTED（当前实现恒 APPROVED） */
    String review(byte[] content, String fileKey);
}
```

- 默认实现 `AutoApproveImageReviewService.review(...)` 直接返回 `"APPROVED"`。
- 上传流程：`校验 → review(→ APPROVED) → 存入 OSS → insert(pet_images, review_status=APPROVED)`。
- 未来替换：真实审核可能返回 `PENDING`（异步）或 `REJECTED`，届时 `list` 已按 `APPROVED` 过滤、公开接口天然只暴露过审图。

## 5. API 契约

统一前缀 `/api/v1`，JWT 鉴权（列表公开），返回 `{code, message, data}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | /pets/{id}/images | 本人 | multipart 上传照片，返回 imageId（+元数据，无签名 URL） |
| GET | /pets/{id}/images | 公开 | 列表，仅 ACTIVE 档案的 APPROVED 图片，含签名 URL |

### 5.1 上传请求

```
POST /api/v1/pets/{id}/images
Authorization: Bearer <jwt>
Content-Type: multipart/form-data
  字段名 file = <图片字节>
```

- 参数名固定 `file`；不接收 JSON body。
- 校验（非法 → 400 / 403 / 404 / 409，见 §8）：
  - `file` 缺失或空 → 400。
  - 大小 > 10MB → 400（同时 Spring `multipart.max-file-size=10MB` 兜底，`MaxUploadSizeExceededException` → 400）。
  - 魔数非 JPEG/PNG → 400「仅支持 JPG/PNG」。
  - 该档案 `PHOTO` 数量 ≥ 9 → 400「图片数量已达上限」。

### 5.2 响应 DTO

```java
record PetImageResponse(Long id, Long petId, String fileType, String reviewStatus, String signedUrl) {}
```

- 上传响应：`signedUrl = null`，`fileType = "PHOTO"`，`reviewStatus = "APPROVED"`。
- 列表响应：`List<PetImageResponse>`，每个含 1 小时有效的 `signedUrl`。

### 5.3 示例

```
# 上传
POST /api/v1/pets/1/images  (multipart file)
→ 200 { "code": 0, "data": { "id": 7, "petId": 1, "fileType": "PHOTO",
       "reviewStatus": "APPROVED", "signedUrl": null } }

# 列表（档案已 ACTIVE）
GET /api/v1/pets/1/images
→ 200 { "code": 0, "data": [
       { "id": 7, "petId": 1, "fileType": "PHOTO", "reviewStatus": "APPROVED",
         "signedUrl": "https://<bucket>.<endpoint>/pets/1/<uuid>.jpg?Expires=...&Signature=..." } ] }

# 越权（他人上传）
POST /api/v1/pets/1/images → 403 { "code": 40300, "message": "仅档案发布者可操作" }

# 非 ACTIVE 档案的列表
GET /api/v1/pets/1/images → 404 { "code": 40400, "message": "宠物不存在" }

# 非法格式
POST /api/v1/pets/1/images (text 字节) → 400 { "code": 40000, "message": "仅支持 JPG/PNG" }
```

## 6. 数据与存储要点

- **`pet_images` 表**（已存在，V1__init_schema.sql）：`pet_id`、`uploader_id`、`file_key`、`file_type='PHOTO'`、`review_status='APPROVED'`、`access_level='PUBLIC'`（DB 默认）、`created_at`。
- **insert 字段**：`pet_id`、`uploader_id=#{currentUserId}`（本轮 uploader == publisher，即当前用户）、`file_key`、`file_type='PHOTO'`、`review_status=#{review(...)}`；`access_level` 用 DB 默认 `PUBLIC`。
- **`file_key`**：`pets/{petId}/{UUID}.{ext}`，`ext` 由魔数决定（`jpg`/`png`）。私有 key，DB 不存 URL。
- **签名 URL**：`ObjectStorage.signedUrl(key, Duration.ofHours(1))`，读取时现签。
- **列表查询**：`SELECT ... FROM pet_images WHERE pet_id=#{petId} AND file_type='PHOTO' AND access_level='PUBLIC' AND review_status='APPROVED' ORDER BY id`；Service 先 `PetMapper.findByIdActive(petId)` 判 ACTIVE。`access_level='PUBLIC'` 本轮恒真，但显式过滤为后续 `PRIVATE`/`AUTHORIZED` 留边界。
- **数量统计**：`SELECT COUNT(*) FROM pet_images WHERE pet_id=#{petId} AND file_type='PHOTO'`。

## 7. 分层与文件清单（backend-package-architecture）

新增业务模块 `com.pethelp.pet`（图片部分）+ 两个基础设施模块：

```
com.pethelp.pet/
├── controller/PetImageController.java       # @RestController，薄层
├── service/PetImageService.java             # 接口
│   └── impl/PetImageServiceImpl.java        # @Service
├── dto/PetImageResponse.java
├── entity/PetImage.java                     # 无 storage 对象
└── mapper/PetImageMapper.java               # @Mapper

com.pethelp.picture.storage/                          # 对象存储适配器（跨模块复用，头像后续也用）
├── ObjectStorage.java                        # put / signedUrl
├── InMemoryObjectStorage.java                # 本地/测试：ConcurrentHashMap，URL=memory://key
├── OssObjectStorage.java                     # 生产：OSS SDK
└── ObjectStorageConfig.java                  # 互斥装配：oss.enabled

com.pethelp.picture.review/                           # 内容审核适配器（跨模块复用）
├── ImageReviewService.java                   # review(byte[], fileKey) -> status
├── AutoApproveImageReviewService.java        # 恒 APPROVED
└── ImageReviewConfig.java                    # @ConditionalOnMissingBean

src/main/resources/mapper/PetImageMapper.xml  # namespace=com.pethelp.pet.mapper.PetImageMapper
```

- `security/SecurityConfig` 增加：`requestMatchers(HttpMethod.GET, "/api/v1/pets/*/images").permitAll()`（公开列表；现有 `GET /pets/*` 不匹配 `/*/images`）。
- `application.yml`：`spring.servlet.multipart.max-file-size=10MB`、`max-request-size=10MB`。
- `common/GlobalExceptionHandler` 增加 `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 400「文件过大」。

跨模块依赖：`PetImageServiceImpl` 依赖 `PetMapper`（查档案归属/状态）+ `PetImageMapper` + `ObjectStorage` + `ImageReviewService` + 异常 + `PetImageResponse`。`ObjectStorage`/`ImageReviewService` 无业务依赖。

## 8. 权限与安全校验（Service 层，禁止 assert）

- 上传：`pet.publisherId.equals(currentUserId)`，否则 `ForbiddenException`（403）。
- 状态：`PetMapper.findById` 读档案（排除 DELETED → 404）；`pet.status ∈ {DRAFT, ACTIVE}` 才可上传，否则 `ConflictException`（409，实际命中 `ARCHIVED`）。
- 可见性：`GET /pets/{id}/images` 仅 `ACTIVE` 档案（`findByIdActive`，否则 404），且仅 `APPROVED` 图片。
- 文件类型：魔数嗅探（后端），不信任 `Content-Type`/后缀。
- 私有 key：不落库 URL、不下发永久 URL；签名 URL 1 小时过期。

## 9. 测试方案

### 9.1 单元测试（Mockito）
- `PetImageServiceImplTest`（mock `PetMapper` + `PetImageMapper` + `ObjectStorage` + `ImageReviewService`）：上传 happy path（存→审→插，返回 `APPROVED`/`signedUrl=null`）、非本人 403、档案不存在 404、非 DRAFT/ACTIVE 409、≥9 张 400、非 JPEG/PNG 400、>10MB 400、列表 happy path（ACTIVE 返回带 URL）、列表非 ACTIVE 404。
- `AutoApproveImageReviewServiceTest`：恒返回 `APPROVED`。
- `InMemoryObjectStorageTest`：`put` 后 `signedUrl` 返回 `memory://key`。
- `OssObjectStorageTest`（mock OSS `OSS` 接口）：`put` → `putObject(bucket,key,InputStream)`；`signedUrl` → `generatePresignedUrl(..., ~1h)`。

### 9.2 集成测试（`PetImageFlowIntegrationTest`，Testcontainers postgis + redis，MockMvc，走 `InMemoryObjectStorage`）
1. 登录 → 建档案 → 上传 PNG 字节 → 200，`id` 非空、`reviewStatus=APPROVED`、`signedUrl=null`。
2. 未发布（DRAFT）档案列表 → 404；publish 后列表 → 200，`signedUrl` 以 `memory://` 开头。
3. 他人上传 → 403。
4. 非 JPEG/PNG 字节上传 → 400。
5. 不存在档案上传 → 404。
6. 软删除（DELETED）档案上传 → 404（`findById` 排除 DELETED）。

> 遵循 docs/09：真实 PostGIS 镜像，不用 H2。OSS 走 `oss.enabled=false`（默认）的 `InMemoryObjectStorage`，不连真实 OSS。

## 10. 新增依赖

- `com.aliyun.oss:aliyun-sdk-oss:3.18.4` —— 生产 `OssObjectStorage` 用；理由：用户明确选择 OSS。测试不依赖它（用内存实现 + mock `OSS` 接口）。

## 11. 留给 builder 的自由度

- `PetImageMapper.xml` 的具体 SQL 写法与 `PetImage` 字段映射。
- 魔数嗅探的具体实现位置（Service 私有方法 vs 独立工具类）与错误文案。
- `ObjectStorageConfig` 互斥装配的具体注解组合（`@ConditionalOnProperty` 两个互斥配置，见 C3）。
- `OssObjectStorage` 构造与 `OSS` 客户端 bean 的装配（`oss.endpoint/access-key-id/access-key-secret/bucket` 从 `application.yml` 占位符读，密钥不提交）。
- 集成测试里构造最小合法 PNG/JPEG 字节数组的方式。

## 12. 与既有文档的差异/缺口修正

| 项 | 说明 |
|---|---|
| `pets.status` 的 `DRAFT→ACTIVE` 失败条件含「图片未过审」（docs/02 §2.1） | 本模块**不**给 `publish` 加图片门槛——照片可空，且当前自动通过；把「publish 强制要求至少一张过审图」留到帖子/发布规则明确时再定（记为 deferred） |
| `pet_images.file_type` 支持 `PHOTO/HEALTH_CERT/QUARANTINE` | 本轮仅 `PHOTO`，其余留后续 |
| 封面/删图 | `pet_images` 无 `is_cover`/排序字段，本轮不引入 |
