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
| `V20260815_01__fix_scholarship_type_check.sql` | `scholarship.scholarship_type` CHECK 제약에 `WORK_STUDY` 추가 | ⬜ 미적용 |

> `V20260815_01` 은 **적용 전까지 근로장학 공고 수집이 계속 실패한다.** `WORK_STUDY` 가
> 2026-07-23(`2ae8a8c`)에 추가됐으나 마이그레이션이 누락됐고, 2026-08-05 점검에서도 빠졌다.
> 운영은 `validate` 라 부팅은 되지만 CHECK 제약은 검증 대상이 아니라 INSERT 때만 터진다.

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
