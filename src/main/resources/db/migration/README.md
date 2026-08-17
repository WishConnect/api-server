# DB 마이그레이션 기록

이 디렉터리는 **운영 DB 에 적용해야 하는 스키마 변경을 기록**한다.
현재 Flyway 등 마이그레이션 도구를 도입하지 않았으므로 **자동 적용되지 않는다.**
배포 담당자가 아래 절차대로 직접 실행해야 한다.

## 왜 필요한가

운영은 `spring.jpa.hibernate.ddl-auto: validate` 라서, 엔티티와 실제 테이블이 다르면
**애플리케이션이 기동에 실패한다.** 스키마 변경이 포함된 배포는 SQL 을 먼저 적용해야 한다.

지금까지는 이 기록이 없어 "운영에 어떤 변경이 적용됐는지" 추적할 수 없었다.
앞으로 스키마를 바꾸는 PR 은 반드시 이 디렉터리에 SQL 을 함께 올린다.

## 적용 순서

1. **배포 전에** 아래 파일들을 파일명 순서대로 운영 DB 에 적용한다.
2. 적용 후 `main` 에 머지해 배포한다. (순서가 바뀌면 `validate` 실패로 배포가 깨진다)
3. 적용한 파일은 아래 목록의 체크박스에 표시한다.

```bash
psql -h <RDS_HOST> -U <USER> -d wishconnect -f V20260729_01__add_role_to_users.sql
```

## 파일 네이밍

`V<날짜>_<순번>__<설명>.sql` — Flyway 네이밍 규칙을 따른다.
나중에 Flyway 를 도입하면 이 파일들을 그대로 사용할 수 있다.

## 적용 이력

| 파일 | 내용 | 운영 적용 |
|---|---|---|
| `V20260729_01__add_role_to_users.sql` | `users.role` 컬럼 추가 (관리자 권한) | ✅ 2026-07-29 |
| `V20260731_01__create_scholarship_report.sql` | `scholarship_report` 테이블 추가 (오등록 신고) | ✅ 2026-07-31 |
| `V20260805_01__fix_enum_check_constraints.sql` | 옛 enum 이 남은 CHECK 제약 정정 (`essay.status`, `user_profile.dual_major`) | ✅ 2026-08-05 |
| `V20260806_01__insight_schema_updates.sql` | insight.source 컬럼 추가, 컬럼 길이 확장 | ✅ 2026-08-06 |
| `V20260816_01__seed_sigungu_regions.sql` | 거주지역 시군구 228건 시딩 + `(name, parent_id)` UNIQUE | ⬜ 미적용 |
| `V20260815_01__fix_scholarship_type_check.sql` | `scholarship.scholarship_type` CHECK 제약에 `WORK_STUDY` 추가 | ✅ 2026-08-16 확인 |
| `V20260816_01__create_admin_audit_log.sql` | `admin_audit_log` 테이블 추가 (관리자 쓰기 작업 기록) | ✅ 2026-08-16 |
| `V20260816_02__scholarship_tag_and_document_url.sql` | `scholarship_tag` 테이블 + `scholarship_document.download_url` | ✅ 2026-08-16 |
| `V20260816_03__users_login_id_birth_date_region.sql` | `users.login_id`, `user_profile.birth_date`, 지역 마스터 17건 시드 | ✅ 2026-08-16 |
| `V20260816_04__drop_scholarship_tag.sql` | `scholarship_tag` 테이블 제거 (태그 기능 철회) | ✅ 2026-08-17 (배포 후 적용) |
| `V20260817_01__scholarship_enrichment.sql` | `scholarship.detail_url`·`enriched_at`, `image.source_url` (자동 보완) | ✅ 2026-08-17 |
| `V20260817_02__release_withdrawn_user_unique_keys.sql` | 탈퇴 회원이 점유한 `users.login_id`·`kakao_id` 해제 (재가입 차단 해소) | ⬜ 미적용 |
| `V20260817_03__scholarship_report_multi_reason.sql` | 신고 사유 다중 선택 (`scholarship_report_reason` 테이블 + `reason` 컬럼 제거) | ⬜ 미적용 |

> `V20260817_03` 은 **반드시 배포보다 먼저** 적용해야 한다. 엔티티에서 `reason` 필드가
> 사라지므로, 적용 전에 새 코드가 뜨면 NOT NULL 인 `scholarship_report.reason` 에 값을 못 넣어
> **신고 접수가 전부 실패**한다. 반대로 SQL 만 먼저 적용하고 옛 코드가 떠 있는 동안에도
> 같은 이유로 신고가 실패하므로, 이 둘 사이 간격을 짧게 가져가는 편이 좋다.

> `V20260817_02` 는 **스키마 변경이 아니라 데이터 정정**이다. `validate` 와 무관하므로
> 배포 순서를 지키지 않아도 기동은 깨지지 않는다. 다만 적용 전까지는 수정 이전에 탈퇴한
> 회원이 같은 아이디·카카오 계정으로 재가입할 수 없으니, 배포와 함께 적용하는 편이 좋다.

> `V20260815_01` 은 2026-08-16 점검에서 **운영 제약에 `WORK_STUDY` 가 이미 들어 있음을 확인**했다.
> (`pg_get_constraintdef` 로 `ARRAY['INTERNAL','EXTERNAL','WORK_STUDY']` 확인)
> 배경: `WORK_STUDY` 가 2026-07-23(`2ae8a8c`)에 추가됐으나 마이그레이션이 누락돼,
> 적용 전까지 근로장학 공고 수집이 트랜잭션째 롤백되고 있었다. `validate` 는 CHECK 제약을
> 검사하지 않아 부팅은 정상이고 INSERT 때만 터지는 유형이다.

> `V20260731_01` 은 2026-08-05 점검에서 **운영에 이미 반영되어 있음을 확인**했다(컬럼·인덱스 모두 일치).
> 표기만 `미적용` 으로 남아 있던 것이라 정정한다. 앞으로는 적용 직후 이 표를 함께 갱신할 것.

## 제약이 어긋나는 이유 (반복 주의)

Hibernate `ddl-auto: update` 는 **컬럼·테이블만 추가할 뿐 기존 CHECK 제약을 고치지 않는다.**
코드에서 enum 값을 바꿔도 DB 제약은 옛 값 그대로 남아, 실행 시점에
`23514 violates check constraint` 로 500 이 난다. `validate` 는 제약 내용까지는 보지 않으므로
**기동은 성공하는데 특정 API 만 계속 실패하는** 형태로 나타난다.

enum 값을 추가·변경하는 PR 은 반드시 이 디렉터리에 `ALTER TABLE ... DROP/ADD CONSTRAINT` SQL 을 함께 올린다.
현재 DB 제약을 확인하는 방법:

```sql
SELECT conrelid::regclass, conname, pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype = 'c' ORDER BY 1;
```
