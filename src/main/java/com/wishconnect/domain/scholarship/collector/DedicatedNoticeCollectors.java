package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.dto.CollectResultResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/*
전용 수집기를 묶어 UnivNoticeCollector 와 같은 방식으로 부를 수 있게 하는 레지스트리입니다.

UnivNoticeCollector 는 게시판 구조가 같은 대학들을 application.yml 설정만으로 처리합니다.
반면 아래 대학들은 공통 규칙으로 묶이지 않아 대학마다 클래스를 따로 두었습니다.
  - 고려대  : 목록 링크가 jf_view() JS 호출, 페이지네이션이 POST
  - 서강대  : Nuxt SPA 라 화면 대신 내부 REST API 를 호출
  - 성균관대: 본문이 이스케이프된 HTML 이라 두 번 파싱해야 함
  - 한양대  : Liferay 포틀릿, 신청기간이 메타 필드로 분리
  - 중앙대  : 목록·상세가 모두 AJAX(JSON POST)
  - 경희대  : 목록 링크가 view() JS 호출, 장학·근로 게시판이 menuNo 로만 갈림

호출부(어드민 컨트롤러·스케줄러)가 대학이 늘 때마다 바뀌지 않도록 여기서만 등록합니다.
공통 추상 클래스로 묶을지는 전체 대학(약 20개)이 모인 뒤 팀과 논의할 사안이라, 지금은
각 수집기를 건드리지 않고 호출 지점만 모으는 선에서 둡니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DedicatedNoticeCollectors {

	private final KoreaUnivNoticeCollector koreaUnivNoticeCollector;
	private final SogangNoticeCollector sogangNoticeCollector;
	private final SkkuNoticeCollector skkuNoticeCollector;
	private final HanyangNoticeCollector hanyangNoticeCollector;
	private final CauNoticeCollector cauNoticeCollector;
	private final KhuNoticeCollector khuNoticeCollector;

	/** 어드민 API 의 {code} 로 쓰는 값. UnivNoticeCollector 의 사이트 코드와 겹치지 않게 둔다. */
	private Map<String, IntFunction<CollectResultResponse>> registry() {
		Map<String, IntFunction<CollectResultResponse>> collectors = new LinkedHashMap<>();
		collectors.put("korea", koreaUnivNoticeCollector::collect);
		collectors.put("sogang", sogangNoticeCollector::collect);
		collectors.put("skku", skkuNoticeCollector::collect);
		collectors.put("hanyang", hanyangNoticeCollector::collect);
		collectors.put("cau", cauNoticeCollector::collect);
		collectors.put("khu", khuNoticeCollector::collect);
		return collectors;
	}

	/** 지원하는 사이트 코드 목록. 어드민 API 문서와 오류 메시지에서 쓴다. */
	public Set<String> codes() {
		return registry().keySet();
	}

	/**
	 * 코드로 한 대학만 수집한다.
	 *
	 * @return 코드가 없으면 {@code Optional.empty()} — 호출부가 다른 수집기로 넘길 수 있게 한다.
	 */
	public Optional<CollectResultResponse> collectByCode(String code, int pages) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		IntFunction<CollectResultResponse> collector = registry().get(code.toLowerCase());
		return collector == null ? Optional.empty() : Optional.of(collector.apply(pages));
	}

	/**
	 * 전체 대학을 수집한다.
	 *
	 * <p>한 대학이 실패해도(사이트 개편·네트워크 오류) 나머지는 계속 수집한다.
	 * 배치에서 한 곳 때문에 그날 수집 전체가 날아가는 것을 막기 위함이다.
	 */
	public List<CollectResultResponse> collectAll(int pages) {
		List<CollectResultResponse> results = new ArrayList<>();
		registry().forEach((code, collector) -> {
			try {
				results.add(collector.apply(pages));
			} catch (Exception e) {
				log.warn("[DedicatedCollectors] {} 수집 실패(나머지 대학은 계속): {}", code, e.getMessage());
			}
		});
		return results;
	}
}
