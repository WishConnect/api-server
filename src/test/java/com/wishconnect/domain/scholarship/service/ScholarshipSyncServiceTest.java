package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.client.ScholarshipApiClient;
import com.wishconnect.domain.scholarship.client.ScholarshipApiItem;
import com.wishconnect.domain.scholarship.client.ScholarshipEndpoint;
import com.wishconnect.domain.scholarship.config.ScholarshipApiProperties;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipDocumentRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.util.ScholarshipMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동기화가 장학금을 <b>물리 삭제하지 않는지</b>를 지킨다.
 *
 * <p>회귀 방지 대상: scholarship 을 참조하는 FK 가 9개 테이블에 있고 전부 ON DELETE NO ACTION 이라,
 * 물리 삭제를 시도하면 FK 위반으로 saveItem 트랜잭션이 통째로 롤백돼 <b>그 공고가 저장되지 않았다</b>
 * (운영 로그 기준 FK 위반 792건, 그로 인한 저장 실패 198건).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipSyncServiceTest {

	@Mock
	private ScholarshipApiClient scholarshipApiClient;
	@Mock
	private RawScholarshipRepository rawScholarshipRepository;
	@Mock
	private ScholarshipRepository scholarshipRepository;
	@Mock
	private ScholarshipDocumentRepository scholarshipDocumentRepository;
	@Mock
	private ScholarshipConditionRepository scholarshipConditionRepository;
	@Mock
	private ScholarshipMapper scholarshipMapper;
	@Mock
	private TransactionTemplate transactionTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private ScholarshipSyncService scholarshipSyncService;

	private static final ScholarshipApiProperties PROPERTIES = new ScholarshipApiProperties(
			null, null, null, null, "key", "KOSAF_SCHOLARSHIP", null, 100, 1);

	@BeforeEach
	void setUp() {
		scholarshipSyncService = new ScholarshipSyncService(
				scholarshipApiClient, PROPERTIES, rawScholarshipRepository, scholarshipRepository,
				scholarshipDocumentRepository, scholarshipConditionRepository, scholarshipMapper,
				transactionTemplate, objectMapper);

		// 트랜잭션 템플릿은 콜백을 그대로 실행하도록 둔다(단위 테스트라 실제 트랜잭션은 없다).
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept((TransactionStatus) null);
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
	}

	private ScholarshipApiItem item() {
		JsonNode payload = objectMapper.createObjectNode().put("상품명", "미래인재 장학금");
		return new ScholarshipApiItem(
				new ScholarshipEndpoint("/15028252/v1/uddi:test", LocalDate.of(2026, 7, 22), "테스트"),
				payload);
	}

	/**
	 * id 를 직접 넣는 이유: 재연결 판정이 {@code Objects.equals(previous.getId(), current.getId())} 라
	 * id 가 둘 다 null 이면 서로 다른 엔티티인데도 같은 것으로 취급된다.
	 */
	private Scholarship scholarship(Long id) {
		Scholarship scholarship = Scholarship.builder()
				.title("미래인재 장학금")
				.provider("위시커넥트")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.applicationStartAt(LocalDateTime.now().minusDays(10))
				.applicationEndAt(LocalDateTime.now().plusDays(10))
				.build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		return scholarship;
	}

	private RawScholarship rawLinkedTo(Scholarship scholarship) {
		RawScholarship raw = RawScholarship.builder()
				.source("KOSAF_SCHOLARSHIP")
				.sourceUrl("https://example.com")
				.sourceId("sid")
				.parseStatus(ParseStatus.PENDING)
				.build();
		if (scholarship != null) {
			raw.markParsed(scholarship);
		}
		return raw;
	}

	@Test
	@DisplayName("모집종료된 공고는 물리 삭제하지 않고 소프트 삭제한다")
	void closedScholarshipIsSoftDeletedNotHardDeleted() {
		Scholarship linked = scholarship(1L);
		given(scholarshipApiClient.fetchScholarships()).willReturn(List.of(item()));
		given(rawScholarshipRepository.findBySourceAndSourceId(any(), any()))
				.willReturn(Optional.of(rawLinkedTo(linked)));
		given(scholarshipMapper.isClosed(any())).willReturn(true);

		scholarshipSyncService.sync();

		verify(scholarshipRepository, never()).delete(any(Scholarship.class));
		assertThat(linked.isDeleted()).isTrue();
		assertThat(linked.isActive()).isFalse();
	}

	@Test
	@DisplayName("모집종료 처리 시 파생 데이터(서류·조건)는 정리한다")
	void closedScholarshipClearsDerivedData() {
		Scholarship linked = scholarship(1L);
		given(scholarshipApiClient.fetchScholarships()).willReturn(List.of(item()));
		given(rawScholarshipRepository.findBySourceAndSourceId(any(), any()))
				.willReturn(Optional.of(rawLinkedTo(linked)));
		given(scholarshipMapper.isClosed(any())).willReturn(true);

		scholarshipSyncService.sync();

		verify(scholarshipDocumentRepository).deleteByScholarship(linked);
		verify(scholarshipConditionRepository).deleteByScholarship(linked);
	}

	@Test
	@DisplayName("참조가 걸린 공고를 처리해도 저장이 실패하지 않는다")
	void referencedScholarshipStillCountsAsSaved() {
		given(scholarshipApiClient.fetchScholarships()).willReturn(List.of(item()));
		given(rawScholarshipRepository.findBySourceAndSourceId(any(), any()))
				.willReturn(Optional.of(rawLinkedTo(scholarship(1L))));
		given(scholarshipMapper.isClosed(any())).willReturn(true);

		var response = scholarshipSyncService.sync();

		// 예전에는 여기서 FK 위반으로 롤백돼 failedCount 로 잡혔다.
		assertThat(response.savedCount()).isEqualTo(1);
		assertThat(response.failedCount()).isZero();
	}

	@Test
	@DisplayName("dedup 으로 다른 공고에 재연결되면 이전 공고를 소프트 삭제한다")
	void relinkedPreviousScholarshipIsSoftDeleted() {
		Scholarship previous = scholarship(1L);
		Scholarship current = scholarship(2L);
		given(scholarshipApiClient.fetchScholarships()).willReturn(List.of(item()));
		given(rawScholarshipRepository.findBySourceAndSourceId(any(), any()))
				.willReturn(Optional.of(rawLinkedTo(previous)));
		given(scholarshipMapper.isClosed(any())).willReturn(false);
		given(scholarshipMapper.createDedupKey(any())).willReturn("dedup");
		given(scholarshipMapper.toScholarship(any(), any(), any())).willReturn(current);
		given(scholarshipRepository.findByDedupKey("dedup")).willReturn(Optional.of(current));
		given(scholarshipRepository.save(current)).willReturn(current);
		given(rawScholarshipRepository.countByScholarship(previous)).willReturn(0L);
		given(scholarshipMapper.toDocuments(any(), any())).willReturn(List.of());
		given(scholarshipMapper.toConditions(any(), any())).willReturn(List.of());

		scholarshipSyncService.sync();

		verify(scholarshipRepository, never()).delete(any(Scholarship.class));
		assertThat(previous.isDeleted()).isTrue();
	}

	/**
	 * 물리 삭제 시절에는 행이 사라졌다가 새로 생겨 자연히 되살아났다. 소프트 삭제로 바꾼 뒤에는
	 * updateFromApi 에서 deletedAt 을 풀어주지 않으면 다시 들어온 공고가 영원히 노출되지 않는다.
	 */
	@Test
	@DisplayName("소프트 삭제된 공고가 동기화 피드에 다시 들어오면 되살아난다")
	void softDeletedScholarshipIsRevivedOnResync() {
		Scholarship revived = scholarship(1L);
		revived.softDelete();
		assertThat(revived.isDeleted()).isTrue();

		revived.updateFromApi(
				"미래인재 장학금", "위시커넥트", "요약", "설명", ScholarshipType.EXTERNAL,
				LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10),
				com.wishconnect.domain.scholarship.entity.RecruitmentStatus.OPEN,
				10, 1_000_000L, "KOSAF_SCHOLARSHIP", "dedup", "https://example.com");
		revived.updateActive(true);

		assertThat(revived.isDeleted()).isFalse();
		assertThat(revived.isActive()).isTrue();
	}

	@Test
	@DisplayName("이전 공고가 아직 다른 raw 에 물려 있으면 건드리지 않는다")
	void previousScholarshipStillReferencedIsKept() {
		Scholarship previous = scholarship(1L);
		Scholarship current = scholarship(2L);
		given(scholarshipApiClient.fetchScholarships()).willReturn(List.of(item()));
		given(rawScholarshipRepository.findBySourceAndSourceId(any(), any()))
				.willReturn(Optional.of(rawLinkedTo(previous)));
		given(scholarshipMapper.isClosed(any())).willReturn(false);
		given(scholarshipMapper.createDedupKey(any())).willReturn("dedup");
		given(scholarshipMapper.toScholarship(any(), any(), any())).willReturn(current);
		given(scholarshipRepository.findByDedupKey("dedup")).willReturn(Optional.of(current));
		given(scholarshipRepository.save(current)).willReturn(current);
		given(rawScholarshipRepository.countByScholarship(previous)).willReturn(2L);
		given(scholarshipMapper.toDocuments(any(), any())).willReturn(List.of());
		given(scholarshipMapper.toConditions(any(), any())).willReturn(List.of());

		scholarshipSyncService.sync();

		assertThat(previous.isDeleted()).isFalse();
	}
}
