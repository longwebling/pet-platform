# 06 — 并发、安全与异常场景分析

> 第一版无资金流转，并发与安全重点在「资源独占」「时段冲突」「越权」「位置隐私」。

## 6.1 十个重点并发/安全场景

### ① 两个用户同时申请同一只宠物

- **现象**：A、B 同时提交领养申请。
- **性质**：允许并存（多人可同时申请），无需互斥。
- **措施**：申请不做互斥；仅防「同一用户对同一帖子重复提交」，用 `UNIQUE (post_id, adopter_id)` 部分索引 `WHERE status='PENDING'` 防重。

### ② 发布人同时审核两个领养申请（关键：领养唯一性）

- **措施**（事务 + 条件更新 + 唯一约束，不能只靠 Service 先查后插）：
  1. 开启事务，`SELECT ... FOR UPDATE` 锁帖子行。
  2. 条件更新 `UPDATE pet_posts SET status='RESERVED' WHERE id=? AND status='OPEN'`，影响行数 ==1 才成功。
  3. 插入 `adoption_arrangements`（`uq_active_adoption_arrangement_per_pet` 唯一索引兜底）。
  4. 成功才提交；失败回滚并返回「已被预留」。

### ③ 同一只宠物出现重叠照护订单

- **措施**：`EXCLUDE USING gist` 约束加在 `foster_orders`（实际占用时段表）上。并发 INSERT 冲突直接报错。

### ④ 照护延期导致时间冲突

- **措施**：延期更新 `care_end_time` 受同一 `EXCLUDE` + `CHECK` 约束；冲突则事务回滚，**原时间不变**；成功重新占用扩展区间。

### ⑤ 重复提交交接确认（并发 + 幂等，P0-5）

- **创建时机**：`handover_records` 在创建安排/工单的**同一事务内预创建**（领养：HANDOVER；照护：HANDOVER + RETURN），确认位 `false`；`confirm` 接口**只更新确认位、不再 INSERT**，杜绝「首次并发确认时 INSERT 竞态」与「RETURN 双并发 INSERT 竞态」。
- **并发**：双方同时确认时，事务内 `SELECT ... FOR UPDATE` 锁 arrangement/foster_order 行，再更新 `handover_records` 对应确认位。
- **客户端只提交确认意图**（`confirm=true`），不能指定「谁确认」「业务状态」，也不能伪造交接记录。
- **服务端按 JWT 身份**判定当前用户是 `from_user_id` 还是 `to_user_id`，设置对应确认位：

```sql
-- 服务端按身份原子置位（幂等：已 true 则无变化）
UPDATE handover_records
SET confirmed_by_from = true
WHERE biz_type='ADOPTION' AND biz_id=:arrId
  AND from_user_id=:currentUserId AND confirmed_by_from=false;
```

- 置位后检查两确认位，均 true 才推进 arrangement/order 状态；重复提交为 no-op。
- `handover_records` 加 `UNIQUE (biz_type, biz_id, event)` 防重复记录。

**确认结果矩阵**：

| 请求场景 | 前置条件 | 结果 |
|---|---|---|
| 一方首次确认 | 本人 ∈ 安排双方，状态 `PENDING_HANDOVER` | 置对应确认位，返回「待对方确认」 |
| 双方均已确认 | 本人确认位已 true，对方确认位 false→true | 推进状态（领养：安排 COMPLETED + 帖子 COMPLETED + 更新 pets + 写 ownership_history；照护：HANDOVER→IN_PROGRESS） |
| 重复确认（幂等） | 确认位已 true | no-op，返回当前状态，不重复写库 |
| 非参与者确认 | 本人 ∉ 安排双方且非运营 | `403`，不置位 |
| 已取消/已异常终止后确认 | 状态 ∈ `CANCELLED`/`ABORTED`/`COMPLETED` | `409`，不推进 |

- **原子性**：领养「双方确认完成」这一状态推进，须与「更新 pets 所有权 + 写 pet_ownership_history」在**同一事务**内完成，任一失败整体回滚。

### ⑥ 未授权用户查看精确位置（P1-2：授权与审计分离）

- **授权**：`location_access_grants`（谁授权谁看，含 `status`/`expires_at`/`revoked_at`）。
- **审计**：`address_visibility_logs`（谁实际查看了什么位置）。
- 精确位置接口校验：`currentUser` 存在 ACTIVE 且未过期的 `location_access_grants(grantee_id=currentUser, pet_id=?)`，或运营特殊授权；否则 `403`。每次访问写 `address_visibility_logs`。
- 公开接口只返回 `geo_hash` 模糊位置。

### ⑦ 非发布人修改宠物帖子

- **措施**：Service 层显式校验 `post.publisherId == currentUserId`，否则 `403`。更新 SQL 兜底 `WHERE id=? AND publisher_id=?`。

### ⑧ 非安排参与者查看订单和消息

- **措施**：安排/工单查询校验 `currentUserId ∈ {owner_id, caregiver_id}`（照护）或 `∈ {adopter_id, custodian_id}`（领养）或运营；会话/消息校验 `conversation_participants` 含 `currentUserId`。

### ⑨ 用户重复取消已完成的安排

- **措施**：状态前置校验，`COMPLETED`/`CANCELLED`/`ABORTED` 为终态，重复取消返回 `409`（状态非法）。

### ⑩ 发布人和申请人之间的异常纠纷

- **措施**：异常事件走 `abort` + `handover_records` 留痕 + `reports` 举报，运营介入；`audit_logs` 记录所有状态变更。

## 6.2 并发/安全手段归类

| 手段 | 适用操作 |
|---|---|
| 数据库事务 + 行锁 | 审核通过（锁帖子 + 条件更新 + 插安排 + 批量拒绝）、交接确认 |
| 条件更新（CAS） | 帖子 `OPEN→RESERVED` |
| 唯一约束 | 领养安排每宠物唯一；交接记录防重；防重复申请 |
| EXCLUDE + CHECK 约束 | 照护时段不重叠、时段合法 |
| 幂等处理 | 交接/归还确认、消息已读 |
| 审计日志 | 精确位置访问、状态变更、运营操作 |

## 6.3 位置隐私三层模型

| 层级 | 数据 | 可见者 |
|---|---|---|
| 精确位置 | `pets.location`（geometry） | 本人、`location_access_grants` 授权的对方、特殊授权运营 |
| 模糊展示位置 | `pets.geo_hash`（~200m~1km 截断） | 所有公开访问者 |
| 约定交接位置 | `handover_records.location` | 安排/工单双方 |

- 申请通过**不自动**获得精确住址，需 `location_access_grants` 授权。
- 运营**不能默认**查看所有精确地址；特殊访问需授权 + 审计。

## 6.4 授权校验（修正：禁止 Java assert）

> 严禁用 `assert` 做权限检查（生产环境 `-ea` 默认关闭会失效）。

正确写法——显式校验 + 业务异常 + 事务：

```java
@Transactional
public AdoptionArrangement approveApplication(Long applicationId, Long currentUserId) {
    AdoptionApplication app = applicationRepository.findById(applicationId)
        .orElseThrow(() -> new NotFoundException("申请不存在"));
    PetPost post = postRepository.findByIdForUpdate(app.getPostId())
        .orElseThrow(() -> new NotFoundException("帖子不存在"));

    // 显式授权校验，禁止 assert
    if (!post.getPublisherId().equals(currentUserId)) {
        throw new ForbiddenException("仅帖子发布者可审核申请");
    }
    if (!petAuthService.canApproveAdoption(post.getPetId(), currentUserId)) {
        throw new ForbiddenException("无该宠物的领养处置授权");
    }
    if (!"OPEN".equals(post.getStatus())) {
        throw new ConflictException("该宠物已被预留或不可申请");
    }

    // 1) 条件更新锁定帖子（CAS）
    if (postRepository.reserve(post.getId(), app.getId()) != 1) {
        throw new ConflictException("该宠物已被预留");
    }
    // 2) 创建领养安排（唯一索引兜底）
    AdoptionArrangement arrangement = arrangementRepository.create(post, app);
    // 3) 批量拒绝其余 PENDING 申请
    applicationRepository.rejectOthers(post.getId(), app.getId(), "该宠物已被其他申请人匹配");
    return arrangement;
}
```

> **事务边界（P1）**：上述四步（锁帖子 → 条件更新 RESERVED → 插安排 + 预创建交接记录 → 批量拒绝其余）在**同一个 `REQUIRED` 事务**内执行，任何一步抛异常或影响行数不符即**整体回滚**。**禁止**用 `REQUIRES_NEW`、`@Async`、消息队列或延迟任务拆分这些步骤，否则会出现「帖子已 RESERVED 但安排未创建」的中间态。照护审核同理（锁帖子 → 写最终时段工单 → 预创建交接记录 → 批量拒绝）。

## 6.5 异常场景清单

- 宠物被预留后安排取消 → 帖子回 `OPEN`，可重新招募。
- 照护时段被并发抢占 → EXCLUDE 约束拒绝，返回可用时段。
- 单方确认交接，另一方未确认 → 状态停留待确认，不推进。
- 走失/受伤 → 工单/安排 `ABORTED` + 异常交接记录 + 举报。
- 疑似走失宠物误入领养 → 必须先走 FINDING 寻主流程，未核实禁转领养（UNAUTHORIZED 无处置权）。
