package com.wishconnect.global.audit;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.dto.ScholarshipAdminSnapshot;
import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.service.ScholarshipManualService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuditRestoreServiceTest {

	@Mock
	private AdminAuditLogRepository repository;
	@Mock
	private ScholarshipManualService scholarshipManualService;

	@Test
	@DisplayName("장학금 수기 변경 로그는 before 스냅샷으로 한 번 복구할 수 있다")
	void restoresScholarshipFromBeforeSnapshot() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		ScholarshipAdminSnapshot snapshot = new ScholarshipAdminSnapshot(
				"이전 제목", "기관", null, null, ScholarshipType.EXTERNAL, null, null,
				RecruitmentStatus.OPEN, null, 500_000L, null, true, true, null);
		AdminAuditLog log = AdminAuditLog.builder()
				.actorId(UUID.randomUUID())
				.action(AdminAction.SCHOLARSHIP_UPDATE)
				.targetType("SCHOLARSHIP")
				.targetId(10L)
				.beforeJson(objectMapper.writeValueAsString(snapshot))
				.build();
		given(repository.findById(3L)).willReturn(Optional.of(log));
		UUID restorer = UUID.randomUUID();
		AdminAuditRestoreService service = new AdminAuditRestoreService(
				repository, scholarshipManualService, objectMapper);

		service.restore(3L, restorer);

		verify(scholarshipManualService).restore(10L, snapshot);
		org.assertj.core.api.Assertions.assertThat(log.getRestoredAt()).isNotNull();
		org.assertj.core.api.Assertions.assertThat(log.getRestoredBy()).isEqualTo(restorer);
	}
}
