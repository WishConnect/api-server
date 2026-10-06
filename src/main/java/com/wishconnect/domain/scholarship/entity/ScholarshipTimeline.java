package com.wishconnect.domain.scholarship.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장학금 선발 일정 한 단계(서류 발표·면접·최종 발표 등).
 *
 * <p>날짜 모양은 {@link #dateType} 이 정한다. DB CHECK 와 같은 규칙이다.
 * <ul>
 *   <li>{@code SINGLE} — startDate = endDate</li>
 *   <li>{@code RANGE} — startDate ≤ endDate</li>
 *   <li>{@code TBD} — 날짜 없이 {@link #dateText}("12월 중 예정")만</li>
 * </ul>
 *
 * <p>지금은 관리자 수기 입력만 이 테이블에 쓴다. 수집 배치는 쓰지 않는다({@link TimelineOrigin}).
 */
@Entity
@Getter
@Table(name = "scholarship_timeline")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ScholarshipTimeline extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id", nullable = false)
	private Scholarship scholarship;

	@Enumerated(EnumType.STRING)
	@Column(name = "stage_code", nullable = false, length = 20)
	private TimelineStageCode stageCode;

	@Column(nullable = false)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "date_type", nullable = false, length = 10)
	private TimelineDateType dateType;

	@Column
	private LocalDate startDate;

	@Column
	private LocalDate endDate;

	/** 미정(TBD)일 때 보여 줄 문구. 예: "12월 중 예정" */
	@Column(name = "date_text", length = 100)
	private String dateText;

	/** "18:00 마감", 트랙 구분 등 */
	@Column(length = 200)
	private String note;

	/** 공고 원문 근거 문장 */
	@Column(columnDefinition = "TEXT")
	private String evidence;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TimelineOrigin origin;

	@Column(nullable = false)
	private int displayOrder;
}
