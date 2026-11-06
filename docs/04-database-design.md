# 04 — 数据库 ER 图与字段设计

> 第一版**无任何资金/交易字段**。所有状态字段统一为 `varchar(32)` 大写蛇形枚举（不使用 PostgreSQL enum，迁移不友好；不使用 smallint，可读性差）。关键业务对象带 `version` 乐观锁。
>
> **共 19 张表**。

## 4.1 ER 关系

```
users 1 ──── n pets (publisher_id; 另 owner_id、current_custodian_id → users)
pets 1 ──── n pet_images
pets 1 ──── n pet_posts (post_type: FINDING/ADOPTION/FOSTER)
pets 1 ──── n pet_authorizations (授权关系：grantor/grantee → users)
pets 1 ──── n pet_ownership_history (所有权变更留痕：from_owner/to_owner → users)
pet_posts 1 ──── n adoption_applications n ──── 1 users(adopter)
pet_posts 1 ──── n foster_applications  n ──── 1 users(caregiver)
adoption_applications 1 ──── 0..1 adoption_arrangements
foster_applications  1 ──── 0..1 foster_orders
adoption_arrangements / foster_orders 1 ──── 0..n handover_records
users 1 ──── n conversations n ──── n users (conversation_participants)
conversations 1 ──── n messages
pets 1 ──── n location_access_grants (授权者/被授权者 → users)
users 1 ──── n notifications / reports
users/pets/posts 1 ──── 0..n audit_logs / address_visibility_logs
```

## 4.2 表字段设计

### users

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| phone | varchar(20) UNIQUE NOT NULL | 登录凭证 |
| nickname | varchar(64) | |
| avatar | varchar(255) | 对象存储 key |
| bio | varchar(255) | 简介 |
| roles | varchar(16)[] | `{user, ops, admin}` |
| verification | jsonb | 认证信息（预留，第一版仅手机号） |
| status | varchar(16) | `ACTIVE`/`BANNED` |
| created_at / updated_at | timestamptz | |

### pets（宠物档案）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| publisher_id | bigint FK→users | **信息发布者**（非必为所有者） |
| owner_id | bigint FK→users NULL | **宠物当前所有者**（走失/无主可空） |
| current_custodian_id | bigint FK→users NULL | **实际照护者** |
| source_type | varchar(16) | `OWNED`/`RESCUED`/`FOUND`/`STRAY` |
| ownership_status | varchar(16) | `OWNED`/`UNKNOWN`/`UNOWNED`/`TRANSFERRING` |
| authorization_status | varchar(16) | `SELF`/`AUTHORIZED`/`UNAUTHORIZED`/`PENDING` |
| species | varchar(16) | `CAT`/`DOG`/`OTHER` |
| breed / age / gender / sterilized / vaccinated | — | 基础档案 |
| health_desc | text | 健康/疫苗/绝育描述 |
| status | varchar(16) | 档案生命周期：`DRAFT`/`ACTIVE`/`ARCHIVED`/`DELETED` |
| location | geometry(Point,4326) | 精确坐标（不外发） |
| geo_hash | varchar(12) | 模糊对外 geohash |
| city / district | varchar | 冗余筛选 |
| version | int NOT NULL DEFAULT 0 | 乐观锁 |
| created_at / updated_at | timestamptz | |
| deleted_at | timestamptz NULL | 软删除时间（`status=DELETED` 时写入，其余为 NULL） |

**语义约定（P0-2）**：
- `publisher_id`：谁发布了这条档案信息（可能是救助者/医院/照护者代发）。
- `owner_id`：宠物当前法律/事实上的所有者，可空。
- `current_custodian_id`：当前实际照护宠物的人。
- `ownership_status`：所有权核实状态。
- `authorization_status`：发布者是否具有发布/处置授权（`SELF` 所有者本人；`AUTHORIZED` 有有效授权记录；`UNAUTHORIZED` 如发现走失无处置权；`PENDING` 待核实）。

### pet_images（图片/健康证明，独立文件记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | |
| uploader_id | bigint FK→users | 上传者 |
| file_key | varchar(255) | 对象存储私有 key（不存永久公开 URL） |
| file_type | varchar(16) | `PHOTO`/`HEALTH_CERT`/`QUARANTINE` |
| review_status | varchar(16) | `PENDING`/`APPROVED`/`REJECTED` |
| access_level | varchar(16) | `PUBLIC`/`PRIVATE`/`AUTHORIZED` |
| created_at | timestamptz | |

### pet_posts（发布信息，与档案分离；post_type 区分）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | 关联宠物档案 |
| publisher_id | bigint FK→users | 发布者 |
| post_type | varchar(16) | `FINDING`/`ADOPTION`/`FOSTER` |
| title / description | text | |
| care_type | varchar(16) NULL | FOSTER 专用：`VISIT`/`BOARDING`/`WALK`/`COMPANY` |
| care_start_time / care_end_time | timestamptz NULL | FOSTER 帖子展示的意向时段 |
| status | varchar(16) | 见状态机 |
| reserved_application_id | bigint NULL | 被预留的申请（互斥） |
| converted_to_post_id | bigint NULL | FINDING 转领养后指向新 ADOPTION 帖子 |
| reject_reason | varchar(255) | 核实驳回原因 |
| verified_at / completed_at | timestamptz NULL | |
| version | int NOT NULL DEFAULT 0 | |
| created_at / updated_at | | |

### pet_authorizations（处置授权关系表，P0-2）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | |
| grantor_id | bigint FK→users | 授权者（通常 owner，或平台运营代授） |
| grantee_id | bigint FK→users | 被授权者（代发者/照护者） |
| auth_type | varchar(16) | `PUBLISH`/`ADOPTION_APPROVAL`/`HANDOVER`/`CUSTODY` |
| status | varchar(16) | `ACTIVE`/`REVOKED`/`EXPIRED` |
| granted_at / expires_at / revoked_at | timestamptz | |
| created_at | | |

### pet_ownership_history（所有权变更留痕，P1-7）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | |
| from_owner_id | bigint FK→users NULL | 变更前所有者（可空，如无主→有主） |
| to_owner_id | bigint FK→users NULL | 变更后所有者（可空，如有主→确认无主） |
| change_type | varchar(16) | `ADOPTION`/`FOUND_RETURN`/`RESCUE`/`MANUAL` |
| arrangement_id | bigint NULL | 关联领养安排（change_type=ADOPTION） |
| order_id | bigint NULL | 关联照护工单（预留） |
| changed_by | bigint FK→users | 操作者（运营/系统） |
| reason | text | 变更说明 |
| created_at | timestamptz | |

> 每次 `pets.owner_id` 变更写入一条历史，保证「同一 pet_id 跨多次归属变更」可追溯，避免靠 `ARCHIVED` 切断生命周期。

### adoption_applications（领养申请）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| post_id | bigint FK→pet_posts | |
| adopter_id | bigint FK→users | 申请人 |
| message | text | 申请留言 |
| status | varchar(16) | `PENDING`/`APPROVED`/`REJECTED`/`WITHDRAWN` |
| reject_reason | varchar(255) | 拒绝原因（含系统自动拒绝） |
| version | int NOT NULL DEFAULT 0 | |
| created_at / updated_at | | |

### foster_applications（照护申请）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| post_id | bigint FK→pet_posts | |
| caregiver_id | bigint FK→users | 照护人 |
| care_start_time / care_end_time | timestamptz NULL | **申请人提议的照护时段**（可空） |
| message | text | |
| status | varchar(16) | `PENDING`/`APPROVED`/`REJECTED`/`WITHDRAWN` |
| reject_reason | varchar(255) | |
| version | int NOT NULL DEFAULT 0 | |
| created_at / updated_at | | |

### adoption_arrangements（领养安排，无资金）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | |
| post_id | bigint FK→pet_posts | |
| application_id | bigint FK→adoption_applications | |
| adopter_id | bigint FK→users | 领养人 |
| custodian_id | bigint FK→users | 交接方（宠物当前照护者） |
| status | varchar(16) | `PENDING_HANDOVER`/`COMPLETED`/`CANCELLED`/`ABORTED` |
| abort_reason | varchar(255) | |
| completed_at | timestamptz NULL | |
| version | int NOT NULL DEFAULT 0 | |
| created_at / updated_at | | |

### foster_orders（照护工单，无资金）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| pet_id | bigint FK→pets | |
| post_id | bigint FK→pet_posts | |
| application_id | bigint FK→foster_applications | |
| owner_id | bigint FK→users | 宠物主人 |
| caregiver_id | bigint FK→users | 照护人 |
| care_start_time / care_end_time | timestamptz NOT NULL | **最终确认的占用时段** |
| status | varchar(16) | `PENDING_HANDOVER`/`IN_PROGRESS`/`PENDING_RETURN`/`COMPLETED`/`CANCELLED`/`ABORTED` |
| no_show | boolean DEFAULT false | 爽约标记 |
| abort_reason | varchar(255) | |
| version | int NOT NULL DEFAULT 0 | |
| created_at / updated_at | | |

### handover_records（交接/归还记录，随安排/工单同事务创建，confirm 接口仅更新）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| biz_type | varchar(16) | `ADOPTION`/`FOSTER` |
| biz_id | bigint | 关联 arrangement/order id |
| from_user_id / to_user_id | bigint FK→users | 交接双方 |
| location | geometry(Point,4326) NULL | 约定交接位置 |
| location_type | varchar(16) | `FUZZY`/`AUTHORIZED`/`AGREED` |
| event | varchar(16) | `HANDOVER`/`RETURN`/`ABORT` |
| confirmed_by_from / confirmed_by_to | boolean NOT NULL DEFAULT false | 双方各自确认（服务端按 JWT 身份设置） |
| occurred_at | timestamptz | |
| created_at | | |

> **创建时机（P1）**：`handover_records` 在创建安排/工单的**同一事务内预创建**，确认位 `false`，`confirm` 接口**只更新确认位、不 INSERT**（杜绝确认竞态）：
> - 领养安排：预创建 1 条 `event=HANDOVER`（`from_user_id=custodian_id` → `to_user_id=adopter_id`）。
> - 照护工单：预创建 2 条 —— `event=HANDOVER`（owner → caregiver）与 `event=RETURN`（caregiver → owner）。
> - `event=ABORT`（异常终止）由 `abort` 接口在行锁内懒创建（`ON CONFLICT DO NOTHING`），不参与对称双确认。

### conversations / conversation_participants / messages

- `conversations`：id、post_id、created_at。
- `conversation_participants`：conversation_id、user_id（联合主键），用于权限校验「参与者可见」。
- `messages`：id、conversation_id、sender_id、content、created_at。

### location_access_grants（精确位置授权，P1-2；与访问审计分离）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigserial PK | |
| grantor_id | bigint FK→users | 授权者（位置拥有方：owner/发布者） |
| grantee_id | bigint FK→users | 被授权者（领养人/照护人） |
| pet_id | bigint FK→pets | |
| location_type | varchar(16) | `EXACT`/`AGREED` |
| status | varchar(16) | `ACTIVE`/`REVOKED`/`EXPIRED` |
| granted_at / expires_at / revoked_at | timestamptz | |
| created_at | | |

### notifications / reports / audit_logs / address_visibility_logs

- `notifications`：user_id、type、payload、read_at。
- `reports`：reporter_id、target_type、target_id、reason、status、handled_by。
- `audit_logs`：actor_id、action、resource_type、resource_id、before/after、created_at。
- `address_visibility_logs`：viewer_id、pet_id、post_id、address_type、viewed_at（**访问审计**：谁实际查看过什么位置）。

> `location_access_grants` 记录「授权」（谁能看），`address_visibility_logs` 记录「访问」（谁实际看了）。二者职责分离。

## 4.3 关键约束与索引

### ① 领养唯一性（P0-1 修复：仅约束有效的待交接安排）

```sql
-- 只约束「有效待交接安排」，COMPLETED/CANCELLED/ABORTED 不占用名额
CREATE UNIQUE INDEX uq_active_adoption_arrangement_per_pet
  ON adoption_arrangements (pet_id)
  WHERE status = 'PENDING_HANDOVER';
```

- `PENDING_HANDOVER` 是唯一「占用名额」的有效安排状态。
- `COMPLETED` 为终态，不占用名额 → 允许未来再次领养。再次领养时**复用同一 `pet_id`**（不新建档案），由当前 owner 发布新 ADOPTION 帖子，产生新的 `PENDING_HANDOVER` 安排；旧 `COMPLETED` 安排作为历史保留，不被新安排阻塞，`pets.owner_id` 变更写入 `pet_ownership_history`。
- 取消/异常终止：status 离开 `PENDING_HANDOVER` 即自动脱离唯一索引，释放名额。
- 帖子预留采用条件更新（CAS）：

```sql
UPDATE pet_posts
SET status = 'RESERVED', reserved_application_id = :appId, version = version + 1
WHERE id = :postId AND status = 'OPEN';
```

### ② 照护时间合法性 + 冲突约束（P0-4）

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 合法性：起止必须有序
ALTER TABLE foster_orders
  ADD CONSTRAINT chk_foster_time_order CHECK (care_start_time < care_end_time);

-- 冲突：半开区间 [start, end)，前一单 end 可等于后一单 start
ALTER TABLE foster_orders
  ADD CONSTRAINT foster_orders_no_time_overlap
  EXCLUDE USING gist (
    pet_id WITH =,
    tstzrange(care_start_time, care_end_time) WITH &&
  )
  WHERE (status IN ('PENDING_HANDOVER','IN_PROGRESS','PENDING_RETURN'));
```

- `tstzrange(start, end)` 默认 `[start, end)`：**结束时间等于下一单开始时间不冲突**。
- 申请人在 `foster_applications` 提议时段；审核通过时由主人确认最终时间写入 `foster_orders`（受上面两约束）。
- 延期：更新 `care_end_time` 走同一约束；失败则事务回滚，**原时间不变**；成功则重新占用扩展区间。
- **跨帖子同样生效（P1）**：`EXCLUDE` 按 `pet_id` 分组，**不看 `post_id`**。同一宠物即使来自不同 FOSTER 帖子（如取消后重新发布、或同一档案多次发帖），只要落在有效时段内的两个工单重叠即被拦截；`post.status=RESERVED` 仅锁定「帖子不再收新申请」，不影响 `foster_orders` 层面的冲突判定。

### ③ 申请审核后的批量拒绝（P0-3，同事务内）

```sql
UPDATE adoption_applications
SET status='REJECTED', reject_reason='该宠物已被其他申请人匹配', version=version+1
WHERE post_id = :postId AND status='PENDING' AND id <> :approvedId;
-- foster_applications 同理，按 post_id 批量拒绝其余 PENDING
```

### ④ 其他索引

- `pets(location)` GiST；`pets(geo_hash)` btree。
- `pet_posts(post_type, status)` btree。
- `foster_orders(pet_id, status)` btree。
- `adoption_applications(post_id, adopter_id)` 唯一部分索引 `WHERE status='PENDING'`（防同人重复申请）。
- `handover_records(biz_type, biz_id, event)` 唯一（防重复交接记录）。

## 4.4 所有权与处置授权规则（P0-2）

| 场景 | owner_id | publisher_id | current_custodian_id | authorization_status |
|---|---|---|---|---|
| 自己饲养 | 本人 | 本人 | 本人 | SELF |
| 救助流浪 | 可空 | 救助者 | 救助者 | PENDING（待平台核实授处置权） |
| 发现疑似走失 | 空（未知） | 发现者 | 发现者 | UNAUTHORIZED（仅可寻主） |
| 医院/照护者代发 | 真实主人 | 医院/照护者 | 医院/照护者 | AUTHORIZED（有 pet_authorizations） |
| 所有者≠照护者 | 主人 | 任意 | 照护者 | 依授权 |

**谁有权做什么**：
- **发布免费领养（ADOPTION 帖子）**：`owner_id == publisher_id`（SELF），或持有 ACTIVE `pet_authorizations(auth_type=PUBLISH)`；发现走失（UNAUTHORIZED）只能发 FINDING 寻主。
- **批准领养申请**：帖子发布者，且对该宠物有 `ADOPTION_APPROVAL` 授权（owner 本人默认具备）。
- **确认领养交接**：安排双方，其中 `custodian_id` 为当前照护者（owner 或获 `CUSTODY` 授权者）。
- **发布/审核照护（第一版）**：要求主人本人发布（`publisher_id == owner_id`，后端校验），不支持代发照护需求。

**处置权不等于所有权状态（P1）**：
- `ownership_status=UNOWNED`（确认无主）**不自动授予处置权**。处置权由 `authorization_status` + `pet_authorizations` 独立判定：无主宠物仍需平台核实后才由运营代授 `PUBLISH`/`ADOPTION_APPROVAL` 授权，`authorization_status` 由 `UNAUTHORIZED`/`PENDING` → `AUTHORIZED`。
- **发现疑似走失（FOUND）转领养核实条件**：`source_type=FOUND` 的宠物，只有同时满足以下条件才允许 `FINDING → ADOPTION` 转换（`convert-to-adoption` 接口后端校验）：
  1. `ownership_status` 已由 `UNKNOWN` 核实为 `UNOWNED`（确认无主，非「疑似走失」）；
  2. `authorization_status` 已由 `UNAUTHORIZED` 核实为 `AUTHORIZED`（平台授予处置权）；
  3. FINDING 帖子已过寻主公示期（运营确认），记录 `converted_to_post_id`。
- 任一条件不满足则拒绝转换，仍仅可寻主。

## 4.5 领养完成时的档案更新（P0-1 / P1-7）

> 领养完成**不再把 `pets.status` 置为 `ARCHIVED`**（保留 `ACTIVE`，允许同一 `pet_id` 未来再次领养），仅更新所有权三字段，并在**同一事务**内写入 `pet_ownership_history`。

```sql
-- 同一事务内执行：
UPDATE pets
SET owner_id = :adopterId,
    current_custodian_id = :adopterId,
    ownership_status = 'OWNED',
    version = version + 1
WHERE id = :petId;

INSERT INTO pet_ownership_history
  (pet_id, from_owner_id, to_owner_id, change_type, arrangement_id, changed_by, reason)
VALUES
  (:petId, :oldOwnerId, :adopterId, 'ADOPTION', :arrangementId, :currentUserId, '领养交接完成');
```

## 4.6 删除的支付相关字段（已确认不再存在）

以下字段/表**已从设计移除**：`amount`、`payment_status`、`payment_method`、`transaction_no`、`refund_amount`、`refund_status`、`escrow_status`、`platform_fee`、`deposit`、`pay_channel`，以及 `payment_records`、`refund_records` 表。`buyer_id`/`seller_id` 交易角色已替换为 `adopter_id`/`custodian_id`/`owner_id`/`caregiver_id` 业务角色。
