package com.wishconnect.domain.user.entity;

import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자. 계정은 {@code (loginType, providerId)} 조합으로 구분한다.
 * 같은 이메일이 LOCAL/GOOGLE/NAVER 등에 공존 가능하므로 email 은 UNIQUE 가 아니다.
 */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(columnDefinition = "uuid")
	private UUID id;

	@Column(nullable = false)
	private String email;

	/** LOCAL 가입 시에만 존재 (BCrypt 해시). 소셜 가입은 null. */
	@Column
	private String password;

	@Column(nullable = false)
	private String name;

	@Column
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LoginType loginType;

	/** KAKAO 가입 시에만 존재 (기존 유지). */
	@Column(unique = true)
	private Long kakaoId;

	/** 소셜(GOOGLE/NAVER 등) 제공자 고유 식별자. loginType 과 함께 계정을 구분한다. */
	@Column
	private String providerId;

	@Column(nullable = false)
	private boolean onboardingCompleted;

	/** 권한. 가입 경로로는 항상 USER 이며, ADMIN 은 DB 에서 직접 부여한다. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	@Column
	private LocalDateTime deletedAt;

	@Builder
	private User(String email, String password, String name, String phone,
			LoginType loginType, Long kakaoId, String providerId, boolean onboardingCompleted) {
		this.email = email;
		this.password = password;
		this.name = name;
		this.phone = phone;
		this.loginType = loginType;
		this.kakaoId = kakaoId;
		this.providerId = providerId;
		this.onboardingCompleted = onboardingCompleted;
		this.role = UserRole.USER;
	}

	/** 기본(이메일/비밀번호) 회원 생성. password 는 해시된 값이어야 한다. */
	public static User createLocal(String email, String encodedPassword, String name, String phone) {
		return User.builder()
				.email(email)
				.password(encodedPassword)
				.name(name)
				.phone(phone)
				.loginType(LoginType.LOCAL)
				.onboardingCompleted(false)
				.build();
	}

	/** 카카오 소셜 회원 생성. */
	public static User createKakao(Long kakaoId, String email, String name) {
		return User.builder()
				.email(email)
				.name(name)
				.loginType(LoginType.KAKAO)
				.kakaoId(kakaoId)
				.onboardingCompleted(false)
				.build();
	}

	/** 소셜(GOOGLE/NAVER) 회원 생성. */
	public static User createSocial(LoginType loginType, String providerId, String email, String name) {
		return User.builder()
				.email(email)
				.name(name)
				.loginType(loginType)
				.providerId(providerId)
				.onboardingCompleted(false)
				.build();
	}

	/** 비밀번호 변경 (BCrypt 해시된 값을 전달). */
	public void changePassword(String encodedPassword) {
		this.password = encodedPassword;
	}

	public void changeEmail(String email) {
		this.email = email;
	}

	public void updateBasicProfile(String name, String phone) {
		this.name = name;
		this.phone = phone;
	}

	public void completeOnboarding() {
		this.onboardingCompleted = true;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}
}
