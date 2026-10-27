package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.MergeBulkConflictResponse;
import com.wishconnect.domain.scholarship.dto.MergeBulkConflictResponse.Reason;
import com.wishconnect.domain.scholarship.dto.MergeBulkResultResponse;
import com.wishconnect.domain.scholarship.dto.MergeBulkResultResponse.Result;
import com.wishconnect.domain.scholarship.dto.MergeResultResponse;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipMergeCandidate;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipMergeCandidateRepository;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 중복 후보 일괄 병합·반려.
 *
 * <p>지키려는 것: (1) 사전 검사에 하나라도 걸리면 아무것도 실행하지 않는다 (2) 실행은 단건 로직을 건마다 부르고,
 * 한 건이 실패해도 나머지는 계속한다 (3) 감사 기록은 성공 건마다 단건과 같은 형식으로 남는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipMergeBulkServiceTest {

	@Mock private ScholarshipMergeCandidateRepository mergeCandidateRepository;
	@Mock private ScholarshipDedupService dedupService;
	@Mock private AdminAuditLogService auditLogService;
	@Mock private TransactionTemplate transactionTemplate;

	private ScholarshipMergeBulkService service;
	private final List<ScholarshipMergeCandidate> stored = new ArrayList<>();
	private final UUID reviewer = UUID.randomUUID();
	private long nextId = 100;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		service = new ScholarshipMergeBulkService(mergeCandidateRepository, dedupService, auditLogService,
				transactionTemplate);
		given(transactionTemplate.execute(any())).willAnswer(invocation ->
				((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
		given(mergeCandidateRepository.findAllById(any())).willAnswer(invocation -> {
			Collection<Long> ids = (Collection<Long>) invocation.getArgument(0);
			return stored.stream().filter(c -> ids.contains(c.getId())).toList();
		});
		given(dedupService.approve(anyLong(), any())).willAnswer(invocation -> merged(invocation.getArgument(0)));
		given(dedupService.reject(anyLong(), any(), anyString())).willAnswer(invocation -> {
			ScholarshipMergeCandidate c = find(invocation.getArgument(0));
			return new MergeResultResponse(c.getId(), "REJECTED", c.getPrimary().getId(), c.getDuplicate().getId(), Map.of());
		});
	}

	// --- 사전 검사: 규칙별 400, 아무것도 실행하지 않음 ---

	@Test
	@DisplayName("없는 후보·처리된 후보·같은 ID 두 번이면 400 이고 아무것도 병합하지 않는다")
	void commonConflicts() {
		ScholarshipMergeCandidate ok = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate done = candidate(scholarship(), scholarship());
		done.markMerged(reviewer, "이미 병합");

		MergeBulkConflictResponse conflicts = conflicts(() ->
				service.approve(List.of(ok.getId(), ok.getId(), done.getId(), 9999L), reviewer));

		assertThat(conflicts.conflicts()).extracting(MergeBulkConflictResponse.Conflict::reason)
				.containsExactlyInAnyOrder(Reason.DUPLICATE_ID, Reason.NOT_PENDING, Reason.NOT_FOUND);
		verify(dedupService, never()).approve(anyLong(), any());
		verify(auditLogService, never()).record(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("같은 장학금이 두 후보에서 내릴 쪽이면 두 후보 모두 SAME_DUPLICATE")
	void sameDuplicateTwice() {
		Scholarship dropped = scholarship();
		ScholarshipMergeCandidate a = candidate(scholarship(), dropped);
		ScholarshipMergeCandidate b = candidate(scholarship(), dropped);

		MergeBulkConflictResponse conflicts = conflicts(() -> service.approve(List.of(a.getId(), b.getId()), reviewer));

		assertThat(conflicts.conflicts()).allSatisfy(c -> {
			assertThat(c.reason()).isEqualTo(Reason.SAME_DUPLICATE);
			assertThat(c.scholarshipIds()).containsExactly(dropped.getId());
		});
		assertThat(conflicts.conflicts()).extracting(MergeBulkConflictResponse.Conflict::candidateId)
				.containsExactlyInAnyOrder(a.getId(), b.getId());
		verify(dedupService, never()).approve(anyLong(), any());
	}

	@Test
	@DisplayName("연쇄 병합(A←B, B←C)이면 두 후보 모두 CHAINED_MERGE 로 표시한다")
	void chainedMerge() {
		Scholarship a = scholarship();
		Scholarship b = scholarship();
		Scholarship c = scholarship();
		ScholarshipMergeCandidate first = candidate(a, b);
		ScholarshipMergeCandidate second = candidate(b, c);

		MergeBulkConflictResponse conflicts = conflicts(() ->
				service.approve(List.of(first.getId(), second.getId()), reviewer));

		assertThat(conflicts.conflicts()).extracting(MergeBulkConflictResponse.Conflict::reason)
				.containsOnly(Reason.CHAINED_MERGE);
		assertThat(conflicts.conflicts()).extracting(MergeBulkConflictResponse.Conflict::candidateId)
				.containsExactlyInAnyOrder(first.getId(), second.getId());
		verify(dedupService, never()).approve(anyLong(), any());
	}

	@Test
	@DisplayName("유지 쪽이나 내릴 쪽이 이미 내려졌으면 SCHOLARSHIP_DELETED")
	void deletedScholarship() {
		Scholarship deleted = scholarship();
		deleted.softDelete();
		ScholarshipMergeCandidate keepDeleted = candidate(deleted, scholarship());

		MergeBulkConflictResponse conflicts = conflicts(() -> service.approve(List.of(keepDeleted.getId()), reviewer));

		assertThat(conflicts.conflicts()).singleElement().satisfies(c -> {
			assertThat(c.reason()).isEqualTo(Reason.SCHOLARSHIP_DELETED);
			assertThat(c.scholarshipIds()).containsExactly(deleted.getId());
		});
	}

	@Test
	@DisplayName("같은 장학금을 유지 쪽으로 둔 두 후보(#683←#307, #683←#644)는 함께 병합할 수 있다")
	void sharedPrimaryIsAllowed() {
		Scholarship keep = scholarship();
		ScholarshipMergeCandidate a = candidate(keep, scholarship());
		ScholarshipMergeCandidate b = candidate(keep, scholarship());

		MergeBulkResultResponse result = service.approve(List.of(a.getId(), b.getId()), reviewer);

		assertThat(result.succeeded()).isEqualTo(2);
	}

	@Test
	@DisplayName("반려는 장학금 관계(연쇄·삭제)를 검사하지 않는다 — 아무것도 옮기지 않으므로")
	void rejectIgnoresMergeOnlyRules() {
		Scholarship dropped = scholarship();
		dropped.softDelete();
		ScholarshipMergeCandidate a = candidate(scholarship(), dropped);
		ScholarshipMergeCandidate b = candidate(scholarship(), dropped);

		MergeBulkResultResponse result = service.reject(List.of(a.getId(), b.getId()), "학기가 다름", reviewer);

		assertThat(result.succeeded()).isEqualTo(2);
		assertThat(result.results()).extracting(MergeBulkResultResponse.Item::result).containsOnly(Result.REJECTED);
	}

	// --- 건수·사유 ---

	@Test
	@DisplayName("0건·51건·null 원소는 MERGE_BULK_SIZE_INVALID, 50건은 허용")
	void sizeLimit() {
		assertCode(() -> service.approve(List.of(), reviewer), ErrorCode.MERGE_BULK_SIZE_INVALID);
		assertCode(() -> service.approve(null, reviewer), ErrorCode.MERGE_BULK_SIZE_INVALID);
		List<Long> fiftyOne = LongStream.rangeClosed(1, 51).boxed().toList();
		assertCode(() -> service.approve(fiftyOne, reviewer), ErrorCode.MERGE_BULK_SIZE_INVALID);
		assertCode(() -> service.reject(fiftyOne, "사유", reviewer), ErrorCode.MERGE_BULK_SIZE_INVALID);
		List<Long> withNull = new ArrayList<>();
		withNull.add(null);
		assertCode(() -> service.approve(withNull, reviewer), ErrorCode.MERGE_BULK_SIZE_INVALID);

		List<Long> fifty = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			fifty.add(candidate(scholarship(), scholarship()).getId());
		}
		assertThat(service.approve(fifty, reviewer).succeeded()).isEqualTo(50);
	}

	@Test
	@DisplayName("반려 사유는 필수(공백 불가)이고 500자까지, 앞뒤 공백은 지워서 남긴다")
	void noteRequired() {
		ScholarshipMergeCandidate c = candidate(scholarship(), scholarship());
		assertCode(() -> service.reject(List.of(c.getId()), null, reviewer), ErrorCode.MERGE_BULK_NOTE_REQUIRED);
		assertCode(() -> service.reject(List.of(c.getId()), "   ", reviewer), ErrorCode.MERGE_BULK_NOTE_REQUIRED);
		assertCode(() -> service.reject(List.of(c.getId()), "가".repeat(501), reviewer), ErrorCode.MERGE_BULK_NOTE_TOO_LONG);
		verify(dedupService, never()).reject(anyLong(), any(), any());

		service.reject(List.of(c.getId()), "  " + "가".repeat(500) + "  ", reviewer);
		verify(dedupService).reject(c.getId(), reviewer, "가".repeat(500));
	}

	// --- 실행: 부분 실패·건너뜀·순서 ---

	@Test
	@DisplayName("한 건이 실패해도 나머지는 요청 순서대로 계속 처리한다")
	void continuesAfterFailure() {
		ScholarshipMergeCandidate a = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate b = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate c = candidate(scholarship(), scholarship());
		willThrow(new CustomException(ErrorCode.MERGE_FAILED)).given(dedupService).approve(eq(b.getId()), any());

		MergeBulkResultResponse result = service.approve(List.of(c.getId(), b.getId(), a.getId()), reviewer);

		assertThat(result.total()).isEqualTo(3);
		assertThat(result.succeeded()).isEqualTo(2);
		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.results()).extracting(MergeBulkResultResponse.Item::candidateId)
				.containsExactly(c.getId(), b.getId(), a.getId());
		assertThat(result.results().get(1)).satisfies(item -> {
			assertThat(item.result()).isEqualTo(Result.FAILED);
			assertThat(item.errorCode()).isEqualTo("MERGE_FAILED");
			assertThat(item.primaryId()).isEqualTo(b.getPrimary().getId());
		});
		var order = org.mockito.Mockito.inOrder(dedupService);
		order.verify(dedupService).approve(c.getId(), reviewer);
		order.verify(dedupService).approve(b.getId(), reviewer);
		order.verify(dedupService).approve(a.getId(), reviewer);
	}

	@Test
	@DisplayName("예상하지 못한 런타임 오류도 그 건만 FAILED 로 남기고 계속한다")
	void unexpectedErrorIsIsolated() {
		ScholarshipMergeCandidate a = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate b = candidate(scholarship(), scholarship());
		willThrow(new IllegalStateException("boom")).given(dedupService).approve(eq(a.getId()), any());

		MergeBulkResultResponse result = service.approve(List.of(a.getId(), b.getId()), reviewer);

		assertThat(result.results()).extracting(MergeBulkResultResponse.Item::result)
				.containsExactly(Result.FAILED, Result.MERGED);
	}

	@Test
	@DisplayName("검사 뒤 다른 관리자가 먼저 처리한 후보는 SKIPPED 로 남긴다")
	void skipsWhenProcessedMeanwhile() {
		ScholarshipMergeCandidate a = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate b = candidate(scholarship(), scholarship());
		willThrow(new CustomException(ErrorCode.MERGE_CANDIDATE_NOT_PENDING))
				.given(dedupService).approve(eq(a.getId()), any());
		willThrow(new CustomException(ErrorCode.MERGE_CANDIDATE_NOT_PENDING))
				.given(dedupService).reject(eq(b.getId()), any(), anyString());

		MergeBulkResultResponse merged = service.approve(List.of(a.getId(), b.getId()), reviewer);
		MergeBulkResultResponse rejected = service.reject(List.of(b.getId()), "사유", reviewer);

		assertThat(merged.skipped()).isEqualTo(1);
		assertThat(merged.results().get(0).result()).isEqualTo(Result.SKIPPED);
		assertThat(merged.results().get(0).errorCode()).isEqualTo("MERGE_CANDIDATE_NOT_PENDING");
		assertThat(rejected.skipped()).isEqualTo(1);
	}

	// --- 감사 기록 ---

	@Test
	@DisplayName("감사 기록은 성공 건마다 1건, 단건과 같은 문구에 '일괄 병합 N건 중 k번째'를 붙인다")
	void auditPerSuccess() {
		ScholarshipMergeCandidate a = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate b = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate c = candidate(scholarship(), scholarship());
		willThrow(new CustomException(ErrorCode.MERGE_FAILED)).given(dedupService).approve(eq(b.getId()), any());

		service.approve(List.of(a.getId(), b.getId(), c.getId()), reviewer);

		ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
		verify(auditLogService, times(2)).record(eq(reviewer), eq(AdminAction.SCHOLARSHIP_MERGE), eq("SCHOLARSHIP"),
				any(), detail.capture());
		assertThat(detail.getAllValues().get(0)).startsWith("일괄 병합 3건 중 1번째 · 중복 " + a.getDuplicate().getId()
				+ " 를 " + a.getPrimary().getId() + " 로 병합. ");
		assertThat(detail.getAllValues().get(1)).startsWith("일괄 병합 3건 중 3번째 · ");
	}

	@Test
	@DisplayName("반려 감사 기록에 같은 사유와 일괄 표시가 남는다")
	void rejectAudit() {
		ScholarshipMergeCandidate a = candidate(scholarship(), scholarship());

		service.reject(List.of(a.getId()), "캠퍼스가 다름", reviewer);

		verify(auditLogService).record(reviewer, AdminAction.SCHOLARSHIP_MERGE_REJECT, "SCHOLARSHIP",
				a.getPrimary().getId(), "일괄 중복 아님 1건 중 1번째 · 중복 후보 " + a.getId() + " 반려 ("
						+ a.getPrimary().getId() + " vs " + a.getDuplicate().getId() + "). 캠퍼스가 다름");
	}

	@Test
	@DisplayName("실패한 후보는 MERGE_CANDIDATE_FAILED 로 남긴다(건너뜀은 남기지 않음)")
	void auditsFailures() {
		ScholarshipMergeCandidate failed = candidate(scholarship(), scholarship());
		ScholarshipMergeCandidate skipped = candidate(scholarship(), scholarship());
		willThrow(new CustomException(ErrorCode.MERGE_FAILED)).given(dedupService).approve(eq(failed.getId()), any());
		willThrow(new CustomException(ErrorCode.MERGE_CANDIDATE_NOT_PENDING))
				.given(dedupService).approve(eq(skipped.getId()), any());

		service.approve(List.of(failed.getId(), skipped.getId()), reviewer);

		ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
		verify(auditLogService, times(1)).record(eq(reviewer), eq(AdminAction.MERGE_CANDIDATE_FAILED), eq("SCHOLARSHIP"),
				eq(failed.getPrimary().getId()), detail.capture());
		assertThat(detail.getValue()).startsWith("일괄 병합 2건 중 1번째 · 중복 후보 " + failed.getId() + " (")
				.contains("실패: MERGE_FAILED");
	}

	// --- 도우미 ---

	private MergeResultResponse merged(Long candidateId) {
		ScholarshipMergeCandidate c = find(candidateId);
		return new MergeResultResponse(c.getId(), "MERGED", c.getPrimary().getId(), c.getDuplicate().getId(),
				Map.of("scrap.moved", 1));
	}

	private ScholarshipMergeCandidate find(Long id) {
		return stored.stream().filter(c -> c.getId().equals(id)).findFirst().orElseThrow();
	}

	private static MergeBulkConflictResponse conflicts(Runnable call) {
		try {
			call.run();
		} catch (CustomDetailException e) {
			assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MERGE_BULK_CONFLICT);
			return (MergeBulkConflictResponse) e.getDetail();
		}
		throw new AssertionError("MERGE_BULK_CONFLICT 가 나야 한다");
	}

	private static void assertCode(Runnable call, ErrorCode code) {
		assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
				.satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
	}

	private Scholarship scholarship() {
		Scholarship scholarship = Scholarship.builder()
				.title("장학금 " + nextId)
				.provider("재단")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.dedupKey("key-" + nextId)
				.build();
		setField(scholarship, "id", nextId++);
		return scholarship;
	}

	private ScholarshipMergeCandidate candidate(Scholarship primary, Scholarship duplicate) {
		ScholarshipMergeCandidate candidate = ScholarshipMergeCandidate.builder()
				.primary(primary).duplicate(duplicate).reason("같은 공고").build();
		setField(candidate, "id", nextId++);
		stored.add(candidate);
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
}
