package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.ReportReason;
import com.wishconnect.domain.scholarship.entity.ReportStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipReport;
import java.time.LocalDateTime;

/** 오등록 신고 응답. 관리자 목록과 신고 접수 응답에서 함께 쓴다. */
public record ScholarshipReportResponse(
		Long reportId,
		Long scholarshipId,
		String scholarshipTitle,
		ReportReason reason,
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
				report.getReason(),
				report.getDetail(),
				report.getStatus(),
				report.getAdminNote(),
				report.getCreatedAt(),
				report.getResolvedAt()
		);
	}
}
