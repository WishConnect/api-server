package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.wishconnect.domain.scholarship.dto.ScholarshipEventRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipEventRequest.Event;
import com.wishconnect.domain.scholarship.entity.ScholarshipEvent;
import com.wishconnect.domain.scholarship.entity.ScholarshipEventType;
import com.wishconnect.domain.scholarship.repository.ScholarshipEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 행동 기록.
 *
 * <p>고정하려는 성질은 하나다. <b>기록은 본래 동작을 깨뜨리지 않는다.</b> 스크랩이 로그 저장 실패로
 * 실패하면 사용자에게는 아무 이득 없이 기능만 망가진 것이다.
 */
@ExtendWith(MockitoExtension.class)
class ScholarshipEventServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScholarshipEventRepository scholarshipEventRepository;

	@InjectMocks
	private ScholarshipEventService scholarshipEventService;

	@Captor
	private ArgumentCaptor<List<ScholarshipEvent>> captor;

	@Test
	@DisplayName("노출 당시의 순위·점수를 그대로 남긴다 — 나중에 다시 계산하면 지금 점수식이 나온다")
	void keepsTheScoreAsShown() {
		given(scholarshipEventRepository.saveAll(anyList())).willReturn(List.of());

		int saved = scholarshipEventService.record(USER_ID, new ScholarshipEventRequest(List.of(
				new Event(1L, ScholarshipEventType.IMPRESSION, 3, 80, "PERSONALIZED", "other", "v2"),
				new Event(2L, ScholarshipEventType.CLICK, 1, 100, "PERSONALIZED", "other", "v2"))));

		assertThat(saved).isEqualTo(2);
		org.mockito.Mockito.verify(scholarshipEventRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).extracting(
						ScholarshipEvent::getScholarshipId, ScholarshipEvent::getEventType,
						ScholarshipEvent::getPosition, ScholarshipEvent::getMatchScore)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(1L, ScholarshipEventType.IMPRESSION, 3, 80),
						org.assertj.core.groups.Tuple.tuple(2L, ScholarshipEventType.CLICK, 1, 100));
	}

	@Test
	@DisplayName("저장이 실패해도 예외를 밖으로 내보내지 않는다")
	void neverBreaksTheCaller() {
		willThrow(new RuntimeException("db down")).given(scholarshipEventRepository).saveAll(anyList());

		int saved = scholarshipEventService.record(USER_ID, new ScholarshipEventRequest(
				List.of(new Event(1L, ScholarshipEventType.IMPRESSION, 1, 50, "PERSONALIZED", "featured", "v2"))));

		assertThat(saved).isZero();
	}

	@Test
	@DisplayName("서버가 직접 아는 행동(스크랩·작성 착수)도 실패를 삼킨다")
	void serverSideRecordAlsoSwallowsFailure() {
		willThrow(new RuntimeException("db down")).given(scholarshipEventRepository).save(any());

		scholarshipEventService.record(USER_ID, 1L, ScholarshipEventType.SCRAP);
	}
}
