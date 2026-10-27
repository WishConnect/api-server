package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.RawDismissResultResponse;
import com.wishconnect.domain.scholarship.dto.RawRestoreResultResponse;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.entity.ParseStatus;
import com.wishconnect.domain.scholarship.entity.RawDismissReason;
import com.wishconnect.domain.scholarship.entity.RawScholarship;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.RawScholarshipRepository;
import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
실패 재처리 원본의 "만료 처리"(목록에서 숨기기)와 되돌리기.

파싱 실패라 마감일을 모르는 원본은 "모집기간 경과 제외"에 걸리지 않아 실패 재처리 목록에 계속 남는다. 관리자가 더 볼
필요가 없다고 판단한 원본을 만료 처리하면 목록·배지·대시보드에서 빠지고, 매일 배치·LLM 재처리·공공데이터 동기화가 그
원본을 다시 처리하지 않는다. 원본은 지우지 않으며(dismissed_* 만 채운다) 되돌리면 그대로 돌아온다.

건마다 별도 트랜잭션으로 처리한다. 한 건이 실패해도 나머지는 남고, 화면은 건별 결과를 보여 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawScholarshipDismissService {

	static final int MAX_BULK = 100;
	static final int MAX_NOTE = 200;
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final Set<ParseStatus> DISMISSABLE = Set.of(ParseStatus.FAILED, ParseStatus.SKIPPED,
			ParseStatus.IMAGE_ONLY);
	private static final Map<RawDismissReason, String> REASON_LABEL = Map.of(
			RawDismissReason.PERIOD_EXPIRED, "모집기간 경과",
			RawDismissReason.NOT_SCHOLARSHIP, "장학금 공고 아님",
			RawDismissReason.DUPLICATE, "중복",
			RawDismissReason.OTHER, "기타");

	private final RawScholarshipRepository rawScholarshipRepository;
	private final AdminAuditLogService adminAuditLogService;
	private final TransactionTemplate transactionTemplate;

	public RawDismissResultResponse dismiss(List<Long> rawIds, RawDismissReason reason, String note,
			boolean closeLinkedScholarship, UUID actor) {
		List<Long> ids = checkSize(rawIds);
		if (reason == null) {
			throw new CustomException(ErrorCode.RAW_DISMISS_REASON_REQUIRED);
		}
		String trimmed = note == null || note.isBlank() ? null : note.trim();
		if (reason == RawDismissReason.OTHER && trimmed == null) {
			throw new CustomException(ErrorCode.RAW_DISMISS_NOTE_REQUIRED);
		}
		if (trimmed != null && trimmed.length() > MAX_NOTE) {
			throw new CustomException(ErrorCode.RAW_DISMISS_NOTE_TOO_LONG);
		}

		LocalDateTime now = LocalDateTime.now();
		List<RawDismissResultResponse.Item> results = new ArrayList<>();
		for (Long id : ids) {
			try {
				results.add(transactionTemplate.execute(
						status -> dismissOne(id, reason, trimmed, closeLinkedScholarship, actor, now)));
			} catch (RuntimeException e) {
				log.error("[RawDismiss] 만료 처리 실패 rawId={}", id, e);
				results.add(new RawDismissResultResponse.Item(id, RawDismissResultResponse.Result.FAILED, null,
						false, false, "처리 중 오류가 나 바뀌지 않았습니다."));
			}
		}
		RawDismissResultResponse response = RawDismissResultResponse.of(results);
		if (response.dismissed() > 0) {
			adminAuditLogService.record(actor, AdminAction.RAW_SCHOLARSHIP_DISMISS, "RAW_SCHOLARSHIP",
					ids.size() == 1 ? ids.get(0) : null, dismissSummary(response, reason, trimmed));
		}
		log.info("[RawDismiss] 만료 처리 요청={} 처리={} 건너뜀={} 실패={} 장학금마감={}",
				response.total(), response.dismissed(), response.skipped(), response.failed(),
				response.scholarshipClosed());
		return response;
	}

	public RawRestoreResultResponse restore(List<Long> rawIds, UUID actor) {
		List<Long> ids = checkSize(rawIds);
		List<RawRestoreResultResponse.Item> results = new ArrayList<>();
		for (Long id : ids) {
			try {
				results.add(transactionTemplate.execute(status -> restoreOne(id)));
			} catch (RuntimeException e) {
				log.error("[RawDismiss] 되돌리기 실패 rawId={}", id, e);
				results.add(new RawRestoreResultResponse.Item(id, RawRestoreResultResponse.Result.FAILED, null, null,
						"처리 중 오류가 나 바뀌지 않았습니다."));
			}
		}
		RawRestoreResultResponse response = RawRestoreResultResponse.of(results);
		if (response.restored() > 0) {
			List<Long> restored = response.results().stream()
					.filter(item -> item.result() == RawRestoreResultResponse.Result.RESTORED)
					.map(RawRestoreResultResponse.Item::rawId).toList();
			adminAuditLogService.record(actor, AdminAction.RAW_SCHOLARSHIP_RESTORE, "RAW_SCHOLARSHIP",
					ids.size() == 1 ? ids.get(0) : null,
					"만료 처리 되돌리기 %d건(요청 %d · 건너뜀 %d · 실패 %d) · 장학금 상태는 그대로 · 원본 %s".formatted(
							response.restored(), response.total(), response.skipped(), response.failed(),
							joinIds(restored)));
		}
		return response;
	}

	private RawDismissResultResponse.Item dismissOne(Long id, RawDismissReason reason, String note, boolean close,
			UUID actor, LocalDateTime now) {
		RawScholarship raw = rawScholarshipRepository.findById(id).orElse(null);
		if (raw == null) {
			return new RawDismissResultResponse.Item(id, RawDismissResultResponse.Result.FAILED, null, false, false,
					"없는 원본입니다.");
		}
		Scholarship scholarship = raw.getScholarship();
		Long linkedId = scholarship == null ? null : scholarship.getId();
		if (raw.isDismissed()) {
			return new RawDismissResultResponse.Item(id, RawDismissResultResponse.Result.SKIPPED, linkedId, false,
					false, "이미 만료 처리된 원본입니다.");
		}
		if (!DISMISSABLE.contains(raw.getParseStatus())) {
			return new RawDismissResultResponse.Item(id, RawDismissResultResponse.Result.SKIPPED, linkedId, false,
					false, "실패·건너뜀·이미지만 있는 원본만 만료 처리할 수 있습니다(현재 " + raw.getParseStatus() + ").");
		}
		boolean warning = stillOpen(scholarship);
		raw.dismiss(reason, note, now);

		boolean closed = false;
		String message = "만료 처리했습니다.";
		if (close && scholarship != null) {
			if (scholarship.isDeleted()) {
				message = "만료 처리했습니다. 연결 장학금 #" + linkedId + " 은(는) 내려져 있어 마감하지 않았습니다.";
			} else if (scholarship.getRecruitmentStatus() == RecruitmentStatus.CLOSED) {
				message = "만료 처리했습니다. 연결 장학금 #" + linkedId + " 은(는) 이미 마감입니다.";
			} else {
				ScholarshipAdminSnapshot before = ScholarshipAdminSnapshot.from(scholarship);
				scholarship.updateRecruitmentStatusByAdmin(RecruitmentStatus.CLOSED);
				closed = true;
				message = "만료 처리하고 연결 장학금 #" + linkedId + " 을(를) 마감했습니다.";
				// 장학금 쓰기는 장학금 기록으로도 남긴다. 작업 기록의 복구로 모집 상태를 되돌릴 수 있다.
				adminAuditLogService.recordChange(actor, AdminAction.SCHOLARSHIP_UPDATE, "SCHOLARSHIP", linkedId,
						scholarship.getTitle() + " · 원본 #" + id + " 만료 처리로 마감 · 변경: 모집 상태("
								+ before.recruitmentStatus() + "→CLOSED)",
						before, ScholarshipAdminSnapshot.from(scholarship));
			}
		} else if (warning) {
			message = "만료 처리했습니다. 연결 장학금 #" + linkedId + " 은(는) 아직 모집 중으로 남아 있습니다.";
		}
		return new RawDismissResultResponse.Item(id, RawDismissResultResponse.Result.DISMISSED, linkedId, closed,
				warning, message);
	}

	private RawRestoreResultResponse.Item restoreOne(Long id) {
		RawScholarship raw = rawScholarshipRepository.findById(id).orElse(null);
		if (raw == null) {
			return new RawRestoreResultResponse.Item(id, RawRestoreResultResponse.Result.FAILED, null, null,
					"없는 원본입니다.");
		}
		Scholarship scholarship = raw.getScholarship();
		Long linkedId = scholarship == null ? null : scholarship.getId();
		String linkedStatus = scholarship == null || scholarship.getRecruitmentStatus() == null
				? null : scholarship.getRecruitmentStatus().name();
		if (!raw.isDismissed()) {
			return new RawRestoreResultResponse.Item(id, RawRestoreResultResponse.Result.SKIPPED, linkedId,
					linkedStatus, "만료 처리된 원본이 아닙니다.");
		}
		raw.clearDismissal();
		return new RawRestoreResultResponse.Item(id, RawRestoreResultResponse.Result.RESTORED, linkedId, linkedStatus,
				"되돌렸습니다. 실패 재처리 목록에 다시 보입니다.");
	}

	/** 연결 장학금이 아직 사용자에게 모집 중으로 보이는지(OPEN/UPCOMING 이고 마감 전). 공고 날짜는 한국 날짜다. */
	static boolean stillOpen(Scholarship scholarship) {
		if (scholarship == null || scholarship.isDeleted()) {
			return false;
		}
		RecruitmentStatus status = scholarship.getRecruitmentStatus();
		if (status != RecruitmentStatus.OPEN && status != RecruitmentStatus.UPCOMING) {
			return false;
		}
		LocalDateTime end = scholarship.getApplicationEndAt();
		return end == null || end.isAfter(LocalDateTime.now(KOREA));
	}

	private static List<Long> checkSize(List<Long> rawIds) {
		if (rawIds == null || rawIds.isEmpty() || rawIds.stream().anyMatch(Objects::isNull)) {
			throw new CustomException(ErrorCode.RAW_DISMISS_SIZE_INVALID);
		}
		// 같은 ID 를 두 번 보내면 한 번만 처리한다(두 번째는 "이미 만료 처리"로 보일 뿐 해가 없지만 결과가 헷갈린다).
		List<Long> ids = List.copyOf(new LinkedHashSet<>(rawIds));
		if (ids.size() > MAX_BULK) {
			throw new CustomException(ErrorCode.RAW_DISMISS_SIZE_INVALID);
		}
		return ids;
	}

	private static String dismissSummary(RawDismissResultResponse response, RawDismissReason reason, String note) {
		List<Long> dismissed = response.results().stream()
				.filter(item -> item.result() == RawDismissResultResponse.Result.DISMISSED)
				.map(RawDismissResultResponse.Item::rawId).toList();
		return "만료 처리 %d건(요청 %d · 건너뜀 %d · 실패 %d) · 사유 %s(%s) · 연결 장학금 마감 %d건%s%s · 원본 %s".formatted(
				response.dismissed(), response.total(), response.skipped(), response.failed(),
				REASON_LABEL.get(reason), reason.name(), response.scholarshipClosed(),
				response.warnings() > 0 ? " · 모집 중 장학금 연결 " + response.warnings() + "건" : "",
				note == null ? "" : " · 메모: " + note, joinIds(dismissed));
	}

	private static String joinIds(List<Long> ids) {
		return String.join(", ", ids.stream().map(id -> "#" + id).toList());
	}
}
