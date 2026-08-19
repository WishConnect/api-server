package com.wishconnect.global.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.service.ScholarshipManualService;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditRestoreService {

	private static final Set<AdminAction> RESTORABLE_ACTIONS = Set.of(
			AdminAction.SCHOLARSHIP_UPDATE, AdminAction.SCHOLARSHIP_DELETE);

	private final AdminAuditLogRepository repository;
	private final ScholarshipManualService scholarshipManualService;
	private final ObjectMapper objectMapper;

	@Transactional
	public void restore(Long logId, UUID actorId) {
		AdminAuditLog log = repository.findById(logId)
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
		if (!RESTORABLE_ACTIONS.contains(log.getAction())
				|| !"SCHOLARSHIP".equals(log.getTargetType())
				|| log.getTargetId() == null || log.getBeforeJson() == null || log.getRestoredAt() != null) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		try {
			ScholarshipAdminSnapshot snapshot = objectMapper.readValue(
					log.getBeforeJson(), ScholarshipAdminSnapshot.class);
			scholarshipManualService.restore(log.getTargetId(), snapshot);
			log.markRestored(actorId);
		} catch (JsonProcessingException exception) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
	}
}
