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

	public void updateRawData(String sourceUrl, Map<String, Object> rawJson) {
		this.sourceUrl = sourceUrl;
		this.rawJson = rawJson;
		this.crawledAt = LocalDateTime.now();
		this.parseStatus = ParseStatus.PENDING;
		this.parseError = null;
	}
}
