# v0.1 消息模块（conversations / messages）— 设计文档

> 对应 docs/05 §5.6、docs/04 §4.2（conversations/conversation_participants/messages）、docs/06 §6.1 ⑧⑩ / §6.2、docs/07 §7.2（v0.1 首发含消息）。
> 本迭代补齐 **5.6 消息**：会话（列表/发起/防重复）+ 消息（列表/发送）+ 已读（游标模型），把 v0.1 领养闭环真正闭环——申请通过后双方能在 App 内联系、协商交接。业务规格以上述编号文档为准，本文只记录本次的决策与实现方案，不重复规格。

## 1. 意图与目标

- **目标**：`conversations` / `messages` 端点跑通（列表、发起、消息列表、发送、已读）；参与者可见校验（06 §⑧）；防重复会话（幂等）；越权全部 403；已读为 O(1) 单行更新；消息列表游标分页（默认 N=10）。
- **非目标**：群聊（会话固定 1:1）、已读回执/实时推送（WebSocket/MQ）、`notifications` 系统通知表（本迭代不碰）、逐条已读回执。
- **反目标**（做成即失败）：已读用「逐条 UPDATE messages」实现（O(N)）；会话可见性不校验 `conversation_participants`；客户端直接传业务状态；消息列表用偏移分页（新消息会错位）。

## 2. 范围边界

**范围内**：`conversations` / `conversation_participants` / `messages` 三表的读写（新模块 `com.pethelp.message`）；V4 迁移（`conversation_participants` 加读游标列）；05 §5.6 五个端点 + 新增 `POST /conversations/{id}/read`。

**范围外**：`notifications` 表、群聊、实时推送、图片/位置授权/帖子状态联动的任何改动。

## 3. 技术决策

### 3.1 已确认（与你对齐）

1. **读游标模型**：不在 `messages` 加 `read_at`，而在 `conversation_participants` 加 `last_read_message_id`；标记已读只写这一行。
2. **`POST /conversations` 请求体 = `{ post_id, peer_user_id }`**：对方显式指定，双向都能发起。
3. **新增会话级已读端点 `POST /conversations/{id}/read`**：打开会话即把游标推到最新一条，覆盖「一次会话多条未读」场景。
4. **发起会话业务关系门槛（B）**：至少一方是帖子发布者（`me == post.publisherId || peer == post.publisherId`），否则 403。覆盖「申请人联系发布人」与「发布人联系被批准的申请人」两条链路，同时不放任任意私信。
5. **会话强制关联帖子**：`post_id` 必填（无「无帖子私信」）；**消息收发不受帖子状态影响**——交接完成后帖子变 `COMPLETED` 或 `close` 仍可继续沟通，「post 存在」只判行存在、不看 status。
6. **消息列表游标分页，默认 N=10**：会话摘要暴露 `unreadCount` + `firstUnreadMessageId` 作锚点；前端据此实现「未读>N 加载所有未读、否则加载最新 N、上翻加载更旧」。

### 3.2 我的 call（实现细节，可调整）

| # | 决策 | 理由 |
|---|---|---|
| C1 | 新模块 `com.pethelp.message`（controller / service(+impl) / dto / entity / mapper） | 遵循 backend-package-architecture 顶层「业务模块优先」 |
| C2 | V4 迁移只加一列：`conversation_participants.last_read_message_id bigint NULL`（NULL = 未读任何，查询用 `COALESCE(…,0)`）；顺带 `ALTER TABLE conversations ALTER COLUMN post_id SET NOT NULL` | 最小改动；游标天然幂等；post 必填落 schema |
| C3 | 会话固定 1:1：发起时写两条 participant 行；`GET /conversations` 的 peer = 非当前用户的另一 participant | 5.6 语义为「双方」，防重复会话以「无序对」为准 |
| C4 | 防重复会话 = 事务内 find-or-create（应用层），不加 DB 唯一索引 | 无序对唯一约束在 join 表上表达困难；竞态窗口小、后果是多余会话（无害）。如需硬约束可后续加功能唯一索引 |
| C5 | 已读幂等：会话级游标推到 `MAX(messages.id)`，消息级推到 `GREATEST(当前, 该消息id)` | 均为单行 UPDATE；MAX/GREATEST 语义天然幂等，重复提交 no-op |
| C6 | `GET /conversations/{id}/messages` 游标分页：`limit`（默认 10）+ 可选 `before_id`（更旧）/`after_id`（更新），均按 `conversation_id` 过滤、升序返回 | 聊天场景新消息不断插入，偏移分页会错位；游标稳定、索引友好 |
| C7 | 消息 content 校验：非空、≤2000 字符（400） | 防脏数据，与 `messages.content NOT NULL` 对齐 |
| C8 | 无需改 SecurityConfig：`/api/v1/conversations/**`、`/api/v1/messages/**` 未在 permitAll，落入 `anyRequest().authenticated()` | 消息端点全需登录 |

## 4. 读游标契约（消息无状态机）

- 消息/会话**无状态机**（02 文档 8 个状态机不含消息），生命周期即「追加 + 游标推进」。
- 未读定义（对我而言）：`messages WHERE conversation_id=? AND sender_id <> me AND id > COALESCE(last_read_message_id, 0)`。
- `firstUnreadMessageId` = 上述集合的 `MIN(id)`（无未读则为 null），供前端定位「屏幕居中第一条未读」。
- 已读操作只写 `conversation_participants` 一行，**永不逐条更新 messages**。
- 幂等：重复标记已读为 no-op。

## 5. API 契约

统一前缀 `/api/v1`，鉴权 JWT，返回 `{code, message, data}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | /conversations | 登录 | 会话列表（peer + 最后一条 + 未读数 + firstUnread 锚点），分页 `page`/`size`、按 `lastMessageAt DESC` |
| POST | /conversations | 登录 | 发起会话 `{post_id, peer_user_id}`，防重复（幂等），需至少一方是发布者 |
| GET | /conversations/{id}/messages | 登录 | 消息列表（游标分页：`limit` 默认 10，可选 `before_id`/`after_id`） |
| POST | /conversations/{id}/messages | 登录 | 发送消息 `{content}` |
| POST | /conversations/{id}/read | 登录 | 标记会话已读（游标推到最新）【新增】 |
| POST | /messages/{id}/read | 登录 | 标记该消息已读（游标推到 ≥ 此 id，幂等） |

### 5.1 关键 DTO（record）

```java
record CreateConversationRequest(Long postId, Long peerUserId) {}
record SendMessageRequest(String content) {}

record ParticipantSummary(Long id, String nickname, String avatar) {}
record MessageResponse(Long id, Long conversationId, Long senderId, String content,
                       java.time.OffsetDateTime createdAt) {}
record ConversationResponse(Long id, Long postId, ParticipantSummary peer,
                            MessageResponse lastMessage, long unreadCount,
                            Long firstUnreadMessageId,
                            java.time.OffsetDateTime lastMessageAt) {}
```

### 5.2 示例

```
POST /api/v1/conversations
Authorization: Bearer <adopter-jwt>
{ "post_id": 10, "peer_user_id": 2 }

→ 200
{ "code": 0, "data": { "id": 1, "postId": 10, "peer": {"id":2,"nickname":"小明","avatar":null},
                      "lastMessage": null, "unreadCount": 0, "firstUnreadMessageId": null,
                      "lastMessageAt": null } }

POST /api/v1/conversations/1/messages
Authorization: Bearer <adopter-jwt>
{ "content": "你好，想了解一下交接时间" }

→ 200
{ "code": 0, "data": { "id": 1, "conversationId": 1, "senderId": 3, "content": "你好，想了解一下交接时间", "createdAt": "..." } }

→ 非参与者 403 { "code": 40300, "message": "无权访问该会话" }
→ 空内容 400 { "code": 40000, "message": "消息内容不能为空" }
```

## 6. 数据与 SQL 要点

- **V4 迁移**（本迭代 schema 变更）：
  ```sql
  ALTER TABLE conversation_participants ADD COLUMN last_read_message_id bigint;
  ALTER TABLE conversations ALTER COLUMN post_id SET NOT NULL;
  ```
- **发起会话**（事务内）：
  1. 校验 post 存在（404）、peer 存在（404）、peer ≠ self（400）、至少一方是发布者（403）。
  2. 查已有会话：`SELECT c.id FROM conversations c JOIN conversation_participants p1 ON p1.conversation_id=c.id AND p1.user_id=:me JOIN conversation_participants p2 ON p2.conversation_id=c.id AND p2.user_id=:peer WHERE c.post_id=:postId LIMIT 1` → 命中则返回已有（幂等）。
  3. 否则 `INSERT conversations(post_id)` + 两条 `conversation_participants(conversation_id, user_id, last_read_message_id=NULL)`。
- **发送消息**：`INSERT messages(conversation_id, sender_id, content)`。
- **消息列表游标分页**（三种模式，均按 `conversation_id` 过滤、升序返回）：
  - 最新 N：`WHERE conversation_id=? ORDER BY id DESC LIMIT :limit` 后反转；
  - 更旧（上翻历史）：`WHERE conversation_id=? AND id < :beforeId ORDER BY id DESC LIMIT :limit` 后反转；
  - 更新（未读窗口）：`WHERE conversation_id=? AND id > :afterId ORDER BY id ASC LIMIT :limit`。
- **会话已读**（单行）：
  ```sql
  UPDATE conversation_participants
  SET last_read_message_id = (SELECT MAX(id) FROM messages WHERE conversation_id=:conversationId)
  WHERE conversation_id=:conversationId AND user_id=:me;
  ```
- **消息已读**（单行，幂等）：
  ```sql
  UPDATE conversation_participants
  SET last_read_message_id = GREATEST(COALESCE(last_read_message_id,0), :messageId)
  WHERE conversation_id=:conversationId AND user_id=:me;
  ```
- **未读数 / firstUnread**：
  ```sql
  SELECT COUNT(*), MIN(id) FROM messages
  WHERE conversation_id=:conversationId AND sender_id<>:me
    AND id > COALESCE((SELECT last_read_message_id FROM conversation_participants
                       WHERE conversation_id=:conversationId AND user_id=:me), 0);
  ```

## 7. 分层与文件清单（backend-package-architecture）

```
com.pethelp.message/
├── controller/ConversationController.java
├── service/ConversationService.java + impl/ConversationServiceImpl.java
├── dto/CreateConversationRequest.java / SendMessageRequest.java /
│       ConversationResponse.java / MessageResponse.java / ParticipantSummary.java
├── entity/Conversation.java / ConversationParticipant.java / Message.java
└── mapper/ConversationMapper.java / ConversationParticipantMapper.java / MessageMapper.java  (+ 3 个 xml)

resources/mapper/ConversationMapper.xml / ConversationParticipantMapper.xml / MessageMapper.xml
resources/db/migration/V4__add_conversation_last_read_message_id.sql
```

- 跨模块依赖（只接口）：`message` → `post`（`PostMapper.findById` 校验帖子存在 + 取 publisherId）+ `user`（`UserMapper.findById` 校验 peer 存在、取昵称/头像）。

## 8. 权限与安全校验（Service 层，禁止 assert）

- 所有端点：`currentUserId` 必须是 `conversation_participants` 参与者（否则 403，06 §⑧）。
- 发起会话：post 存在（404）、peer 存在（404）、peer ≠ self（400）、**至少一方是帖子发布者**（`me == post.publisherId || peer == post.publisherId`，否则 403）。
- 发送消息：参与者（403）；content 非空且 ≤2000（400）。
- 会话已读：参与者（403）。
- 消息已读：参与者（403）+ 该消息 `sender_id ≠ me`（403，仅接收者标记）。

## 9. 测试方案

### 9.1 单元测试（`com.pethelp.message` 同包，Mockito mock mapper）

- `ConversationServiceImplTest`：发起会话（peer 校验、**业务关系门槛 403**、防重复幂等返回已有）、发送消息（参与者校验、content 校验）、会话/消息已读（游标推进、非参与者 403、仅接收者 403）、未读计数与 firstUnread 计算。

### 9.2 集成测试（`com.pethelp.MessageFlowIntegrationTest`，Testcontainers postgis+redis，MockMvc）

1. 全链路：adopter 发起会话（post_id + peer=owner）→ 返回 conversation → 发消息 → owner 拉会话列表（peer=adopter、unreadCount=1、firstUnreadMessageId 指向首条）→ owner 回消息 → adopter 会话已读 → unreadCount=0。
2. 防重复：同一 (post, 双方) 再发起 → 返回同一 conversation id，不新建。
3. 业务关系门槛：非发布者互发（双方都非该 post 发布者）→ 403。
4. 越权：第三方拉会话消息 → 403；第三方标记已读 → 403。
5. 幂等：重复消息已读 / 会话已读 → 游标不变。
6. 游标分页：发 >N 条消息后 `before_id`/`after_id` 正确切片。

> 遵循 docs/09：真实 PostGIS 镜像（`conversation_participants` 联合主键、`MAX`/`GREATEST`/`MIN` 等 SQL 需真实 PG）。

## 10. 新增依赖与异常类

- 无新增 Maven 依赖。
- 复用 `ForbiddenException` / `NotFoundException` / `ConflictException` / `BadRequestException`。
- 新增数据库迁移 V4（本迭代唯一 schema 变更）。

## 11. 留给 builder 的自由度

- 各 mapper 方法命名与 SQL 落点：未读数 / firstUnread / 最后一条消息 / peer 信息是否合并到一个 `findConversationSummaries` 查询，还是拆分查询再应用层组装。
- `GET /conversations` 的 peer 昵称/头像：`UserMapper` 联查 vs 应用层批量组装。
- 游标分页的三种模式（最新/更旧/更新）在 mapper 里如何组织（单方法 + 动态 SQL vs 三方法）。
- 集成测试如何复用现有 JWT 登录 helper（参照 `LocationAccessFlowIntegrationTest` / `AdoptionFlowIntegrationTest`）。
