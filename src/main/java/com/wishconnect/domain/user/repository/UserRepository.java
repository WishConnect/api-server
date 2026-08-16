package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.LoginType;
import com.wishconnect.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** 기본(LOCAL) 계정 존재 여부. 같은 이메일이 소셜 계정으로 존재해도 무관하게 LOCAL 기준으로만 판단. */
	boolean existsByEmailAndLoginType(String email, LoginType loginType);

	/** 로그인 아이디 중복 검사. 소셜 계정은 loginId 가 null 이라 검사 대상이 아니다. */
	boolean existsByLoginId(String loginId);

	/** 기본(LOCAL) 계정 조회. */
	Optional<User> findByEmailAndLoginType(String email, LoginType loginType);

	/** 카카오 계정 조회 (기존 유지). */
	Optional<User> findByKakaoId(Long kakaoId);

	/** 소셜(GOOGLE/NAVER) 계정 조회. */
	Optional<User> findByLoginTypeAndProviderId(LoginType loginType, String providerId);
}
