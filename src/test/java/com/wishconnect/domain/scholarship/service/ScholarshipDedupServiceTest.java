package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.scholarship.dto.MergeDetectionResponse;
import com.wishconnect.domain.scholarship.dto.ManualMergeCandidateRequest;
import com.wishconnect.domain.scholarship.entity.MergeCandidateOrigin;
import com.wishconnect.domain.scholarship.entity.MergeCandidateStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * 중복 탐지·승인 흐름 검증.
 *
 * <p>여기서 지키려는 것은 둘이다.
 *
 * <ul>
 *   <li><b>LLM 호출을 아껴 쓰는가</b> — 그룹당 1회, 후보가 없는 그룹은 0회. 이 서비스는 크레딧을
 *       쓰므로 "호출하지 않아야 할 때 호출하지 않음"이 기능 요구사항이다.</li>
 *   <li><b>승인 없이는 아무것도 병합하지 않는가</b> — 탐지는 큐에만 올린다. 병합은 되돌리기
 *       어려우므로 실행 경로가 사람의 승인 뒤에만 열려야 한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipDedupServiceTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ScholarshipMergeCandidateRepository mergeCandidateRepository;
	@Mock private ScholarshipMergeExecutor mergeExecutor;
	@Mock private LlmClient llmClient;

	private ScholarshipDedupService service;
	private long nextId;

	@BeforeEach
	void setUp() {
		service = new ScholarshipDedupService(scholarshipRepository, mergeCandidateRepository,
				mergeExecutor, llmClient, new ObjectMapper());
		nextId = 1L;
		given(mergeCandidateRepository.findScholarshipIdsByStatus(any())).willReturn(List.of());
		given(mergeCandidateRepository.existsByPrimary_IdAndDuplicate_Id(anyLong(), anyLong()))
				.willReturn(false);
		given(mergeCandidateRepository.save(any())).willAnswer(i -> i.getArgument(0));
	}

	// --- 탐지: LLM 호출 절약 ---

	@Test
	@DisplayName("혼자 남는 제목은 LLM 에 묻지 않는다 — 비교할 상대가 없다")
	void doesNotCallLlmForSingletonGroups() {
		givenScholarships(
				scholarship("국가장학금 신청 안내"),
				scholarship("교내 성적우수 장학금 선발 공고"),
				scholarship("근로장학생 모집"));

		MergeDetectionResponse result = service.detect(100);

		verify(llmClient, never()).chat(any());
		assertThat(result.groupCount()).isZero();
		assertThat(result.candidateCount()).isZero();
	}

	@Test
	@DisplayName("같은 키로 묶인 그룹만 LLM 에 넘긴다 — 그룹당 1회")
	void callsLlmOncePerGroup() {
		givenScholarships(
				scholarship("2026학년도 1학기 국가장학금 1차 신청 안내"),
				scholarship("2026학년도 2학기 국가장학금 2차 신청기간 안내"),
				scholarship("교내 성적우수 장학금 선발 공고"));
		given(llmClient.chat(any())).willReturn("[]");

		MergeDetectionResponse result = service.detect(100);

		verify(llmClient, org.mockito.Mockito.times(1)).chat(any());
		assertThat(result.groupCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("그룹이 너무 크면 건너뛴다 — blocking 키가 뭉툭해 무관한 공고까지 묶인 상태")
	void skipsOversizedGroups() {
		List<Scholarship> many = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			many.add(scholarship("국가장학금 " + (i + 1) + "차 신청 안내"));
		}
		givenScholarships(many.toArray(new Scholarship[0]));

		MergeDetectionResponse result = service.detect(100);

		verify(llmClient, never()).chat(any());
		assertThat(result.groupCount()).isZero();
	}

	@Test
	@DisplayName("이미 승인 대기 중인 장학금은 제외한다 — 한 장학금이 여러 쌍에 걸리면 병합 순서에 따라 결과가 달라진다")
	void excludesAlreadyQueuedScholarships() {
		Scholarship queued = scholarship("국가장학금 1차 신청 안내");
		Scholarship other = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(queued, other);
		given(mergeCandidateRepository.findScholarshipIdsByStatus(MergeCandidateStatus.PENDING))
				.willReturn(List.of(queued.getId()));

		MergeDetectionResponse result = service.detect(100);

		verify(llmClient, never()).chat(any());
		assertThat(result.groupCount()).isZero();
	}

	@Test
	@DisplayName("입력 상한을 넘겨도 조회 크기를 넘기지 않는다 — 크레딧 방어")
	void clampsScanSize() {
		givenScholarships();

		service.detect(100_000);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(scholarshipRepository).findByDeletedAtIsNullOrderByIdDesc(captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(500);
	}

	// --- 탐지: LLM 응답 방어 ---

	@Test
	@DisplayName("LLM 이 그룹에 없는 ID 를 돌려주면 버린다 — 무관한 장학금이 병합될 수 있다")
	void discardsPairsOutsideGroup() {
		Scholarship a = scholarship("국가장학금 1차 신청 안내");
		Scholarship b = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(a, b);
		given(llmClient.chat(any())).willReturn(
				"[{\"primaryId\":%d,\"duplicateId\":9999,\"reason\":\"같음\"}]".formatted(a.getId()));

		MergeDetectionResponse result = service.detect(100);

		verify(mergeCandidateRepository, never()).save(any());
		assertThat(result.candidateCount()).isZero();
	}

	@Test
	@DisplayName("같은 ID 쌍은 버린다 — 자기 자신과의 병합")
	void discardsSelfPair() {
		Scholarship a = scholarship("국가장학금 1차 신청 안내");
		Scholarship b = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(a, b);
		given(llmClient.chat(any())).willReturn(
				"[{\"primaryId\":%d,\"duplicateId\":%d,\"reason\":\"같음\"}]"
						.formatted(a.getId(), a.getId()));

		assertThat(service.detect(100).candidateCount()).isZero();
		verify(mergeCandidateRepository, never()).save(any());
	}

	@Test
	@DisplayName("코드펜스로 감싼 응답도 읽는다 — LLM 이 ```json 을 붙이는 일이 잦다")
	void parsesFencedJson() {
		Scholarship a = scholarship("국가장학금 1차 신청 안내");
		Scholarship b = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(a, b);
		given(llmClient.chat(any())).willReturn("""
				```json
				[{"primaryId":%d,"duplicateId":%d,"reason":"같은 기관의 같은 장학금"}]
				```
				""".formatted(a.getId(), b.getId()));

		MergeDetectionResponse result = service.detect(100);

		assertThat(result.candidateCount()).isEqualTo(1);
		ArgumentCaptor<ScholarshipMergeCandidate> captor =
				ArgumentCaptor.forClass(ScholarshipMergeCandidate.class);
		verify(mergeCandidateRepository).save(captor.capture());
		assertThat(captor.getValue().getPrimary().getId()).isEqualTo(a.getId());
		assertThat(captor.getValue().getDuplicate().getId()).isEqualTo(b.getId());
		assertThat(captor.getValue().isPending()).isTrue();
	}

	@Test
	@DisplayName("응답이 JSON 이 아니어도 배치가 죽지 않는다")
	void survivesUnparsableResponse() {
		givenScholarships(
				scholarship("국가장학금 1차 신청 안내"),
				scholarship("국가장학금 2차 신청 안내"));
		given(llmClient.chat(any())).willReturn("중복된 장학금을 찾지 못했습니다.");

		MergeDetectionResponse result = service.detect(100);

		assertThat(result.candidateCount()).isZero();
		assertThat(result.failedCount()).isZero();
	}

	@Test
	@DisplayName("LLM 호출이 실패해도 나머지 그룹은 계속 본다")
	void isolatesGroupFailure() {
		givenScholarships(
				scholarship("국가장학금 1차 신청 안내"),
				scholarship("국가장학금 2차 신청 안내"));
		given(llmClient.chat(any())).willThrow(new RuntimeException("credit balance too low"));

		MergeDetectionResponse result = service.detect(100);

		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(result.candidateCount()).isZero();
	}

	@Test
	@DisplayName("이미 올라온 쌍은 방향이 뒤집혀 있어도 다시 만들지 않는다")
	void skipsExistingPairInEitherDirection() {
		Scholarship a = scholarship("국가장학금 1차 신청 안내");
		Scholarship b = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(a, b);
		given(llmClient.chat(any())).willReturn(
				"[{\"primaryId\":%d,\"duplicateId\":%d,\"reason\":\"같음\"}]"
						.formatted(a.getId(), b.getId()));
		// 저장된 방향은 (b, a) 다 — 반대 방향으로 조회해야 걸러진다.
		given(mergeCandidateRepository.existsByPrimary_IdAndDuplicate_Id(b.getId(), a.getId()))
				.willReturn(true);

		MergeDetectionResponse result = service.detect(100);

		verify(mergeCandidateRepository, never()).save(any());
		assertThat(result.skippedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("탐지는 Haiku 로 호출한다 — 판정량이 많아 비용이 누적된다")
	void usesParsingModel() {
		givenScholarships(
				scholarship("국가장학금 1차 신청 안내"),
				scholarship("국가장학금 2차 신청 안내"));
		given(llmClient.chat(any())).willReturn("[]");

		service.detect(100);

		ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
		verify(llmClient).chat(captor.capture());
		assertThat(captor.getValue().model())
				.isEqualTo(com.wishconnect.domain.application.client.dto.LlmModel.PARSING);
	}

	@Test
	@DisplayName("탐지 단계에서는 절대 병합하지 않는다")
	void detectNeverMerges() {
		Scholarship a = scholarship("국가장학금 1차 신청 안내");
		Scholarship b = scholarship("국가장학금 2차 신청 안내");
		givenScholarships(a, b);
		given(llmClient.chat(any())).willReturn(
				"[{\"primaryId\":%d,\"duplicateId\":%d,\"reason\":\"같음\"}]"
						.formatted(a.getId(), b.getId()));

		service.detect(100);

		verify(mergeExecutor, never()).merge(any(), any());
	}

	@Test
	@DisplayName("관리자가 고른 두 장학금은 병합하지 않고 MANUAL 승인 후보로만 올린다")
	void queuesManualCandidateWithoutMerging() {
		Scholarship primary = scholarship("남길 장학금");
		Scholarship duplicate = scholarship("중복 장학금");
		given(scholarshipRepository.findById(primary.getId())).willReturn(Optional.of(primary));
		given(scholarshipRepository.findById(duplicate.getId())).willReturn(Optional.of(duplicate));
		ArgumentCaptor<ScholarshipMergeCandidate> captor =
				ArgumentCaptor.forClass(ScholarshipMergeCandidate.class);

		service.queueManual(new ManualMergeCandidateRequest(
				primary.getId(), duplicate.getId(), "관리자 비교 완료"));

		verify(mergeCandidateRepository).save(captor.capture());
		assertThat(captor.getValue().getOrigin()).isEqualTo(MergeCandidateOrigin.MANUAL);
		assertThat(captor.getValue().getStatus()).isEqualTo(MergeCandidateStatus.PENDING);
		assertThat(captor.getValue().getReason()).isEqualTo("관리자 비교 완료");
		verify(mergeExecutor, never()).merge(any(), any());
	}

	@Test
	@DisplayName("같은 장학금을 수기 중복 후보 양쪽에 선택하면 거부한다")
	void rejectsManualSelfPair() {
		Scholarship scholarship = scholarship("한 장학금");

		assertThatThrownBy(() -> service.queueManual(new ManualMergeCandidateRequest(
				scholarship.getId(), scholarship.getId(), null)))
				.isInstanceOf(CustomException.class);

		verify(mergeCandidateRepository, never()).save(any());
	}

	// --- 승인·반려 ---

	@Test
	@DisplayName("승인하면 병합하고 MERGED 로 기록한다")
	void approveMergesAndRecords() {
		Scholarship a = scholarship("국가장학금 신청 안내");
		Scholarship b = scholarship("국가장학금 신청기간 안내");
		ScholarshipMergeCandidate candidate = candidate(a, b);
		UUID reviewer = UUID.randomUUID();
		given(mergeExecutor.merge(a, b)).willReturn(Map.of("scrap.moved", 2));

		var result = service.approve(candidate.getId(), reviewer);

		verify(mergeExecutor).merge(a, b);
		assertThat(result.status()).isEqualTo("MERGED");
		assertThat(result.primaryId()).isEqualTo(a.getId());
		assertThat(result.duplicateId()).isEqualTo(b.getId());
		assertThat(result.moved()).containsEntry("scrap.moved", 2);
		assertThat(candidate.getStatus()).isEqualTo(MergeCandidateStatus.MERGED);
		assertThat(candidate.getReviewedBy()).isEqualTo(reviewer);
	}

	@Test
	@DisplayName("이미 처리된 후보는 다시 승인하지 않는다 — 병합이 두 번 일어난다")
	void rejectsDoubleApproval() {
		Scholarship a = scholarship("국가장학금 신청 안내");
		Scholarship b = scholarship("국가장학금 신청기간 안내");
		ScholarshipMergeCandidate candidate = candidate(a, b);
		candidate.markMerged(UUID.randomUUID(), "이미 병합됨");

		assertThatThrownBy(() -> service.approve(candidate.getId(), UUID.randomUUID()))
				.isInstanceOf(CustomException.class);

		verify(mergeExecutor, never()).merge(any(), any());
	}

	@Test
	@DisplayName("병합이 실패하면 후보를 PENDING 으로 남긴다 — 아무것도 바뀌지 않았으니 다시 승인할 수 있어야 한다")
	void leavesCandidatePendingOnFailure() {
		Scholarship a = scholarship("국가장학금 신청 안내");
		Scholarship b = scholarship("국가장학금 신청기간 안내");
		ScholarshipMergeCandidate candidate = candidate(a, b);
		given(mergeExecutor.merge(a, b)).willThrow(new RuntimeException("제약 위반"));

		assertThatThrownBy(() -> service.approve(candidate.getId(), UUID.randomUUID()))
				.isInstanceOf(CustomException.class);

		// 실패 상태를 적어도 트랜잭션과 함께 롤백되어 남지 않는다. 상태를 건드리지 않는 것이 옳다.
		assertThat(candidate.getStatus()).isEqualTo(MergeCandidateStatus.PENDING);
		assertThat(candidate.getReviewedBy()).isNull();
	}

	@Test
	@DisplayName("병합 후 후보를 다시 읽어 MERGED 로 기록한다 — merge() 가 영속성 컨텍스트를 비우기 때문")
	void reloadsCandidateAfterMergeClearsContext() {
		Scholarship a = scholarship("국가장학금 신청 안내");
		Scholarship b = scholarship("국가장학금 신청기간 안내");
		ScholarshipMergeCandidate candidate = candidate(a, b);
		given(mergeExecutor.merge(a, b)).willReturn(Map.of());

		service.approve(candidate.getId(), UUID.randomUUID());

		// clear() 로 detach 된 엔티티에 쓰면 반영되지 않는다. 관리 상태로 다시 읽어야 한다.
		verify(mergeCandidateRepository, org.mockito.Mockito.times(2))
				.findById(candidate.getId());
		assertThat(candidate.getStatus()).isEqualTo(MergeCandidateStatus.MERGED);
	}

	@Test
	@DisplayName("반려하면 병합하지 않고 REJECTED 로 남긴다")
	void rejectDoesNotMerge() {
		Scholarship a = scholarship("국가장학금 신청 안내");
		Scholarship b = scholarship("국가장학금 신청기간 안내");
		ScholarshipMergeCandidate candidate = candidate(a, b);
		UUID reviewer = UUID.randomUUID();

		var result = service.reject(candidate.getId(), reviewer, "캠퍼스가 다른 별개 모집");

		verify(mergeExecutor, never()).merge(any(), any());
		assertThat(result.status()).isEqualTo("REJECTED");
		assertThat(candidate.getStatus()).isEqualTo(MergeCandidateStatus.REJECTED);
		assertThat(candidate.getNote()).isEqualTo("캠퍼스가 다른 별개 모집");
		assertThat(candidate.getReviewedBy()).isEqualTo(reviewer);
	}

	@Test
	@DisplayName("없는 후보를 승인·반려하면 거부한다")
	void rejectsUnknownCandidate() {
		given(mergeCandidateRepository.findById(anyLong())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.approve(999L, UUID.randomUUID()))
				.isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> service.reject(999L, UUID.randomUUID(), null))
				.isInstanceOf(CustomException.class);
	}

	// --- fixture ---

	private void givenScholarships(Scholarship... scholarships) {
		given(scholarshipRepository.findByDeletedAtIsNullOrderByIdDesc(any(Pageable.class)))
				.willReturn(List.of(scholarships));
	}

	private Scholarship scholarship(String title) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("경희대학교")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-" + nextId)
				.build();
		setField(scholarship, "id", nextId++);
		return scholarship;
	}

	private ScholarshipMergeCandidate candidate(Scholarship primary, Scholarship duplicate) {
		ScholarshipMergeCandidate candidate = ScholarshipMergeCandidate.builder()
				.primary(primary)
				.duplicate(duplicate)
				.reason("같은 기관의 같은 장학금")
				.build();
		setField(candidate, "id", nextId++);
		given(mergeCandidateRepository.findById(candidate.getId()))
				.willReturn(Optional.of(candidate));
		return candidate;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Class<?> current = target.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					field.set(target, value);
					return;
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 사용하지 않지만 목록 조회 시그니처가 바뀌면 컴파일로 드러나게 남겨 둔다. */
	@SuppressWarnings("unused")
	private void listSignatureGuard() {
		given(mergeCandidateRepository.findByStatusOrderByIdAsc(any(), any()))
				.willReturn(new PageImpl<>(List.of()));
	}
}
