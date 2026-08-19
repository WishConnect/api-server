package com.wishconnect.domain.scholarship.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 화면 상단 요약. 수집 파이프라인이 지금 어떤 상태인지 한 화면에서 보기 위한 집계다.
 *
 * <p>배치 실행 이력을 저장하는 테이블이 없어(현재는 로그로만 남는다) 기존 테이블을 집계해서 만든다.
 * 그래서 "이번 배치가 몇 건 실패했는가"는 알 수 없고, "지금 데이터가 어떤 상태인가"를 보여준다.
 * 3·4단계에서 파싱을 고칠 때 {@code sourceQuality} 의 채움률이 오르는 것으로 효과를 확인한다.
 */
public record AdminOverviewResponse(
		LocalDateTime generatedAt,
		RawSummary raw,
		ScholarshipSummary scholarship,
		List<SourceQuality> sourceQuality
) {

	/** 원본 수집 데이터의 파싱 상태 분포. */
	public record RawSummary(
			long total,
			long pending,
			long parsed,
			long skipped,
			long imageOnly,
			long failed
	) {
	}

	public record ScholarshipSummary(
			long total,
			long active,
			long softDeleted,
			long open,
			long upcoming,
			long closed,

			/**
			 * 마감일이 없어 자동으로 닫을 수 없는 공고.
			 *
			 * <p>"충원 시 마감" 처럼 마감일이 없는 것이 정상인 공고가 있다. 날짜가 없으니
			 * 배치가 손댈 수 없어 그대로 두면 작년 것이 영원히 목록에 남는다. 여기 모아 두고
			 * 사람이 확인해 닫는다.
			 */
			long alwaysOpen,
			long createdToday,
			LocalDateTime lastSyncedAt
	) {
	}

	/**
	 * 출처별 데이터 품질. 공공 API 와 대학 크롤링은 결함이 정반대라(공공 API 는 본문이 좋고 링크가 나쁘며,
	 * 크롤링은 링크가 좋고 본문이 없다) 출처를 나눠서 봐야 어디를 고쳐야 하는지 알 수 있다.
	 */
	public record SourceQuality(
			String source,
			long total,
			long withSummary,
			long withAmount,
			long withHomepageUrl,
			long withPoster
	) {

		public int summaryRate() {
			return percent(withSummary, total);
		}

		public int amountRate() {
			return percent(withAmount, total);
		}

		public int homepageUrlRate() {
			return percent(withHomepageUrl, total);
		}

		public int posterRate() {
			return percent(withPoster, total);
		}

		private static int percent(long value, long total) {
			return total == 0 ? 0 : (int) Math.round(value * 100.0 / total);
		}
	}
}
