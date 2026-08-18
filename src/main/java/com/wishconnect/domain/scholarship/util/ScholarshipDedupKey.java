package com.wishconnect.domain.scholarship.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 정제 장학금({@code scholarship}) 한 행의 신원.
 *
 * <p><b>같은 공지를 두 번 수집했을 때 같은 행으로 모으는 것</b>이 유일한 목적이다. 수집 배치가
 * 매일 도니까 어제 본 공지를 오늘 또 본다. 그때 새 행을 만들면 목록에 같은 게 여러 번 뜬다.
 *
 * <p>예전에는 {@code 출처|제목|모집기간} 으로 만들었는데, 제목이 <b>불안정한 재료</b>였다.
 * 게시판 스킨이 셀렉터에 안 걸리면 제목 추출이 페이지 문서 제목으로 떨어지고, 그러면 그 학교의
 * 모든 공지가 같은 제목("대학공지 공유팝업 열기 카카오 공유하기…")을 갖는다. 기간까지 못 뽑으면
 * 재료 셋이 전부 같아져 <b>서로 다른 공지 9건이 한 행에 묶였다</b>(운영에서 실제로 발생).
 *
 * <p>지금은 출처가 이미 가진 고유 번호를 쓴다. 게시글 번호는 겹칠 수가 없고, 제목이 엉터리든
 * 기간이 비었든 영향을 받지 않는다. {@code raw_scholarship} 에도 이미 같은 조합으로
 * {@code UNIQUE (source, source_id)} 가 걸려 있어, 원본과 정제가 같은 기준으로 정렬된다.
 *
 * <p><b>서로 다른 출처에 실린 같은 장학금을 하나로 합치는 일은 여기서 하지 않는다.</b>
 * 해시는 완전히 같아야 같다고 하므로 표기가 조금만 달라도("2026학년도 1학기" vs "2026-1학기")
 * 놓치고, 재료를 느슨하게 하면 반대로 캠퍼스만 다른 별개 모집이 붙는다. 그건 유사도로 후보를
 * 찾고 사람이 승인하는 {@code ScholarshipDedupService} 의 몫이다.
 */
public final class ScholarshipDedupKey {

	private ScholarshipDedupKey() {
	}

	/**
	 * @param source   출처 코드 (예: {@code UNIV_HALLYM})
	 * @param sourceId 출처가 매긴 공지 고유 번호 (예: 게시글 번호 {@code 387838})
	 */
	public static String of(String source, String sourceId) {
		if (source == null || sourceId == null || sourceId.isBlank()) {
			throw new IllegalArgumentException(
					"공지 신원을 만들 수 없습니다. source=" + source + " sourceId=" + sourceId);
		}
		return sha256(source + "|" + sourceId.trim());
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of()
					.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
					.substring(0, 64);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 사용 불가", e);
		}
	}
}
