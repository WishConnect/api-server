package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.scholarship.dto.ScholarshipManualRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualResponse;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScholarshipManualServiceTest {

	@Mock
	private ScholarshipRepository scholarshipRepository;

	@InjectMocks
	private ScholarshipManualService scholarshipManualService;

	private ScholarshipManualRequest.Create createRequest(
			LocalDateTime startAt, LocalDateTime endAt) {
		return new ScholarshipManualRequest.Create(
				"  미래인재 장학금  ", "위시커넥트", "요약", "설명",
				ScholarshipType.EXTERNAL, startAt, endAt, 10, 1_000_000L,
				"https://example.com/apply");
	}

	@Test
	@DisplayName("수기 등록분은 MANUAL 출처와 검증됨 표시로 저장된다")
	void create_marksManualAndVerified() {
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		ScholarshipManualResponse response = scholarshipManualService.create(
				createRequest(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10)));

		assertThat(response.primarySource()).isEqualTo("MANUAL");
		assertThat(response.verified()).isTrue();
		assertThat(response.title()).isEqualTo("미래인재 장학금");
		assertThat(response.recruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
	}

	@Test
	@DisplayName("수기 등록의 dedupKey 는 MANUAL 접두사를 써서 동기화가 덮어쓰지 못한다")
	void create_usesManualDedupKeyPrefix() {
		given(scholarshipRepository.save(any(Scholarship.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		scholarshipManualService.create(
				createRequest(LocalDateTime.now(), LocalDateTime.now().plusDays(1)));

		org.mockito.ArgumentCaptor<Scholarship> captor =
				org.mockito.ArgumentCaptor.forClass(Scholarship.class);
		org.mockito.Mockito.verify(scholarshipRepository).save(captor.capture());
		assertThat(captor.getValue().getDedupKey()).startsWith("MANUAL:");
	}

	@Test
	@DisplayName("마감이 시작보다 빠르면 INVALID_APPLICATION_PERIOD")
	void create_rejectsInvertedPeriod() {
		assertThatThrownBy(() -> scholarshipManualService.create(
				createRequest(LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(1))))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_APPLICATION_PERIOD);
	}

	@Test
	@DisplayName("부분 수정은 보낸 필드만 반영하고 나머지는 유지한다")
	void update_appliesOnlyProvidedFields() {
		Scholarship existing = Scholarship.createManual(
				"원래 제목", "원래 기관", "원래 요약", "원래 설명", ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10),
				5, 500_000L, "https://old.example.com", "MANUAL:abc");
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(existing));

		ScholarshipManualResponse response = scholarshipManualService.update(1L,
				new ScholarshipManualRequest(
						"새 제목", null, null, null, null, null, null, null, 2_000_000L, null));

		assertThat(response.title()).isEqualTo("새 제목");
		assertThat(response.amount()).isEqualTo(2_000_000L);
		// 안 보낸 필드는 그대로여야 한다.
		assertThat(response.provider()).isEqualTo("원래 기관");
		assertThat(response.homepageUrl()).isEqualTo("https://old.example.com");
	}

	@Test
	@DisplayName("이미 내려간 장학금은 수정할 수 없다")
	void update_rejectsDeleted() {
		Scholarship deleted = Scholarship.createManual(
				"제목", null, null, null, ScholarshipType.EXTERNAL, null, null, null, null, null,
				"MANUAL:x");
		deleted.softDelete();
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(deleted));

		assertThatThrownBy(() -> scholarshipManualService.update(1L,
				new ScholarshipManualRequest("새 제목", null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("삭제는 목록에서 내리되 행은 남긴다")
	void delete_softDeletes() {
		Scholarship scholarship = Scholarship.createManual(
				"제목", null, null, null, ScholarshipType.EXTERNAL, null, null, null, null, null,
				"MANUAL:y");
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));

		scholarshipManualService.delete(1L);

		assertThat(scholarship.isDeleted()).isTrue();
		assertThat(scholarship.isActive()).isFalse();
	}
}
