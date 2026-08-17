-- 이미 탈퇴한 회원이 점유하고 있는 UNIQUE 값 해제 (데이터 정정, 스키마 변경 없음)
--
-- 배경: 탈퇴는 soft delete(deleted_at) 인데 users.login_id 와 users.kakao_id 는 UNIQUE 라,
-- 탈퇴 행이 값을 계속 쥐고 있었다. 그 상태에서 같은 아이디·같은 카카오 계정으로 재가입하면
-- INSERT 가 UNIQUE 제약 위반으로 실패한다 = 한 번 탈퇴하면 영구히 못 돌아온다.
--
-- 코드는 이제 탈퇴 시점에 두 값을 비우지만(User.withdraw), 그건 앞으로의 탈퇴에만 적용된다.
-- 이 스크립트는 수정 전에 이미 탈퇴한 회원의 값을 풀어준다.
--
-- 스키마를 바꾸지 않으므로 ddl-auto=validate 와 무관하다. 배포 전/후 아무 때나 적용 가능.

UPDATE users
SET login_id = NULL
WHERE deleted_at IS NOT NULL
  AND login_id IS NOT NULL;

UPDATE users
SET kakao_id = NULL
WHERE deleted_at IS NOT NULL
  AND kakao_id IS NOT NULL;

-- 확인용: 아래 결과가 0 이어야 한다.
-- SELECT count(*) FROM users WHERE deleted_at IS NOT NULL AND (login_id IS NOT NULL OR kakao_id IS NOT NULL);
