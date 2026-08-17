package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.domain.user.entity.User;
import com.wishconnect.global.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

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

	/**
	 * 선택된 신고 사유. 화면이 체크박스 다중 선택이라 한 건에 여러 개가 붙는다.
	 *
	 * <p>{@code Set} 을 쓰는 이유는 프론트가 같은 값을 두 번 보내도 저장이 깨지지 않게 하기 위함이다
	 * ({@code (report_id, reason)} 이 복합 PK 라 중복이 오면 제약 위반이 난다).
	 * 순서는 화면 체크박스 순서를 그대로 돌려주려고 {@link LinkedHashSet} 으로 보존한다.
	 */
	@ElementCollection
	@CollectionTable(
			name = "scholarship_report_reason",
			joinColumns = @JoinColumn(name = "report_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "reason", nullable = false, length = 30)
	// 목록 조회에서 신고 건마다 사유 조회가 따로 나가는(N+1) 것을 막는다.
	@BatchSize(size = 100)
	private Set<ReportReason> reasons = new LinkedHashSet<>();

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

	private ScholarshipReport(
			Scholarship scholarship, User user, Collection<ReportReason> reasons, String detail) {
		this.scholarship = scholarship;
		this.user = user;
		this.reasons = new LinkedHashSet<>(reasons);
		this.detail = detail;
		this.status = ReportStatus.PENDING;
	}

	public static ScholarshipReport create(
			Scholarship scholarship, User user, Collection<ReportReason> reasons, String detail) {
		return new ScholarshipReport(scholarship, user, reasons, detail);
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
