package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.entity.School;
import com.wishconnect.domain.common.repository.SchoolRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 공고가 쓰는 학교 표기를 {@link School} 마스터로 해석한다.
 *
 * <p>같은 학교가 자료마다 다르게 적힌다 — {@code "인천대"}, {@code "인천대학교"},
 * {@code "국립인천대학교"}, {@code "인천 대학교"}. 문자열끼리 견주면 표기가 어긋나는 순간
 * 대조가 통째로 빗나가므로, 한 번 ID 로 바꿔 두고 그다음부터는 ID 로만 비교한다.
 *
 * <p><b>애매하면 해석하지 않는다.</b> 정규화한 이름이 여러 학교에 걸리면 {@code null} 을 돌려준다.
 * 잘못 지정한 학교는 자격 있는 학생을 조용히 떨어뜨리는데, 사용자도 우리도 알아챌 방법이 없다.
 * 지정하지 않으면 관문이 걸리지 않을 뿐이라 실패의 방향이 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchoolResolver {

	private final SchoolRepository schoolRepository;

	/**
	 * 학교명으로 마스터를 찾는다. 특정할 수 없으면 null.
	 *
	 * @param name 공고의 {@code provider} 나 수집기 설정의 학교명
	 */
	public School byName(String name) {
		if (!StringUtils.hasText(name)) {
			return null;
		}
		School exact = schoolRepository.findFirstByName(name.trim()).orElse(null);
		if (exact != null) {
			return exact;
		}

		String normalized = normalize(name);
		if (normalized.isBlank()) {
			return null;
		}
		// 마스터는 수백 건이라 전량 대조해도 부담이 없다. 정규화 비교는 SQL 로 표현하기 번거롭다.
		List<School> matched = schoolRepository.findAll().stream()
				.filter(school -> normalize(school.getName()).equals(normalized))
				.toList();
		if (matched.size() == 1) {
			return matched.get(0);
		}
		if (matched.size() > 1) {
			log.debug("[SchoolResolver] 이름이 여러 학교에 걸려 해석하지 않는다. name={} 후보={}",
					name, matched.size());
		}
		return null;
	}

	/**
	 * 표기 차이를 흡수한다. 공백을 지우고, 꼬리의 {@code 대학교}·{@code 대학}을 {@code 대} 로 맞춘다.
	 *
	 * <p>{@code 국립}·{@code 사립} 같은 설립 구분 접두어도 뗀다. 공고는 붙여 쓰고 마스터는
	 * 빼고 쓰는 경우가 섞여 있다.
	 */
	static String normalize(String name) {
		if (name == null) {
			return "";
		}
		return name.replaceAll("\\s+", "")
				.replaceFirst("^(국립|공립|사립)", "")
				.replaceAll("(대학교|대학)$", "대");
	}
}
