package com.wishconnect.domain.scholarship.entity;

/*
raw_scholarship 원본 데이터의 파싱 상태를 표현합니다.
원본 저장 후 정제 테이블(scholarship)로 변환하는 단계에서 상태 추적에 사용합니다.
 */
public enum ParseStatus {
	PENDING,
	PARSED,
	FAILED,

	/** 본문을 못 찾았거나 내용이 없어 건너뜀. 다시 시도해도 결과가 같다. */
	SKIPPED,

	/**
	 * 본문이 <b>포스터 이미지 한 장</b>이라 읽을 글자가 없어 건너뜀.
	 *
	 * <p>{@link #SKIPPED} 와 나눠 두는 이유가 있다. 이쪽은 <b>내용이 없는 게 아니라 형식이 다른</b>
	 * 것이다. 공고 내용은 이미지 안에 다 들어 있고, OCR 이나 이미지를 읽는 모델을 붙이면 살릴 수 있다.
	 * 섞어 두면 나중에 그 대상을 골라낼 방법이 없다.
	 *
	 * <p>실측(2026-08-18): 홍익대 12건, 서울시립대 2건이 이 상태다. 이미지에 설명({@code alt})을
	 * 달아 둔 곳은 그 글을 본문으로 쓰므로 여기 오지 않는다(한국외대).
	 */
	IMAGE_ONLY
}
