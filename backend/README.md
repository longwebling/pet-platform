# pet-help-backend

宠物互助平台后端（Spring Boot 3.x 模块化单体骨架，当前不含任何宠物业务）。

## 技术栈

- Java 17+（本机可用 JDK 17/21/23 构建与运行）
- Spring Boot 3.5.x
- Maven
- PostgreSQL 16 + PostGIS
- MyBatis（mybatis-spring-boot-starter 3.0.x）
- Flyway（迁移脚本目录已就绪，业务 DDL 后续加入）
- JUnit 5（Testcontainers 后续接入）

## 目录结构

```
backend/
├── pom.xml
├── docker-compose.yml      # PostgreSQL + PostGIS
├── .env.example            # 环境变量示例（无真实密码）
├── .gitignore
├── README.md
└── src/
    ├── main/
    │   ├── java/com/pethelp/PetHelpApplication.java
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/   # Flyway 迁移（空，待加业务表）
    │       └── mapper/         # MyBatis XML（空，待加）
    └── test/java/com/pethelp/SmokeTest.java
```

## 快速开始

### 1. 准备环境变量

```bash
cp .env.example .env
# 编辑 .env，把 DB_PASSWORD 改成你自己的强密码
```

> `.env` 已被 `.gitignore` 忽略，不会提交；`.env.example` 只含占位值，无真实密码。

### 2. 启动数据库（PostgreSQL + PostGIS）

```bash
docker compose up -d
docker compose ps   # 等待 STATUS 为 healthy
```

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
| SERVER_PORT | 8080 | 后端端口 |

## 下一步（本次未实现）

- 接入 Testcontainers 编写集成测试（当前仅有 JUnit 5 冒烟测试）。
- 在 `db/migration` 加入业务表 DDL（暂不执行完整 19 表 DDL）。
- 添加 MyBatis mapper 与用户/宠物/领养等业务模块。
