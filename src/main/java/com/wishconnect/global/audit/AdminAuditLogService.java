package com.wishconnect.global.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
관리자 쓰기 작업 기록.

기록은 본 작업과 독립이어야 한다.
- REQUIRES_NEW: 본 작업이 나중에 롤백돼도 "시도했다"는 사실은 남는다.
- 예외를 삼킨다: 감사 기록이 실패했다고 관리자 작업 자체를 실패시키면 안 된다.
  프로젝트 규칙상 try-catch 를 남발하지 않지만, 여기는 삼키는 것이 의도된 동작이라 예외로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

	private static final int DETAIL_MAX = 1000;

	private final AdminAuditLogRepository adminAuditLogRepository;
	private final ObjectMapper objectMapper;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(UUID actorId, AdminAction action, String targetType, Long targetId, String detail) {
		try {
			adminAuditLogRepository.save(AdminAuditLog.builder()
					.actorId(actorId)
					.action(action)
					.targetType(targetType)
					.targetId(targetId)
					.detail(truncate(detail))
					.build());
		} catch (RuntimeException e) {
			log.error("[AdminAudit] 감사 기록 실패 actor={} action={} target={}/{}",
					actorId, action, targetType, targetId, e);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordChange(UUID actorId, AdminAction action, String targetType, Long targetId,
			String detail, Object before, Object after) {
		try {
			adminAuditLogRepository.save(AdminAuditLog.builder()
					.actorId(actorId).action(action).targetType(targetType).targetId(targetId)
					.detail(truncate(detail)).beforeJson(json(before)).afterJson(json(after)).build());
		} catch (RuntimeException | JsonProcessingException e) {
			log.error("[AdminAudit] 변경 스냅샷 기록 실패 actor={} action={} target={}/{}",
					actorId, action, targetType, targetId, e);
		}
	}

	@Transactional(readOnly = true)
	public Page<AdminAuditLog> find(AdminAction action, Pageable pageable) {
		return action == null
				? adminAuditLogRepository.findAllByOrderByIdDesc(pageable)
				: adminAuditLogRepository.findAllByActionOrderByIdDesc(action, pageable);
	}

	/** detail 이 길어 컬럼을 넘치면 저장 자체가 실패한다. 기록이 사라지느니 잘라서 남긴다. */
	private String truncate(String detail) {
		if (detail == null || detail.length() <= DETAIL_MAX) {
			return detail;
		}
		return detail.substring(0, DETAIL_MAX - 3) + "...";
	}

	private String json(Object value) throws JsonProcessingException {
		return value == null ? null : objectMapper.writeValueAsString(value);
	}
}
