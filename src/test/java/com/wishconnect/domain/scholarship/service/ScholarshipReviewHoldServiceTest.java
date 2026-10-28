package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 새 장학금 검수 대기(선작업·후노출) 해제 규칙. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipReviewHoldServiceTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipMergeCandidateRepository mergeCandidateRepository;
	@Mock private ScholarshipConditionRepository scholarshipConditionRepository;
	@InjectMocks private ScholarshipReviewHoldService service;

	private long nextId = 1L;

	@BeforeEach
	void setUp() {
		given(mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING)).willReturn(List.of());
		given(scholarshipConditionRepository.countByScholarshipId(anyLong())).willReturn(1L);
	}

	@Test
	@DisplayName("수집으로 만든 장학금은 검수 대기로 시작하고, 수기 등록분은 바로 공개다")
	void newCollectedStartsHeldButManualDoesNot() {
		assertThat(collected("공고").isReviewHeld()).isTrue();
		assertThat(collected("공고").isVisibleToUsers()).isFalse();
		Scholarship manual = Scholarship.createManual("수기 공고", "기관", null, null, ScholarshipType.EXTERNAL,
				null, null, null, null, "https://example.com", "manual-key");
		assertThat(manual.isReviewHeld()).isFalse();
		assertThat(manual.isVisibleToUsers()).isTrue();
	}

	@Test
	@DisplayName("배치 해제: 중복 검사를 마쳤고 후보·이상이 없는 건만 공개한다")
	void releasesOnlyClearedOnes() {
		Scholarship clean = scanned(collected("깨끗한 공고"));
		Scholarship notScanned = collected("중복 검사 전 공고");
		Scholarship pendingDup = scanned(collected("중복 후보 공고"));
		Scholarship anomaly = scanned(collected("링크 없는 공고"));
		setField(anomaly, "homepageUrl", null);
		given(scholarshipRepository.findReviewHeld()).willReturn(List.of(clean, notScanned, pendingDup, anomaly));
		given(mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING))
				.willReturn(List.of(pendingDup.getId()));

		ScholarshipReviewHoldService.ReleaseResult result = service.releaseCleared();

		assertThat(result.released()).isEqualTo(1);
		assertThat(result.waitingDedup()).isEqualTo(1);
		assertThat(result.waitingDuplicate()).isEqualTo(1);
		assertThat(result.waitingAnomaly()).isEqualTo(1);
		assertThat(clean.isReviewHeld()).isFalse();
		assertThat(notScanned.isReviewHeld()).isTrue();
		assertThat(pendingDup.isReviewHeld()).isTrue();
		assertThat(anomaly.isReviewHeld()).isTrue();
	}

	@Test
	@DisplayName("중복 후보 처리 후 해제: 사람이 봤으므로 중복 검사 표시는 따지지 않는다")
	void releaseIfClearIgnoresScanStamp() {
		Scholarship held = collected("병합 후 남는 공고");
		given(scholarshipRepository.findById(held.getId())).willReturn(Optional.of(held));

		assertThat(service.releaseIfClear(held.getId())).isTrue();
		assertThat(held.isReviewHeld()).isFalse();
	}

	@Test
	@DisplayName("중복 후보 처리 후 해제: 다른 대기 후보가 남아 있으면 그대로 둔다")
	void releaseIfClearKeepsWhenAnotherCandidatePending() {
		Scholarship held = collected("다른 후보에도 걸린 공고");
		given(scholarshipRepository.findById(held.getId())).willReturn(Optional.of(held));
		given(mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING))
				.willReturn(List.of(held.getId()));

		assertThat(service.releaseIfClear(held.getId())).isFalse();
		assertThat(held.isReviewHeld()).isTrue();
	}

	@Test
	@DisplayName("관리자 공개: 남은 사유가 있어도 풀고, 공개 전 상태를 돌려준다")
	void publishForcesRelease() {
		Scholarship held = collected("관리자가 확인한 공고");
		given(scholarshipRepository.findById(held.getId())).willReturn(Optional.of(held));

		ScholarshipReviewHoldService.PublishResult result = service.publish(held.getId());

		assertThat(result.wasHeld()).isTrue();
		assertThat(result.reasons()).contains(ScholarshipReviewHoldService.REASON_DEDUP_NOT_CHECKED);
		assertThat(held.isVisibleToUsers()).isTrue();
	}

	@Test
	@DisplayName("관리자 공개: 내린 장학금은 공개하지 않는다")
	void publishRejectsDeleted() {
		Scholarship deleted = collected("내린 공고");
		deleted.softDelete();
		given(scholarshipRepository.findById(deleted.getId())).willReturn(Optional.of(deleted));

		assertThatThrownBy(() -> service.publish(deleted.getId())).isInstanceOf(CustomException.class);
	}

	private Scholarship collected(String title) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("경희대학교")
				.scholarshipType(ScholarshipType.INTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.applicationEndAt(LocalDateTime.now().plusDays(10))
				.primarySource("UNIV_KHU")
				.homepageUrl("https://example.com/notice")
				.dedupKey("key-" + nextId)
				.build();
		setField(scholarship, "id", nextId++);
		return scholarship;
	}

	private Scholarship scanned(Scholarship scholarship) {
		setField(scholarship, "dedupScannedAt", LocalDateTime.now());
		return scholarship;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Class<?> type = target.getClass();
			while (type != null) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {
					type = type.getSuperclass();
				}
			}
			throw new IllegalArgumentException(name);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
