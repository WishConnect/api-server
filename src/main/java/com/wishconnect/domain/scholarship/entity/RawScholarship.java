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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
외부 장학금 API에서 받은 원본 데이터를 보관하는 엔티티입니다.
필드 매핑이 바뀌어도 raw_json을 기준으로 다시 파싱할 수 있도록 원본 응답을 JSONB로 저장합니다.
 */
@Getter
@Entity
@Table(
	name = "raw_scholarship",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_raw_scholarship_source_source_id", columnNames = {"source", "source_id"})
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawScholarship extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "scholarship_id")
	private Scholarship scholarship;

	@Column(nullable = false, length = 50)
	private String source;

	@Column(name = "source_url", length = 1000)
	private String sourceUrl;

	@Column(name = "source_id", nullable = false, length = 200)
	private String sourceId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_json", columnDefinition = "jsonb")
	private Map<String, Object> rawJson;

	@Column(name = "raw_html", columnDefinition = "TEXT")
	private String rawHtml;

	@Column(name = "crawled_at")
	private LocalDateTime crawledAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "parse_status", nullable = false, length = 20)
	private ParseStatus parseStatus;

	@Column(name = "parse_error", columnDefinition = "TEXT")
	private String parseError;

	/**
	 * 관리자가 실패 재처리 대상에서 "만료 처리"한 시각. null 이면 만료 처리 안 됨.
	 *
	 * <p>파싱 상태와 별개 축이다. 만료 처리된 원본은 실패 재처리 목록·배지에서 빠지고, 매일 배치 파싱·LLM 재처리·
	 * 공공데이터 동기화가 다시 처리하지 않는다(동기화는 본문만 갱신). 되돌리면 세 칸을 비운다.
	 */
	@Column(name = "dismissed_at")
	private LocalDateTime dismissedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "dismissed_reason", length = 30)
	private RawDismissReason dismissedReason;

	@Column(name = "dismissed_note", length = 200)
	private String dismissedNote;

	@Builder
	private RawScholarship(
		Scholarship scholarship,
		String source,
		String sourceUrl,
		String sourceId,
		Map<String, Object> rawJson,
		String rawHtml,
		ParseStatus parseStatus,
		String parseError
	) {
		this.scholarship = scholarship;
		this.source = source;
		this.sourceUrl = sourceUrl;
		this.sourceId = sourceId;
		this.rawJson = rawJson;
		this.rawHtml = rawHtml;
		this.crawledAt = LocalDateTime.now();
		this.parseStatus = parseStatus == null ? ParseStatus.PENDING : parseStatus;
		this.parseError = parseError;
	}

	public void markParsed(Scholarship scholarship) {
		this.scholarship = scholarship;
		this.parseStatus = ParseStatus.PARSED;
		this.parseError = null;
	}

	public void markFailed(String parseError) {
		this.parseStatus = ParseStatus.FAILED;
		this.parseError = parseError;
	}

	public void markSkipped(String parseError) {
		markSkipped(parseError, ParseStatus.SKIPPED);
	}

	/**
	 * 건너뜀. 사유에 따라 상태를 나눠 남긴다.
	 *
	 * <p>{@link ParseStatus#IMAGE_ONLY} 는 나중에 OCR·이미지 모델로 다시 볼 대상이라
	 * 그냥 건너뛴 것과 섞이면 골라낼 수 없다.
	 */
	public void markSkipped(String parseError, ParseStatus status) {
		this.scholarship = null;
		this.parseStatus = status;
		this.parseError = parseError;
	}

	/** 실패 재처리 대상에서 만료 처리(숨기기). 파싱 상태는 그대로 둔다. */
	public void dismiss(RawDismissReason reason, String note, LocalDateTime at) {
		this.dismissedAt = at;
		this.dismissedReason = reason;
		this.dismissedNote = note;
	}

	/** 만료 처리 되돌리기. */
	public void clearDismissal() {
		this.dismissedAt = null;
		this.dismissedReason = null;
		this.dismissedNote = null;
	}

	public boolean isDismissed() {
		return dismissedAt != null;
	}

	/**
	 * 만료 처리된 원본을 수집기가 다시 만났을 때: 본문만 갱신한다. 파싱 상태·오류·만료 표시는 건드리지 않는다
	 * ({@link #updateRawData} 는 상태를 PENDING 으로 되돌리므로 쓰지 않는다).
	 */
	public void refreshDismissedBody(String sourceUrl, Map<String, Object> rawJson) {
		this.sourceUrl = sourceUrl;
		this.rawJson = rawJson;
		this.crawledAt = LocalDateTime.now();
	}

	public void updateRawData(String sourceUrl, Map<String, Object> rawJson) {
		this.sourceUrl = sourceUrl;
		this.rawJson = rawJson;
		this.crawledAt = LocalDateTime.now();
		this.parseStatus = ParseStatus.PENDING;
		this.parseError = null;
	}
}
