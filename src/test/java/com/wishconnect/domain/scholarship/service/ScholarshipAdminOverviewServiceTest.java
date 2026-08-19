package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.AdminOverviewResponse;
import com.wishconnect.domain.scholarship.dto.AdminScholarshipRow;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipSourceAggregate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipAdminOverviewServiceTest {

	@Mock
	private ScholarshipRepository scholarshipRepository;
	@Mock
	private RawScholarshipRepository rawScholarshipRepository;
	@Mock
	private ImageRepository imageRepository;
	@Mock
	private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock
	private ScholarshipDocumentRepository scholarshipDocumentRepository;
	@Mock
	private ImageStorageService imageStorageService;

	@InjectMocks
	private ScholarshipAdminOverviewService service;

	private ScholarshipSourceAggregate aggregate(
			String source, long total, long summary, long amount, long url) {
		return new ScholarshipSourceAggregate() {
			public String getSource() {
				return source;
			}

			public long getTotal() {
				return total;
			}

			public long getWithSummary() {
				return summary;
			}

			public long getWithAmount() {
				return amount;
			}

			public long getWithHomepageUrl() {
				return url;
			}
		};
	}

	private Scholarship scholarship(Long id, String source, String summary, Long amount, String url) {
		Scholarship s = Scholarship.builder()
				.title("미래인재 장학금")
				.provider("위시커넥트")
				.summary(summary)
				.amount(amount)
				.homepageUrl(url)
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.primarySource(source)
				.applicationEndAt(LocalDateTime.now().plusDays(7))
				.build();
		ReflectionTestUtils.setField(s, "id", id);
		return s;
	}

	@Test
	@DisplayName("출처별 품질 집계에 포스터 보유 건수가 합쳐진다")
	void overviewMergesPosterCountsIntoSourceQuality() {
		given(scholarshipRepository.aggregateQualityBySource()).willReturn(List.of(
				aggregate("KOSAF_SCHOLARSHIP", 77, 77, 57, 77),
				aggregate("UNIV_INU", 89, 0, 0, 89)));
		given(imageRepository.findEntityIdsByEntityType(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP))
				.willReturn(List.of(10L, 11L));
		// List.of(new Object[]{..}) 는 varargs 로 풀려버리므로 타입을 명시한다.
		given(scholarshipRepository.countBySourceForIds(any()))
				.willReturn(List.<Object[]>of(new Object[] {"UNIV_INU", 2L}));

		AdminOverviewResponse response = service.overview();

		AdminOverviewResponse.SourceQuality kosaf = response.sourceQuality().get(0);
		AdminOverviewResponse.SourceQuality inu = response.sourceQuality().get(1);
		assertThat(kosaf.withPoster()).isZero();
		assertThat(inu.withPoster()).isEqualTo(2);
	}

	@Test
	@DisplayName("채움률은 백분율로 계산된다")
	void sourceQualityComputesRates() {
		given(scholarshipRepository.aggregateQualityBySource())
				.willReturn(List.of(aggregate("KOSAF_SCHOLARSHIP", 77, 77, 57, 77)));
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());

		AdminOverviewResponse.SourceQuality quality = service.overview().sourceQuality().get(0);

		assertThat(quality.summaryRate()).isEqualTo(100);
		assertThat(quality.amountRate()).isEqualTo(74);
		assertThat(quality.posterRate()).isZero();
	}

	/** 포스터가 하나도 없으면 IN () 이 되어 쿼리가 깨지므로 아예 호출하지 않아야 한다. */
	@Test
	@DisplayName("포스터가 없으면 IN 조회를 하지 않는다")
	void skipsPosterQueryWhenNoPosters() {
		given(scholarshipRepository.aggregateQualityBySource())
				.willReturn(List.of(aggregate("UNIV_INU", 89, 0, 0, 89)));
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());

		service.overview();

		verify(scholarshipRepository, never()).countBySourceForIds(any());
	}

	@Test
	@DisplayName("출처가 null 인 수기 등록분은 MANUAL 로 표기한다")
	void nullSourceIsShownAsManual() {
		given(scholarshipRepository.aggregateQualityBySource())
				.willReturn(List.of(aggregate(null, 11, 11, 11, 0)));
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());

		assertThat(service.overview().sourceQuality().get(0).source()).isEqualTo("MANUAL");
	}

	@Test
	@DisplayName("목록은 본문 대신 항목별 채움 여부만 내려준다")
	void recentRowsExposeFilledFlagsOnly() {
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of(1L));
		given(scholarshipRepository.findRecentForAdmin(any(), any(Pageable.class))).willReturn(List.of(
				scholarship(1L, "KOSAF_SCHOLARSHIP", "요약", 1_000_000L, "https://example.com"),
				scholarship(2L, "UNIV_INU", null, null, "https://inu.ac.kr/1")));

		List<AdminScholarshipRow> rows = service.recent(null, 50);

		assertThat(rows.get(0).hasSummary()).isTrue();
		assertThat(rows.get(0).hasAmount()).isTrue();
		assertThat(rows.get(0).hasPoster()).isTrue();
		assertThat(rows.get(1).hasSummary()).isFalse();
		assertThat(rows.get(1).hasAmount()).isFalse();
		assertThat(rows.get(1).hasPoster()).isFalse();
		assertThat(rows.get(1).hasHomepageUrl()).isTrue();
	}

	@Test
	@DisplayName("목록 건수는 200건으로 상한을 둔다")
	void recentSizeIsCapped() {
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());
		given(scholarshipRepository.findRecentForAdmin(any(), any(Pageable.class))).willReturn(List.of());

		service.recent(null, 9999);

		verify(scholarshipRepository).findRecentForAdmin(any(),
				org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 200));
	}

	@Test
	@DisplayName("빈 출처 문자열은 필터 없음으로 처리한다")
	void blankSourceMeansNoFilter() {
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());
		given(scholarshipRepository.findRecentForAdmin(any(), any(Pageable.class))).willReturn(List.of());

		service.recent("  ", 10);

		verify(scholarshipRepository).findRecentForAdmin(org.mockito.ArgumentMatchers.isNull(),
				any(Pageable.class));
	}

	@Test
	@DisplayName("원본 파싱 상태 합계는 상태별 건수의 합이다")
	void rawSummaryTotalsAllStatuses() {
		given(scholarshipRepository.aggregateQualityBySource()).willReturn(List.of());
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of());
		given(rawScholarshipRepository.countByParseStatus(ParseStatus.PENDING)).willReturn(1L);
		given(rawScholarshipRepository.countByParseStatus(ParseStatus.PARSED)).willReturn(363L);
		given(rawScholarshipRepository.countByParseStatus(ParseStatus.SKIPPED)).willReturn(3669L);
		given(rawScholarshipRepository.countByParseStatus(ParseStatus.FAILED)).willReturn(2L);

		AdminOverviewResponse.RawSummary raw = service.overview().raw();

		assertThat(raw.total()).isEqualTo(4035L);
		assertThat(raw.parsed()).isEqualTo(363L);
	}

	@Test
	@DisplayName("관리자 전체 검색은 검색어와 출처 공백을 제거하고 결과를 요약 행으로 변환한다")
	void searchNormalizesFiltersAndMapsRows() {
		Scholarship target = scholarship(31L, "UNIV_KONKUK", "요약", 500_000L,
				"https://example.com/31");
		given(imageRepository.findEntityIdsByEntityType(anyString())).willReturn(List.of(31L));
		given(scholarshipRepository.searchForAdmin(
				org.mockito.ArgumentMatchers.eq("건국"),
				org.mockito.ArgumentMatchers.eq("UNIV_KONKUK"),
				org.mockito.ArgumentMatchers.eq(RecruitmentStatus.OPEN),
				org.mockito.ArgumentMatchers.eq(false), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(target)));

		var result = service.search("  건국  ", " UNIV_KONKUK ",
				RecruitmentStatus.OPEN, false, Pageable.ofSize(20));

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).hasPoster()).isTrue();
	}

	@Test
	@DisplayName("상시모집 계속 진행 확인은 마지막 검수 시각을 기록한다")
	void confirmAlwaysOpenRecordsReviewTime() {
		Scholarship target = Scholarship.builder()
				.title("상시 장학금")
				.recruitmentStatus(RecruitmentStatus.ALWAYS_OPEN)
				.build();
		ReflectionTestUtils.setField(target, "id", 77L);
		given(scholarshipRepository.findById(77L)).willReturn(Optional.of(target));

		service.confirmAlwaysOpen(77L);

		assertThat(target.getAlwaysOpenReviewedAt()).isNotNull();
	}

	@Test
	@DisplayName("실패 재처리함은 실패 사유와 원본 식별자를 반환한다")
	void failuresExposeRetryMaterial() {
		RawScholarship raw = RawScholarship.builder()
				.source("UNIV_KONKUK")
				.sourceId("notice-10")
				.sourceUrl("https://example.com/10")
				.parseStatus(ParseStatus.FAILED)
				.parseError("마감일 근거 불일치")
				.build();
		ReflectionTestUtils.setField(raw, "id", 10L);
		given(rawScholarshipRepository.findByParseStatusInOrderByUpdatedAtDesc(any(), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(raw)));

		var response = service.failures(Pageable.ofSize(20)).getContent().get(0);

		assertThat(response.rawId()).isEqualTo(10L);
		assertThat(response.status()).isEqualTo("FAILED");
		assertThat(response.error()).contains("마감일");
	}

	@Test
	@DisplayName("모집 중인데 기관·링크·조건이 없으면 이상 유형을 모두 표시한다")
	void anomaliesDescribeEveryMatchedRule() {
		Scholarship target = Scholarship.builder()
				.title("기관 미상 장학금")
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.applicationEndAt(LocalDateTime.now().plusDays(3))
				.build();
		ReflectionTestUtils.setField(target, "id", 88L);
		given(scholarshipRepository.findAdminAnomalies(any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(target)));
		given(scholarshipConditionRepository.countByScholarshipId(88L)).willReturn(0L);

		var response = service.anomalies(Pageable.ofSize(20)).getContent().get(0);

		assertThat(response.anomalyTypes())
				.containsExactly("MISSING_PROVIDER", "MISSING_LINK", "MISSING_CONDITION");
	}
}
