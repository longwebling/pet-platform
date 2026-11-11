# pet-help-backend

宠物互助平台后端（Spring Boot 3.x 模块化单体，已实现用户登录/注册与用户资料）。

## 技术栈

- Java 17+（本机可用 JDK 17/21/23 构建与运行）
- Spring Boot 3.5.x
- Maven
- PostgreSQL 16 + PostGIS
- Redis 7（验证码存储 + 限流）
- MyBatis（mybatis-spring-boot-starter 3.0.x）
- Spring Security 6 + JWT（无状态认证）
- Flyway（迁移脚本）
- JUnit 5 + Testcontainers（PostgreSQL + Redis 集成测试）

## 目录结构

```
backend/
├── pom.xml
├── docker-compose.yml      # PostgreSQL + PostGIS + Redis
├── .env.example            # 环境变量示例（无真实密码）
├── .gitignore
├── README.md
└── src/
    ├── main/
    │   ├── java/com/pethelp/
    │   │   ├── PetHelpApplication.java
    │   │   ├── auth/       # 验证码登录、JWT、SMS 适配器
    │   │   ├── user/       # 用户资料
    │   │   ├── security/   # Spring Security 配置 + JWT 过滤器
    │   │   └── common/     # 统一响应、异常、MyBatis TypeHandler
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/   # Flyway 迁移
    │       └── mapper/         # MyBatis XML
    └── test/java/com/pethelp/ # 单元测试 + Testcontainers 集成测试
```

## 快速开始

### 1. 准备环境变量

```bash
cp .env.example .env
# 编辑 .env，把 DB_PASSWORD 改成你自己的强密码
```

> `.env` 已被 `.gitignore` 忽略，不会提交；`.env.example` 只含占位值，无真实密码。

### 2. 启动数据库（PostgreSQL + PostGIS + Redis）

```bash
docker compose up -d
docker compose ps   # 等待 STATUS 为 healthy
```

> Redis 默认端口 `6379`，开启 AOF 持久化（`--appendonly yes`），数据卷 `redis_data`。

### 3. 构建

```bash
mvn clean package
```

### 4. 启动后端

Spring Boot 从**环境变量**读取 `DB_*`，不会自动读取 `.env`，本地运行前先加载：

```bash
# bash / Git Bash / zsh
set -a; source .env; set +a

# 方式 A：Maven 运行
mvn spring-boot:run

# 方式 B：运行打包产物
java -jar target/pet-help-backend-0.0.1-SNAPSHOT.jar
```

> 也可用 IDE（IDEA 的 EnvFile 插件）或 direnv 注入环境变量。

### 5. 健康检查

```bash
curl http://localhost:8080/actuator/health
# 预期：{"status":"UP",...}
```

### 6. 数据库连接（供数据库客户端）

| 项 | 值 |
|---|---|
| Host | localhost |
| Port | 5432 |
| Database | pet_help |
| User | pet_help |
| Password | 你在 `.env` 中设置的 `DB_PASSWORD` |

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| DB_HOST | localhost | 数据库地址 |
| DB_PORT | 5432 | 数据库端口 |
| DB_NAME | pet_help | 数据库名 |
| DB_USER | pet_help | 数据库用户 |
| DB_PASSWORD | （无默认，必填） | 数据库密码 |
| REDIS_HOST | localhost | Redis 地址 |
| REDIS_PORT | 6379 | Redis 端口 |
| JWT_SECRET | （无默认，必填） | JWT HS256 签名密钥 |
| SERVER_PORT | 8080 | 后端端口 |

## 已实现 API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/auth/sms-code | 发送验证码（限流：60s 1 次 / 日 10 次） |
| POST | /api/v1/auth/login | 验证码登录，返回 JWT（新用户自动注册） |
| GET | /api/v1/users/me | 当前用户资料 |
| PATCH | /api/v1/users/me | 更新昵称/头像/简介 |
| GET | /api/v1/users/{id} | 查看他人公开资料 |

> 开发环境默认使用 `DevSmsSender` 打印验证码日志（`[DEV SMS] phone=... code=...`）；生产环境注入真实 `SmsSender` 实现即可覆盖（`@ConditionalOnMissingBean`）。

## 测试

```bash
# 全部测试（含 Testcontainers 集成测试，需本机 Docker 可用）
mvn test
```

## 下一步（本次未实现）

- 宠物档案、发布信息、申请、交接、消息、运营等业务模块（见 `docs/` 设计文档）。
- 生产级 SMS 实现与对象存储接入。
