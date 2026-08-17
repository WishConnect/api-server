package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 사용자가 고른 거주지역 문자열을 {@link Region} 으로 바꾼다.
 *
 * <p>시군구가 들어오면서 이름만으로는 한 건을 특정할 수 없게 됐다. {@code 중구} 는 서울·부산·대구·
 * 인천·대전·울산에 모두 있고 {@code 동구}·{@code 서구}·{@code 남구}·{@code 북구} 도 마찬가지다.
 * 그래서 아래 순서로 해석한다.
 *
 * <ol>
 *   <li>{@code "서울 광진구"} 처럼 시도와 함께 오면 그 조합으로 정확히 찾는다(권장).</li>
 *   <li>시도 이름 하나면 시도를 돌려준다({@code "서울"}).</li>
 *   <li>시군구 이름 하나만 왔고 전국에서 유일하면 그것을 쓴다({@code "광진구"}).</li>
 *   <li>유일하지 않으면 특정할 수 없으므로 {@code null}. 호출측이 400 으로 처리한다.</li>
 * </ol>
 *
 * <p>프론트가 시군구까지 고르는 경우에는 목록 API 가 준 {@code regionId} 를 그대로 보내는 쪽이
 * 가장 확실하다. 이름 해석은 기존 요청(시도명만 보내던 것)과의 호환을 위해 남긴다.
 */
@Component
@RequiredArgsConstructor
public class RegionResolver {

	private final RegionRepository regionRepository;

	/** 목록 API 가 준 id. 이름 해석보다 우선한다. */
	public Region byId(Long regionId) {
		if (regionId == null) {
			return null;
		}
		return regionRepository.findById(regionId).orElse(null);
	}

	/**
	 * 이름으로 지역을 찾는다. 특정할 수 없으면 null.
	 *
	 * @param value {@code "서울"}, {@code "서울 광진구"}, {@code "광진구"} 등
	 */
	public Region byName(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim().replaceAll("\\s+", " ");

		// "서울특별시 광진구" 처럼 시도+시군구로 온 경우
		int lastSpace = trimmed.lastIndexOf(' ');
		if (lastSpace > 0) {
			String parent = normalizeSido(trimmed.substring(0, lastSpace));
			String child = trimmed.substring(lastSpace + 1);
			Region matched = regionRepository.findByNameAndParent_Name(child, parent).orElse(null);
			if (matched != null) {
				return matched;
			}
		}

		// 시도 이름 하나
		String sido = normalizeSido(trimmed);
		Region topLevel = regionRepository.findByNameAndParentIsNull(sido).orElse(null);
		if (topLevel != null) {
			return topLevel;
		}

		// 시군구 이름 하나. 전국에서 유일할 때만 인정한다.
		List<Region> candidates = regionRepository.findAllByName(trimmed);
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	/** 공공데이터·외부 API 가 주는 정식 명칭을 마스터의 짧은 표기로 맞춘다. */
	public static String normalizeSido(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		return switch (value.trim()) {
			case "서울특별시" -> "서울";
			case "부산광역시" -> "부산";
			case "대구광역시" -> "대구";
			case "인천광역시" -> "인천";
			case "광주광역시" -> "광주";
			case "대전광역시" -> "대전";
			case "울산광역시" -> "울산";
			case "세종특별자치시", "세종시" -> "세종";
			case "경기도" -> "경기";
			case "강원특별자치도", "강원도" -> "강원";
			case "충청북도" -> "충북";
			case "충청남도" -> "충남";
			case "전북특별자치도", "전라북도" -> "전북";
			case "전라남도" -> "전남";
			case "경상북도" -> "경북";
			case "경상남도" -> "경남";
			case "제주특별자치도", "제주도" -> "제주";
			default -> value.trim();
		};
	}
}
