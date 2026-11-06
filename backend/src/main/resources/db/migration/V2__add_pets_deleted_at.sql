-- V2: 为 pets 表增加软删除时间字段（对应 docs/04 §4.2、docs/02 §2.1）。
-- status=DELETED 时写入删除时间，其余行为 NULL；不修改已提交的 V1__init_schema.sql。
ALTER TABLE pets ADD COLUMN deleted_at timestamptz;
