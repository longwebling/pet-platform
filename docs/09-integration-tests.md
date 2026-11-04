# 09 — 数据库集成测试场景

> 针对 04 §4.3 关键约束与 06 并发安全设计，梳理可执行的集成测试场景。
> 均为**场景描述**（尚未接真实测试框架）；每项标注「验证点」对应 DDL/SQL/事务边界。

## 9.1 照护时段冲突（EXCLUDE + btree_gist）

### T1 跨帖子时段重叠被拒
- **前置**：宠物 P1 有 FOSTER 帖子 A、B（不同 `post_id`）；帖子 A 已审批产生工单 O1，时段 `[2026-11-10 08:00, 2026-11-10 20:00)`，状态 `PENDING_HANDOVER`。
- **操作**：帖子 B 审批通过，尝试插入工单 O2，时段 `[2026-11-10 12:00, 2026-11-11 12:00)`。
- **预期**：INSERT 触发 `foster_orders_no_time_overlap` 冲突报错；事务回滚，O2 未落库。
- **验证点**：EXCLUDE 按 `pet_id` 分组、跨 `post_id` 生效。

### T2 相邻时段允许（半开区间）
- **前置**：工单 O1 时段 `[08:00, 20:00)`。
- **操作**：插入工单 O2 时段 `[20:00, 22:00)`。
- **预期**：插入成功（`end == start` 不 `&&` 冲突）。
- **验证点**：`tstzrange` 默认 `[start,end)`。

### T3 起止非法被拒
- **操作**：插入工单 `care_start_time >= care_end_time`。
- **预期**：触发 `chk_foster_time_order` CHECK 冲突。
- **验证点**：`CHECK (care_start_time < care_end_time)`。

### T4 已取消工单释放时段
- **前置**：工单 O1 时段 `[08:00, 20:00)`，先置 `CANCELLED`。
- **操作**：插入工单 O2 时段 `[08:00, 20:00)`（与 O1 原时段重叠）。
- **预期**：插入成功（O1 离开 WHERE 集合，不再占用）。
- **验证点**：partial EXCLUDE 的 `WHERE status IN (...)` 排除终态。

### T5 延期重校验
- **前置**：工单 O1 时段 `[08:00, 20:00)`，工单 O2 时段 `[20:00, 22:00)`。
- **操作**：O1 延期 `care_end_time` → `21:00`。
- **预期**：UPDATE 触发冲突回滚，`care_end_time` 保持 `20:00`（原值不变）。
- **验证点**：延期走同一 EXCLUDE 约束，失败回滚。

## 9.2 交接确认幂等与 RETURN 竞态

### T6 双方首次确认
- **前置**：领养安排 A1（`adopter_id=U1`, `custodian_id=U2`），预创建 `handover_records`(event=HANDOVER, from=U2, to=U1, 两确认位 false)。
- **操作**：U2 调 `confirm-handover`，再 U1 调 `confirm-handover`。
- **预期**：先置 `confirmed_by_from`，后置 `confirmed_by_to`；两位置 true 后 A1 → `COMPLETED`，帖子 → `COMPLETED`，`pets.owner_id/current_custodian_id` → U1，`ownership_status` → `OWNED`，并写入 `pet_ownership_history`。
- **验证点**：双确认推进 + 所有权更新同事务。

### T7 重复确认幂等
- **操作**：U2 已确认后再次 `confirm-handover`。
- **预期**：no-op，返回当前状态，`confirmed_by_from` 不变（仍 true），不重复写库。
- **验证点**：`UPDATE ... AND confirmed_by_from=false` 影响 0 行，幂等。

### T8 RETURN 双并发确认不产生重复记录
- **前置**：照护工单 O1，预创建 `event=HANDOVER` + `event=RETURN`（from=caregiver, to=owner）。
- **操作**：owner 与 caregiver **并发**调用 `confirm-return`。
- **预期**：二者都只 UPDATE 各自确认位（RETURN 记录已预创建，无 INSERT）；无唯一冲突；两位置 true 后 O1 → `COMPLETED`。`uq_handover_record(biz_type,biz_id,event)` 下 RETURN 仅一条。
- **验证点**：RETURN 预创建消除「首次并发 INSERT 竞态」。

### T9 非参与者确认被拒
- **操作**：第三方 U3 对 A1 调 `confirm-handover`。
- **预期**：`403`，确认位不变。
- **验证点**：`currentUser ∈ {from_user_id,to_user_id}` 校验。

### T10 已取消后确认被拒
- **前置**：A1 已 `CANCELLED`。
- **操作**：U1 调 `confirm-handover`。
- **预期**：`409`，不推进。
- **验证点**：状态前置校验（终态不可确认）。

## 9.3 领养唯一性与批量拒绝

### T11 并发审批同一宠物两申请仅一成功
- **前置**：宠物 P1 的 ADOPTION 帖子处于 `OPEN`，申请 App1、App2 均 `PENDING`。
- **操作**：发布者**并发**对 App1、App2 调 `approve`。
- **预期**：仅一个事务条件更新 `pet_posts.status OPEN→RESERVED` 成功（影响行数==1），另一个返回 `409`「已被预留」；`uq_active_adoption_arrangement_per_pet` 兜底，仅一条 `PENDING_HANDOVER` 安排。
- **验证点**：行锁 + CAS + 部分唯一索引。

### T12 批量拒绝
- **前置**：同 T11，App1 通过。
- **操作**：同一事务内对 App2（及其余 PENDING）置 `REJECTED`。
- **预期**：App2 及其余 PENDING 变为 `REJECTED`，`reject_reason='该宠物已被其他申请人匹配'`。
- **验证点**：`UPDATE ... WHERE post_id=? AND status='PENDING' AND id<>?`（同事务）。

### T13 领养完成后可再次领养（不阻塞）
- **前置**：P1 完成一次领养（安排 A1 `COMPLETED`，`pets.owner_id` 更新 + 写 history，`pets.status` 仍 `ACTIVE`）。
- **操作**：现任 owner 发布新 ADOPTION 帖子，产生新安排 A2（`PENDING_HANDOVER`）。
- **预期**：A2 插入成功（旧 A1 为 COMPLETED，不占用唯一索引名额）。
- **验证点**：部分唯一索引仅约束 `PENDING_HANDOVER`；`pets.status` 保持 `ACTIVE`。

## 9.4 所有权更新 + 历史同事务

### T14 所有权更新与历史写入原子性
- **前置**：领养安排 A1 双方确认完成路径。
- **操作**：在 `UPDATE pets SET owner_id...` 之后、`INSERT pet_ownership_history` 之前制造异常（如 changed_by 越权/DB 错误）。
- **预期**：整个事务回滚——`pets.owner_id` 不变、`pet_ownership_history` 无新行、安排状态不 `COMPLETED`。
- **验证点**：`REQUIRED` 单事务，无 `REQUIRES_NEW`/异步拆分。

## 9.5 位置隐私授权与审计

### T15 精确位置授权 + 审计
- **前置**：P1 的 `location_access_grants` 有一条 ACTIVE 授权（grantee=U2）。
- **操作**：U2 调 `GET /pets/{id}/exact-location`。
- **预期**：返回精确坐标；`address_visibility_logs` 新增一条（viewer=U2, pet=P1, address_type=EXACT）。
- **验证点**：授权（grants）与访问审计（visibility_logs）分离。

### T16 未授权精确位置被拒
- **操作**：无授权的 U3 调精确位置接口。
- **预期**：`403`；不写审计日志（未访问）。
- **验证点**：`location_access_grants(grantee_id=?, pet_id=?, status=ACTIVE, expires_at>now())` 校验。

## 9.6 待补：需真实运行验证的部分

- partial EXCLUDE 约束在目标 PG 版本的行为（PG 9.0+ 支持，需在 14+ 实测）。
- `pet_ownership_history` 唯一性/追溯查询（本 DDL 未加唯一索引，如需防重需另行设计）。
- RETURN 预创建记录的 `occurred_at` 为空时，`UNIQUE(biz_type,biz_id,event)` 仍按 event 区分（无冲突），需实测确认。
- 上述场景需接入 Testcontainers + PostgreSQL/PostGIS 镜像，或用 Spring `@SpringBootTest` + 真实 PG 验证，而非 H2（H2 不支持 PostGIS/EXCLUDE/partial unique 行为）。
