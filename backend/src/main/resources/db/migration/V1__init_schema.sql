-- ============================================================================
-- 宠物互助平台 数据库初始化 DDL（v0.1）
-- 对应 docs/04-database-design.md（共 19 张表）
-- 环境：PostgreSQL 14+ / PostGIS 3.x
-- 约定：状态字段统一 varchar(32) 大写蛇形枚举（不用 enum、不用 smallint）；
--       关键业务对象带 version 乐观锁；无任何资金/支付字段。
-- 说明：多态引用（biz_id / reserved_application_id / converted_to_post_id /
--       pet_ownership_history.arrangement_id/order_id）不加 FK，由应用层约束。
--       updated_at 由应用层维护（本 DDL 不建触发器）。
-- ============================================================================

-- ---------- 扩展 ----------
CREATE EXTENSION IF NOT EXISTS postgis;      -- geometry / ST_DWithin
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- EXCLUDE 中 pet_id 的 GiST 等值

-- ============================================================================
-- 表（按依赖顺序）
-- ============================================================================

-- 1. users
CREATE TABLE users (
    id            bigserial PRIMARY KEY,
    phone         varchar(20)  NOT NULL UNIQUE,          -- 登录凭证
    nickname      varchar(64),
    avatar        varchar(255),                          -- 对象存储 key
    bio           varchar(255),
    roles         varchar(16)[] NOT NULL DEFAULT '{user}',  -- {user, ops, admin}
    verification  jsonb,                                 -- 认证信息（预留）
    status        varchar(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/BANNED
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 2. pets（宠物档案）
CREATE TABLE pets (
    id                    bigserial PRIMARY KEY,
    publisher_id          bigint NOT NULL REFERENCES users(id),  -- 信息发布者（非必为所有者）
    owner_id              bigint REFERENCES users(id),           -- 当前所有者（走失/无主可空）
    current_custodian_id  bigint REFERENCES users(id),           -- 实际照护者
    source_type           varchar(16) NOT NULL,                  -- OWNED/RESCUED/FOUND/STRAY
    ownership_status      varchar(16) NOT NULL,                  -- OWNED/UNKNOWN/UNOWNED/TRANSFERRING
    authorization_status  varchar(16) NOT NULL,                  -- SELF/AUTHORIZED/UNAUTHORIZED/PENDING
    species               varchar(16) NOT NULL,                  -- CAT/DOG/OTHER
    breed                 varchar(64),
    age                   varchar(32),                           -- 描述式（如 "2岁"）
    gender                varchar(16),                           -- MALE/FEMALE/UNKNOWN
    sterilized            boolean,
    vaccinated            boolean,
    health_desc           text,
    status                varchar(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/ACTIVE/ARCHIVED/DELETED
    location              geometry(Point, 4326),                 -- 精确坐标（不外发）
    geo_hash              varchar(12),                           -- 模糊对外 geohash
    city                  varchar(64),
    district              varchar(64),
    version               int NOT NULL DEFAULT 0,                -- 乐观锁
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

-- 3. pet_images
CREATE TABLE pet_images (
    id            bigserial PRIMARY KEY,
    pet_id        bigint NOT NULL REFERENCES pets(id),
    uploader_id   bigint NOT NULL REFERENCES users(id),
    file_key      varchar(255) NOT NULL,                          -- 对象存储私有 key
    file_type     varchar(16) NOT NULL,                           -- PHOTO/HEALTH_CERT/QUARANTINE
    review_status varchar(16) NOT NULL DEFAULT 'PENDING',         -- PENDING/APPROVED/REJECTED
    access_level  varchar(16) NOT NULL DEFAULT 'PUBLIC',          -- PUBLIC/PRIVATE/AUTHORIZED
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 4. pet_posts（发布信息，post_type 区分）
CREATE TABLE pet_posts (
    id                      bigserial PRIMARY KEY,
    pet_id                  bigint NOT NULL REFERENCES pets(id),
    publisher_id            bigint NOT NULL REFERENCES users(id),
    post_type               varchar(16) NOT NULL,                 -- FINDING/ADOPTION/FOSTER
    title                   text NOT NULL,
    description             text,
    care_type               varchar(16),                          -- FOSTER: VISIT/BOARDING/WALK/COMPANY
    care_start_time         timestamptz,                          -- FOSTER 意向时段（展示）
    care_end_time           timestamptz,
    status                  varchar(16) NOT NULL DEFAULT 'DRAFT', -- 见状态机
    reserved_application_id bigint,                               -- 被预留申请（多态，无 FK）
    converted_to_post_id    bigint,                               -- FINDING 转领养指向（无 FK）
    reject_reason           varchar(255),
    verified_at             timestamptz,
    completed_at            timestamptz,
    version                 int NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

-- 5. pet_authorizations（处置授权关系）
CREATE TABLE pet_authorizations (
    id         bigserial PRIMARY KEY,
    pet_id     bigint NOT NULL REFERENCES pets(id),
    grantor_id bigint NOT NULL REFERENCES users(id),
    grantee_id bigint NOT NULL REFERENCES users(id),
    auth_type  varchar(16) NOT NULL,                              -- PUBLISH/ADOPTION_APPROVAL/HANDOVER/CUSTODY
    status     varchar(16) NOT NULL DEFAULT 'ACTIVE',             -- ACTIVE/REVOKED/EXPIRED
    granted_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 6. pet_ownership_history（所有权变更留痕）
CREATE TABLE pet_ownership_history (
    id             bigserial PRIMARY KEY,
    pet_id         bigint NOT NULL REFERENCES pets(id),
    from_owner_id  bigint REFERENCES users(id),                   -- 变更前所有者（可空）
    to_owner_id    bigint REFERENCES users(id),                   -- 变更后所有者（可空）
    change_type    varchar(16) NOT NULL,                          -- ADOPTION/FOUND_RETURN/RESCUE/MANUAL
    arrangement_id bigint,                                        -- 关联领养安排（无 FK）
    order_id       bigint,                                        -- 关联照护工单（预留，无 FK）
    changed_by     bigint NOT NULL REFERENCES users(id),          -- 操作者（运营/系统）
    reason         text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- 7. adoption_applications（领养申请）
CREATE TABLE adoption_applications (
    id            bigserial PRIMARY KEY,
    post_id       bigint NOT NULL REFERENCES pet_posts(id),
    adopter_id    bigint NOT NULL REFERENCES users(id),
    message       text,
    status        varchar(16) NOT NULL DEFAULT 'PENDING',         -- PENDING/APPROVED/REJECTED/WITHDRAWN
    reject_reason varchar(255),
    version       int NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 8. foster_applications（照护申请）
CREATE TABLE foster_applications (
    id              bigserial PRIMARY KEY,
    post_id         bigint NOT NULL REFERENCES pet_posts(id),
    caregiver_id    bigint NOT NULL REFERENCES users(id),
    care_start_time timestamptz,                                  -- 申请人提议时段（可空）
    care_end_time   timestamptz,
    message         text,
    status          varchar(16) NOT NULL DEFAULT 'PENDING',       -- PENDING/APPROVED/REJECTED/WITHDRAWN
    reject_reason   varchar(255),
    version         int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- 9. adoption_arrangements（领养安排，无资金）
CREATE TABLE adoption_arrangements (
    id             bigserial PRIMARY KEY,
    pet_id         bigint NOT NULL REFERENCES pets(id),
    post_id        bigint NOT NULL REFERENCES pet_posts(id),
    application_id bigint NOT NULL REFERENCES adoption_applications(id),
    adopter_id     bigint NOT NULL REFERENCES users(id),          -- 领养人
    custodian_id   bigint NOT NULL REFERENCES users(id),          -- 交接方（当前照护者）
    status         varchar(16) NOT NULL DEFAULT 'PENDING_HANDOVER',  -- PENDING_HANDOVER/COMPLETED/CANCELLED/ABORTED
    abort_reason   varchar(255),
    completed_at   timestamptz,
    version        int NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- 10. foster_orders（照护工单，无资金）
CREATE TABLE foster_orders (
    id              bigserial PRIMARY KEY,
    pet_id          bigint NOT NULL REFERENCES pets(id),
    post_id         bigint NOT NULL REFERENCES pet_posts(id),
    application_id  bigint NOT NULL REFERENCES foster_applications(id),
    owner_id        bigint NOT NULL REFERENCES users(id),         -- 宠物主人
    caregiver_id    bigint NOT NULL REFERENCES users(id),         -- 照护人
    care_start_time timestamptz NOT NULL,                         -- 最终确认占用时段
    care_end_time   timestamptz NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'PENDING_HANDOVER',  -- PENDING_HANDOVER/IN_PROGRESS/PENDING_RETURN/COMPLETED/CANCELLED/ABORTED
    no_show         boolean NOT NULL DEFAULT false,
    abort_reason    varchar(255),
    version         int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_foster_time_order CHECK (care_start_time < care_end_time)
);

-- 11. handover_records（交接/归还记录，随安排/工单同事务预创建）
CREATE TABLE handover_records (
    id                 bigserial PRIMARY KEY,
    biz_type           varchar(16) NOT NULL,                      -- ADOPTION/FOSTER
    biz_id             bigint NOT NULL,                           -- arrangement/order id（多态，无 FK）
    from_user_id       bigint NOT NULL REFERENCES users(id),
    to_user_id         bigint NOT NULL REFERENCES users(id),
    location           geometry(Point, 4326),
    location_type      varchar(16),                               -- FUZZY/AUTHORIZED/AGREED
    event              varchar(16) NOT NULL,                      -- HANDOVER/RETURN/ABORT
    confirmed_by_from  boolean NOT NULL DEFAULT false,
    confirmed_by_to    boolean NOT NULL DEFAULT false,
    occurred_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_handover_record UNIQUE (biz_type, biz_id, event)  -- 防重复交接记录
);

-- 12. conversations
CREATE TABLE conversations (
    id         bigserial PRIMARY KEY,
    post_id    bigint REFERENCES pet_posts(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 13. conversation_participants
CREATE TABLE conversation_participants (
    conversation_id bigint NOT NULL REFERENCES conversations(id),
    user_id         bigint NOT NULL REFERENCES users(id),
    PRIMARY KEY (conversation_id, user_id)                        -- 联合主键（参与者可见校验）
);

-- 14. messages
CREATE TABLE messages (
    id              bigserial PRIMARY KEY,
    conversation_id bigint NOT NULL REFERENCES conversations(id),
    sender_id       bigint NOT NULL REFERENCES users(id),
    content         text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- 15. location_access_grants（精确位置授权，与审计分离）
CREATE TABLE location_access_grants (
    id            bigserial PRIMARY KEY,
    grantor_id    bigint NOT NULL REFERENCES users(id),           -- 授权者
    grantee_id    bigint NOT NULL REFERENCES users(id),           -- 被授权者
    pet_id        bigint NOT NULL REFERENCES pets(id),
    location_type varchar(16) NOT NULL,                           -- EXACT/AGREED
    status        varchar(16) NOT NULL DEFAULT 'ACTIVE',          -- ACTIVE/REVOKED/EXPIRED
    granted_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 16. notifications
CREATE TABLE notifications (
    id         bigserial PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES users(id),
    type       varchar(32) NOT NULL,
    payload    jsonb,
    read_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 17. reports
CREATE TABLE reports (
    id          bigserial PRIMARY KEY,
    reporter_id bigint NOT NULL REFERENCES users(id),
    target_type varchar(16) NOT NULL,                             -- PET/POST/USER
    target_id   bigint NOT NULL,
    reason      text NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'PENDING',
    handled_by  bigint REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- 18. audit_logs
CREATE TABLE audit_logs (
    id            bigserial PRIMARY KEY,
    actor_id      bigint NOT NULL REFERENCES users(id),
    action        varchar(32) NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id   bigint NOT NULL,
    before_data   jsonb,
    after_data    jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 19. address_visibility_logs（访问审计：谁实际查看过什么位置）
CREATE TABLE address_visibility_logs (
    id           bigserial PRIMARY KEY,
    viewer_id    bigint NOT NULL REFERENCES users(id),
    pet_id       bigint NOT NULL REFERENCES pets(id),
    post_id      bigint REFERENCES pet_posts(id),
    address_type varchar(16) NOT NULL,                            -- EXACT/AGREED/FUZZY
    viewed_at    timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================
-- 关键约束与索引
-- ============================================================================

-- ① 领养唯一性：仅约束有效待交接安排（COMPLETED/CANCELLED/ABORTED 不占用名额）
CREATE UNIQUE INDEX uq_active_adoption_arrangement_per_pet
    ON adoption_arrangements (pet_id)
    WHERE status = 'PENDING_HANDOVER';

-- ② 照护时间冲突：EXCLUDE（pet_id 等值需 btree_gist；范围 && 为内建 range opclass）
--    半开区间 [start, end)，end 等于下一单 start 不冲突。
--    按 pet_id 分组，不看 post_id —— 跨帖子时段重叠同样拦截。
ALTER TABLE foster_orders
  ADD CONSTRAINT foster_orders_no_time_overlap
  EXCLUDE USING gist (
    pet_id WITH =,
    tstzrange(care_start_time, care_end_time) WITH &&
  )
  WHERE (status IN ('PENDING_HANDOVER','IN_PROGRESS','PENDING_RETURN'));

-- ③ 申请审核后的批量拒绝为应用层 UPDATE（见 06 §6.4），无 DDL。

-- ④ 其他索引
CREATE INDEX idx_pets_location_gist ON pets USING gist (location);
CREATE INDEX idx_pets_geo_hash ON pets (geo_hash);
CREATE INDEX idx_pet_posts_type_status ON pet_posts (post_type, status);
CREATE INDEX idx_foster_orders_pet_status ON foster_orders (pet_id, status);

-- 防同人重复申请（仅 PENDING）
CREATE UNIQUE INDEX uq_adoption_application_pending
    ON adoption_applications (post_id, adopter_id)
    WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_foster_application_pending
    ON foster_applications (post_id, caregiver_id)
    WHERE status = 'PENDING';
