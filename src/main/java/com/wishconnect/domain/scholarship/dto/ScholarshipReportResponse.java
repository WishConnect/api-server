package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ReportReason;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipReport;
import java.time.LocalDateTime;
import java.util.List;

/** 오등록 신고 응답. 관리자 목록과 신고 접수 응답에서 함께 쓴다. */
public record ScholarshipReportResponse(
		Long reportId,
		Long scholarshipId,
		String scholarshipTitle,
		/** 선택된 사유 전부. 화면이 다중 선택이라 단건이 아니다. */
		List<ReportReason> reasons,
		String detail,
		ReportStatus status,
		String adminNote,
		LocalDateTime createdAt,
		LocalDateTime resolvedAt
) {

	public static ScholarshipReportResponse from(ScholarshipReport report) {
		return new ScholarshipReportResponse(
				report.getId(),
				report.getScholarship().getId(),
				report.getScholarship().getTitle(),
				List.copyOf(report.getReasons()),
				report.getDetail(),
				report.getStatus(),
				report.getAdminNote(),
				report.getCreatedAt(),
				report.getResolvedAt()
		);
	}
}
