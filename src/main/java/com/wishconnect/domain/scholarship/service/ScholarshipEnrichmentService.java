package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.insight.client.NaverSearchClient;
import com.wishconnect.domain.insight.dto.NaverSearchItem;
import com.wishconnect.domain.insight.dto.NaverSearchResponse;
import com.wishconnect.domain.scholarship.dto.EnrichmentResult;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipDocument;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.DetailPageMatcher;
import com.wishconnect.domain.scholarship.util.ScholarshipPageParser;
import com.wishconnect.domain.scholarship.util.ScholarshipPageParser.Attachment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/*
공공데이터가 주지 않는 3가지(상세페이지 URL·제출서류 첨부·포스터 이미지)를 자동으로 채운다.

왜 필요한가: 원문 22개 필드에 상세 URL·이미지·첨부파일이 아예 없다(엔드포인트 69개 전수 확인).
'홈페이지 주소' 하나뿐인데 그 값이 기관 메인이라 사용자가 눌러도 장학금을 볼 수 없다.
파싱으로는 해결이 안 되고, 400건 가까이를 사람이 손으로 채우는 것도 현실성이 없다.

흐름
  1) "상품명 + 운영기관명" 으로 웹 검색
  2) 결과의 호스트를 공공데이터의 '홈페이지 주소' 호스트와 대조해 점수를 매긴다(DetailPageMatcher)
  3) 임계값 이상만 채택해 그 페이지를 크롤링 -> og:image, 첨부파일 링크 추출
  4) 임계값 미만이면 아무것도 반영하지 않고 시도 시각만 남긴다(사람이 관리자 화면에서 처리)

안전장치
  - 자동 반영 임계값(70점). 틀린 정보가 붙는 건 정보가 없는 것보다 나쁘다.
  - 요청 간 지연. 남의 사이트를 두들기지 않는다.
  - 한 건이 실패해도 다음 건으로 넘어간다. 외부 사이트는 언제든 죽는다.
  - enrichedAt 으로 재시도 주기를 지켜 같은 건을 매번 검색하지 않는다(검색 API 쿼터 보호).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipEnrichmentService {

	/** 검색 결과를 몇 개까지 후보로 볼지. 늘려도 상위 몇 개 밖은 거의 안 맞는다. */
	private static final int SEARCH_DISPLAY = 5;
	/** 한 번 시도한 건은 이 기간이 지나야 다시 본다. 검색 API 쿼터를 아끼기 위함. */
	private static final int RETRY_AFTER_DAYS = 14;
	private static final int CRAWL_TIMEOUT_MS = 10_000;
	private static final String USER_AGENT = "Mozilla/5.0 (compatible; WishConnectBot/1.0)";

	private final ScholarshipRepository scholarshipRepository;
	private final ScholarshipDocumentRepository scholarshipDocumentRepository;
	private final ImageRepository imageRepository;
	private final ImageStorageService imageStorageService;
	private final NaverSearchClient naverSearchClient;

	/** 남의 사이트를 두들기지 않도록 요청 사이에 쉬는 시간(ms). */
	@Value("${scholarship.enrich.delay-ms:1500}")
	private long delayMs;

	/** 포스터 이미지 수집 여부. 저작권 이슈가 생기면 재배포 없이 끌 수 있어야 한다. */
	@Value("${scholarship.enrich.collect-image:true}")
	private boolean collectImage;

	@Transactional
	public EnrichmentResult enrich(int limit) {
		LocalDateTime now = LocalDateTime.now();
		List<Scholarship> targets = scholarshipRepository.findEnrichmentTargets(
				now, now.minusDays(RETRY_AFTER_DAYS), PageRequest.of(0, Math.max(1, limit)));
		if (targets.isEmpty()) {
			return EnrichmentResult.empty();
		}

		int detailFound = 0;
		int imageSaved = 0;
		int documentLinked = 0;
		int skipped = 0;
		List<EnrichmentResult.Skipped> skippedRows = new ArrayList<>();

		for (Scholarship scholarship : targets) {
			try {
				Candidate best = findBestCandidate(scholarship);
				// 시도 시각은 성공·실패와 무관하게 남긴다. 안 그러면 매 배치가 같은 건만 붙잡는다.
				scholarship.applyEnrichment(best == null ? null : best.url());

				if (best == null) {
					skipped++;
					skippedRows.add(new EnrichmentResult.Skipped(
							scholarship.getId(), scholarship.getTitle(), "신뢰할 만한 상세페이지를 찾지 못함"));
					continue;
				}
				detailFound++;

				Document page = fetch(best.url());
				if (page == null) {
					continue;
				}
				if (collectImage && saveposterIfAbsent(scholarship, page)) {
					imageSaved++;
				}
				documentLinked += linkAttachments(scholarship, page);
			} catch (RuntimeException e) {
				// 외부 사이트는 언제든 죽는다. 한 건 때문에 배치 전체를 멈추지 않는다.
				log.warn("[Enrich] 보완 실패 scholarshipId={} : {}", scholarship.getId(), e.getMessage());
				skipped++;
				skippedRows.add(new EnrichmentResult.Skipped(
						scholarship.getId(), scholarship.getTitle(), "처리 중 오류: " + e.getMessage()));
			}
			sleep();
		}

		log.info("[Enrich] 대상={} 상세URL={} 이미지={} 첨부={} 건너뜀={}",
				targets.size(), detailFound, imageSaved, documentLinked, skipped);
		return new EnrichmentResult(
				targets.size(), detailFound, imageSaved, documentLinked, skipped, skippedRows);
	}

	/** 검색 결과 중 점수가 가장 높은 후보. 임계값 미만이면 null(= 채택하지 않음). */
	private Candidate findBestCandidate(Scholarship scholarship) {
		String query = (scholarship.getTitle() + " " + defaultText(scholarship.getProvider())).trim();
		NaverSearchResponse response = naverSearchClient.searchWeb(query, SEARCH_DISPLAY);
		if (response == null || response.items() == null || response.items().isEmpty()) {
			return null;
		}
		return response.items().stream()
				.map(item -> new Candidate(item.link(), item.getCleanTitle(), DetailPageMatcher.score(
						item.link(), item.getCleanTitle(),
						scholarship.getHomepageUrl(), scholarship.getTitle(), scholarship.getProvider())))
				.filter(candidate -> candidate.score() >= DetailPageMatcher.AUTO_APPLY_THRESHOLD)
				.max(Comparator.comparingInt(Candidate::score))
				.orElse(null);
	}

	private Document fetch(String url) {
		try {
			return Jsoup.connect(url).userAgent(USER_AGENT).timeout(CRAWL_TIMEOUT_MS).get();
		} catch (Exception e) {
			log.warn("[Enrich] 페이지를 읽지 못했습니다 url={} : {}", url, e.getMessage());
			return null;
		}
	}

	/** 이미 포스터가 있으면 건드리지 않는다(크롤링 수집분·관리자 업로드분을 덮지 않기 위함). */
	private boolean saveposterIfAbsent(Scholarship scholarship, Document page) {
		if (imageRepository.existsByEntityTypeAndEntityId(
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId())) {
			return false;
		}
		String imageUrl = ScholarshipPageParser.findPosterImageUrl(page);
		if (!StringUtils.hasText(imageUrl)) {
			return false;
		}
		String stored = imageStorageService.storeFromUrl(
				imageUrl, "scholarship", ImageStorageService.ENTITY_TYPE_SCHOLARSHIP,
				scholarship.getId(), scholarship.getTitle());
		if (stored == null) {
			return false;
		}
		// 출처를 남긴다. 저작권 문의가 오면 어디서 가져왔는지 확인하고 지울 수 있어야 한다.
		imageRepository.findFirstByEntityTypeAndEntityIdOrderByIdAsc(
						ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarship.getId())
				.ifPresent(image -> image.updateSourceUrl(imageUrl));
		return true;
	}

	/**
	 * 첨부파일을 기존 제출서류 행에 연결한다.
	 *
	 * <p>서류 이름과 첨부 파일명을 대조해 붙이고, 못 붙인 첨부는 새 행으로 추가하지 않는다.
	 * 공고문·개인정보동의서 같은 게 제출서류 목록에 섞여 들어가면 사용자가 헷갈린다.
	 */
	private int linkAttachments(Scholarship scholarship, Document page) {
		List<Attachment> attachments = ScholarshipPageParser.findAttachments(page);
		if (attachments.isEmpty()) {
			return 0;
		}
		List<ScholarshipDocument> documents =
				scholarshipDocumentRepository.findAllByScholarshipIdOrderByDisplayOrderAsc(scholarship.getId());
		if (documents.isEmpty()) {
			return 0;
		}
		Map<String, Attachment> byNormalizedName = attachments.stream()
				.collect(Collectors.toMap(a -> normalize(a.name()), a -> a, (first, second) -> first));

		int linked = 0;
		for (ScholarshipDocument document : documents) {
			if (StringUtils.hasText(document.getDownloadUrl())) {
				continue;
			}
			String key = normalize(document.getName());
			Attachment matched = byNormalizedName.entrySet().stream()
					.filter(entry -> entry.getKey().contains(key) || key.contains(entry.getKey()))
					.map(Map.Entry::getValue)
					.findFirst()
					.orElse(null);
			if (matched != null) {
				document.updateDownloadUrl(matched.downloadUrl());
				linked++;
			}
		}
		return linked;
	}

	private String normalize(String value) {
		return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
	}

	private String defaultText(String value) {
		return value == null ? "" : value;
	}

	private void sleep() {
		if (delayMs <= 0) {
			return;
		}
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record Candidate(String url, String title, int score) {
	}
}
