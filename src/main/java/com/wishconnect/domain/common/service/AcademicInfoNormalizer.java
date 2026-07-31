package com.wishconnect.domain.common.service;

import org.springframework.util.StringUtils;

/**
 * 학사정보 공공데이터 값 정규화 유틸.
 * 동기화 서비스(중복 판정)와 저장 담당(엔티티 생성) 양쪽에서 같은 규칙을 써야 해서 분리했다.
 */
final class AcademicInfoNormalizer {

	private AcademicInfoNormalizer() {
	}

	/** 앞뒤 공백 제거. 빈 값이면 null 을 돌려준다. */
	static String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/** 공공데이터의 행정구역 표기(서울특별시 등)를 region 테이블 표기(서울)로 맞춘다. */
	static String toRegionName(String value) {
		String normalized = normalize(value);
		if (normalized == null) {
			return null;
		}
		return switch (normalized) {
			case "서울특별시" -> "서울";
			case "부산광역시" -> "부산";
			case "대구광역시" -> "대구";
			case "인천광역시" -> "인천";
			case "광주광역시" -> "광주";
			case "대전광역시" -> "대전";
			case "울산광역시" -> "울산";
			case "세종특별자치시" -> "세종";
			case "경기도" -> "경기";
			case "강원특별자치도", "강원도" -> "강원";
			case "충청북도" -> "충북";
			case "충청남도" -> "충남";
			case "전북특별자치도", "전라북도" -> "전북";
			case "전라남도" -> "전남";
			case "경상북도" -> "경북";
			case "경상남도" -> "경남";
			case "제주특별자치도", "제주도" -> "제주";
			default -> normalized;
		};
	}
}
