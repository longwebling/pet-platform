---
name: backend-package-architecture
description: Use when creating, moving, or reorganizing backend Java packages and classes in the pet-help project. Enforces the modular-monolith, business-module-first layout with internal technical layers (controller/service/dto/entity/mapper), module-specific sub-packages, cross-module dependency rules, common-package usage, test directory conventions, and a refactoring checklist. Applies to Java 17 + Spring Boot + MyBatis.
---

# 后端包结构规范（Backend Package Architecture）

模块化单体后端的分包与分层规范。适用于本项目技术栈：**Java 17 + Spring Boot 3.5 + MyBatis + Spring Security + Redis**。

## 1. 总原则：模块化单体 + 业务模块优先

- 单个 Spring Boot 应用，根包 `com.pethelp`，顶层按**业务模块**划分：`auth`、`user`，未来 `pet` / `post` / `application` / `arrangement` / `message` / `admin` 等。
- 每个业务模块内部再按**技术分层**细分（见第 2 节）。
- 横切能力独立成包，不进业务模块：`security`（认证/授权）、`common`（统一响应/异常/类型处理器）。

## 2. 业务模块内部标准结构

每个业务模块遵循统一的内部技术分层：

```
com.pethelp.<module>/
├── controller/       # REST 入口（@RestController）
├── service/          # 业务接口
│   └── impl/         # 业务实现（@Service，实现 service 接口）
├── dto/              # 请求/响应 DTO（record）
├── entity/           # 领域实体（POJO，对应表）
└── mapper/           # MyBatis Mapper 接口（@Mapper）
```

- 无自有实体/表的模块（如 `auth`）可省略 `entity` / `mapper`，复用其他模块的 mapper。
- service 采用「接口 + impl 实现」分离；接口放 `service/`，实现放 `service/impl/`。

## 3. 模块特有子包

当某模块有专属横向能力时，单独成子包，内部同样「接口/实现分离」：

- `auth/token/`：JWT 令牌（`JwtService` 接口 + `impl/JwtServiceImpl` + `JwtClaims`）。
- `auth/sms/`：短信验证码（`SmsSender` SPI + `DevSmsSender` + `SmsCodeService` 接口 + `impl/SmsCodeServiceImpl` + `SmsSenderConfig`）。

约定：子包内接口/SPI 放根，实现放 `impl/`；可被外部实现覆盖的 SPI（如 `SmsSender`）用 `@ConditionalOnMissingBean` 提供默认实现。

## 4. 跨模块依赖规则

- 模块间通过**接口**依赖：引用其他模块的 `service` 接口、`mapper` 接口、`dto`、`entity`；**禁止**引用其他模块的 `impl`。
- `controller` 只依赖本模块 `service` 接口 + `common`（`ApiResponse`、异常）+ `security`（`CurrentUser`）。
- `service.impl` 依赖本模块 `mapper`、跨模块 `service`/`mapper`、`common` 异常。
- `security` 是横切层，可依赖业务模块公共接口（当前依赖 `auth.token` 的 `JwtService`）。
- 新增跨模块依赖时，优先面向接口，保持模块解耦。

## 5. common 包使用规则

`common` 是全项目共享层，**不得**反向依赖任何业务模块：

- `common/ApiResponse`：统一响应包装 `{ code, message, data }`。
- `common/GlobalExceptionHandler`：`@RestControllerAdvice` 全局异常处理。
- `common/exception/*`：`BusinessException` 基类 + 子类（`BadRequestException 40000` / `InvalidSmsCodeException 40100` / `NotFoundException 40400` / `RateLimitException 42900`），错误码与 HTTP 状态对齐。
- `common/mybatis/*`：共享 MyBatis TypeHandler（如 `StringListTypeHandler` 映射 PostgreSQL `varchar[]`）。

## 6. 测试目录规范

测试代码镜像主代码包结构，位于 `src/test/java/com/pethelp/`：

- 单元测试与模块同包：`com.pethelp.auth`、`com.pethelp.common`，类名 `<Class>Test`。
- 集成测试（Testcontainers：PostgreSQL + Redis）放 `com.pethelp` 根包，类名 `<Flow>IntegrationTest`（如 `AuthFlowIntegrationTest`）。
- 测试内实例化实现类（`new AuthServiceImpl(...)`），字段/依赖类型用接口。

## 7. 重构检查清单

移动或重构类时依次执行：

1. 纯移动（无行为变化）依赖既有绿色测试兜底；行为变更先写失败测试（TDD）。
2. 移动类：改 `package` 声明，并更新所有引用方的 import。
3. 移动 `mapper` / `entity`：同步更新 MyBatis XML 的 `namespace` 与 `resultMap` 的 `type`。
4. 更新相关测试的 import。
5. 用 grep 检索残留的旧全限定名引用，确认无遗漏。
6. 运行完整测试套件 `mvn test`，必须全绿。
