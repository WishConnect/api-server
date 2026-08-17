package com.wishconnect.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.auth.service.EmailVerificationService;
import com.wishconnect.domain.auth.service.RefreshTokenService;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.jwt.WithdrawnTokenStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원 탈퇴")
class UserAccountServiceTest {

	@Mock
	private UserRepository userRepository;
	@Mock
	private ScrapRepository scrapRepository;
	@Mock
	private EssayRepository essayRepository;
	@Mock
	private UserProfileService userProfileService;
	@Mock
	private EmailVerificationService emailVerificationService;
	@Mock
	private RefreshTokenService refreshTokenService;
	@Mock
	private WithdrawnTokenStore withdrawnTokenStore;
	@Mock
	private PasswordEncoder passwordEncoder;

	@InjectMocks
	private UserAccountService userAccountService;

	private static User localUser() {
		User user = User.createLocal("user@example.com", "user01", "encoded", "홍길동", "010-1234-5678");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	private static User kakaoUser() {
		User user = User.createKakao(777L, "k@kakao.com", "카카오닉");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	@Nested
	@DisplayName("deleteMe")
	class DeleteMe {

		@Test
		@DisplayName("soft delete 하고 Refresh Token 삭제 + Access Token 블랙리스트 등록까지 한다")
		void success() {
			User user = localUser();
			UUID userId = user.getId();
			given(userRepository.findById(userId)).willReturn(Optional.of(user));

			userAccountService.deleteMe(userId);

			assertThat(user.isDeleted()).isTrue();
			verify(refreshTokenService).delete(userId);
			// 이게 빠지면 남은 Access Token 으로 탈퇴 후에도 30분간 API 를 쓸 수 있다.
			verify(withdrawnTokenStore).markWithdrawn(userId);
		}

		/*
		login_id 는 DB UNIQUE 라, 탈퇴 행이 값을 쥐고 있으면 같은 아이디로 재가입할 때
		INSERT 가 제약 위반으로 실패한다. 아이디가 영구히 소멸되지 않도록 비워야 한다.
		 */
		@Test
		@DisplayName("UNIQUE 컬럼인 loginId 를 비워 같은 아이디로 재가입할 수 있게 한다")
		void releasesLoginId() {
			User user = localUser();
			given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

			userAccountService.deleteMe(user.getId());

			assertThat(user.getLoginId()).isNull();
		}

		/** kakaoId 도 UNIQUE 라 같은 이유로 비워야 같은 카카오 계정으로 다시 가입할 수 있다. */
		@Test
		@DisplayName("UNIQUE 컬럼인 kakaoId 를 비워 같은 카카오 계정으로 재가입할 수 있게 한다")
		void releasesKakaoId() {
			User user = kakaoUser();
			given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

			userAccountService.deleteMe(user.getId());

			assertThat(user.getKakaoId()).isNull();
		}

		@Test
		@DisplayName("이미 탈퇴한 계정이면 WITHDRAWN_USER(401)")
		void alreadyWithdrawn() {
			User user = localUser();
			user.withdraw();
			given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

			assertThatThrownBy(() -> userAccountService.deleteMe(user.getId()))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.WITHDRAWN_USER);
			verify(refreshTokenService, never()).delete(user.getId());
		}

		@Test
		@DisplayName("존재하지 않는 사용자면 USER_NOT_FOUND")
		void userNotFound() {
			UUID userId = UUID.randomUUID();
			given(userRepository.findById(userId)).willReturn(Optional.empty());

			assertThatThrownBy(() -> userAccountService.deleteMe(userId))
					.isInstanceOf(CustomException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
			verifyNoInteractions(refreshTokenService, withdrawnTokenStore);
		}
	}

	@Test
	@DisplayName("탈퇴한 계정의 마이페이지 조회는 WITHDRAWN_USER(401) 로 막는다")
	void getMyPageRejectsWithdrawnUser() {
		User user = localUser();
		user.withdraw();
		given(userRepository.findById(user.getId())).willReturn(Optional.of(user));

		assertThatThrownBy(() -> userAccountService.getMyPage(user.getId()))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.WITHDRAWN_USER);
	}
}
