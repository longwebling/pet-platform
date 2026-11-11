# 用户认证与资料模块 — 设计文档

> 对应 docs/01 §A、docs/05 §5.1、docs/06。本模块只实现 4 个端点，不引入任何业务对象状态机。

## 1. 意图与目标

让用户用**手机号 + 验证码**登录，拿到 JWT，并查看/修改自己的资料。这是所有后续业务模块（宠物档案、发布、申请、交接）的**身份底座**：后续每个写操作都依赖「从 JWT 解析出当前用户身份」。

- **目标**：`POST /auth/sms-code`、`POST /auth/login`、`GET /users/me`、`PATCH /users/me` 可跑通；JWT 可签发/解析/过期；验证码有防刷。
- **非目标**（本轮不做）：`GET /users/{id}`（他人公开资料）、运营/管理员登录与角色分配、真实短信供应商、refresh token、账号注销/换绑手机号、`verification` 认证信息（预留字段保持 NULL）。
- **反目标**（做成即失败）：模拟验证码是固定码、日志泄露 JWT/密码/完整验证码、验证码校验只放 Controller、引入微服务/消息队列/过度分层。

## 2. 技术决策（已确认，实现不得偏离）

1. 引入 **Spring Security** + **JWT（jjwt 0.12.x，HS256）**。
2. 引入 **spring-boot-starter-data-redis**，Redis 用于验证码存储/TTL/发送频率/错误次数。
3. 本地开发用**模拟短信适配器**（`SmsProvider` 接口 + `MockSmsProvider` 实现），不接真实供应商。
4. 引入 **spring-boot-starter-validation**（DTO `@Valid` + Jakarta 注解）。
5. **JWT 密钥等敏感配置仅通过环境变量注入**，不写 Git；`app.jwt.secret=${JWT_SECRET}` 无默认值，缺失启动失败。
6. 用户只能通过**当前登录身份**访问/修改自己资料（`/users/me` 从 JWT 取 id，入参不含 id，结构上防 IDOR）。
7. 测试环境用 **Testcontainers Redis**，不依赖本机 Redis 状态。

**本轮确认的业务规则：**
- JWT 生命周期：**单 token 7 天**（无 refresh）。
- 验证码登录：手机号首次出现**自动注册**（登录即注册，`roles={user}`、`status=ACTIVE`）。
- 开发/测试获取验证码：**测试用 StringRedisTemplate 直读 Redis；人工联调用 redis-cli**。`MockSmsProvider` 不日志、不回显验证码。

## 3. 约束

- **不改数据库 schema**：`users` 表已满足需求（`phone UNIQUE NOT NULL`、`status ACTIVE/BANNED`、`roles DEFAULT '{user}'`），无新表/新字段/新迁移。
- 遵循 docs/05 约定：前缀 `/api/v1`，统一返回 `{code, message, data}`，错误码 5 位（前 3 位 = HTTP 状态），客户端不能传业务状态。
- 遵循 docs/06：权限显式校验在 **Service 层**，禁止 `assert`，越权 403，状态非法 409，不存在 404。
- 无资金/支付；无密码字段（不配 `PasswordEncoder`/`AuthenticationManager`）。

## 4. 认证流程

**POST /auth/sms-code**：
1. 校验手机号格式 `^1[3-9]\d{9}$`（非法 → 400）。
2. 查 `sms:cooldown:{phone}`（存在 → 429「发送过于频繁」）。
3. 查 `sms:daily:{phone}` 计数（>= 10 → 429「当日发送次数已达上限」）。
4. `SecureRandom` 生成 6 位随机码。
5. `smsProvider.send(phone, code)`（Mock 为 no-op，不落日志）。
6. 写 Redis：`sms:code:{phone}`=code（TTL 5min）、`sms:cooldown:{phone}`=1（TTL 60s）、`INCR sms:daily:{phone}`（TTL 至当天结束）。
7. 返回 `{code:0}`（不回显验证码）。

**POST /auth/login**：
1. 校验 `phone` + `code` 格式。
2. 查 `login:fail:{phone}`（>= 5 → 429「尝试过多，请稍后再试」）。
3. 从 Redis 读 `sms:code:{phone}` 比对（constant-time）；不存在/不匹配 → `INCR login:fail:{phone}`（TTL 15min），返回 401「验证码错误或已过期」。
4. 成功 → 删 `sms:code:{phone}`、删 `login:fail:{phone}`。
5. `findByPhone`：不存在 → 自动注册（insert `roles={user}`, `status=ACTIVE`）；存在 → 校验 `status`（BANNED → 403「账号已被封禁」）。
6. 签发 JWT（claims：`sub=userId, phone, roles, iat, exp, jti`）。
7. 返回 `{code:0, data:{token, user:{id, phone, nickname, avatar, bio, roles}}}`。

**GET /users/me**：JWT 取 userId → `findById` → 返回资料（id/phone/nickname/avatar/bio/roles）。

**PATCH /users/me**：JWT 取 userId → 校验 nickname(≤64)/avatar(≤255)/bio(≤255) → `updateProfile` → 返回新资料。

## 5. Redis Key 设计

| Key | 值 | TTL | 用途 |
|---|---|---|---|
| `sms:code:{phone}` | 6 位随机码 | 5 min | 验证码本体 |
| `sms:cooldown:{phone}` | 1 | 60 s | 发送间隔 |
| `sms:daily:{phone}` | 计数器 | 至当天 23:59:59 | 每日上限 10 条 |
| `sms:verify:{phone}` | 计数器 | 5 min | 单码校验上限 5 次 |
| `login:fail:{phone}` | 计数器 | 15 min | 连续失败 5 次锁定 |

- 存储用 `StringRedisTemplate`（验证码与计数器均为字符串）。
- MVP 只做 phone 维度；IP 维度限流标记为可选，不默认做。

## 6. JWT 设计

- 算法 HS256；密钥 `app.jwt.secret` 从 `${JWT_SECRET}` 注入（≥32 字节），缺失启动失败。
- 有效期 `app.jwt.expiration=7d`（可配置）。
- Claims：`sub`(userId)、`phone`、`roles`(List)、`iat`、`exp`、`jti`(UUID，预留撤销)。
- 解析：`JwtService.parse(token)` 校验签名 + `exp`；失败/过期抛 401。
- 安全：JWT 只签发与解析，**任何日志/异常消息不得打印 token 原文**。

## 7. Spring Security 过滤器链

- `SessionCreationPolicy.STATELESS`；CSRF disable；CORS 可配（预留前端源）。
- `JwtAuthenticationFilter extends OncePerRequestFilter`：解析 `Authorization: Bearer` → 校验 → 构造 `CurrentUser` 为 principal → `UsernamePasswordAuthenticationToken`（authorities=`ROLE_*`）→ 写入 `SecurityContext`。
- `SecurityFilterChain`：`/auth/**`、`/actuator/health/**`、`/error` 放行，`anyRequest().authenticated()`。
- `AuthenticationEntryPoint` → 401 JSON；`AccessDeniedHandler` → 403 JSON。

## 8. CurrentUser

- `record CurrentUser(Long id, String phone, List<String> roles)` 作 principal。
- Controller 用 `@AuthenticationPrincipal CurrentUser`；Service 方法传 `Long currentUserId`（对齐 docs/06 风格）。

## 9. 分层职责

- **Controller**（`AuthController`、`UserController`）：薄层，`@Valid` + 委托 Service + 返回 `ApiResponse`。
- **Service**：
  - `AuthService`：发码限流、验码、find-or-create、签发 JWT。
  - `UserService`：`getCurrentUser`、`updateProfile`（本人校验）。
  - `JwtService`：`issueToken(user)`、`parseToken(token)`。
  - `SmsProvider`（接口）+ `MockSmsProvider`（实现）。
- **Mapper**（MyBatis，注解）：`UserMapper` — `findByPhone` / `findById` / `insert` / `updateProfile`。无业务逻辑。
- **DTO**：`SmsCodeRequest` / `LoginRequest` / `LoginResponse` / `UserProfileResponse` / `UpdateProfileRequest`。
- **common**：`ApiResponse<T>`、`GlobalExceptionHandler`（`@RestControllerAdvice`）、业务异常 `ApiException`（含 code）及其子类（BadRequest/Unauthorized/Forbidden/Conflict/NotFound/TooManyRequests）。

## 10. 错误码方案（我的 call）

| code | 含义 |
|---|---|
| 0 | 成功 |
| 40000 / 40001 | 参数错误 / 手机号格式非法 |
| 40100 / 40101 | 未登录或凭证无效 / 验证码错误或已过期 |
| 40300 / 40301 | 越权 / 账号已被封禁 |
| 40400 | 资源不存在 |
| 40900 | 冲突 |
| 42900 / 42901 / 42902 | 限流 / 发送过于频繁或超上限 / 登录尝试过多 |

## 11. 集成测试方案

- Testcontainers：PostgreSQL（已有）+ **Redis `redis:7-alpine`**（新增），`@SpringBootTest` + `@DynamicPropertySource` 双注入，`MockMvc` 发请求，`StringRedisTemplate` 直读验证码。
- 场景（对应 docs/09 思路，逐个 TDD）：
  1. 发码成功 → Redis 有 code 且 TTL 正确。
  2. 60s 内重发 → 429。
  3. 正确码登录 → 200 + JWT + 自动建号（DB 有 `{user}` 用户）。
  4. 错误码登录 → 401，`login:fail` 计数 +1。
  5. 连续失败 5 次 → 429 锁定。
  6. BANNED 用户登录 → 403。
  7. `GET /users/me` 带有效 JWT → 200 返回本人。
  8. 无 token → 401；篡改 token → 401。
  9. `PATCH /users/me` 更新成功 → 200，DB 已更新。
  10. JWT 过期 → 401（短 TTL 或注入 clock）。

## 12. 新增依赖

- `spring-boot-starter-security`（BOM 管版本）。
- `spring-boot-starter-validation`（BOM 管版本）。
- `spring-boot-starter-data-redis`（BOM 管版本）。
- `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson`（0.12.x，**实现时用 Context7 锁定最新 patch**）。
- 测试：`testcontainers` 已有；`spring-boot-starter-test` 已有（含 MockMvc）；Redis 用官方镜像 `redis:7-alpine`，无需额外 testcontainers artifact（`GenericContainer`）。

## 13. 留给 builder 的自由度

- MyBatis 注解 SQL 的具体写法；`ApiResponse`/异常类的具体字段；`JwtService` 内部实现（jjwt API 用法）；`MockSmsProvider` 的具体 no-op 实现。以上均在测试约束下自决，不改变本设计的行为契约。
