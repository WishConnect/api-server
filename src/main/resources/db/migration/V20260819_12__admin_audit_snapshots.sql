ALTER TABLE admin_audit_log
    ADD COLUMN IF NOT EXISTS before_json TEXT,
    ADD COLUMN IF NOT EXISTS after_json TEXT,
    ADD COLUMN IF NOT EXISTS restored_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS restored_by UUID;

COMMENT ON COLUMN admin_audit_log.before_json IS '관리자 변경 전 복구 가능 스냅샷';
COMMENT ON COLUMN admin_audit_log.after_json IS '관리자 변경 후 비교 스냅샷';
COMMENT ON COLUMN admin_audit_log.restored_at IS '해당 로그의 이전 값으로 복구한 시각';
COMMENT ON COLUMN admin_audit_log.restored_by IS '복구를 실행한 관리자 UUID';
