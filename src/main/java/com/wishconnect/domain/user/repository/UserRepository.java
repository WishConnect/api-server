package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 탈퇴는 soft delete(deletedAt) 이므로, 계정 조회·중복 검사는 전부
 * {@code DeletedAtIsNull} 을 붙여 탈퇴 회원을 제외한다. 빠뜨리면 탈퇴한 이메일·아이디가
 * 계속 점유된 것으로 보여 같은 정보로 재가입이 영구히 막힌다.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	/** 기본(LOCAL) 계정 존재 여부. 같은 이메일이 소셜 계정으로 존재해도 무관하게 LOCAL 기준으로만 판단. */
	boolean existsByEmailAndLoginTypeAndDeletedAtIsNull(String email, LoginType loginType);

	/** 로그인 아이디 중복 검사. 소셜 계정은 loginId 가 null 이라 검사 대상이 아니다. */
	boolean existsByLoginIdAndDeletedAtIsNull(String loginId);

	/** 기본(LOCAL) 계정 조회. */
	Optional<User> findByEmailAndLoginTypeAndDeletedAtIsNull(String email, LoginType loginType);

	/** 아이디 로그인 및 계정 복구용 LOCAL 계정 조회. */
	Optional<User> findByLoginIdAndLoginTypeAndDeletedAtIsNull(String loginId, LoginType loginType);

	/** 비밀번호 찾기에서 아이디와 이메일이 같은 LOCAL 계정을 가리키는지 확인한다. */
	Optional<User> findByLoginIdAndEmailIgnoreCaseAndLoginTypeAndDeletedAtIsNull(
			String loginId, String email, LoginType loginType);

	/** 아이디 찾기에서 본인 이름과 이메일이 일치하는 LOCAL 계정만 찾는다. */
	Optional<User> findByEmailIgnoreCaseAndNameAndLoginTypeAndDeletedAtIsNull(
			String email, String name, LoginType loginType);

	/** 카카오 계정 조회 (기존 유지). */
	Optional<User> findByKakaoIdAndDeletedAtIsNull(Long kakaoId);

	/** 소셜(GOOGLE/NAVER) 계정 조회. */
	Optional<User> findByLoginTypeAndProviderIdAndDeletedAtIsNull(LoginType loginType, String providerId);
}
