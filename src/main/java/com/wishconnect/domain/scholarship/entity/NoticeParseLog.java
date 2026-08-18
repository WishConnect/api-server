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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LLM 파싱 1회의 기록. 재파싱할 때마다 행이 하나 쌓인다.
 *
 * <p>남기는 이유는 셋이다.
 *
 * <ul>
 *   <li><b>정확도 측정</b> — 프롬프트를 고쳤을 때 나아졌는지 판단하려면 이전 결과가 있어야 한다.
 *       {@code promptVersion} 없이 결과만 쌓으면 무엇과 비교하는지 알 수 없다.</li>
 *   <li><b>실패 원인 추적</b> — 형식이 깨졌는지, 잘렸는지, 모델이 거부했는지는 응답 원문을 봐야 안다.
 *       그래서 성공은 정제된 {@link com.wishconnect.domain.scholarship.dto.ParsedNotice},
 *       <b>실패는 응답 원문</b>을 저장한다. 실패 시 정제 객체는 애초에 만들어지지 않는다.</li>
 *   <li><b>원본 없이도 남는 기록</b> — {@code raw_html} 을 나중에 지우게 되더라도 파싱 결과와
 *       그때의 모델·프롬프트는 남는다. 다만 본문이 없으면 프롬프트를 바꿔 다시 뽑는 것은 불가능하다.</li>
 * </ul>
 *
 * <p>{@code raw_scholarship} 을 FK 로 잡지 않고 id 만 들고 있다. 원본이 지워져도 기록은 남아야 하기 때문이다.
 */
@Getter
@Entity
@Table(name = "notice_parse_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeParseLog extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 대상 {@code raw_scholarship.id}. FK 를 걸지 않는다(원본 삭제와 무관하게 보존). */
	@Column(name = "raw_scholarship_id", nullable = false)
	private Long rawScholarshipId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ParseStatus status;

	@Column(name = "model_id", length = 60)
	private String modelId;

	/** 프롬프트 개정 번호. 결과를 비교하려면 어떤 프롬프트로 뽑았는지가 함께 있어야 한다. */
	@Column(name = "prompt_version", length = 20)
	private String promptVersion;

	/** 본문이 상한을 넘어 잘린 채 호출됐는지. 성공한 건에도 남긴다(조용한 정보 손실 추적). */
	/**
	 * 본문이 이미지뿐이라 {@code alt} 설명으로 대체했는가.
	 *
	 * <p>이런 공고는 마감일 정도만 건지고 조건·제출서류는 비어 있다. OCR 을 붙일 때 대상이
	 * 되는데, 상태는 PARSED 라 {@code IMAGE_ONLY} 로는 골라낼 수 없어 따로 남긴다.
	 */
	@Column(name = "body_from_image_alt", nullable = false)
	private boolean bodyFromImageAlt;

	@Column(name = "body_truncated", nullable = false)
	private boolean bodyTruncated;

	/** 자르기 전 정제 본문 길이. 상한을 올릴지 판단하는 근거가 된다. */
	@Column(name = "body_length")
	private Integer bodyLength;

	/** 성공 시 정제 결과(JSON). 실패면 null. */
	@Column(name = "parsed_json", columnDefinition = "TEXT")
	private String parsedJson;

	/** 실패 시 LLM 응답 원문. 성공이면 null — 정제 결과에서 언제든 되돌릴 수 있으므로 중복 저장하지 않는다. */
	@Column(name = "raw_response", columnDefinition = "TEXT")
	private String rawResponse;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Builder
	private NoticeParseLog(Long rawScholarshipId, ParseStatus status, String modelId,
			String promptVersion, boolean bodyTruncated, boolean bodyFromImageAlt, Integer bodyLength,
			String parsedJson, String rawResponse, String errorMessage) {
		this.rawScholarshipId = rawScholarshipId;
		this.status = status;
		this.modelId = modelId;
		this.promptVersion = promptVersion;
		this.bodyTruncated = bodyTruncated;
		this.bodyFromImageAlt = bodyFromImageAlt;
		this.bodyLength = bodyLength;
		this.parsedJson = parsedJson;
		this.rawResponse = rawResponse;
		this.errorMessage = errorMessage;
	}
}
