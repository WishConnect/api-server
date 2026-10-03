package com.wishconnect.global.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields.Kind;
import com.wishconnect.domain.scholarship.service.ScholarshipFieldRestorer;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 감사 로그 복구. 미리보기로 필드별 비교를 보여 주고, 고른 필드만 되돌리며, 복구 자체를 새 감사 기록으로 남긴다.
 *
 * <p>복구 대상: 수기 수정·내리기·통합 수정, 그리고 복구 기록(AUDIT_RESTORE — 복구를 다시 되돌릴 때).
 * 한 기록은 한 번만 복구한다. 일부 필드만 되돌렸더라도 같다 — 더 되돌리려면 새 미리보기에서 현재 값을 보고
 * 통합 수정이나 복구 기록 되돌리기를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditRestoreService {

	static final Set<AdminAction> RESTORABLE_ACTIONS = Set.of(
			AdminAction.SCHOLARSHIP_UPDATE, AdminAction.SCHOLARSHIP_DELETE,
			AdminAction.SCHOLARSHIP_AGGREGATE_UPDATE, AdminAction.AUDIT_RESTORE);

	private final AdminAuditLogRepository repository;
	private final ScholarshipFieldRestorer fieldRestorer;
	private final AdminAuditLogService adminAuditLogService;
	private final ObjectMapper objectMapper;

	public static boolean isRestorable(AdminAuditLog log) {
		return notRestorableReason(log) == null;
	}

	@Transactional(readOnly = true)
	public AuditRestorePreviewResponse preview(Long logId) {
		return buildPreview(find(logId));
	}

	/**
	 * 고른 필드만 복구한다.
	 *
	 * @param fields null 이면 미리보기의 기본 선택 필드(모집 상태·이후 변경 필드 제외)만 되돌린다.
	 *               예전 콘솔의 복구 버튼(본문 없는 PATCH)이 이 경로를 탄다
	 */
	@Transactional
	public AuditRestoreResultResponse restore(Long logId, UUID actorId, List<String> fields, String reason) {
		AdminAuditLog log = find(logId);
		if (log.getRestoredAt() != null) {
			throw new CustomException(ErrorCode.AUDIT_ALREADY_RESTORED);
		}
		if (!isRestorable(log)) {
			throw new CustomException(ErrorCode.AUDIT_RESTORE_NOT_SUPPORTED);
		}
		AuditRestorePreviewResponse preview = buildPreview(log);
		Set<String> allowed = new LinkedHashSet<>();
		Set<String> defaults = new LinkedHashSet<>();
		for (AuditRestorePreviewResponse.FieldPreview field : preview.fields()) {
			allowed.add(field.field());
			if (field.defaultSelected()) {
				defaults.add(field.field());
			}
		}
		Set<String> selected = fields == null ? defaults : new LinkedHashSet<>(fields);
		if (selected.isEmpty()) {
			throw new CustomException(ErrorCode.AUDIT_RESTORE_FIELDS_REQUIRED);
		}
		if (!allowed.containsAll(selected)) {
			throw new CustomException(ErrorCode.AUDIT_RESTORE_INVALID_FIELD);
		}

		JsonNode recorded = readTree(log.getBeforeJson());
		Kind kind = ScholarshipChangeFields.kindOf(recorded);
		JsonNode before = fieldRestorer.currentSnapshot(kind, log.getTargetId());
		fieldRestorer.restore(kind, log.getTargetId(), recorded, selected);
		JsonNode after = fieldRestorer.currentSnapshot(kind, log.getTargetId());
		log.markRestored(actorId);

		List<String> labels = selected.stream().map(ScholarshipChangeFields::label).toList();
		String detail = "감사 기록 #" + logId + " 복구: " + String.join(", ", labels)
				+ (StringUtils.hasText(reason) ? " / 사유: " + reason.trim() : "");
		Long restoreLogId = adminAuditLogService.recordChange(actorId, AdminAction.AUDIT_RESTORE,
				"SCHOLARSHIP", log.getTargetId(), detail, before, after);
		return new AuditRestoreResultResponse(logId, log.getTargetId(), List.copyOf(selected), restoreLogId);
	}

	private AuditRestorePreviewResponse buildPreview(AdminAuditLog log) {
		String reason = notRestorableReason(log);
		List<AuditRestorePreviewResponse.LaterChange> later = log.getTargetId() == null ? List.of()
				: repository.findTop20ByTargetTypeAndTargetIdAndIdGreaterThanOrderByIdDesc(
						log.getTargetType(), log.getTargetId(), log.getId()).stream()
						.map(value -> new AuditRestorePreviewResponse.LaterChange(value.getId(), value.getAction(),
								value.getActorId(), value.getCreatedAt(), value.getDetail()))
						.toList();
		if (log.getBeforeJson() == null || !"SCHOLARSHIP".equals(log.getTargetType()) || log.getTargetId() == null) {
			return new AuditRestorePreviewResponse(log.getId(), log.getAction(), log.getTargetType(),
					log.getTargetId(), log.getCreatedAt(), log.getActorId(), false, reason, log.getRestoredAt(),
					!later.isEmpty(), later, List.of());
		}

		JsonNode recorded = readTree(log.getBeforeJson());
		JsonNode logged = log.getAfterJson() == null ? null : readTree(log.getAfterJson());
		Kind kind = ScholarshipChangeFields.kindOf(recorded);
		JsonNode current = fieldRestorer.currentSnapshot(kind, log.getTargetId());

		List<String> changed = logged == null
				? ScholarshipChangeFields.changed(kind, recorded, current)
				: ScholarshipChangeFields.changed(kind, recorded, logged);
		List<AuditRestorePreviewResponse.FieldPreview> fields = new ArrayList<>();
		boolean anyChangedSince = false;
		for (String field : changed) {
			JsonNode recordedValue = ScholarshipChangeFields.value(kind, recorded, field);
			JsonNode loggedValue = logged == null ? NullNode.getInstance()
					: ScholarshipChangeFields.value(kind, logged, field);
			JsonNode currentValue = ScholarshipChangeFields.value(kind, current, field);
			boolean differs = !recordedValue.equals(currentValue);
			boolean changedSince = logged != null && !loggedValue.equals(currentValue);
			anyChangedSince |= changedSince;
			boolean status = ScholarshipChangeFields.RECRUITMENT_STATUS.equals(field);
			fields.add(new AuditRestorePreviewResponse.FieldPreview(field, ScholarshipChangeFields.label(field),
					recordedValue, loggedValue, currentValue, differs, changedSince,
					differs && !changedSince && !status, warning(status, changedSince, differs, kind, current)));
		}
		return new AuditRestorePreviewResponse(log.getId(), log.getAction(), log.getTargetType(), log.getTargetId(),
				log.getCreatedAt(), log.getActorId(), reason == null, reason, log.getRestoredAt(),
				anyChangedSince || !later.isEmpty(), later, fields);
	}

	private static String warning(boolean status, boolean changedSince, boolean differs, Kind kind, JsonNode current) {
		if (!differs) {
			return "현재 값과 같아 복구해도 바뀌지 않습니다.";
		}
		if (status) {
			JsonNode end = ScholarshipChangeFields.value(kind, current, "applicationEndAt");
			return "모집 상태는 기본으로 선택하지 않습니다. 현재 마감일(" + (end.isNull() ? "없음" : end.asText())
					+ ")과 맞는지 확인한 뒤 직접 고르세요.";
		}
		if (changedSince) {
			return "기록 이후 다시 바뀐 값입니다. 복구하면 그 변경을 덮어씁니다.";
		}
		return null;
	}

	static String notRestorableReason(AdminAuditLog log) {
		if (log.getRestoredAt() != null) {
			return "이미 복구한 기록입니다.";
		}
		if (!RESTORABLE_ACTIONS.contains(log.getAction())) {
			return "자동 복구를 지원하지 않는 작업입니다.";
		}
		if (!"SCHOLARSHIP".equals(log.getTargetType()) || log.getTargetId() == null || log.getBeforeJson() == null) {
			return "변경 전 값이 남아 있지 않은 기록입니다.";
		}
		return null;
	}

	private AdminAuditLog find(Long logId) {
		return repository.findById(logId).orElseThrow(() -> new CustomException(ErrorCode.AUDIT_LOG_NOT_FOUND));
	}

	private JsonNode readTree(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JsonProcessingException e) {
			throw new CustomException(ErrorCode.AUDIT_RESTORE_NOT_SUPPORTED, e);
		}
	}
}
