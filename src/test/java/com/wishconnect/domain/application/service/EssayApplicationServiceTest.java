package com.wishconnect.domain.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.application.dto.response.CreateApplicationResponse;
import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.application.repository.AiInterviewRepository;
import com.wishconnect.domain.application.repository.EssayAnswerRepository;
import com.wishconnect.domain.application.repository.EssayQuestionRepository;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.domain.scholarship.entity.RequirementLevel;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipEventService;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 지원서 생성이 장학금의 자기소개서 요구 여부를 지키는지 검증한다.
 *
 * <p>핵심은 <b>{@code null} 과 {@code NOT_REQUIRED} 를 구분</b>하는 것이다. null 은 "공고에 언급이
 * 없어 모른다"는 뜻이라 막으면 안 된다. 아직 파싱되지 않았거나 본문이 부실한 공고에서 지원서
 * 작성이 통째로 막히기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EssayApplicationServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock private EssayRepository essayRepository;
	@Mock private EssayQuestionRepository essayQuestionRepository;
	@Mock private EssayAnswerRepository essayAnswerRepository;
	@Mock private AiInterviewRepository aiInterviewRepository;
	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private UserRepository userRepository;
	@Mock private NotificationService notificationService;
	@Mock private ScholarshipEventService scholarshipEventService;

	private EssayApplicationService service;

	@BeforeEach
	void setUp() {
		service = new EssayApplicationService(essayRepository, essayQuestionRepository,
				essayAnswerRepository, aiInterviewRepository, scholarshipRepository,
				userRepository, notificationService, scholarshipEventService);

		given(essayRepository.findByUser_IdAndScholarship_Id(any(), any()))
				.willReturn(Optional.empty());
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
		given(essayRepository.save(any())).willAnswer(i -> {
			Essay essay = i.getArgument(0);
			setField(essay, "id", 100L);
			return essay;
		});
		given(essayQuestionRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));
	}

	@Test
	@DisplayName("자기소개서를 요구하는 장학금은 지원서를 만든다")
	void createsWhenEssayRequired() {
		givenScholarship(RequirementLevel.REQUIRED);

		CreateApplicationResponse response = service.createApplication(USER_ID, 1L);

		assertThat(response.questionCount()).isEqualTo(2);
		verify(essayRepository).save(any());
	}

	@Test
	@DisplayName("조건부(서류 합격자에 한해)도 지원서를 만든다 — 준비가 필요한 경우다")
	void createsWhenConditional() {
		givenScholarship(RequirementLevel.CONDITIONAL);

		assertThat(service.createApplication(USER_ID, 1L).questionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("공고에 언급이 없으면(null) 막지 않는다 — 모르는 것을 없는 것으로 취급하지 않는다")
	void createsWhenRequirementUnknown() {
		givenScholarship(null);

		assertThat(service.createApplication(USER_ID, 1L).questionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("자기소개서가 필요 없다고 확인된 장학금은 지원서를 만들지 않는다")
	void rejectsWhenEssayNotRequired() {
		givenScholarship(RequirementLevel.NOT_REQUIRED);

		assertThatThrownBy(() -> service.createApplication(USER_ID, 1L))
				.isInstanceOf(CustomException.class);

		// 문항·답변·알림까지 하나도 만들지 않아야 한다. 만들어두면 목록에 빈 지원서가 남는다.
		verify(essayRepository, never()).save(any());
		verify(essayQuestionRepository, never()).saveAll(any());
		verify(essayAnswerRepository, never()).save(any());
	}

	@Test
	@DisplayName("요구 여부를 보기 전에 장학금 존재부터 확인한다")
	void rejectsUnknownScholarship() {
		given(scholarshipRepository.findById(any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.createApplication(USER_ID, 999L))
				.isInstanceOf(CustomException.class);

		verify(essayRepository, never()).save(any());
	}

	// --- fixture ---

	private void givenScholarship(RequirementLevel essayRequirement) {
		Scholarship scholarship = Scholarship.builder()
				.title("가계곤란 장학금")
				.provider("경희대학교")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-1")
				.build();
		setField(scholarship, "id", 1L);
		setField(scholarship, "essayRequirement", essayRequirement);
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));
	}

	private User user() {
		User user = User.builder().build();
		setField(user, "id", USER_ID);
		return user;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Class<?> current = target.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
