package com.wishconnect.domain.scholarship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조건이 가리키는 마스터 값 하나.
 *
 * <p>조건 원문을 <b>대조 가능한 값</b>으로 바꾸기 위한 것이다. 지금 지역 매칭이
 * {@code raw.contains(지역명)} 인데, "서구"는 대구·인천·광주·대전·부산에 다 있어 엉뚱한 사람이
 * 통과한다. 게다가 문자열로는 "아니다"를 말할 수 없어 안 맞으면 {@code UNKNOWN}(=통과)이 된다.
 * ID 로 비교하면 둘 다 사라진다.
 *
 * <p>한 조건에 여러 개가 붙는다. "기초생활수급자 <b>또는</b> 차상위계층" 처럼 OR 로 묶인 요건이
 * 흔하기 때문이다. 판정은 <b>사용자 값 집합과의 교집합이 비어 있지 않은가</b>로 한다.
 * 행을 나눠 저장하면 {@code mismatchCount} 규칙상 AND 로 뒤집혀 의미가 반대가 된다.
 *
 * <p>두 가지 형태를 함께 담는다. 마스터가 테이블이면 {@code refId}(지역·가정형태·관심분야),
 * enum 이면 {@code refCode}(전공계열·재학상태). 하나만 채워진다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConditionRef {

	/** 테이블 기반 마스터의 PK. enum 기반이면 null. */
	@Column(name = "ref_id")
	private Long refId;

	/** enum 기반 마스터의 이름. 테이블 기반이면 null. */
	@Column(name = "ref_code", length = 40)
	private String refCode;

	private ConditionRef(Long refId, String refCode) {
		this.refId = refId;
		this.refCode = refCode;
	}

	public static ConditionRef ofId(Long refId) {
		return new ConditionRef(refId, null);
	}

	public static ConditionRef ofCode(String refCode) {
		return new ConditionRef(null, refCode);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ConditionRef ref)) {
			return false;
		}
		return java.util.Objects.equals(refId, ref.refId)
				&& java.util.Objects.equals(refCode, ref.refCode);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(refId, refCode);
	}
}
