-- 오등록 신고 사유를 단일 값에서 다중 선택으로 바꾼다.
--
-- 피그마 "신고 팝업"이 "신고 사유를 모두 선택해 주세요" 체크박스라, 신고 한 건에 사유가
-- 여러 개 붙는다. scholarship_report.reason 컬럼(단일)을 자식 테이블로 옮긴다.
--
-- ⚠ 배포 전에 적용해야 한다. 엔티티에서 reason 필드가 사라지므로, 적용 전에 새 코드가
--   뜨면 NOT NULL 인 reason 컬럼에 값을 못 넣어 신고 접수가 전부 실패한다.

CREATE TABLE IF NOT EXISTS scholarship_report_reason (
    report_id BIGINT      NOT NULL REFERENCES scholarship_report (id) ON DELETE CASCADE,
    reason    VARCHAR(30) NOT NULL,
    CONSTRAINT pk_scholarship_report_reason PRIMARY KEY (report_id, reason)
);

-- 기존 신고의 단일 사유를 그대로 옮긴다.
-- 옛 값(WRONG_DEADLINE·WRONG_AMOUNT·BROKEN_LINK)은 화면에서 내려갔지만 enum 에 남겨 뒀으므로
-- 변환하지 않고 원래 값으로 보존한다. 신고자가 실제로 고른 것이 무엇이었는지가 남아야 한다.
INSERT INTO scholarship_report_reason (report_id, reason)
SELECT id, reason
FROM scholarship_report
WHERE reason IS NOT NULL
ON CONFLICT DO NOTHING;

-- 옮긴 뒤 원본 컬럼 제거. 남겨 두면 NOT NULL 인데 엔티티가 채우지 않아 INSERT 가 깨진다.
ALTER TABLE scholarship_report DROP COLUMN IF EXISTS reason;
