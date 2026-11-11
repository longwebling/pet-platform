# 用户认证与资料模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现手机号验证码登录（JWT 签发/解析）+ 本人资料查看/修改，为后续所有业务模块提供身份底座。

**Architecture:** 在现有 Spring Boot 3.5.16 + MyBatis 单体上，引入 Spring Security（无状态 JWT 过滤器链）+ Redis（验证码/限流/失败计数）+ jjwt。不改数据库 schema，`users` 表直接复用。4 个端点：`POST /auth/sms-code`、`POST /auth/login`、`GET /users/me`、`PATCH /users/me`。

**Tech Stack:** Java 17、Spring Boot 3.5.16、MyBatis 3.0.5、Spring Security 6、spring-boot-starter-data-redis (Lettuce)、jjwt 0.12.x、spring-boot-starter-validation、Testcontainers (PostgreSQL `postgis/postgis:16-3.4` + Redis `redis:7-alpine`)、MockMvc、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-06-user-auth-profile-design.md`

## Global Constraints

- 不改数据库 schema：**无新表、无新字段、无新 Flyway 迁移**。
- 统一前缀 `/api/v1`；返回统一包装 `{code, message, data}`；错误码 5 位（前 3 位 = HTTP 状态）。
- 敏感配置仅经环境变量注入；`app.jwt.secret=${JWT_SECRET}` **无默认值**，缺失启动失败。
- 权限/归属校验在 **Service 层**，禁止 `assert`；越权 403、状态非法 409、不存在 404。
- 模拟验证码：`SecureRandom` 随机、**不固定、不落日志、不回显**。
- 验证码校验在 **Service 层**（不在 Controller）。
- 不引入微服务 / MQ / 过度分层；不配 `PasswordEncoder` / `AuthenticationManager`（无密码）。

## Review Focus

1. **模拟验证码必须是随机码而非固定码** — 两个相邻发送应产生不同码。Pin: Task 4 `sendSmsCodeGeneratesRandomCode`。
2. **验证码校验只放在 Controller 会绕过业务入口** — 校验必须在 Service。Pin: Task 5 的登录测试直接调用 `AuthService.login`（不经 Controller）。
3. **JWT secret / 验证码 / token 不得出现在日志** — `MockSmsProvider`、`AuthService`、`JwtService` 中不得 `log`/`println` 这些值。无自动化测试，Reviewer 按此 grep 检查。
4. **`/users/me` 结构上必须防 IDOR** — 身份仅来自 JWT，请求体不得含 id/phone。Pin: Task 7 `updateProfileIgnoresIdAndPhoneFields`。
5. **过期/篡改 JWT 必须被拒绝** — Pin: Task 2 `parseRejectsExpiredToken` / `parseRejectsTamperedToken`（单测），Task 7 `getMeWithTamperedTokenReturns401`（集成）。

---

### Task 1: 依赖与配置

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: 类路径上可用 Spring Security、validation、data-redis、jjwt；`app.jwt.*`、`app.sms.*`、`spring.data.redis.*` 配置可被绑定。

- [ ] **Step 1: 在 `pom.xml` 加依赖**（BOM 管版本的 3 项不加 `<version>`；jjwt 单独加）

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
<dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.6</version></dependency>
<dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
<dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
```

用 Context7 确认 jjwt 0.12.x 最新 patch，若更新则替换三处 `0.12.6`。

- [ ] **Step 2: 在 `application.yml` 追加配置**

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
app:
  jwt:
    secret: ${JWT_SECRET}
    expiration: ${JWT_EXPIRATION:7d}
  sms:
    provider: mock
    code-length: 6
    code-ttl: 5m
    cooldown: 60s
    daily-limit: 10
    verify-max-attempts: 5
    login-fail-max: 5
    login-fail-ttl: 15m
```

- [ ] **Step 3: 校验编译 + 依赖解析**

Run: `mvn -f backend/pom.xml -q -DskipTests compile`
Expected: exit 0。

Run: `mvn -f backend/pom.xml -q dependency:resolve -DincludeScope=test`
Expected: exit 0，无 "could not resolve"。

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml
git commit -m "build: add security, validation, redis, jjwt dependencies"
```

---

### Task 2: 领域异常 + JwtService + CurrentUser（纯单测，无 Spring）

**Files:**
- Create: `backend/src/main/java/com/pethelp/common/exception/ApiException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/BadRequestException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/UnauthorizedException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/ForbiddenException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/NotFoundException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/ConflictException.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/TooManyRequestsException.java`
- Create: `backend/src/main/java/com/pethelp/security/CurrentUser.java`
- Create: `backend/src/main/java/com/pethelp/security/JwtService.java`
- Create: `backend/src/main/java/com/pethelp/security/JwtProperties.java`
- Create: `backend/src/main/java/com/pethelp/security/JwtConfig.java`
- Test: `backend/src/test/java/com/pethelp/security/JwtServiceTest.java`

**Interfaces:**
- Produces:
  - `ApiException extends RuntimeException`：字段 `int code`，构造 `(int code, String message)`，`int getCode()`。
  - 子类构造 `(int code, String message)`，另带一个默认码便捷构造：`BadRequestException` 40000、`UnauthorizedException` 40100、`ForbiddenException` 40300、`NotFoundException` 40400、`ConflictException` 40900、`TooManyRequestsException` 42900。
  - `record CurrentUser(Long id, String phone, List<String> roles)`。
  - `JwtService(String secret, Duration expiration)` — 纯类，可 `new` 直接单测。
  - `String JwtService.issueToken(Long userId, String phone, List<String> roles)`。
  - `CurrentUser JwtService.parseToken(String token)` — 无效/过期抛 `UnauthorizedException(40100, "未登录或凭证无效")`。
  - `JwtProperties`：`@Component @ConfigurationProperties(prefix="app.jwt")`，字段 `String secret; Duration expiration`。
  - `JwtConfig`：`@Configuration`，`@Bean JwtService jwtService(JwtProperties)` → `new JwtService(props.getSecret(), props.getExpiration())`。

- [ ] **Step 1: 写失败单测 `JwtServiceTest`（纯 JUnit，构造 `new JwtService("test-secret-0123456789abcdef0123456789abcdef", Duration.ofDays(7))`）**

测试方法与断言（HS256；`issueToken` 返回字符串）：
- `issueAndParseRoundTripPreservesClaims()` — issue(42,"13800000001",["user"]) → parse 得到 id=42、phone=、roles=["user"]。
- `parseRejectsExpiredToken()` — `new JwtService(secret, Duration.ofMillis(1))` issue 后 `Thread.sleep(5)` 再 parse → 抛异常。
- `parseRejectsTamperedToken()` — issue 后把 token 中间某字符替换为不同字符再 parse → 抛异常。
- `parseRejectsTokenSignedWithDifferentSecret()` — 用 secretA issue、secretB parse → 抛异常。
- `parseRejectsMalformedToken()` — parse("not.a.jwt") → 抛异常。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=JwtServiceTest test`
Expected: FAIL（`JwtService`/`CurrentUser` 不存在，编译失败或 NoClassDef）。

- [ ] **Step 3: 实现 `CurrentUser` + `JwtService` + `JwtProperties` + `JwtConfig`**

`JwtService` 用 jjwt：`Jwts.builder().subject(String.valueOf(userId)).claim("phone",phone).claim("roles",roles).issuedAt(...).expiration(...).id(UUID).signWith(key).compact()`；`parseToken` 用 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`，`exp`/签名由 jjwt 校验，失败抛统一异常。claims 读取：`sub`→Long、`phone`→String、`roles`→List<String>。secret 需 ≥32 字节，用 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=JwtServiceTest test`
Expected: PASS（5/5）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/security/ backend/src/test/java/com/pethelp/security/
git commit -m "feat: add JWT issue/parse service with unit tests"
```

---

### Task 3: User 实体 + UserMapper + 集成测试基类（Postgres）

**Files:**
- Create: `backend/src/main/java/com/pethelp/user/User.java`
- Create: `backend/src/main/java/com/pethelp/user/UserMapper.java`
- Create: `backend/src/main/java/com/pethelp/common/typehandler/StringArrayTypeHandler.java`
- Create: `backend/src/test/java/com/pethelp/AbstractIntegrationTest.java`
- Test: `backend/src/test/java/com/pethelp/user/UserMapperIntegrationTest.java`

**Interfaces:**
- Produces:
  - `User`：字段 `Long id, String phone, String nickname, String avatar, String bio, List<String> roles, String status`（无时间戳字段）。
  - `UserMapper`（`@Mapper`）：
    - `User findByPhone(String phone)` — `SELECT id, phone, nickname, avatar, bio, roles, status FROM users WHERE phone = #{phone}`。
    - `User findById(Long id)` — 同列，`WHERE id = #{id}`。
    - `int insert(User user)` — `INSERT INTO users (phone) VALUES (#{phone})`，`@Options(useGeneratedKeys=true, keyProperty="id")`；roles/status 走 DB 默认 `{user}`/`ACTIVE`。
    - `int updateProfile(Long id, String nickname, String avatar, String bio)` — `UPDATE users SET nickname=#{nickname}, avatar=#{avatar}, bio=#{bio}, updated_at=now() WHERE id=#{id}`。
  - `roles` 列经 `StringArrayTypeHandler`（`BaseTypeHandler<List<String>>`，`setNonNullParameter` 用 `ps.setArray` 转 `varchar[]`、`getNullableResult` 用 `rs.getArray`）映射；在 `@Select` 上用 `@Results(@Result(column="roles", property="roles", typeHandler=StringArrayTypeHandler.class))`。
  - `AbstractIntegrationTest`（`abstract`，供 Task 4–7 复用）：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`；static `PostgreSQLContainer`（`postgis/postgis:16-3.4`，`asCompatibleSubstituteFor("postgres")`）+ static `GenericContainer`（`redis:7-alpine`，`withExposedPorts(6379)`）；`@DynamicPropertySource` 注入 datasource、redis host/port、`app.jwt.secret`（测试用 ≥32 字节字符串）；`@Autowired MockMvc`、`StringRedisTemplate`、`JdbcTemplate`。

- [ ] **Step 1: 写失败集成测试 `UserMapperIntegrationTest extends AbstractIntegrationTest`**

- `insertAndFindByPhoneReturnsDefaults()` — `insert` 一个 phone="13800000001" 的 User → `findByPhone` 非空，phone 一致、roles=[user]、status="ACTIVE"、id 非空。
- `findByIdReturnsNullWhenMissing()` — `findById(999999L)` == null。
- `updateProfileUpdatesOnlyTargetFields()` — insert 后 `updateProfile(id,"新昵称","http://a/1.png","简介")` → `findById` 的 nickname/avatar/bio 已变、phone 不变。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=UserMapperIntegrationTest test`
Expected: FAIL（`UserMapper`/`User`/`AbstractIntegrationTest` 不存在）。

- [ ] **Step 3: 实现 `User` + `UserMapper` + `StringArrayTypeHandler` + `AbstractIntegrationTest`**

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=UserMapperIntegrationTest test`
Expected: PASS（3/3）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/user/ backend/src/main/java/com/pethelp/common/typehandler/ backend/src/test/java/com/pethelp/AbstractIntegrationTest.java backend/src/test/java/com/pethelp/user/
git commit -m "feat: add User entity and MyBatis mapper with integration test base"
```

---

### Task 4: SmsProvider + MockSmsProvider + AuthService.sendSmsCode（Redis）

**Files:**
- Create: `backend/src/main/java/com/pethelp/auth/SmsProvider.java`
- Create: `backend/src/main/java/com/pethelp/auth/MockSmsProvider.java`
- Create: `backend/src/main/java/com/pethelp/auth/SmsProperties.java`
- Create: `backend/src/main/java/com/pethelp/auth/AuthService.java`（本任务只含 `sendSmsCode`）
- Test: `backend/src/test/java/com/pethelp/auth/AuthServiceSmsCodeIntegrationTest.java`

**Interfaces:**
- Produces:
  - `interface SmsProvider { void send(String phone, String code); }`。
  - `MockSmsProvider implements SmsProvider`（`@Component` + `@ConditionalOnProperty(prefix="app.sms", name="provider", havingValue="mock", matchIfMissing=true)`）：`send` 为 no-op，**不日志不打印 code**。
  - `SmsProperties`：`@Component @ConfigurationProperties(prefix="app.sms")`，字段 `provider, int codeLength=6, Duration codeTtl, Duration cooldown, int dailyLimit, int verifyMaxAttempts, int loginFailMax, Duration loginFailTtl`。
  - `AuthService`（`@Component`，构造 `(SmsProvider, StringRedisTemplate, SmsProperties)`）：
    - `void sendSmsCode(String phone)` — 校验 `^1[3-9]\d{9}$`（非法抛 40001）；`sms:cooldown:{phone}` 存在抛 42901；`sms:daily:{phone}` 计数 ≥ dailyLimit 抛 42902；`SecureRandom` 生成 6 位随机码；`smsProvider.send`；写 `sms:code:{phone}`(codeTtl)、`sms:cooldown:{phone}`(cooldown)、`INCR sms:daily:{phone}`（TTL 至当天 23:59:59）。

- [ ] **Step 1: 写失败集成测试 `AuthServiceSmsCodeIntegrationTest extends AbstractIntegrationTest`**（`@Autowired AuthService`、`StringRedisTemplate`）

- `sendSmsCodeStoresRandomCodeWithTtl()` — send("13800000001") 后 Redis `sms:code:13800000001` 为 6 位数字，`getExpire` 在 (0, codeTtl]。
- `sendSmsCodeGeneratesRandomCode()` — 两次 send（中间 `redisTemplate.delete("sms:cooldown:13800000001")`），两次 `sms:code` 值不同。
- `sendSmsCodeWithinCooldownReturns429()` — 连发两次 → 第二次抛 `TooManyRequestsException`（code 42901）。
- `sendSmsCodeExceedingDailyLimitReturns429()` — 先 `redisTemplate.opsForValue().set("sms:daily:13800000001", String.valueOf(dailyLimit))`，再 send → 抛 42902。
- `sendSmsCodeRejectsInvalidPhone()` — send("123") → 抛 `BadRequestException`（40001）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthServiceSmsCodeIntegrationTest test`
Expected: FAIL（`AuthService`/`SmsProvider`/异常类不存在）。

- [ ] **Step 3: 实现 `SmsProvider` + `MockSmsProvider` + `SmsProperties` + `AuthService.sendSmsCode`**

（`TooManyRequestsException`/`BadRequestException` 已在 Task 2 的 `common.exception` 包建好，直接 import。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthServiceSmsCodeIntegrationTest test`
Expected: PASS（5/5）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/auth/ backend/src/test/java/com/pethelp/auth/
git commit -m "feat: add SMS provider abstraction and send-code service"
```

---

### Task 5: AuthService.login（Postgres + Redis）

**Files:**
- Create: `backend/src/main/java/com/pethelp/user/dto/UserProfileResponse.java`
- Create: `backend/src/main/java/com/pethelp/auth/dto/LoginResponse.java`
- Modify: `backend/src/main/java/com/pethelp/auth/AuthService.java`（加 `login`，构造扩展为 `(SmsProvider, StringRedisTemplate, SmsProperties, UserMapper, JwtService)`）
- Test: `backend/src/test/java/com/pethelp/auth/AuthServiceLoginIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record UserProfileResponse(Long id, String phone, String nickname, String avatar, String bio, List<String> roles)` + `static UserProfileResponse from(User u)`。
  - `record LoginResponse(String token, UserProfileResponse user)`。
  - `LoginResponse AuthService.login(String phone, String code)`：
    - `login:fail:{phone}` ≥ loginFailMax → 抛 42903。
    - 读 `sms:code:{phone}` 比对（constant-time `MessageDigest.isEqual`）；缺失/不匹配 → `INCR login:fail:{phone}`（loginFailTtl），抛 40101。
    - 成功 → `delete` `sms:code:{phone}` 与 `login:fail:{phone}`。
    - `userMapper.findByPhone`：空 → `insert`（phone）；非空且 `status="BANNED"` → 抛 40301。
    - `jwtService.issueToken(id, phone, roles)` → 组装 `LoginResponse`。

- [ ] **Step 1: 写失败集成测试 `AuthServiceLoginIntegrationTest extends AbstractIntegrationTest`**

- `loginWithCorrectCodeCreatesUserAndReturnsToken()` — send 后从 Redis 读码 → `login(phone, code)` 返回非空 token、user.phone、user.roles=[user]；`JdbcTemplate` 查 `users` 有 1 行；Redis `sms:code` 已删。
- `loginWithWrongCodeReturns401AndIncrementsFailCounter()` — send 后 `login(phone, "000000")` 抛 40101；`sms:login:fail` 不存在，`login:fail:{phone}` == "1"。
- `loginWithBannedUserReturns403()` — `JdbcTemplate` 直接 `INSERT INTO users(phone,status) VALUES('13800000002','BANNED')`，send 后 `login` 抛 40301。
- `loginLockedAfterTooManyFailuresReturns429()` — `redisTemplate.opsForValue().set("login:fail:13800000003", String.valueOf(loginFailMax))`，send 后 `login` 抛 42903。
- `loginWithMissingCodeReturns401()` — 不 send 直接 `login(phone,"123456")` 抛 40101。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthServiceLoginIntegrationTest test`
Expected: FAIL（`login` 未实现 / `LoginResponse` 不存在）。

- [ ] **Step 3: 实现 `login` + `LoginResponse` + `UserProfileResponse`**

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthServiceLoginIntegrationTest test`
Expected: PASS（5/5）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/auth/ backend/src/main/java/com/pethelp/user/dto/ backend/src/test/java/com/pethelp/auth/
git commit -m "feat: add SMS-code login with auto-register and lockout"
```

---

### Task 6: 安全过滤器链 + 统一响应/异常 + AuthController

**Files:**
- Create: `backend/src/main/java/com/pethelp/common/ApiResponse.java`
- Create: `backend/src/main/java/com/pethelp/common/exception/GlobalExceptionHandler.java`
- （`ApiException` 及子类已在 Task 2 创建，此处不重建）
- Create: `backend/src/main/java/com/pethelp/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/pethelp/security/SecurityConfig.java`
- Create: `backend/src/main/java/com/pethelp/auth/dto/SmsCodeRequest.java`
- Create: `backend/src/main/java/com/pethelp/auth/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/pethelp/auth/AuthController.java`
- Test: `backend/src/test/java/com/pethelp/auth/AuthControllerIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record ApiResponse<T>(int code, String message, T data)` + `static <T> ok(T)`（code=0,message="ok"）、`static error(int code, String message)`（data=null）。
  - `ApiException` 及子类（Task 2 已有）被 handler 消费；错误码映射见 Task 2 默认码。
  - `GlobalExceptionHandler`（`@RestControllerAdvice`）：`ApiException` → `ApiResponse.error(e.getCode(), e.getMessage())` + 对应 HTTP status；`MethodArgumentNotValidException` → 40000。
  - `JwtAuthenticationFilter extends OncePerRequestFilter`（构造 `JwtService`）：取 `Authorization: Bearer` → `parseToken` → `CurrentUser` → `UsernamePasswordAuthenticationToken(principal=currentUser, null, authorities=roles→"ROLE_"+role)` → 写入 `SecurityContext`；无/坏 token 则不设置（交 EntryPoint）。
  - `SecurityConfig`：`SecurityFilterChain`——`SessionCreationPolicy.STATELESS`、`csrf().disable()`、`cors()` 默认；`requestMatchers("/auth/**","/actuator/health/**","/error").permitAll()`、`anyRequest().authenticated()`；`addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`；`exceptionHandling().authenticationEntryPoint(...→401 JSON {code:40100}).accessDeniedHandler(...→403 JSON {code:40300})`。
  - `record SmsCodeRequest(@NotBlank @Pattern(regexp="^1[3-9]\\d{9}$") String phone)`。
  - `record LoginRequest(@NotBlank String phone, @NotBlank String code)`。
  - `AuthController`（`@RequestMapping("/api/v1/auth")`）：`POST /sms-code`（`@Valid @RequestBody SmsCodeRequest` → `authService.sendSmsCode` → `ApiResponse.ok(null)`）；`POST /login`（`@Valid @RequestBody LoginRequest` → `authService.login` → `ApiResponse.ok(LoginResponse)`）。

- [ ] **Step 1: 写失败集成测试 `AuthControllerIntegrationTest extends AbstractIntegrationTest`**（`MockMvc` + JSONPath）

- `sendSmsCodeReturnsOk()` — `POST /api/v1/auth/sms-code` body `{"phone":"13800000001"}` → status 200，`$.code`=0。
- `sendSmsCodeInvalidPhoneReturns400()` — body `{"phone":"123"}` → status 400，`$.code`=40000（或 40001）。
- `loginReturnsToken()` — 先发码、从 Redis 读码，`POST /api/v1/auth/login` body `{phone, code}` → 200，`$.code`=0、`$.data.token` 非空。
- `loginWrongCodeReturns401()` — body `{phone, "000000"}` → status 401，`$.code`=40101。
- `protectedPathWithoutTokenReturns401()` — `GET /api/v1/users/me` 无 token → 401，`$.code`=40100。
- `protectedPathWithValidTokenPassesSecurity()` — 用 login 返回的 token `GET /api/v1/users/me` → 非 401（此时无控制器，预期 404，`$.code`=40400），证明过滤器放行了合法 token。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthControllerIntegrationTest test`
Expected: FAIL（控制器/安全配置不存在，默认 basic auth 拦 `/auth` 返回 401）。

- [ ] **Step 3: 实现本任务全部文件**

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=AuthControllerIntegrationTest test`
Expected: PASS（6/6）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/common/ backend/src/main/java/com/pethelp/security/ backend/src/main/java/com/pethelp/auth/ backend/src/test/java/com/pethelp/auth/
git commit -m "feat: add security filter chain and auth endpoints"
```

---

### Task 7: UserService + UserController（本人资料）

**Files:**
- Create: `backend/src/main/java/com/pethelp/user/UserService.java`
- Create: `backend/src/main/java/com/pethelp/user/dto/UpdateProfileRequest.java`
- Create: `backend/src/main/java/com/pethelp/user/UserController.java`
- Test: `backend/src/test/java/com/pethelp/user/UserControllerIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record UpdateProfileRequest(@Size(max=64) String nickname, @Size(max=255) String avatar, @Size(max=255) String bio)`（字段可空，表示不修改；全 null 时返回当前值）。
  - `UserService`（`@Component`，构造 `UserMapper`）：
    - `UserProfileResponse getCurrentUser(Long currentUserId)` — `findById`，空抛 40400。
    - `UserProfileResponse updateProfile(Long currentUserId, UpdateProfileRequest req)` — `updateProfile(currentUserId, req.nickname(), req.avatar(), req.bio())` 后 `findById` 返回。
  - `UserController`（`@RequestMapping("/api/v1/users")`）：
    - `GET /me`（`@AuthenticationPrincipal CurrentUser`）→ `userService.getCurrentUser(currentUser.id())`。
    - `PATCH /me`（`@AuthenticationPrincipal CurrentUser` + `@Valid @RequestBody UpdateProfileRequest`）→ `userService.updateProfile(...)`。

- [ ] **Step 1: 写失败集成测试 `UserControllerIntegrationTest extends AbstractIntegrationTest`**

- `getMeReturnsProfile()` — login 建号拿 token → `GET /api/v1/users/me` → 200，`$.data.phone` 正确、`$.data.roles[0]`="user"。
- `getMeWithoutTokenReturns401()` — `GET /api/v1/users/me` 无 token → 401，`$.code`=40100。
- `getMeWithTamperedTokenReturns401()` — 把 token 中间字符改动 → 401。
- `updateMeUpdatesProfile()` — login 拿 token → `PATCH /api/v1/users/me` body `{"nickname":"小明","bio":"hi"}` → 200，`$.data.nickname`="小明"；`JdbcTemplate` 查 `users.nickname` 已变、phone 不变。
- `updateProfileIgnoresIdAndPhoneFields()` — `PATCH /api/v1/users/me` body 含 `{"id":999,"phone":"13899999999"}`（UpdateProfileRequest 无这些字段，Jackson 忽略）→ 200，DB 的 id/phone 均未变。
- `updateMeWithoutTokenReturns401()` — 无 token → 401。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -q -Dtest=UserControllerIntegrationTest test`
Expected: FAIL（`UserService`/`UserController` 不存在，`GET /users/me` 返回 404）。

- [ ] **Step 3: 实现 `UserService` + `UpdateProfileRequest` + `UserController`**

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -q -Dtest=UserControllerIntegrationTest test`
Expected: PASS（6/6）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/pethelp/user/ backend/src/test/java/com/pethelp/user/
git commit -m "feat: add current-user profile endpoints"
```

---

### 全量回归

- [ ] **Step 1: 跑全量测试**

Run: `mvn -f backend/pom.xml -q test`
Expected: PASS（DatabaseMigrationTest 4 + SmokeTest 1 + JwtServiceTest 5 + UserMapper 3 + SmsCode 5 + Login 5 + AuthController 6 + UserController 6 = 35）。

- [ ] **Step 2: 汇报（不自动 commit/push）**

列出修改/新增文件、测试结果、Git 状态。

---

## 执行顺序说明（供执行者遵守）

`common/exception` 包（`ApiException` 及子类）是纯 Java 基础依赖，被 `JwtService`（Task 2）、`AuthService`（Task 4/5）、`UserService`（Task 7）共同依赖，故在 **Task 2** 一次建好；`ApiResponse` + `GlobalExceptionHandler`（依赖 Spring MVC）在 Task 6 才补。执行时按 Task 顺序即可，无需跳序。
