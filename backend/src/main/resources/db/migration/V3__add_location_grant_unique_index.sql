-- V3: 为精确位置授权增加局部唯一索引（对应 docs/06 §6.2、docs/04 §4.2）。
-- 同一宠物对同一被授权者，同时只允许一条 ACTIVE 授权；撤销（REVOKED）后可再次授权。
CREATE UNIQUE INDEX uq_location_grant_active
    ON location_access_grants (pet_id, grantee_id)
    WHERE status = 'ACTIVE';
