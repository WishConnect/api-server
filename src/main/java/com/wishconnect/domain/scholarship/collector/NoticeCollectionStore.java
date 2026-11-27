package com.wishconnect.domain.scholarship.collector;

import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.util.NoticeHtmlExtractor;
import com.wishconnect.domain.scholarship.util.NoticeContent;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 학교별 HTTP 수집 방식은 유지하고 원본 갱신과 재파싱 예약 정책을 공유한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoticeCollectionStore {

	private final RawScholarshipRepository repository;

	@Value("${scholarship.collect.refresh-limit-per-source:20}")
	private int refreshLimit = 20;

	public boolean existsBySourceAndSourceId(String source, String sourceId) {
		return repository.existsBySourceAndSourceId(source, sourceId);
	}

	@Transactional
	public RawScholarship save(RawScholarship incoming) {
		var existing = repository.findBySourceAndSourceId(incoming.getSource(), incoming.getSourceId());
		if (existing.isEmpty()) return repository.save(incoming);
		RawScholarship raw = existing.get();
		// 일시적인 빈 페이지·차단 페이지를 정상 원문 위에 저장하지 않는다.
		if (incoming.getParseStatus() != ParseStatus.PENDING
				|| NoticeHtmlExtractor.body(Jsoup.parse(incoming.getRawHtml() == null ? "" : incoming.getRawHtml()), null)
						.isEmpty()) return raw;
		boolean changed = !Objects.equals(NoticeContent.comparable(raw.getRawHtml(), raw.getSourceUrl()),
				NoticeContent.comparable(incoming.getRawHtml(), incoming.getSourceUrl()));
		raw.refreshNotice(incoming.getSourceUrl(), incoming.getRawHtml(), changed);
		return repository.save(raw);
	}

	@Transactional
	public int refreshDue(String source, Refresher refresher) {
		if (refreshLimit <= 0) return 0;
		LocalDateTime now = LocalDateTime.now();
		int refreshed = 0;
		for (var raw : repository.findNoticeRefreshTargets(source, now.minusDays(1), now.minusDays(60),
				PageRequest.of(0, refreshLimit))) {
			try {
				if (refresher.refresh(raw)) refreshed++;
			} catch (Exception e) {
				log.warn("[NoticeRefresh] 원문 갱신 실패 source={} rawId={} reason={}", source, raw.getId(),
						e.getClass().getSimpleName());
			}
		}
		return refreshed;
	}

	@FunctionalInterface
	public interface Refresher {
		boolean refresh(RawScholarship raw) throws Exception;
	}
}
