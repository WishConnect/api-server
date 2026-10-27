package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.dto.RawDismissResultResponse;
import com.wishconnect.domain.scholarship.dto.RawRestoreResultResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawDismissReason;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실패 재처리 원본 만료 처리·되돌리기.
 *
 * <p>지키려는 것: 원본을 지우지 않고 dismissed_* 만 채운다 / 연결 장학금은 요청할 때만, 삭제가 아니라 마감으로 /
 * 이미 마감·내려진 장학금은 건너뛴다 / 사유·메모·건수 검증 / 되돌리기는 장학금 상태를 바꾸지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RawScholarshipDismissServiceTest {

	@Mock private RawScholarshipRepository rawScholarshipRepository;
	@Mock private AdminAuditLogService auditLogService;
	@Mock private TransactionTemplate transactionTemplate;

	private RawScholarshipDismissService service;
	private final Map<Long, RawScholarship> raws = new HashMap<>();
	private final UUID actor = UUID.randomUUID();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		service = new RawScholarshipDismissService(rawScholarshipRepository, auditLogService, transactionTemplate);
		given(transactionTemplate.execute(any())).willAnswer(invocation ->
				((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
		given(rawScholarshipRepository.findById(anyLong()))
				.willAnswer(invocation -> Optional.ofNullable(raws.get(invocation.<Long>getArgument(0))));
	}

	// --- 검증 ---

	@Test
	@DisplayName("사유는 필수, 기타면 메모 필수, 메모는 200자까지")
	void validatesReasonAndNote() {
		raw(1L, ParseStatus.FAILED, null);
		assertCode(() -> service.dismiss(List.of(1L), null, null, false, actor), ErrorCode.RAW_DISMISS_REASON_REQUIRED);
		assertCode(() -> service.dismiss(List.of(1L), RawDismissReason.OTHER, "  ", false, actor),
				ErrorCode.RAW_DISMISS_NOTE_REQUIRED);
		assertCode(() -> service.dismiss(List.of(1L), RawDismissReason.DUPLICATE, "가".repeat(201), false, actor),
				ErrorCode.RAW_DISMISS_NOTE_TOO_LONG);
		assertThat(raws.get(1L).isDismissed()).isFalse();

		// 기타가 아니면 메모 없이도 된다. 200자 메모는 허용, 앞뒤 공백은 지운다.
		service.dismiss(List.of(1L), RawDismissReason.OTHER, "  " + "가".repeat(200) + " ", false, actor);
		assertThat(raws.get(1L).getDismissedNote()).isEqualTo("가".repeat(200));
	}

	@Test
	@DisplayName("0건·101건·null 원소는 RAW_DISMISS_SIZE_INVALID, 100건은 허용, 같은 ID 는 한 번만 처리")
	void sizeLimit() {
		assertCode(() -> service.dismiss(List.of(), RawDismissReason.DUPLICATE, null, false, actor),
				ErrorCode.RAW_DISMISS_SIZE_INVALID);
		List<Long> hundredOne = LongStream.rangeClosed(1, 101).boxed().toList();
		assertCode(() -> service.dismiss(hundredOne, RawDismissReason.DUPLICATE, null, false, actor),
				ErrorCode.RAW_DISMISS_SIZE_INVALID);
		assertCode(() -> service.restore(hundredOne, actor), ErrorCode.RAW_DISMISS_SIZE_INVALID);
		assertCode(() -> service.restore(null, actor), ErrorCode.RAW_DISMISS_SIZE_INVALID);

		List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList();
		hundred.forEach(id -> raw(id, ParseStatus.FAILED, null));
		assertThat(service.dismiss(hundred, RawDismissReason.DUPLICATE, null, false, actor).dismissed()).isEqualTo(100);

		RawDismissResultResponse twice = service.restore(List.of(5L, 5L), actor).restored() == 1
				? service.dismiss(List.of(5L, 5L), RawDismissReason.DUPLICATE, null, false, actor) : null;
		assertThat(twice.total()).isEqualTo(1);
	}

	// --- 만료 처리 ---

	@Test
	@DisplayName("만료 처리는 dismissed_* 만 채우고 파싱 상태·오류는 그대로 둔다")
	void dismissKeepsParseState() {
		RawScholarship raw = raw(1L, ParseStatus.IMAGE_ONLY, null);

		RawDismissResultResponse result = service.dismiss(List.of(1L), RawDismissReason.NOT_SCHOLARSHIP, "행사 안내",
				false, actor);

		assertThat(result.dismissed()).isEqualTo(1);
		assertThat(raw.isDismissed()).isTrue();
		assertThat(raw.getDismissedReason()).isEqualTo(RawDismissReason.NOT_SCHOLARSHIP);
		assertThat(raw.getDismissedNote()).isEqualTo("행사 안내");
		assertThat(raw.getParseStatus()).isEqualTo(ParseStatus.IMAGE_ONLY);
		assertThat(raw.getParseError()).isEqualTo("원래 오류");
	}

	@Test
	@DisplayName("없는 원본은 FAILED, 이미 만료 처리됐거나 실패 상태가 아닌 원본은 SKIPPED 로 남기고 나머지는 계속한다")
	void perItemResults() {
		raw(1L, ParseStatus.FAILED, null);
		raw(2L, ParseStatus.PARSED, null);
		RawScholarship done = raw(3L, ParseStatus.SKIPPED, null);
		done.dismiss(RawDismissReason.DUPLICATE, null, LocalDateTime.now());

		RawDismissResultResponse result = service.dismiss(List.of(99L, 2L, 3L, 1L), RawDismissReason.PERIOD_EXPIRED,
				null, false, actor);

		assertThat(result.results()).extracting(RawDismissResultResponse.Item::result).containsExactly(
				RawDismissResultResponse.Result.FAILED, RawDismissResultResponse.Result.SKIPPED,
				RawDismissResultResponse.Result.SKIPPED, RawDismissResultResponse.Result.DISMISSED);
		assertThat(done.getDismissedReason()).isEqualTo(RawDismissReason.DUPLICATE);
	}

	@Test
	@DisplayName("closeLinkedScholarship=true 면 연결 장학금을 삭제하지 않고 CLOSED 로, 장학금 기록(스냅샷)도 남긴다")
	void closesLinkedScholarship() {
		Scholarship open = scholarship(10L, RecruitmentStatus.OPEN, LocalDateTime.now().plusDays(30));
		raw(1L, ParseStatus.FAILED, open);

		RawDismissResultResponse result = service.dismiss(List.of(1L), RawDismissReason.PERIOD_EXPIRED, null, true,
				actor);

		assertThat(open.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(open.isDeleted()).isFalse();
		assertThat(result.scholarshipClosed()).isEqualTo(1);
		assertThat(result.results().get(0).scholarshipClosed()).isTrue();
		assertThat(result.results().get(0).warningOpenScholarship()).isTrue();
		verify(auditLogService).recordChange(eq(actor), eq(AdminAction.SCHOLARSHIP_UPDATE), eq("SCHOLARSHIP"),
				eq(10L), contains("원본 #1 만료 처리로 마감"), any(ScholarshipAdminSnapshot.class),
				any(ScholarshipAdminSnapshot.class));
	}

	@Test
	@DisplayName("이미 마감이거나 내려진 장학금은 마감 처리를 건너뛴다(원본 만료 처리는 된다)")
	void skipsClosedOrDeletedScholarship() {
		Scholarship closed = scholarship(10L, RecruitmentStatus.CLOSED, LocalDateTime.now().minusDays(3));
		Scholarship deleted = scholarship(11L, RecruitmentStatus.OPEN, LocalDateTime.now().plusDays(3));
		deleted.softDelete();
		raw(1L, ParseStatus.FAILED, closed);
		raw(2L, ParseStatus.FAILED, deleted);

		RawDismissResultResponse result = service.dismiss(List.of(1L, 2L), RawDismissReason.PERIOD_EXPIRED, null, true,
				actor);

		assertThat(result.dismissed()).isEqualTo(2);
		assertThat(result.scholarshipClosed()).isZero();
		assertThat(deleted.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.OPEN);
		assertThat(result.results()).extracting(RawDismissResultResponse.Item::message)
				.anySatisfy(m -> assertThat(m).contains("이미 마감"))
				.anySatisfy(m -> assertThat(m).contains("내려져"));
		verify(auditLogService, never()).recordChange(any(), eq(AdminAction.SCHOLARSHIP_UPDATE), any(), any(), any(),
				any(), any());
	}

	@Test
	@DisplayName("closeLinkedScholarship=false 면 장학금을 건드리지 않고, 모집 중이면 경고만 한다(막지 않음)")
	void warnsWithoutClosing() {
		Scholarship upcoming = scholarship(10L, RecruitmentStatus.UPCOMING, LocalDateTime.now().plusDays(30));
		Scholarship ended = scholarship(11L, RecruitmentStatus.OPEN, LocalDateTime.now().minusDays(1));
		raw(1L, ParseStatus.FAILED, upcoming);
		raw(2L, ParseStatus.FAILED, ended);

		RawDismissResultResponse result = service.dismiss(List.of(1L, 2L), RawDismissReason.PERIOD_EXPIRED, null,
				false, actor);

		assertThat(upcoming.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.UPCOMING);
		assertThat(result.warnings()).isEqualTo(1);
		assertThat(result.results()).extracting(RawDismissResultResponse.Item::warningOpenScholarship)
				.containsExactly(true, false);
	}

	@Test
	@DisplayName("감사 기록은 요청당 1건, 건수·사유·장학금 마감 건수가 요약에 들어간다. 바뀐 게 없으면 남기지 않는다")
	void auditSummary() {
		Scholarship open = scholarship(10L, RecruitmentStatus.OPEN, null);
		raw(1L, ParseStatus.FAILED, open);
		raw(2L, ParseStatus.SKIPPED, null);

		service.dismiss(List.of(1L, 2L), RawDismissReason.PERIOD_EXPIRED, "2025학년도", true, actor);
		service.dismiss(List.of(1L), RawDismissReason.PERIOD_EXPIRED, null, true, actor);

		verify(auditLogService, times(1)).record(eq(actor), eq(AdminAction.RAW_SCHOLARSHIP_DISMISS),
				eq("RAW_SCHOLARSHIP"), isNull(), contains("만료 처리 2건(요청 2"));
		verify(auditLogService).record(eq(actor), eq(AdminAction.RAW_SCHOLARSHIP_DISMISS), any(), any(),
				contains("사유 모집기간 경과(PERIOD_EXPIRED) · 연결 장학금 마감 1건"));
	}

	// --- 되돌리기 ---

	@Test
	@DisplayName("되돌리기는 dismissed_* 를 비우고 장학금 모집 상태는 그대로 둔다(안내 문구 포함)")
	void restoreClearsOnlyDismissal() {
		Scholarship closed = scholarship(10L, RecruitmentStatus.CLOSED, null);
		RawScholarship raw = raw(1L, ParseStatus.FAILED, closed);
		raw.dismiss(RawDismissReason.PERIOD_EXPIRED, "메모", LocalDateTime.now());
		raw(2L, ParseStatus.FAILED, null);

		RawRestoreResultResponse result = service.restore(List.of(1L, 2L, 3L), actor);

		assertThat(raw.isDismissed()).isFalse();
		assertThat(raw.getDismissedReason()).isNull();
		assertThat(raw.getDismissedNote()).isNull();
		assertThat(closed.getRecruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
		assertThat(result.results()).extracting(RawRestoreResultResponse.Item::result).containsExactly(
				RawRestoreResultResponse.Result.RESTORED, RawRestoreResultResponse.Result.SKIPPED,
				RawRestoreResultResponse.Result.FAILED);
		assertThat(result.results().get(0).linkedRecruitmentStatus()).isEqualTo("CLOSED");
		assertThat(result.notice()).contains("모집 상태는 그대로");
		verify(auditLogService).record(eq(actor), eq(AdminAction.RAW_SCHOLARSHIP_RESTORE), eq("RAW_SCHOLARSHIP"),
				isNull(), contains("되돌리기 1건"));
	}

	// --- 도우미 ---

	private RawScholarship raw(Long id, ParseStatus status, Scholarship linked) {
		RawScholarship raw = RawScholarship.builder()
				.source("UNIV_KHU").sourceId("n-" + id).sourceUrl("https://example.com/" + id)
				.parseStatus(status).parseError("원래 오류").build();
		ReflectionTestUtils.setField(raw, "id", id);
		if (linked != null) {
			ReflectionTestUtils.setField(raw, "scholarship", linked);
		}
		raws.put(id, raw);
		return raw;
	}

	private static Scholarship scholarship(Long id, RecruitmentStatus status, LocalDateTime end) {
		Scholarship scholarship = Scholarship.builder()
				.title("장학금 " + id).provider("재단").scholarshipType(ScholarshipType.EXTERNAL)
				.applicationEndAt(end).build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		scholarship.updateRecruitmentStatusByAdmin(status);
		return scholarship;
	}

	private static void assertCode(Runnable call, ErrorCode code) {
		assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
				.satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
	}
}
