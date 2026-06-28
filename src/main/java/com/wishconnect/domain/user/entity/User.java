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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(columnDefinition = "uuid")
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	/** LOCAL 가입 시에만 존재 (BCrypt 해시). 카카오 가입은 null. */
	@Column
	private String password;

	@Column(nullable = false)
	private String name;

	@Column
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LoginType loginType;

	/** KAKAO 가입 시에만 존재. */
	@Column(unique = true)
	private Long kakaoId;

	@Column(nullable = false)
	private boolean onboardingCompleted;

	@Builder
	private User(String email, String password, String name, String phone,
			LoginType loginType, Long kakaoId, boolean onboardingCompleted) {
		this.email = email;
		this.password = password;
		this.name = name;
		this.phone = phone;
		this.loginType = loginType;
		this.kakaoId = kakaoId;
		this.onboardingCompleted = onboardingCompleted;
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
}
