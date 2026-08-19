package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 노출·클릭 기록 1건.
 *
 * <p><b>연관관계를 걸지 않는다.</b> 다른 엔티티처럼 {@code @ManyToOne} 으로 묶으면 세 가지가 걸린다 —
 * 이벤트를 넣을 때마다 대상 조회가 따라붙고(노출은 화면 한 번에 수십 건이다), 중복 장학금을 병합하면
 * 기록이 함께 지워지며, 탈퇴한 회원의 기록도 사라져 과거 실험 결과가 흔들린다. 기록은 대상이 없어져도
 * 남아야 하는 종류의 데이터다. {@code admin_audit_log} 와 같은 이유다.
 *
 * <p>{@code position} 과 {@code matchScore} 는 <b>노출 당시의</b> 값이다. 나중에 다시 계산하면
 * 그때의 점수식이 아니라 지금 점수식이 나오므로 비교가 불가능해진다.
 */
@Getter
@Entity
@Table(name = "scholarship_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipEvent extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "scholarship_id", nullable = false)
	private Long scholarshipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 20)
	private ScholarshipEventType eventType;

	/** 목록에서 몇 번째로 보였는가(1부터). 상세·스크랩처럼 목록 밖에서 일어난 일이면 null. */
	@Column(name = "position")
	private Integer position;

	/** 노출 당시의 매칭 점수. 점수식을 바꾼 뒤 전후를 비교하려면 그때 값이 필요하다. */
	@Column(name = "match_score")
	private Integer matchScore;

	/** 어느 화면인가(GUEST / ONBOARDING_REQUIRED / PERSONALIZED, 또는 상세·검색). */
	@Column(name = "view_mode", length = 30)
	private String viewMode;

	/**
	 * 어느 섹션에서 일어났는가(featured · campus · other · ineligible).
	 *
	 * <p>화면이 세 덩이로 나뉘어 있는데 이게 없으면 전부 한 덩어리로 섞인다. 마감임박 배너가
	 * 잘 먹히는지, 교내 섹션이 쓸모 있는지를 따로 볼 수 없다.
	 */
	@Column(name = "section", length = 30)
	private String section;

	/**
	 * 노출 당시의 점수식 판.
	 *
	 * <p>점수식을 바꿔도 좋아졌는지 나빠졌는지 말할 근거가 없었다. 같은 기간에 두 판이 섞여
	 * 있으면 비교 자체가 안 되므로, 어느 판이 만든 노출인지 함께 남긴다.
	 */
	@Column(name = "ranker_version", length = 20)
	private String rankerVersion;

	@Builder
	private ScholarshipEvent(UUID userId, Long scholarshipId, ScholarshipEventType eventType,
			Integer position, Integer matchScore, String viewMode, String section,
			String rankerVersion) {
		this.userId = userId;
		this.scholarshipId = scholarshipId;
		this.eventType = eventType;
		this.position = position;
		this.matchScore = matchScore;
		this.viewMode = viewMode;
		this.section = section;
		this.rankerVersion = rankerVersion;
	}
}
