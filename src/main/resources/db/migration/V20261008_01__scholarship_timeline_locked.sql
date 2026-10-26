-- 크롤링 자동 일정 보완이 관리자 편집·의도적으로 비운 일정을 되살리지 않도록 보호한다.
-- 배포 전에 적용한다. 운영은 ddl-auto: validate 이며 SQL은 자동 실행되지 않는다.
BEGIN;
ALTER TABLE scholarship ADD COLUMN IF NOT EXISTS timeline_locked BOOLEAN NOT NULL DEFAULT false;
UPDATE scholarship s SET timeline_locked = true
WHERE EXISTS (SELECT 1 FROM scholarship_timeline t WHERE t.scholarship_id = s.id AND t.origin = 'MANUAL');
COMMENT ON COLUMN scholarship.timeline_locked IS '수기로 편집·삭제하거나 복구한 선발 일정. 자동 파싱으로 덮지 않는다';
COMMIT;
