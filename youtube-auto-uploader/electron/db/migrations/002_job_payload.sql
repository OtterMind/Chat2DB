ALTER TABLE synced_videos ADD COLUMN payload_json TEXT;
ALTER TABLE channels ADD COLUMN custom_cron TEXT;
ALTER TABLE channels ADD COLUMN etag TEXT;
ALTER TABLE channels ADD COLUMN next_retry_at TEXT;
