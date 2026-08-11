package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.ReportResolveRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipReportResponse;
import com.wishconnect.domain.scholarship.entity.ReportReason;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipReport;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipReportRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScholarshipReportServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScholarshipReportRepository scholarshipReportRepository;
	@Mock
	private ScholarshipRepository scholarshipRepository;
	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private ScholarshipReportService scholarshipReportService;

	private Scholarship scholarship() {
		return Scholarship.createManual(
				"장학금", "기관", null, null, ScholarshipType.EXTERNAL,
				null, null, null, null, null, "MANUAL:test");
	}

	private User user() {
		return User.createLocal("u@example.com", "encoded", "홍길동", "010-1111-2222");
	}

	@Test
	@DisplayName("신고를 접수하면 PENDING 상태로 저장된다")
	void report_savesAsPending() {
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship()));
		given(scholarshipReportRepository.existsByScholarship_IdAndUser_IdAndStatus(
				1L, USER_ID, ReportStatus.PENDING)).willReturn(false);
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
		given(scholarshipReportRepository.save(any(ScholarshipReport.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		ScholarshipReportResponse response = scholarshipReportService.report(
				USER_ID, 1L, new ScholarshipReportRequest(ReportReason.WRONG_DEADLINE, "마감일이 다릅니다"));

		assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
		assertThat(response.reason()).isEqualTo(ReportReason.WRONG_DEADLINE);
		assertThat(response.resolvedAt()).isNull();
	}

	@Test
	@DisplayName("미처리 신고가 남아 있으면 같은 사용자의 중복 신고를 막는다")
	void report_rejectsDuplicatePending() {
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship()));
		given(scholarshipReportRepository.existsByScholarship_IdAndUser_IdAndStatus(
				1L, USER_ID, ReportStatus.PENDING)).willReturn(true);

		assertThatThrownBy(() -> scholarshipReportService.report(
				USER_ID, 1L, new ScholarshipReportRequest(ReportReason.OTHER, "중복")))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.REPORT_ALREADY_EXISTS);
		verify(scholarshipReportRepository, never()).save(any(ScholarshipReport.class));
	}

	@Test
	@DisplayName("이미 내려간 장학금은 신고할 수 없다")
	void report_rejectsDeletedScholarship() {
		Scholarship deleted = scholarship();
		deleted.softDelete();
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(deleted));

		assertThatThrownBy(() -> scholarshipReportService.report(
				USER_ID, 1L, new ScholarshipReportRequest(ReportReason.OTHER, null)))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("처리하면 상태와 처리 시각이 남는다")
	void resolve_recordsStatusAndTime() {
		ScholarshipReport report = ScholarshipReport.create(
				scholarship(), user(), ReportReason.BROKEN_LINK, "링크 깨짐");
		given(scholarshipReportRepository.findById(1L)).willReturn(Optional.of(report));

		ScholarshipReportResponse response = scholarshipReportService.resolve(
				1L, new ReportResolveRequest(ReportStatus.RESOLVED, "링크 수정 완료"));

		assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
		assertThat(response.adminNote()).isEqualTo("링크 수정 완료");
		assertThat(response.resolvedAt()).isNotNull();
		assertThat(report.isPending()).isFalse();
	}

	@Test
	@DisplayName("없는 신고를 처리하면 REPORT_NOT_FOUND")
	void resolve_rejectsMissingReport() {
		given(scholarshipReportRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> scholarshipReportService.resolve(
				99L, new ReportResolveRequest(ReportStatus.REJECTED, null)))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
	}
}
