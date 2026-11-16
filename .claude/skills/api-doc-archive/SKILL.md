---
name: api-doc-archive
description: Use when the user asks to 创建说明 / 写接口说明文档 / 归档接口文档 / 为某功能生成说明文档 (e.g. "创建说明", "写个说明", "归档这个接口的文档"). Produces a Chinese markdown doc under docs/archive/ following the project's established format — Mermaid sequence diagram(s), important notes, error-code table, and API debugging steps with curl examples. Applies to the pet-help Java 17 + Spring Boot backend.
---

# 接口说明文档归档（API Doc Archive）

当用户提出「创建说明」「写接口说明文档」「归档接口文档」等请求时，为某个功能/接口在 `docs/archive/` 下生成说明文档。

## 1. 目标与位置

- 输出位置：`docs/archive/<feature-name>.md`，文件名用 kebab-case（如 `login-flow.md`、`image-upload.md`）。
- 语言：中文。
- **先读范例对齐风格**：`docs/archive/login-flow.md`、`docs/archive/image-upload.md`（后者是最新模板，优先参考）。

## 2. 生成前必做：从源码提取事实

说明文档必须与代码一致，**禁止臆造**端点、错误码、message。生成前先读：

- `controller`：端点路径、HTTP 方法、鉴权要求（`@AuthenticationPrincipal` / 是否 `permitAll`）。
- `service.impl`：业务校验顺序、状态机、抛出的异常与 message。
- `common/exception/*` 与 `common/GlobalExceptionHandler`：异常 → HTTP 状态 / 错误码映射。
- `mapper` / XML / DDL 迁移脚本：落库字段、过滤条件、默认值。
- `application.yml`：端口、multipart 等配置。

若用户只给了功能名、无源码可读，先定位相关类再写；找不到对应实现时向用户确认，不要凭空生成。

## 3. 文档结构（固定四段）

按以下顺序组织，标题层级与范例一致。

### 3.1 标题与元信息

```markdown
# <功能>说明

> 适用模块：<涉及模块的完整包路径，逗号分隔>
> 技术栈：<本次涉及的技术栈，如 Spring Boot 3.5 + MyBatis + PostgreSQL + Redis>
```

### 3.2 第 1 段：时序图

标题 `## 1. <功能>流程`，下含一个或多个 `### 1.N <子流程>时序图`，每个用 Mermaid `sequenceDiagram`。

要求：

- participant 用**真实类名**（Controller / ServiceImpl / Mapper / Adapter）。
- 用 `alt`/`else` 画出关键校验分支（404/403/409/400 等）。
- 标注返回体结构（`{code:0, message:"ok", data:{...}}`）。

### 3.3 第 2 段：重要说明

标题 `## 2. 重要说明`，用 `- ` 项目符号列出关键设计点，至少覆盖：

- 适配器/外部集成如何隔离、如何互斥装配（如有）。
- 关键业务约束（数量/大小/状态机/可见性）。
- 安全（鉴权、归属、`permitAll` 范围）。
- **本期不做（deferred）** 的项。

### 3.4 第 3 段：异常与错误码

标题 `## 3. 异常与错误码`，一段话说明 `{ code, message, data }` 及 code 与 HTTP 的对应，然后表格：

```markdown
| 场景 | 触发点 | HTTP | code | message |
|---|---|---|---|---|
| ... | ... | 400 | 40000 | ... |
```

表格后跟 `**说明**` 项目符号，解释异常继承体系、特殊 handler（如 `MaxUploadSizeExceededException`、`MissingServletRequestPartException`）等。

### 3.5 第 4 段：接口调试步骤

标题 `## 4. 接口调试步骤`，三个子节：

- `### 4.1 启动`：docker compose 启动基础设施（注明 compose 文件在 `backend/` 下）+ 环境变量 + `mvn spring-boot:run`（在 `backend/` 下），端口默认 8080，健康检查。
- `### 4.2 调用接口（curl 示例）`：编号 **① ② ③ …** 的 curl 示例，每个附预期响应 JSON。登录/取 token 的完整步骤可引用 `docs/archive/login-flow.md`。
- `### 4.3 错误响应示例`：针对关键错误场景（伪造输入、越权、404、缺字段等）的 curl + 预期错误响应。

## 4. 一致性要求

- 响应 `message` 用当前 `ApiResponse` 的实现值（`ok()` → `"ok"`，`error()` → 传入 message），不要照抄旧文档中可能已过期的 `"message":null`。
- 错误码从异常类源码确认：`40000` / `40100` / `40300` / `40400` / `40900` / `42900` / `50000`。
- 写完自查：端点路径、错误码、message 是否与源码逐字一致；时序图分支是否覆盖「重要说明」与「错误码表」里提到的所有场景。
