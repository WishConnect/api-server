package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.domain.user.entity.User;
import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장학금 오등록 신고. 사용자가 잘못된 정보를 발견하면 접수하고, 관리자가 확인해 처리한다.
 *
 * <p>수집이 크롤링·공공데이터 파싱에 의존해 오류가 남을 수밖에 없어서,
 * 실사용자가 발견한 오류를 되먹임받는 창구가 필요하다.
 */
@Getter
@Entity
@Table(name = "scholarship_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipReport extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	/** 신고자. 탈퇴해도 신고 이력은 남기므로 사용자 삭제와 연동하지 않는다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReportReason reason;

	@Column(columnDefinition = "TEXT")
	private String detail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportStatus status;

	/** 관리자가 남기는 처리 메모. 반려 사유를 남겨 같은 신고가 반복될 때 참고한다. */
	@Column(name = "admin_note", columnDefinition = "TEXT")
	private String adminNote;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	private ScholarshipReport(Scholarship scholarship, User user, ReportReason reason, String detail) {
		this.scholarship = scholarship;
		this.user = user;
		this.reason = reason;
		this.detail = detail;
		this.status = ReportStatus.PENDING;
	}

	public static ScholarshipReport create(
			Scholarship scholarship, User user, ReportReason reason, String detail) {
		return new ScholarshipReport(scholarship, user, reason, detail);
	}

	/** 관리자 처리. PENDING 이 아닌 상태로 바뀌는 시점을 처리 시각으로 본다. */
	public void resolve(ReportStatus status, String adminNote) {
		this.status = status;
		this.adminNote = adminNote;
		this.resolvedAt = status == ReportStatus.PENDING ? null : LocalDateTime.now();
	}

	public boolean isPending() {
		return status == ReportStatus.PENDING;
	}
}
