package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.repository.FamilyTypeRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 공공데이터 조건 원문에서 <b>마스터 라벨 후보</b>를 뽑는다.
 *
 * <p>대학공지는 LLM 이 본문을 읽고 라벨을 준다. 공공데이터는 그럴 필요가 없다 —
 * 한국장학재단이 이미 필드를 나눠서 주기 때문이다. 890건에 LLM 을 태우는 건 돈을 태우는 것이고,
 * 무엇보다 <b>같은 입력에 매번 같은 답이 나오지 않는다.</b> 여기서는 규칙으로 뽑는다.
 *
 * <p>두 가지 모양을 다룬다.
 *
 * <ul>
 *   <li><b>열거형</b>({@code 학과구분}, {@code 학자금유형구분}) — {@code "공학계열,자연계열"} 처럼
 *       구분자로 이어 붙여 온다. 쪼개기만 하면 된다.</li>
 *   <li><b>서술형</b>({@code 지역거주여부}, {@code 특정자격}) — {@code "전라남도 나주시 거주자"} 같은
 *       문장이다. 마스터에 있는 이름이 문장 안에 나오는지 훑는다.</li>
 * </ul>
 *
 * <p>확실하지 않으면 <b>아무것도 내지 않는다.</b> 라벨이 없으면 그 조건은 판정 불가로 남아
 * 아무도 배제하지 않지만, 잘못된 라벨은 자격 있는 학생을 조용히 탈락시킨다. 두 실패는 무게가 다르다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConditionLabelExtractor {

	/** {@code "공학계열, 자연계열 및 의약계열"} → 세 조각. */
	private static final String ENUM_DELIMITER = "\\s*(?:,|/|·|;|\\||및|또는|그리고)\\s*";

	/** 마스터 이름이 두 글자 미만이면 아무 문장에나 걸린다. */
	private static final int MIN_SCAN_LENGTH = 2;

	private final RegionRepository regionRepository;
	private final FamilyTypeRepository familyTypeRepository;

	public List<String> extract(ConditionType type, String valueString) {
		if (!StringUtils.hasText(valueString)) {
			return List.of();
		}
		String text = valueString.replaceAll("\\s+", " ").trim();
		return switch (type) {
			case MAJOR_FIELD, FINANCIAL_AID_TYPE -> splitEnumeration(text);
			case REGION_RESIDENCY -> scanRegions(text);
			case SPECIFIC_QUALIFICATION -> scanFamilyTypes(text);
			// 나머지는 대조할 마스터가 없다. 수치나 원문으로 판정한다.
			default -> List.of();
		};
	}

	/** 구분자로 이어 붙은 열거를 쪼갠다. */
	private List<String> splitEnumeration(String text) {
		Set<String> labels = new LinkedHashSet<>();
		for (String piece : text.split(ENUM_DELIMITER)) {
			String label = piece.trim();
			if (label.length() >= MIN_SCAN_LENGTH) {
				labels.add(label);
			}
		}
		return List.copyOf(labels);
	}

	/**
	 * 문장에서 지역명을 찾는다.
	 *
	 * <p>시군구를 먼저 본다. {@code "전라남도 나주시 거주자"} 에서 시도까지 같이 내면 참조 집합이
	 * {@code 전남 OR 나주시} 가 되는데, 나주시는 전남에 속하므로 <b>사실상 전남 전체가 통과</b>한다.
	 * 조건을 넓히는 방향이라 조용히 틀린다. 그래서 시군구가 잡힌 시도는 따로 내지 않는다.
	 *
	 * <p>{@code "중구"} 처럼 여러 시도에 있는 이름은 시도가 문장에 함께 있을 때만 인정한다.
	 * 없으면 어느 중구인지 알 방법이 없고, 하나를 고르면 그냥 틀린 답이다.
	 */
	private List<String> scanRegions(String text) {
		Set<String> labels = new LinkedHashSet<>();
		Set<Long> parentsCoveredBySigungu = new LinkedHashSet<>();
		List<Region> all = regionRepository.findAll();

		List<Region> sigungu = all.stream().filter(region -> region.getParent() != null).toList();
		List<Region> sido = all.stream().filter(region -> region.getParent() == null).toList();

		for (Region region : sigungu) {
			String name = region.getName();
			if (name.length() < MIN_SCAN_LENGTH || !text.contains(name)) {
				continue;
			}
			Region parent = region.getParent();
			boolean unique = sigungu.stream().filter(other -> other.getName().equals(name)).count() == 1;
			if (!unique && !mentionsSido(text, parent.getName())) {
				log.debug("[ConditionLabel] 시도 없이 중복 시군구라 건너뜀. name={}", name);
				continue;
			}
			labels.add(parent.getName() + " " + name);
			parentsCoveredBySigungu.add(parent.getId());
		}

		for (Region region : sido) {
			if (!parentsCoveredBySigungu.contains(region.getId()) && mentionsSido(text, region.getName())) {
				labels.add(region.getName());
			}
		}
		return List.copyOf(labels);
	}

	/** 마스터는 {@code "서울"} 로 짧게 갖고 있고 공공데이터는 {@code "서울특별시"} 로 준다. 둘 다 본다. */
	private boolean mentionsSido(String text, String shortName) {
		return text.contains(shortName);
	}

	/**
	 * 문장에서 가정형태·본인해당 자격을 찾는다.
	 *
	 * <p>{@code "차상위 계층"}(마스터)과 {@code "차상위계층"}(공고문)은 같은 말이라 공백을 지우고 견준다.
	 * 찾으면 <b>마스터 표기</b>를 낸다 — 해석은 이름으로 하므로 원문 표기를 그대로 넘기면 못 찾는다.
	 */
	private List<String> scanFamilyTypes(String text) {
		String squeezed = text.replaceAll("\\s+", "");
		List<String> labels = new ArrayList<>();
		for (FamilyType familyType : familyTypeRepository.findAll()) {
			String name = familyType.getName();
			if (name == null || name.length() < MIN_SCAN_LENGTH) {
				continue;
			}
			if (squeezed.contains(name.replaceAll("\\s+", "")) && !labels.contains(name)) {
				labels.add(name);
			}
		}
		return List.copyOf(labels);
	}
}
