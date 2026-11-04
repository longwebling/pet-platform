# 02 — 状态机

> 业务对象严格分离，禁止混用状态。所有状态转移由后端服务端校验，前端不得直接传入状态。

## 2.0 业务对象总览（8 个状态机）

| # | 对象 | 存储表 | 状态字段 |
|---|---|---|---|
| 1 | 宠物档案 | `pets` | `status`（另含独立的 `ownership_status`、`authorization_status`、`source_type`） |
| 2 | 领养帖子 | `pet_posts` (post_type=ADOPTION) | `status` |
| 3 | 临时照护帖子 | `pet_posts` (post_type=FOSTER) | `status` |
| 4 | 领养申请 | `adoption_applications` | `status` |
| 5 | 临时照护申请 | `foster_applications` | `status` |
| 6 | 领养安排 | `adoption_arrangements` | `status` |
| 7 | 临时照护工单 | `foster_orders` | `status` |
| — | （额外）寻主帖子 | `pet_posts` (post_type=FINDING) | `status` |

> 7 个核心对象状态机 + 1 个 FINDING 寻主帖子状态机 = **8 个状态机**。

**RESERVED / PENDING_HANDOVER 的归属（P1-5）**：
- `RESERVED` 属于**帖子**（已被预留，锁定不再接受新申请）。
- `PENDING_HANDOVER` 属于**安排/工单**（待交接）。帖子不再有 `PENDING_HANDOVER`，二者不重叠。

## 2.1 宠物档案状态机（pets.status）

| 状态 | 含义 |
|---|---|
| `DRAFT` | 草稿（未发布） |
| `ACTIVE` | 已发布可用（经过核实） |
| `ARCHIVED` | 已归档（寻主结束/手动归档；**领养完成不再置此态**） |
| `DELETED` | 已删除（软删除） |

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| `DRAFT` → `ACTIVE` | 发布档案 | 发布者 | 必填项缺失、图片未过审 | 否 | status |
| `ACTIVE` → `ARCHIVED` | 寻主结束 / 手动归档 | 发布者 / 运营 | 存在进行中的安排 | 否 | status |
| `ACTIVE` → `DELETED` | 删除 | 发布者 / 运营 | — | 否 | status + deleted_at |

`ownership_status` 独立流转：`UNKNOWN`(待核实) → `UNOWNED`(确认无主) / `OWNED`(有主)；领养交接完成 → `OWNED`（`pets.status` **保持 `ACTIVE`**，`owner_id` 变更写入 `pet_ownership_history`）。

## 2.2 领养帖子状态机（pet_posts, ADOPTION）

状态：`DRAFT` / `PUBLISHED`(发布中) / `PENDING_VERIFY`(待核实) / `OPEN`(招募中) / `RESERVED`(已被预留) / `COMPLETED`(已完成) / `CANCELLED`(已取消) / `CLOSED`(已下架) / `REJECTED`(核实驳回)

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| `DRAFT` → `PUBLISHED` | 发布 | 发布者 | 必填项缺失、无处置授权 | 否 | status |
| `PUBLISHED` → `PENDING_VERIFY` | 进入核实 | 系统/运营 | 内容违禁 | 否 | status |
| `PENDING_VERIFY` → `OPEN` | 核实通过 | 运营 | 核实不通过 | 否 | status + verified_at |
| `PENDING_VERIFY` → `REJECTED` | 核实驳回 | 运营 | — | 否 | status + reject_reason |
| `OPEN` → `RESERVED` | 审核通过某申请→创建安排 | 发布者 | 帖子非 OPEN、宠物已有有效安排 | 否 | status + reserved_application_id（条件更新） |
| `RESERVED` → `COMPLETED` | 安排双方确认完成 | 系统（安排完成联动） | 安排未 COMPLETED | 否 | status + completed_at |
| `RESERVED` → `OPEN` | 安排取消/异常终止，重新开放 | 发布者/申请人取消 | 已 COMPLETED | 否 | status + 清空 reserved |
| 任意非终态 → `CANCELLED` | 取消 | 发布者 | 已 COMPLETED | 否 | status |
| 任意非终态 → `CLOSED` | 下架 | 发布者/运营 | — | 否 | status |

> `RESERVED` = 已有有效安排（等价于 arrangement 处于 `PENDING_HANDOVER`）。交接进度由 arrangement 跟踪，帖子只在安排 COMPLETED 时联动 `COMPLETED`。

## 2.3 寻主帖子状态机（pet_posts, FINDING）

`DRAFT` → `PUBLISHED` → `PENDING_VERIFY` → `OPEN`(寻主中) → `CLOSED`(已找到主人/已结束) 或 → 转领养。

**转领养（P1-6）**：新建一条 ADOPTION 帖子（新 id），将 FINDING 帖子 `status='CLOSED'` 且 `converted_to_post_id` 指向新帖子，**保留 FINDING 历史**。

## 2.4 临时照护帖子状态机（pet_posts, FOSTER）

状态：`DRAFT` / `PUBLISHED` / `PENDING_VERIFY` / `OPEN`(招募中) / `RESERVED`(已预留) / `CLOSED`(已下架) / `CANCELLED` / `REJECTED`

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| `DRAFT` → `PUBLISHED` | 发布 | 主人（须 owner 本人） | 缺起止时间、图片未过审、非 owner | 否 | status |
| `PUBLISHED` → `PENDING_VERIFY` → `OPEN` | 核实 | 运营 | 违禁 | 否 | status |
| `OPEN` → `RESERVED` | 审核通过申请→创建工单 | 主人 | 时段冲突（EXCLUDE） | 否 | status + reserved_application_id |
| `RESERVED` → `OPEN` | 工单取消，重新开放 | 主人 | 工单已进行 | 否 | status |
| 任意非终态 → `CLOSED`/`CANCELLED` | 下架/取消 | 主人/运营 | — | 否 | status |

> 照护帖子「完成」体现在 `foster_orders` 状态；帖子在工单完成后由主人或系统置 `CLOSED`。

## 2.5 领养申请状态机（adoption_applications.status）

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| （创建）→ `PENDING` | 提交申请 | 申请人 | 帖子非 OPEN、已有自己 PENDING 申请 | 否 | insert |
| `PENDING` → `APPROVED` | 审核通过 | 发布者 | 帖子非 OPEN、宠物已被预留 | 否 | status + 创建 arrangement + 批量拒绝其余 PENDING |
| `PENDING` → `REJECTED` | 拒绝（人工或系统自动） | 发布者 / 系统 | 已 APPROVED | 否 | status + reject_reason |
| `PENDING` → `WITHDRAWN` | 撤回 | 申请人 | 已 APPROVED/REJECTED | 否 | status |

## 2.6 临时照护申请状态机（foster_applications.status）

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| （创建）→ `PENDING` | 提交申请（可含提议时段） | 照护人 | 帖子非 OPEN、已有自己 PENDING 申请 | 否 | insert |
| `PENDING` → `APPROVED` | 审核通过（确认最终时段） | 主人 | 时段冲突、非 owner | 否 | status + 创建 foster_order（写最终时间）+ 批量拒绝其余 PENDING |
| `PENDING` → `REJECTED` | 拒绝 | 主人 / 系统 | 已 APPROVED | 否 | status |
| `PENDING` → `WITHDRAWN` | 撤回 | 照护人 | 已 APPROVED/REJECTED | 否 | status |

## 2.7 领养安排状态机（adoption_arrangements.status）

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| （创建）→ `PENDING_HANDOVER` | 申请通过后创建 | 系统 | 宠物已有有效安排（唯一索引） | 否 | insert |
| `PENDING_HANDOVER` → `COMPLETED` | 双方确认交接完成 | 双方（各自确认） | 未双方确认 | 否（双确认幂等） | status + handover 记录 + 更新 pets |
| `PENDING_HANDOVER` → `CANCELLED` | 取消安排 | 任一方 | 已 COMPLETED | 否 | status + 帖子回 OPEN |
| `PENDING_HANDOVER` → `ABORTED` | 异常终止（走失/受伤/争议） | 任一方发起，运营确认 | — | 否 | status + abort_reason + 帖子回 OPEN |

> 完成时联动更新 `pets`（见 04 §4.5）：`owner_id`/`current_custodian_id` → 领养人，`ownership_status` → `OWNED`，`status` **保持 `ACTIVE`**；同时写 `pet_ownership_history`。同一 `pet_id` 可被再次领养。

## 2.8 临时照护工单状态机（foster_orders.status）

| 当前 → 目标 | 事件 | 执行者 | 失败条件 | 可重复 | DB 更新 |
|---|---|---|---|---|---|
| （创建）→ `PENDING_HANDOVER` | 申请通过后创建（写入最终时段） | 系统 | 时段冲突（EXCLUDE） | 否 | insert |
| `PENDING_HANDOVER` → `IN_PROGRESS` | 交接确认，照护开始 | 双方 | 未确认交接 | 否 | status + start 记录 |
| `IN_PROGRESS` → `PENDING_RETURN` | 照护结束，待归还确认 | 照护人/主人 | — | 否 | status |
| `PENDING_RETURN` → `COMPLETED` | 双方确认归还 | 双方 | 未双方确认 | 否（双确认幂等） | status + return 记录 |
| `IN_PROGRESS` → `IN_PROGRESS`（延期） | 申请延期 | 双方同意 | 新时段冲突（EXCLUDE） | 是（重校验） | care_end_time + version |
| `PENDING_HANDOVER`/`IN_PROGRESS` → `CANCELLED` | 取消 | 任一方 | 已 COMPLETED | 否 | status + 帖子回 OPEN |
| 任意非终态 → `ABORTED` | 异常终止（走失/受伤/爽约） | 任一方发起，运营确认 | — | 否 | status + abort_reason |

### 延期 / 取消 / 爽约 / 走失 / 受伤处理

| 事件 | 处理 |
|---|---|
| 延期 | 双方同意后更新 `care_end_time`，`EXCLUDE` 重校验；冲突则拒绝且**原时间不变**；成功则重新占用扩展区间 |
| 取消 | 工单 `CANCELLED`，帖子回 `OPEN`（可重新招募） |
| 爽约 | 记录 `no_show`，运营介入；帖子可重新开放 |
| 走失/受伤 | 工单 `ABORTED`，写入 `handover_records` 异常记录，触发举报/仲裁 |

## 2.9 状态转移通用约束

- 每次转移校验：当前状态合法、操作者拥有资源或具备运营/管理员权限。
- 禁止客户端直接传入状态；后端根据「操作意图」推导目标状态。
- 交接/归还确认需**双方确认**，各自确认幂等。
- 关键表带 `version` 乐观锁，更新时 `version = version + 1` 并校验。
- **申请审核通过 = 同事务**：当前申请 APPROVED + 创建安排/工单 + 其余 PENDING 自动 REJECTED。
