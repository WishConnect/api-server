package com.wishconnect.domain.scholarship.entity;

import com.wishconnect.domain.common.entity.School;
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
import java.util.UUID;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
서비스에서 실제 조회/추천에 사용할 정제된 장학금 엔티티입니다.
raw_scholarship의 원본 JSON을 파싱한 뒤 제목, 기관, 신청기간, 금액 등을 이 테이블에 저장합니다.
 */
@Getter
@Entity
@Table(name = "scholarship")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Scholarship extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(length = 200)
	private String provider;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "scholarship_type", length = 20)
	private ScholarshipType scholarshipType;

	@Column(name = "application_start_at")
	private LocalDateTime applicationStartAt;

	@Column(name = "application_end_at")
	private LocalDateTime applicationEndAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "recruitment_status", length = 20)
	private RecruitmentStatus recruitmentStatus;

	@Column(name = "selection_count")
	private Integer selectionCount;

	private Long amount;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Column(name = "is_verified", nullable = false)
	private boolean verified;

	@Column(name = "primary_source", length = 50)
	private String primarySource;

	/*
	같은 장학금이 여러 월별 API 엔드포인트에 중복으로 들어오는 경우가 있어,
	상품명/운영기관/모집기간을 기준으로 만든 키로 정제 테이블 중복 저장을 막습니다.
	 */
	@Column(name = "dedup_key", length = 64, unique = true)
	private String dedupKey;

	/**
	 * 자기소개서가 필요한가. {@code null} 이면 공고에 언급이 없어 모른다는 뜻이다.
	 *
	 * <p>기존 {@code ScholarshipDocument.essay} 는 서류 이름에 키워드가 있는지만 봤다.
	 * "수학계획서"·"지원동기서" 처럼 이름이 다르면 놓치고, 언급이 없으면 무조건 false 였다.
	 */
	/**
	 * 공지 종류. {@code null} 이면 판단하지 못한 것이다.
	 *
	 * <p>{@code GUIDE} 는 장학금이 아니므로 목록에서 뺀다. {@code RESULT} 는 모집기간이 없는 게
	 * 정상이라, 채움률을 잴 때 분모에서 빼야 지표가 정확해진다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "notice_kind", length = 20)
	private NoticeKind noticeKind;

	/**
	 * 한 공고에 <b>여러 장학금</b>이 함께 실려 있는가.
	 *
	 * <p>"교외통합장학금" 처럼 표로 7~8개를 나열하는 공고가 있다. 조건을 성실히 뽑으면 서로 다른
	 * 장학금의 요건이 한 행에 섞이는데, {@code eligible = mismatchCount == 0} 규칙상 전부 AND 로
	 * 걸린다. 실측에서 조건 11개가 뭉쳐 <b>아무도 통과할 수 없는 상태</b>가 됐다 —
	 * 시각디자인전공이면서 선교사 자녀인 학생만 지원 가능해진다.
	 *
	 * <p>참이면 <b>조건을 게이트로 쓰지 않는다.</b> 조건은 사실대로 REQUIRED 로 저장하되 판정에서만
	 * 제외한다. 나중에 장학금별로 행을 나누게 되면 데이터를 고칠 필요 없이 이 예외만 걷어내면 된다.
	 */
	@Column(name = "is_combined", nullable = false)
	private boolean combined;

	/**
	 * 어떻게 내는가. 온라인 신청인지, 우편·방문 제출인지.
	 *
	 * <p>마감이 "온라인 자정" 인지 "오전 10시 도착분에 한함" 인지에 따라 준비가 완전히 달라진다.
	 * 오프라인 제출 공고가 실제로 마감일 추출도 어렵게 만들고 있었다 — 표현이 제각각이라
	 * ("도착분에 한함", "우편 소인", "방문 접수") LLM 이 인용을 다듬다가 근거 대조에 걸렸다.
	 */
	@Column(name = "submission_method", length = 300)
	private String submissionMethod;

	@Column(name = "contact", length = 500)
	private String contact;

	/**
	 * 공공데이터 장학금의 <b>보조 정보만</b> 채운다.
	 *
	 * <p>제목·기간·금액은 이미 정확한 구조화 필드로 들어와 있어 손대지 않는다. 모델이 그것들을
	 * 다시 추측하게 두면 멀쩡한 값을 잃는다. 자유 텍스트에만 있던 것들을 여기서 채운다.
	 */
	public void applyLlmSupplement(
			RequirementLevel essayRequirement,
			String essayEvidence,
			RequirementLevel interviewRequirement,
			String interviewEvidence,
			String submissionMethod,
			SubmissionChannel submissionChannel,
			String submissionEvidence,
			String contact
	) {
		this.contact = contact;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
	}

	/**
	 * 제출방식 판단의 근거가 된 본문 문장.
	 *
	 * <p>본문이 없는 공고에 "이메일로 서류 접수" 가 지어내진 채 저장된 일이 있었다. 근거를 함께
	 * 남겨야 나중에 사람이 대조할 수 있다. 근거가 본문에 없으면 방식·채널과 함께 버린다.
	 */
	@Column(name = "submission_evidence", columnDefinition = "TEXT")
	private String submissionEvidence;

	/** 제출 경로. 화면 배지·필터용이고, 구체적인 안내는 {@link #submissionMethod} 에 있다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "submission_channel", length = 20)
	private SubmissionChannel submissionChannel;

	@Enumerated(EnumType.STRING)
	@Column(name = "essay_requirement", length = 20)
	private RequirementLevel essayRequirement;

	/** 위 판단의 근거가 된 공고 문장. 사용자에게 우리 판단 대신 원문을 보여주기 위해 남긴다. */
	@Column(name = "essay_evidence", columnDefinition = "TEXT")
	private String essayEvidence;

	/** 면접이 있는가. 대부분 CONDITIONAL("서류 합격자에 한해") 이다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "interview_requirement", length = 20)
	private RequirementLevel interviewRequirement;

	@Column(name = "interview_evidence", columnDefinition = "TEXT")
	private String interviewEvidence;

	@Column(name = "last_synced_at")
	private LocalDateTime lastSyncedAt;

	/** 관리자가 상시모집 원문을 마지막으로 확인한 시각. */
	@Column(name = "always_open_reviewed_at")
	private LocalDateTime alwaysOpenReviewedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	/**
	 * 관리자가 내렸거나(사유 필수) 병합으로 내린 경우의 관리자 ID. 수집 배치가 스스로 내린 경우는 null 이다.
	 *
	 * <p><b>수집 배치가 되살리면 안 되는 삭제인지</b>를 이 값으로 가른다. 동기화는 같은 공고가 다시 들어오면
	 * {@code deletedAt} 을 풀어 주는데(원본에서 잠시 빠졌다 돌아온 공고를 살리기 위해), 그 경로가 관리자가 내린
	 * 공고까지 다음 날 되살리고 있었다.
	 */
	@Column(name = "deleted_by")
	private UUID deletedBy;

	/** 내린 사유. 관리자 내리기는 필수, 병합은 "병합: #N 로 합쳐짐". */
	@Column(name = "delete_reason", length = 500)
	private String deleteReason;

	@Column(name = "homepage_url", length = 1000)
	private String homepageUrl;

	/**
	 * 장학금 상세페이지. 공공데이터 원문에는 이 필드가 없고 {@code homepageUrl} 이 기관 메인이라,
	 * 검색으로 찾아 채운다({@code ScholarshipEnrichmentService}). 사람이 확인해 넣기도 한다.
	 */
	@Column(name = "detail_url", length = 1000)
	private String detailUrl;

	/** 자동 보완을 마지막으로 시도한 시각. 매 배치마다 같은 건을 다시 검색하지 않으려고 둔다. */
	@Column(name = "enriched_at")
	private LocalDateTime enrichedAt;

	/**
	 * 이 공고를 낸 학교. <b>교내 공고를 다른 학교 학생에게 보여주지 않기 위한 것</b>이다.
	 *
	 * <p>그동안은 {@code provider} 문자열을 프로필 학교명과 견줬는데, 표기가 조금만 달라도
	 * ("인천대"/"인천대학교"/"국립인천대학교") 대조가 빗나갔다. 게다가 타입으로는 거를 수 없다 —
	 * 학교를 짚는 공고 17건 중 16건이 {@code INTERNAL} 이 아니라 {@code WORK_STUDY}·{@code EXTERNAL}
	 * 로 분류돼 있었다(근로장학은 대개 교내인데 타입이 다르게 붙는다).
	 *
	 * <p><b>수집 시점에는 어느 대학인지 이미 안다.</b> 대학 공지 수집기는 사이트별로 돌고
	 * {@code UnivNoticeProperties} 에 학교명이 있다. 그 사실을 여기 남겨 두면 추천에서 확실히 쓸 수 있다.
	 *
	 * <p>{@code null} 은 "학교와 무관한 공고"가 아니라 <b>"모른다"</b>이다. 공공데이터(재단·기업)
	 * 공고는 대부분 여기에 해당하고, 관문은 값이 있을 때만 건다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "school_id")
	private School school;

	/**
	 * 중복 후보 탐지를 마지막으로 마친 시각. 한 번도 안 봤으면 {@code null}.
	 *
	 * <p>없을 때는 <b>매일 최신 30건만</b> 다시 봤다. 그래서 오래된 공고는 영영 검사되지 않았고,
	 * 정작 실제 중복인 <b>출처가 다른 쌍</b>(공공데이터 vs 대학공지)은 며칠 차이로 들어와
	 * 같은 창에 함께 담길 일이 없었다 — 중복 판정 큐가 계속 비어 있던 이유다.
	 *
	 * <p>이 값을 두면 안 본 것부터 가져가 전체가 한 바퀴 돈다. 새 공고가 들어오면 그 공고가
	 * 속한 묶음이 다시 대상이 되므로, 오래된 쪽과 짝이 맞는지도 그때 확인된다.
	 */
	@Column(name = "dedup_scanned_at")
	private LocalDateTime dedupScannedAt;

	/**
	 * 관리자가 모집기간을 직접 고쳤는지. true 면 공공데이터 동기화({@link #updateFromApi})와 대학 공지
	 * 재파싱({@link #applyLlmParsed})이 <b>기간만</b> 건너뛰고 나머지는 평소처럼 갱신한다.
	 *
	 * <p>없을 때는 관리자가 통합 수정으로 고친 마감일을 다음 날 동기화가 원래 값으로 되돌렸다.
	 * 통합 수정에서 기간이 바뀌면 자동으로 켜지고, 콘솔의 "자동 수집 값으로 되돌리기"로 끈다.
	 */
	@Column(name = "period_locked", nullable = false)
	private boolean periodLocked;

	@Builder
	private Scholarship(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String primarySource,
		String dedupKey,
		String homepageUrl,
		// 뒤에 붙인다. 위치 기반으로 호출하는 곳(ScholarshipMapper)이 있어 중간에 끼우면 깨진다.
		RequirementLevel essayRequirement,
		String essayEvidence,
		RequirementLevel interviewRequirement,
		String interviewEvidence,
		NoticeKind noticeKind,
		boolean combined,
		String submissionMethod,
		SubmissionChannel submissionChannel,
		String submissionEvidence,
		String contact
	) {
		this.contact = contact;
		this.noticeKind = noticeKind;
		this.combined = combined;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.recruitmentStatus = recruitmentStatus == null ? RecruitmentStatus.UPCOMING : recruitmentStatus;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.active = true;
		this.verified = false;
		this.primarySource = primarySource;
		this.dedupKey = dedupKey;
		this.homepageUrl = homepageUrl;
		this.lastSyncedAt = LocalDateTime.now();
	}

	public void updateFromApi(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		RecruitmentStatus recruitmentStatus,
		Integer selectionCount,
		Long amount,
		String primarySource,
		String dedupKey,
		String homepageUrl
	) {
		// 관리자가 내린 공고는 같은 공고가 다시 수집돼도 되살리지 않고 내용도 덮지 않는다.
		if (isDeletedByAdmin()) {
			return;
		}
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		// 관리자가 고정한 기간은 덮지 않는다. 모집 상태는 호출부(ScholarshipMapper)가 고정 기간으로 계산해 넘긴다.
		if (!periodLocked) {
			this.applicationStartAt = applicationStartAt;
			this.applicationEndAt = applicationEndAt;
		}
		this.recruitmentStatus = recruitmentStatus == null ? RecruitmentStatus.UPCOMING : recruitmentStatus;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.primarySource = primarySource;
		this.dedupKey = dedupKey;
		this.homepageUrl = homepageUrl;
		// 동기화 피드에 다시 들어왔다는 것은 살아 있는 공고라는 뜻이므로 소프트 삭제를 해제한다.
		// 물리 삭제 시절에는 행이 사라졌다가 새로 생겨 자연히 되살아났는데,
		// 소프트 삭제로 바꾼 뒤에는 여기서 풀어주지 않으면 deletedAt 이 남아 영원히 노출되지 않는다.
		// (조회 쿼리가 전부 deletedAt IS NULL 로 거른다)
		this.deletedAt = null;
		this.lastSyncedAt = LocalDateTime.now();
	}

	/**
	 * 공고를 낸 학교를 지정한다.
	 *
	 * <p>빌더가 아니라 별도 메서드인 이유: {@code @Builder} 가 붙은 생성자를 <b>위치 기반</b>으로
	 * 부르는 곳이 있어({@code ScholarshipMapper}) 인자를 늘리면 조용히 어긋난다.
	 */
	public void assignSchool(School school) {
		this.school = school;
	}

	/** 자동 보완 결과 반영. 상세 URL 을 못 찾았어도 시도 시각은 남겨 재시도 주기를 지킨다. */
	public void applyEnrichment(String detailUrl) {
		if (org.springframework.util.StringUtils.hasText(detailUrl)) {
			this.detailUrl = detailUrl;
		}
		this.enrichedAt = LocalDateTime.now();
	}

	public void updateActive(boolean active) {
		if (isDeletedByAdmin()) {
			return;
		}
		this.active = active;
	}

	/** 수기 등록분의 primary_source. 동기화 배치가 건드리지 않는 출처임을 구분한다. */
	public static final String MANUAL_SOURCE = "MANUAL";

	/**
	 * 관리자가 직접 등록한 장학금.
	 *
	 * <p>{@code dedupKey} 는 공공데이터 응답으로 만드는 키와 절대 겹치지 않는 형식을 쓴다.
	 * 겹치면 다음 동기화 때 {@link #updateFromApi} 로 덮여 수기 입력이 날아간다.
	 * 사람이 확인하고 넣은 값이므로 {@code verified} 는 참으로 시작한다.
	 */
	public static Scholarship createManual(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl,
		String dedupKey
	) {
		Scholarship scholarship = Scholarship.builder()
			.title(title)
			.provider(provider)
			.summary(summary)
			.description(description)
			.scholarshipType(scholarshipType)
			.applicationStartAt(applicationStartAt)
			.applicationEndAt(applicationEndAt)
			.recruitmentStatus(resolveStatus(applicationStartAt, applicationEndAt))
			.selectionCount(selectionCount)
			.amount(amount)
			.primarySource(MANUAL_SOURCE)
			.dedupKey(dedupKey)
			.homepageUrl(homepageUrl)
			.build();
		scholarship.verified = true;
		return scholarship;
	}

	/**
	 * 관리자 직접 수정. null 인 필드는 기존 값을 유지해 부분 수정이 가능하다.
	 * (오등록 신고가 들어온 항목의 한두 필드만 고치는 게 주 용도라 전체 교체는 오히려 위험하다)
	 */
	public void updateByAdmin(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl
	) {
		if (title != null) {
			this.title = title;
		}
		if (provider != null) {
			this.provider = provider;
		}
		if (summary != null) {
			this.summary = summary;
		}
		if (description != null) {
			this.description = description;
		}
		if (scholarshipType != null) {
			this.scholarshipType = scholarshipType;
		}
		if (applicationStartAt != null) {
			this.applicationStartAt = applicationStartAt;
		}
		if (applicationEndAt != null) {
			this.applicationEndAt = applicationEndAt;
		}
		if (selectionCount != null) {
			this.selectionCount = selectionCount;
		}
		if (amount != null) {
			this.amount = amount;
		}
		if (homepageUrl != null) {
			this.homepageUrl = homepageUrl;
		}
		// 모집 상태는 날짜로 다시 계산하지 않는다. 제목만 고쳐도 상태가 바뀌던 문제가 있었다
		// (관리자가 조기 마감한 CLOSED 가 미래 마감일 때문에 OPEN 으로 되돌아감). 상태는 관리자가 직접 고른다.
		// 사람이 확인해 고친 값이므로 검증된 것으로 표시한다.
		this.verified = true;
	}

	/** 통합 수기 등록에서 자동 수집과 같은 수준의 제출·심사·공고 메타데이터를 채운다. */
	public void applyManualDetails(
		String detailUrl,
		RecruitmentStatus recruitmentStatus,
		NoticeKind noticeKind,
		boolean combined,
		String submissionMethod,
		SubmissionChannel submissionChannel,
		String submissionEvidence,
		String contact,
		RequirementLevel essayRequirement,
		String essayEvidence,
		RequirementLevel interviewRequirement,
		String interviewEvidence
	) {
		this.detailUrl = detailUrl;
		this.noticeKind = noticeKind;
		this.combined = combined;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
		this.contact = contact;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		if (recruitmentStatus != null) {
			updateRecruitmentStatusByAdmin(recruitmentStatus);
		}
	}

	/** 관리자 통합 편집 화면의 최종 값을 그대로 반영한다. primarySource와 dedupKey는 보존한다. */
	public void replaceByAdmin(
		String title, String provider, String summary, String description,
		ScholarshipType scholarshipType, LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt, RecruitmentStatus recruitmentStatus,
		Integer selectionCount, Long amount, String homepageUrl, String detailUrl,
		NoticeKind noticeKind, boolean combined, String submissionMethod,
		SubmissionChannel submissionChannel, String submissionEvidence, String contact,
		RequirementLevel essayRequirement, String essayEvidence,
		RequirementLevel interviewRequirement, String interviewEvidence
	) {
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.homepageUrl = homepageUrl;
		this.detailUrl = detailUrl;
		this.noticeKind = noticeKind;
		this.combined = combined;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
		this.contact = contact;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		// 모집 상태는 화면에서 고른 값만 쓴다. 비어 있으면 날짜로 계산하지 않고 기존 상태를 둔다.
		if (recruitmentStatus != null) {
			this.recruitmentStatus = recruitmentStatus;
		}
		this.active = this.recruitmentStatus != RecruitmentStatus.CLOSED;
		this.verified = true;
	}

	/**
	 * 대학 장학공지를 LLM 으로 재파싱한 결과를 덮어쓴다.
	 *
	 * <p>{@link #updateFromApi} 와 달리 공공데이터 응답이 아니라 공고 본문 파싱 결과를 받는다.
	 * 기존 값이 정규식으로 잘못 파싱된 것일 수 있으므로 <b>null 도 그대로 덮어쓴다.</b>
	 * 예를 들어 근무기간을 신청기간으로 잘못 넣어둔 행은, 재파싱에서 기간을 찾지 못하면
	 * null 로 비워야 맞다. null 을 무시하면 잘못된 옛 값이 영구히 남는다.
	 *
	 * <p>{@code verified} 는 건드리지 않는다. 사람이 검수한 표시를 기계 파싱이 되돌리면 안 된다.
	 */
	public void applyLlmParsed(
		String title,
		String provider,
		String summary,
		String description,
		ScholarshipType scholarshipType,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		Integer selectionCount,
		Long amount,
		String homepageUrl,
		RequirementLevel essayRequirement,
		String essayEvidence,
		RequirementLevel interviewRequirement,
		String interviewEvidence,
		NoticeKind noticeKind,
		boolean combined,
		String submissionMethod,
		SubmissionChannel submissionChannel,
		String submissionEvidence,
		String contact
	) {
		// 관리자가 내린 공고는 재파싱이 되살리지 않는다(아래에서 deletedAt 을 풀기 때문).
		if (isDeletedByAdmin()) {
			return;
		}
		this.contact = contact;
		this.noticeKind = noticeKind;
		this.combined = combined;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		// 관리자가 고정한 기간은 재파싱이 덮지 않는다(나머지 필드는 평소처럼 갱신). 상태는 남은 기간으로 계산한다.
		if (!periodLocked) {
			this.applicationStartAt = applicationStartAt;
			this.applicationEndAt = applicationEndAt;
		}
		this.selectionCount = selectionCount;
		this.amount = amount;
		if (homepageUrl != null) {
			this.homepageUrl = homepageUrl;
		}
		this.recruitmentStatus = resolveStatus(this.applicationStartAt, this.applicationEndAt);
		// 마감된 공고는 목록에서 내린다. 마감일을 못 찾은 경우(null)는 노출을 유지한다 —
		// 기간을 모른다는 것이 끝났다는 뜻은 아니고, 숨기는 쪽이 더 해롭다.
		this.active = this.recruitmentStatus != RecruitmentStatus.CLOSED;
		this.deletedAt = null;
		this.lastSyncedAt = LocalDateTime.now();
	}

	/** 모집기간 수기 고정을 켜거나 끈다. 끄면 다음 동기화·재파싱부터 수집한 기간으로 다시 덮인다. */
	public void changePeriodLocked(boolean periodLocked) {
		this.periodLocked = periodLocked;
	}

	/** 오등록으로 확인된 장학금을 목록에서 내린다. 이력 추적을 위해 행은 남긴다. */
	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
		this.active = false;
	}

	/** 관리자 내리기. 수집 배치가 되살리지 않도록 누가·왜 내렸는지 남긴다. */
	public void deleteByAdmin(UUID actorId, String reason) {
		softDelete();
		this.deletedBy = actorId;
		this.deleteReason = reason;
	}

	/** 병합으로 내린 중복 쪽. 같은 공고가 다시 수집돼도 되살아나 중복이 다시 보이지 않게 한다. */
	public void markMergedInto(Long primaryId, UUID reviewer) {
		softDelete();
		this.deletedBy = reviewer;
		this.deleteReason = "병합: #" + primaryId + " 로 합쳐짐";
	}

	/** 관리자 복원. 노출 여부는 현재 모집 상태를 따른다(복원했다고 마감 공고가 다시 열리지 않는다). */
	public void restoreByAdmin() {
		this.deletedAt = null;
		this.deletedBy = null;
		this.deleteReason = null;
		this.active = this.recruitmentStatus != RecruitmentStatus.CLOSED;
	}

	/** 관리자가 내렸거나 병합으로 내린 장학금. 수집·재파싱이 내용·노출을 바꾸지 않는다. */
	public boolean isDeletedByAdmin() {
		return deletedAt != null && deletedBy != null;
	}

	public void updateRecruitmentStatusByAdmin(RecruitmentStatus recruitmentStatus) {
		this.recruitmentStatus = recruitmentStatus;
		this.active = recruitmentStatus != RecruitmentStatus.CLOSED;
	}

	public void confirmAlwaysOpen() {
		if (this.recruitmentStatus != RecruitmentStatus.ALWAYS_OPEN || this.deletedAt != null) {
			throw new IllegalStateException("상시모집 중인 장학금만 확인 처리할 수 있습니다.");
		}
		this.alwaysOpenReviewedAt = LocalDateTime.now();
	}

	/**
	 * 감사 로그 복구(통합 수정 기록). 화면 값을 그대로 쓰되 {@link #replaceByAdmin} 과 두 가지가 다르다.
	 * 모집 상태를 날짜로 다시 계산하지 않고(사람이 고른 값만 되돌린다), 검수 표시를 바꾸지 않는다.
	 */
	public void restoreAggregateFields(
		String title, String provider, String summary, String description,
		ScholarshipType scholarshipType, LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt, RecruitmentStatus recruitmentStatus,
		Integer selectionCount, Long amount, String homepageUrl, String detailUrl,
		NoticeKind noticeKind, boolean combined, String submissionMethod,
		SubmissionChannel submissionChannel, String submissionEvidence, String contact,
		RequirementLevel essayRequirement, String essayEvidence,
		RequirementLevel interviewRequirement, String interviewEvidence
	) {
		this.title = title;
		this.provider = provider;
		this.summary = summary;
		this.description = description;
		this.scholarshipType = scholarshipType;
		this.applicationStartAt = applicationStartAt;
		this.applicationEndAt = applicationEndAt;
		this.selectionCount = selectionCount;
		this.amount = amount;
		this.homepageUrl = homepageUrl;
		this.detailUrl = detailUrl;
		this.noticeKind = noticeKind;
		this.combined = combined;
		this.submissionMethod = submissionMethod;
		this.submissionChannel = submissionChannel;
		this.submissionEvidence = submissionEvidence;
		this.contact = contact;
		this.essayRequirement = essayRequirement;
		this.essayEvidence = essayEvidence;
		this.interviewRequirement = interviewRequirement;
		this.interviewEvidence = interviewEvidence;
		if (recruitmentStatus != null && recruitmentStatus != this.recruitmentStatus) {
			this.recruitmentStatus = recruitmentStatus;
			this.active = this.deletedAt == null && recruitmentStatus != RecruitmentStatus.CLOSED;
		}
	}

	/** 감사로그의 수기 변경 스냅샷으로 복구한다. 자동 파싱 전용 필드는 건드리지 않는다. */
	public void restoreAdminSnapshot(ScholarshipAdminSnapshot snapshot) {
		this.title = snapshot.title();
		this.provider = snapshot.provider();
		this.summary = snapshot.summary();
		this.description = snapshot.description();
		this.scholarshipType = snapshot.scholarshipType();
		this.applicationStartAt = snapshot.applicationStartAt();
		this.applicationEndAt = snapshot.applicationEndAt();
		this.recruitmentStatus = snapshot.recruitmentStatus();
		this.selectionCount = snapshot.selectionCount();
		this.amount = snapshot.amount();
		this.homepageUrl = snapshot.homepageUrl();
		this.active = snapshot.active();
		this.verified = snapshot.verified();
		this.deletedAt = snapshot.deletedAt();
		if (this.deletedAt == null) {
			// 내리기를 되돌렸으면 내린 사람·사유도 지운다. 남기면 수집 배치가 계속 "관리자 삭제" 로 본다.
			this.deletedBy = null;
			this.deleteReason = null;
		}
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	/**
	 * 날짜로 모집 상태를 정한다.
	 *
	 * <p>마감일이 없으면 OPEN 으로 두고 있었다. 날짜가 없으니 배치도 닫을 수 없어 영원히
	 * 목록에 남는다 — 운영에서 183건이 그랬다. 마감일 없는 공고 자체는 정상이므로
	 * ({@code "충원 시 마감"}) 닫지 않되, 자동 판정을 포기했다는 사실을 상태로 남긴다.
	 */
	private static RecruitmentStatus resolveStatus(LocalDateTime startAt, LocalDateTime endAt) {
		LocalDateTime now = LocalDateTime.now();
		if (endAt != null && endAt.isBefore(now)) {
			return RecruitmentStatus.CLOSED;
		}
		if (startAt != null && startAt.isAfter(now)) {
			return RecruitmentStatus.UPCOMING;
		}
		return endAt == null ? RecruitmentStatus.ALWAYS_OPEN : RecruitmentStatus.OPEN;
	}
}
