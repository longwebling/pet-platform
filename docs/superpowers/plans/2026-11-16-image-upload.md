# 宠物图片上传（Pet Image Upload）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现宠物照片上传（`POST /pets/{id}/images`）与列表（`GET /pets/{id}/images`），文件经 `ObjectStorage` 适配器私有存储、经 `ImageReviewService` 自动过审，列表返回 1 小时签名 URL。

**Architecture:** 后端代理 multipart 上传 → 校验（魔数/大小/数量/归属/状态）→ 自动审核 → 存私有 key → 落 `pet_images` 行；读取时按 key 现签 URL。两个适配器（`ObjectStorage`/`ImageReviewService`）隔离 OSS 与审核，本地/测试走内存实现。

**Tech Stack:** Java 17、Spring Boot 3.5.16、MyBatis 3.0.5、PostgreSQL 16 + PostGIS、阿里云 OSS SDK 3.18.4、Testcontainers、MockMvc、Mockito、AssertJ。

**Spec:** `docs/superpowers/specs/2026-11-16-image-upload-design.md`

## Global Constraints

- Java 17；Spring Boot 3.5.16；MyBatis 3.0.5；无 Lombok（可变 POJO + getter/setter）。
- 手动校验（`BadRequestException` 40000），不引入 jakarta validation。
- 统一响应 `{code, message, data}` 经 `ApiResponse`；错误码 40000/40100/40300/40400/40900/42900。
- 归属与状态转移在后端校验，禁止 `assert`。
- 外部集成（OSS、审核）一律藏到适配器后；测试不连真实 OSS。
- 私有 `file_key` 落库，永不存/下发永久公开 URL；签名 URL 1 小时。
- 不提交任何密钥（OSS 密钥经 `oss.*` 环境变量/占位符注入）。

## Review Focus

以下 5 类输入/失效模式最易咬人，各配一条测试钉到对应 Task：

1. **multipart 缺 `file` 字段** → 应 400「文件缺失」，不得 500。→ Task 3 `uploadImage_missingFilePart_400`。
2. **对软删除（DELETED）档案上传** → `PetMapper.findById` 排除 DELETED，应 404，不得 409。→ Task 3 `uploadImage_deletedPet_404`。
3. **Content-Type 伪造**（声明 `image/png` 但内容非 PNG 字节）→ 魔数嗅探应 400。→ Task 3 `uploadImage_invalidFormat_400`。
4. **空文件**（0 字节）→ 400「文件为空」。→ Task 3 单元 `upload_emptyContent_throws400`。
5. **上传到不存在档案** → 404。→ Task 3 `uploadImage_missingPet_404`。

---

### Task 1: 存储与审核适配器

**Files:**
- Create: `backend/src/main/java/com/pethelp/picture/storage/ObjectStorage.java`
- Create: `backend/src/main/java/com/pethelp/picture/storage/InMemoryObjectStorage.java`
- Create: `backend/src/main/java/com/pethelp/picture/storage/OssObjectStorage.java`
- Create: `backend/src/main/java/com/pethelp/picture/storage/ObjectStorageConfig.java`
- Create: `backend/src/main/java/com/pethelp/picture/review/ImageReviewService.java`
- Create: `backend/src/main/java/com/pethelp/picture/review/AutoApproveImageReviewService.java`
- Create: `backend/src/main/java/com/pethelp/picture/review/ImageReviewConfig.java`
- Modify: `backend/pom.xml`（加 `com.aliyun.oss:aliyun-sdk-oss:3.18.4`）
- Test: `backend/src/test/java/com/pethelp/picture/storage/InMemoryObjectStorageTest.java`
- Test: `backend/src/test/java/com/pethelp/picture/storage/OssObjectStorageTest.java`
- Test: `backend/src/test/java/com/pethelp/picture/review/AutoApproveImageReviewServiceTest.java`

**Interfaces:**
- Produces: `ObjectStorage.put(String key, byte[] content, String contentType)`、`ObjectStorage.signedUrl(String key, Duration ttl) -> String`、`ImageReviewService.review(byte[] content, String fileKey) -> String`。

- [ ] **Step 1: 加 OSS SDK 依赖**

在 `backend/pom.xml` 的 `<dependencies>` 内追加（版本 3.18.4）：

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.18.4</version>
</dependency>
```

- [ ] **Step 2: 写失败测试（3 个文件）**

`InMemoryObjectStorageTest`（`com.pethelp.picture.storage`）：

```java
class InMemoryObjectStorageTest {
    @Test
    void put_thenSignedUrl_returnsMemoryUrl() {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        storage.put("pets/1/a.jpg", new byte[]{1, 2, 3}, "image/jpeg");
        assertThat(storage.signedUrl("pets/1/a.jpg", Duration.ofHours(1)))
                .isEqualTo("memory://pets/1/a.jpg");
    }
}
```

`AutoApproveImageReviewServiceTest`（`com.pethelp.picture.review`）：

```java
class AutoApproveImageReviewServiceTest {
    @Test
    void review_alwaysApproved() {
        AutoApproveImageReviewService service = new AutoApproveImageReviewService();
        assertThat(service.review(new byte[]{1}, "pets/1/a.jpg")).isEqualTo("APPROVED");
    }
}
```

`OssObjectStorageTest`（`com.pethelp.picture.storage`，mock `com.aliyun.oss.OSS`）：

```java
class OssObjectStorageTest {
    @Test
    void put_delegatesToOssPutObject() {
        OSS oss = mock(OSS.class);
        OssObjectStorage storage = new OssObjectStorage(oss, "bucket");
        byte[] content = {1, 2, 3};
        storage.put("pets/1/a.jpg", content, "image/jpeg");
        verify(oss).putObject(eq("bucket"), eq("pets/1/a.jpg"),
                any(InputStream.class), any(ObjectMetadata.class));
    }

    @Test
    void signedUrl_usesOneHourTtl() throws Exception {
        OSS oss = mock(OSS.class);
        when(oss.generatePresignedUrl(eq("bucket"), eq("k"),
                any(Date.class), eq(HttpMethod.GET)))
                .thenReturn(new java.net.URL("https://oss.example/bucket/k"));
        OssObjectStorage storage = new OssObjectStorage(oss, "bucket");

        String url = storage.signedUrl("k", Duration.ofHours(1));

        assertThat(url).contains("oss.example");
        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(oss).generatePresignedUrl(eq("bucket"), eq("k"), captor.capture(), eq(HttpMethod.GET));
        long delta = captor.getValue().getTime() - System.currentTimeMillis();
        assertThat(delta).isBetween(3_500_000L, 3_650_000L);
    }
}
```

（`HttpMethod` 为 `com.aliyun.oss.HttpMethod`，`ObjectMetadata` 为 `com.aliyun.oss.model.ObjectMetadata`。）

- [ ] **Step 3: 运行测试验证失败**

Run: `mvn -f backend/pom.xml test -Dtest=InMemoryObjectStorageTest,AutoApproveImageReviewServiceTest,OssObjectStorageTest`
Expected: FAIL（`ObjectStorage`/`InMemoryObjectStorage`/`OssObjectStorage`/`ImageReviewService` 等类不存在，编译错误）。

- [ ] **Step 4: 实现接口与实现类**

`ObjectStorage`（`com.pethelp.picture.storage`）接口：

```java
public interface ObjectStorage {
    void put(String key, byte[] content, String contentType);
    String signedUrl(String key, Duration ttl);
}
```

`InMemoryObjectStorage`：`ConcurrentHashMap<String, byte[]>`；`put` 存 map；`signedUrl` 返回 `"memory://" + key`。

`ImageReviewService`（`com.pethelp.picture.review`）接口：

```java
public interface ImageReviewService {
    String review(byte[] content, String fileKey);
}
```

`AutoApproveImageReviewService`：`review(...)` 直接 `return "APPROVED";`。

`OssObjectStorage`（`com.pethelp.picture.storage`）：构造 `(OSS oss, String bucket)`；`put` 用 `ObjectMetadata` 设 `contentType` 后 `oss.putObject(bucket, key, new ByteArrayInputStream(content), meta)`；`signedUrl` 用 `oss.generatePresignedUrl(bucket, key, new Date(System.currentTimeMillis() + ttl.toMillis()), com.aliyun.oss.HttpMethod.GET).toString()`。

- [ ] **Step 5: 实现配置装配**

`ObjectStorageConfig`（`com.pethelp.picture.storage`），互斥装配（`oss.enabled` 缺省 false）：

```java
@Configuration
public class ObjectStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "oss.enabled", havingValue = "false", matchIfMissing = true)
    public ObjectStorage inMemoryObjectStorage() {
        return new InMemoryObjectStorage();
    }

    @Configuration
    @ConditionalOnProperty(name = "oss.enabled", havingValue = "true")
    static class OssStorageConfig {
        @Bean(destroyMethod = "shutdown")
        public OSS ossClient(@Value("${oss.endpoint}") String endpoint,
                             @Value("${oss.access-key-id}") String accessKeyId,
                             @Value("${oss.access-key-secret}") String accessKeySecret) {
            return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        }

        @Bean
        public ObjectStorage ossObjectStorage(OSS ossClient, @Value("${oss.bucket}") String bucket) {
            return new OssObjectStorage(ossClient, bucket);
        }
    }
}
```

`ImageReviewConfig`（`com.pethelp.picture.review`）：

```java
@Configuration
public class ImageReviewConfig {
    @Bean
    @ConditionalOnMissingBean(ImageReviewService.class)
    public ImageReviewService autoApproveImageReviewService() {
        return new AutoApproveImageReviewService();
    }
}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `mvn -f backend/pom.xml test -Dtest=InMemoryObjectStorageTest,AutoApproveImageReviewServiceTest,OssObjectStorageTest`
Expected: PASS（3 个测试类全绿）。

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/pethelp/picture/storage backend/src/main/java/com/pethelp/picture/review backend/src/test/java/com/pethelp/picture/storage backend/src/test/java/com/pethelp/picture/review
git commit -m "feat: add ObjectStorage and ImageReviewService adapters"
```

---

### Task 2: PetImage 实体 + Mapper + 响应 DTO

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/entity/PetImage.java`
- Create: `backend/src/main/java/com/pethelp/pet/mapper/PetImageMapper.java`
- Create: `backend/src/main/resources/mapper/PetImageMapper.xml`
- Create: `backend/src/main/java/com/pethelp/pet/dto/PetImageResponse.java`
- Test: `backend/src/test/java/com/pethelp/pet/PetImageMapperIntegrationTest.java`

**Interfaces:**
- Consumes: 无（用已有 `pet_images` 表结构）。
- Produces: `PetImage`（字段 `id/petId/uploaderId/fileKey/fileType/reviewStatus`，getter/setter）、`PetImageMapper.insert(PetImage)`、`PetImageMapper.countByPetIdAndType(Long petId, String fileType)`、`PetImageMapper.findByPetIdPublicApproved(Long petId)`、`PetImageResponse(Long id, Long petId, String fileType, String reviewStatus, String signedUrl)`。

- [ ] **Step 1: 写失败集成测试**

`PetImageMapperIntegrationTest`（`com.pethelp.pet`，Testcontainers postgis + redis，`@SpringBootTest`，镜像与 `PetFlowIntegrationTest` 相同）。种子数据用 `JdbcTemplate` 直接插 user + pet（绕过登录）。

```java
class PetImageMapperIntegrationTest {
    // @Container postgis + redis, @DynamicPropertySource 同 PetFlowIntegrationTest
    @Autowired PetImageMapper petImageMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void insert_count_find_roundTrip() {
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (phone) VALUES (?) RETURNING id", Long.class, "13900139021");
        long petId = jdbcTemplate.queryForObject(
                "INSERT INTO pets (publisher_id, source_type, ownership_status, authorization_status, species, status) " +
                "VALUES (?, 'OWNED', 'OWNED', 'SELF', 'CAT', 'DRAFT') RETURNING id", Long.class, userId);

        PetImage approved = image(petId, userId, "pets/1/a.jpg", "PHOTO", "APPROVED");
        PetImage rejected = image(petId, userId, "pets/1/b.jpg", "PHOTO", "REJECTED");
        petImageMapper.insert(approved);
        petImageMapper.insert(rejected);

        assertThat(approved.getId()).isNotNull();
        assertThat(petImageMapper.countByPetIdAndType(petId, "PHOTO")).isEqualTo(2);

        List<PetImage> list = petImageMapper.findByPetIdPublicApproved(petId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getFileKey()).isEqualTo("pets/1/a.jpg");
        assertThat(list.get(0).getReviewStatus()).isEqualTo("APPROVED");
    }

    private PetImage image(Long petId, Long uploaderId, String key, String type, String status) {
        PetImage img = new PetImage();
        img.setPetId(petId);
        img.setUploaderId(uploaderId);
        img.setFileKey(key);
        img.setFileType(type);
        img.setReviewStatus(status);
        return img;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn -f backend/pom.xml test -Dtest=PetImageMapperIntegrationTest`
Expected: FAIL（`PetImageMapper`/`PetImage` 不存在，编译错误）。

- [ ] **Step 3: 实现实体**

`PetImage`（`com.pethelp.pet.entity`）：可变 POJO，字段 `Long id, Long petId, Long uploaderId, String fileKey, String fileType, String reviewStatus`，各配 getter/setter。无 `access_level`/`created_at`（insert 用 DB 默认，list 不读）。

- [ ] **Step 4: 实现 Mapper 接口与 XML**

`PetImageMapper`（`com.pethelp.pet.mapper`，`@Mapper`）：

```java
@Mapper
public interface PetImageMapper {
    int insert(PetImage image);
    int countByPetIdAndType(@Param("petId") Long petId, @Param("fileType") String fileType);
    List<PetImage> findByPetIdPublicApproved(@Param("petId") Long petId);
}
```

`PetImageMapper.xml`（`backend/src/main/resources/mapper/PetImageMapper.xml`，namespace `com.pethelp.pet.mapper.PetImageMapper`）：

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO pet_images (pet_id, uploader_id, file_key, file_type, review_status)
    VALUES (#{petId}, #{uploaderId}, #{fileKey}, #{fileType}, #{reviewStatus})
</insert>

<select id="countByPetIdAndType" resultType="int">
    SELECT COUNT(*) FROM pet_images WHERE pet_id = #{petId} AND file_type = #{fileType}
</select>

<select id="findByPetIdPublicApproved" resultType="com.pethelp.pet.entity.PetImage">
    SELECT id, pet_id, uploader_id, file_key, file_type, review_status
    FROM pet_images
    WHERE pet_id = #{petId} AND file_type = 'PHOTO'
      AND access_level = 'PUBLIC' AND review_status = 'APPROVED'
    ORDER BY id
</select>
```

（`access_level` 未出现在 insert，落 DB 默认 `PUBLIC`。）

- [ ] **Step 5: 实现响应 DTO**

`PetImageResponse`（`com.pethelp.pet.dto`）：

```java
public record PetImageResponse(Long id, Long petId, String fileType, String reviewStatus, String signedUrl) {}
```

- [ ] **Step 6: 运行测试验证通过**

Run: `mvn -f backend/pom.xml test -Dtest=PetImageMapperIntegrationTest`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/pethelp/pet/entity/PetImage.java backend/src/main/java/com/pethelp/pet/mapper/PetImageMapper.java backend/src/main/resources/mapper/PetImageMapper.xml backend/src/main/java/com/pethelp/pet/dto/PetImageResponse.java backend/src/test/java/com/pethelp/pet/PetImageMapperIntegrationTest.java
git commit -m "feat: add PetImage entity, mapper, and response DTO"
```

---

### Task 3: 上传与列表服务 + 端点

**Files:**
- Create: `backend/src/main/java/com/pethelp/pet/service/PetImageService.java`
- Create: `backend/src/main/java/com/pethelp/pet/service/impl/PetImageServiceImpl.java`
- Create: `backend/src/main/java/com/pethelp/pet/controller/PetImageController.java`
- Modify: `backend/src/main/java/com/pethelp/security/SecurityConfig.java`
- Modify: `backend/src/main/java/com/pethelp/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/resources/application.yml`（multipart 配置）
- Test: `backend/src/test/java/com/pethelp/pet/PetImageServiceImplTest.java`
- Test: `backend/src/test/java/com/pethelp/PetImageFlowIntegrationTest.java`
- Test: Modify `backend/src/test/java/com/pethelp/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `ObjectStorage.put/signedUrl`、`ImageReviewService.review`、`PetImage`、`PetImageMapper.insert/countByPetIdAndType/findByPetIdPublicApproved`、`PetImageResponse`、`PetMapper.findById/findByIdActive`（既有）。

- [ ] **Step 1: 写失败单元测试 `PetImageServiceImplTest`**

mock `PetMapper` + `PetImageMapper` + `ObjectStorage` + `ImageReviewService`，`new PetImageServiceImpl(petMapper, petImageMapper, objectStorage, imageReviewService)`。关键用例与断言：

```java
// 合法 PNG 字节常量（魔数嗅探只看前 8 字节）
byte[] PNG = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

// happy path：upload_validPng_storesAndInsertsApproved
//   petMapper.findById(1L) → pet(publisherId=5, status=DRAFT)
//   petImageMapper.countByPetIdAndType(1L,"PHOTO") → 0
//   imageReviewService.review(any, any) → "APPROVED"
//   petImageMapper.insert 用 doAnswer 回填 id=7
//   service.upload(1L, 5L, PNG)
//   assert response = (id=7, petId=1, fileType="PHOTO", reviewStatus="APPROVED", signedUrl=null)
//   verify objectStorage.put(keyCaptor.capture(), eq(PNG), eq("image/png"))
//   assert keyCaptor 以 "pets/1/" 开头且以 ".png" 结尾
//   verify petImageMapper.insert 的 PetImage：uploaderId=5, reviewStatus="APPROVED", fileType="PHOTO"

// upload_emptyContent_throws400：content = new byte[0] → BadRequestException
// upload_oversize_throws400：content = new byte[10*1024*1024+1] → BadRequestException
// upload_unsupportedFormat_throws400：content = "hello".getBytes() → BadRequestException
// upload_notOwner_throws403：pet.publisherId=99 → ForbiddenException
// upload_petNotFound_throws404：findById → null → NotFoundException
// upload_archivedPet_throws409：pet.status="ARCHIVED" → ConflictException
// upload_limitReached_throws400：countByPetIdAndType → 9 → BadRequestException
// list_activePet_returnsSignedUrls：
//   findByIdActive(1L) → pet
//   findByPetIdPublicApproved(1L) → [image(id=7, petId=1, fileKey="pets/1/a.jpg", fileType="PHOTO", reviewStatus="APPROVED")]
//   objectStorage.signedUrl("pets/1/a.jpg", any) → "memory://pets/1/a.jpg"
//   assert list.get(0).signedUrl() == "memory://pets/1/a.jpg"
// list_inactivePet_throws404：findByIdActive → null → NotFoundException
```

- [ ] **Step 2: 运行单元测试验证失败**

Run: `mvn -f backend/pom.xml test -Dtest=PetImageServiceImplTest`
Expected: FAIL（`PetImageService`/`PetImageServiceImpl` 不存在）。

- [ ] **Step 3: 实现服务接口与实现类**

`PetImageService`（`com.pethelp.pet.service`）：

```java
public interface PetImageService {
    PetImageResponse upload(Long petId, Long currentUserId, byte[] content);
    List<PetImageResponse> list(Long petId);
}
```

`PetImageServiceImpl`（`@Service`，构造注入 4 个依赖）。常量：`MAX_IMAGES=9`、`MAX_BYTES=10*1024*1024`、`EDITABLE=Set.of("DRAFT","ACTIVE")`。`upload` 顺序：

1. `content == null || content.length == 0` → `BadRequestException("文件为空")`。
2. `content.length > MAX_BYTES` → `BadRequestException("文件过大")`。
3. `sniff(content)` 判定类型（魔数），非 JPEG/PNG → `BadRequestException("仅支持 JPG/PNG")`。
4. `petMapper.findById(petId)` → null 则 `NotFoundException("宠物不存在")`。
5. `!pet.getPublisherId().equals(currentUserId)` → `ForbiddenException("仅档案发布者可操作")`。
6. `!EDITABLE.contains(pet.getStatus())` → `ConflictException("当前状态不可上传")`。
7. `petImageMapper.countByPetIdAndType(petId,"PHOTO") >= MAX_IMAGES` → `BadRequestException("图片数量已达上限")`。
8. `fileKey = "pets/" + petId + "/" + UUID.randomUUID() + "." + type.extension()`；`reviewStatus = imageReviewService.review(content, fileKey)`；`objectStorage.put(fileKey, content, type.contentType())`。
9. 建 `PetImage`（petId/uploaderId=currentUserId/fileKey/fileType="PHOTO"/reviewStatus），`insert`，返回 `new PetImageResponse(image.getId(), petId, "PHOTO", reviewStatus, null)`。

`list`：`petMapper.findByIdActive(petId)` → null 则 404；`findByPetIdPublicApproved(petId)` 逐条 `objectStorage.signedUrl(fileKey, Duration.ofHours(1))` 映射为 `PetImageResponse`。

魔数嗅探（`sniff` 私有方法 + 私有枚举）：

```java
private static final byte[] PNG_SIG  = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
private static final byte[] JPEG_SIG = {(byte)0xFF, (byte)0xD8, (byte)0xFF};

private enum ImageType {
    PNG("png", "image/png"), JPG("jpg", "image/jpeg");
    final String extension, contentType;
    ImageType(String e, String c) { extension = e; contentType = c; }
}
// sniff：startsWith(content, PNG_SIG)→PNG；startsWith(content, JPEG_SIG)→JPG；否则抛 BadRequestException
```

- [ ] **Step 4: 运行单元测试验证通过**

Run: `mvn -f backend/pom.xml test -Dtest=PetImageServiceImplTest`
Expected: PASS。

- [ ] **Step 5: 写失败集成测试 `PetImageFlowIntegrationTest` + 扩展 `GlobalExceptionHandlerTest`**

`PetImageFlowIntegrationTest`（`com.pethelp`，容器与 `PetFlowIntegrationTest` 相同，含 `loginAndGetToken`/`createPet` 私有 helper）。用例：

```java
byte[] PNG = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG);

// uploadImage_returnsApprovedId：
//   建档案后 multipart("/api/v1/pets/{id}/images").file(file) 带 token → 200
//   jsonPath $.data.id 非空、$.data.reviewStatus == "APPROVED"、$.data.signedUrl 不存在或 null
// listImages_draftHidesThenActiveShows：
//   建档案 → upload PNG → GET /pets/{id}/images → 404
//   publish → GET /pets/{id}/images → 200，$.data[0].signedUrl 以 "memory://" 开头
// uploadImage_notOwner_403：用户B的 token 传用户A档案 → 403
// uploadImage_invalidFormat_400：text 字节声明 image/png → 400
// uploadImage_missingPet_404：id=999999 → 404
// uploadImage_missingFilePart_400：multipart 不带 file 字段 → 400（code 40000）
// uploadImage_deletedPet_404：publish→delete 后上传 → 404
```

`GlobalExceptionHandlerTest` 追加：

```java
@Test
void handleMaxUploadSizeExceeded_returns400() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleMaxUpload(new MaxUploadSizeExceededException(1024));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().code()).isEqualTo(40000);
}
```

- [ ] **Step 6: 运行集成测试验证失败**

Run: `mvn -f backend/pom.xml test -Dtest=PetImageFlowIntegrationTest,GlobalExceptionHandlerTest`
Expected: FAIL（`PetImageController` 不存在 / 路由 404 / handler 缺方法）。

- [ ] **Step 7: 实现 Controller 与配置改动**

`PetImageController`（`com.pethelp.pet.controller`，`@RestController @RequestMapping("/api/v1/pets")`，构造注入 `PetImageService`）：

```java
@PostMapping("/{id}/images")
public ApiResponse<PetImageResponse> upload(@PathVariable Long id,
                                            @AuthenticationPrincipal CurrentUser currentUser,
                                            @RequestParam("file") MultipartFile file) {
    try {
        return ApiResponse.ok(petImageService.upload(id, currentUser.id(), file.getBytes()));
    } catch (IOException e) {
        throw new BadRequestException("文件读取失败");
    }
}

@GetMapping("/{id}/images")
public ApiResponse<List<PetImageResponse>> list(@PathVariable Long id) {
    return ApiResponse.ok(petImageService.list(id));
}
```

`SecurityConfig`：在 `authorizeHttpRequests` 链中，现有 `GET /api/v1/pets/*` 之后加：

```java
.requestMatchers(HttpMethod.GET, "/api/v1/pets/*/images").permitAll()
```

`GlobalExceptionHandler` 加两个 handler：

```java
@ExceptionHandler(MaxUploadSizeExceededException.class)
public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(40000, "文件过大"));
}

@ExceptionHandler(MissingServletRequestPartException.class)
public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(40000, "文件缺失"));
}
```

（`org.springframework.web.multipart.MaxUploadSizeExceededException`、`org.springframework.web.multipart.support.MissingServletRequestPartException`。）

`application.yml` 在 `spring:` 下追加：

```yaml
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

- [ ] **Step 8: 运行全量测试验证通过**

Run: `mvn -f backend/pom.xml test`
Expected: PASS（含新增单元 + 集成 + 既有 42 个用例全绿）。

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/pethelp/pet/service/PetImageService.java backend/src/main/java/com/pethelp/pet/service/impl/PetImageServiceImpl.java backend/src/main/java/com/pethelp/pet/controller/PetImageController.java backend/src/main/java/com/pethelp/security/SecurityConfig.java backend/src/main/java/com/pethelp/common/GlobalExceptionHandler.java backend/src/main/resources/application.yml backend/src/test/java/com/pethelp/pet/PetImageServiceImplTest.java backend/src/test/java/com/pethelp/PetImageFlowIntegrationTest.java backend/src/test/java/com/pethelp/common/GlobalExceptionHandlerTest.java
git commit -m "feat: add pet image upload and list endpoints"
```

---

## Self-Review 备注

- **Spec 覆盖**：design §5 两个端点 → Task 3；§6 数据/SQL → Task 2；§7 两适配器 → Task 1；§8 安全 → Task 3 单测/集成；§12 的「publish 不设图片门槛」「仅 PHOTO」「封面/删图不做」均为非目标，无对应任务。
- **类型一致**：`PetImageResponse` 5 字段在 Task 2 定义、Task 3 消费一致；`ObjectStorage.put/signedUrl`、`ImageReviewService.review` 签名 Task 1 产出、Task 3 消费一致；`PetImageMapper` 三方法一致。
- **Review Focus**：5 条各有一条 Task 3 测试钉住（`missingFilePart`/`deletedPet`/`invalidFormat`/`emptyContent`/`missingPet`）。
