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
 * 같은 이메일이 LOCAL/KAKAO/GOOGLE/NAVER 등에 공존 가능하므로 email 단독 UNIQUE 는 아니다.
 * DB에서는 활성 계정에 한해 (email, login_type) 부분 UNIQUE 인덱스로 같은 방식의 중복만 막는다.
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

	/**
	 * 로그인 아이디. 회원가입 화면이 이메일과 별개로 아이디를 받도록 바뀌었다.
	 *
	 * <p>소셜 가입은 아이디를 입력받지 않으므로 null 이다. Postgres 의 UNIQUE 는 NULL 을
	 * 서로 다른 값으로 보기 때문에, 소셜 계정이 여러 개여도 제약에 걸리지 않는다.
	 */
	@Column(name = "login_id", unique = true, length = 30)
	private String loginId;

	@Column
	private LocalDateTime deletedAt;

	@Builder
	private User(String email, String loginId, String password, String name, String phone,
			LoginType loginType, Long kakaoId, String providerId, boolean onboardingCompleted) {
		this.email = email;
		this.loginId = loginId;
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
	public static User createLocal(String email, String loginId, String encodedPassword,
			String name, String phone) {
		return User.builder()
				.email(email)
				.loginId(loginId)
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

	/**
	 * 회원 탈퇴. deletedAt 만 남기고 조회 쿼리에서 제외한다.
	 *
	 * <p>loginId 와 kakaoId 는 DB UNIQUE 라, 탈퇴 행이 값을 계속 점유하면 같은 아이디·같은
	 * 카카오 계정으로는 재가입이 영구히 불가능해진다(재가입 INSERT 가 제약 위반으로 실패).
	 * 그래서 탈퇴 시점에 비워 다음 가입자가 쓸 수 있게 돌려준다.
	 *
	 * <p>email/name/phone 은 보관 기간·파기 정책이 정해지면 함께 익명화 대상이다.
	 * email 은 활성 계정만 대상으로 하는 부분 UNIQUE 인덱스를 사용하므로, 탈퇴 행에 남아 있어도
	 * 같은 로그인 방식으로 재가입하거나 다른 로그인 방식의 계정을 만드는 것을 막지 않는다.
	 */
	public void withdraw() {
		this.deletedAt = LocalDateTime.now();
		this.loginId = null;
		this.kakaoId = null;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}
}
