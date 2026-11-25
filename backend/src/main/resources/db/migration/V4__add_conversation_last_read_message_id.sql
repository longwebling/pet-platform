ALTER TABLE conversation_participants ADD COLUMN last_read_message_id bigint;
ALTER TABLE conversations ALTER COLUMN post_id SET NOT NULL;
