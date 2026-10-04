package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.wishconnect.domain.application.entity.EssayStatus;
import com.wishconnect.domain.application.repository.EssayRepository;
import com.wishconnect.domain.scholarship.dto.DedupScanRow;
import com.wishconnect.domain.scholarship.dto.ScholarshipDeleteCheckResponse;
import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("장학금 내리기·복원")
class ScholarshipTakedownServiceTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScrapRepository scrapRepository;
	@Mock private EssayRepository essayRepository;
	@Mock private ScholarshipMergeCandidateRepository mergeCandidateRepository;
	private ScholarshipTakedownService service;
	private Scholarship scholarship;
	private final UUID actor = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new ScholarshipTakedownService(scholarshipRepository, scrapRepository, essayRepository,
				mergeCandidateRepository);
		scholarship = scholarship(1L, "2026 국가근로장학생 선발결과 안내");
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship));
		given(mergeCandidateRepository.findTop10ByPrimary_IdOrDuplicate_IdOrderByIdDesc(anyLong(), anyLong()))
				.willReturn(List.of());
		given(scholarshipRepository.findDedupScanRows()).willReturn(List.of());
	}

	private static Scholarship scholarship(Long id, String title) {
		Scholarship value = Scholarship.builder().title(title).provider("기관")
				.scholarshipType(ScholarshipType.INTERNAL).recruitmentStatus(RecruitmentStatus.ALWAYS_OPEN)
				.dedupKey("k" + id).build();
		ReflectionTestUtils.setField(value, "id", id);
		return value;
	}

	@Test
	@DisplayName("사유 없이는 내리지 않는다")
	void reasonRequired() {
		assertThatThrownBy(() -> service.delete(1L, actor, "  "))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.DELETE_REASON_REQUIRED);
		assertThat(scholarship.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("내리면 관리자·사유를 남기고, 다시 내리면 거부한다")
	void deleteRecordsActorAndReason() {
		var result = service.delete(1L, actor, " 선발 결과 안내 ");

		assertThat(scholarship.isDeletedByAdmin()).isTrue();
		assertThat(scholarship.getDeletedBy()).isEqualTo(actor);
		assertThat(scholarship.getDeleteReason()).isEqualTo("선발 결과 안내");
		assertThat(result.before().deletedAt()).isNull();
		assertThat(result.after().deletedAt()).isNotNull();
		assertThatThrownBy(() -> service.delete(1L, actor, "또"))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_ALREADY_DELETED);
	}

	@Test
	@DisplayName("복원은 내린 것만, 병합으로 내린 쪽은 거부한다")
	void restoreRules() {
		assertThatThrownBy(() -> service.restore(1L, actor))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_NOT_DELETED);

		scholarship.markMergedInto(9L, actor);
		given(mergeCandidateRepository.findFirstByDuplicate_IdAndStatusOrderByIdDesc(1L, MergeCandidateStatus.MERGED))
				.willReturn(Optional.of(org.mockito.Mockito.mock(ScholarshipMergeCandidate.class)));
		assertThatThrownBy(() -> service.restore(1L, actor))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_MERGED_CANNOT_RESTORE);
	}

	@Test
	@DisplayName("관리자가 내린 것은 복원된다")
	void restoresAdminDeletion() {
		scholarship.deleteByAdmin(actor, "실수");

		service.restore(1L, actor);

		assertThat(scholarship.isDeleted()).isFalse();
		assertThat(scholarship.getDeletedBy()).isNull();
	}

	@Test
	@DisplayName("내리기 전 확인: 스크랩·자소서 수와 제목이 같은 공고를 보여 주고 병합을 권한다")
	void checkCountsAndSuggestsMerge() {
		given(scrapRepository.countByScholarship_Id(1L)).willReturn(3L);
		given(essayRepository.countByScholarship_IdAndStatus(1L, EssayStatus.IN_PROGRESS)).willReturn(2L);
		given(essayRepository.countByScholarship_IdAndStatus(1L, EssayStatus.COMPLETED)).willReturn(1L);
		Scholarship twin = scholarship(2L, "2026 국가근로장학생 선발결과 안내");
		given(scholarshipRepository.findDedupScanRows()).willReturn(List.of(
				new DedupScanRow(1L, scholarship.getTitle(), null), new DedupScanRow(2L, twin.getTitle(), null)));
		given(scholarshipRepository.findAllById(any())).willReturn(List.of(twin));

		ScholarshipDeleteCheckResponse check = service.check(1L);

		assertThat(check.scrapCount()).isEqualTo(3);
		assertThat(check.essayInProgressCount()).isEqualTo(2);
		assertThat(check.essayCompletedCount()).isEqualTo(1);
		assertThat(check.similarScholarships()).extracting(ScholarshipDeleteCheckResponse.Similar::scholarshipId)
				.containsExactly(2L);
		assertThat(check.mergeSuggested()).isTrue();
		assertThat(check.warnings()).anyMatch(warning -> warning.contains("병합"));
	}
}
