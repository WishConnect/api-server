package com.wishconnect.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.scholarship.service.ScholarshipChangeFields.Kind;
import com.wishconnect.domain.scholarship.service.ScholarshipFieldRestorer;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("감사 로그 복구 — 미리보기와 필드 선택")
class AdminAuditRestoreServiceTest {

	private static final UUID ACTOR = UUID.randomUUID();

	@Mock private AdminAuditLogRepository repository;
	@Mock private ScholarshipFieldRestorer fieldRestorer;
	@Mock private AdminAuditLogService adminAuditLogService;
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private AdminAuditRestoreService service;

	/** QA #725 재현: 8/20 에 제목이 기관명으로 덮어써졌고, 그 뒤 배치가 마감(CLOSED) 처리했다. */
	private static final String BEFORE = """
			{"title":"[조형예술학부] 국가근로장학생 모집","provider":"인천대학교","recruitmentStatus":"OPEN",
			 "applicationEndAt":"2026-08-27T23:59:00","active":true,"verified":false,"deletedAt":null}""";
	private static final String AFTER = """
			{"title":"인천대학교","provider":"인천대학교","recruitmentStatus":"OPEN",
			 "applicationEndAt":"2026-08-27T23:59:00","active":true,"verified":false,"deletedAt":null}""";
	private static final String CURRENT = """
			{"title":"인천대학교","provider":"인천대학교","recruitmentStatus":"CLOSED",
			 "applicationEndAt":"2026-08-27T23:59:00","active":false,"verified":false,"deletedAt":null}""";

	@BeforeEach
	void setUp() throws Exception {
		service = new AdminAuditRestoreService(repository, fieldRestorer, adminAuditLogService, objectMapper);
		given(repository.findTop20ByTargetTypeAndTargetIdAndIdGreaterThanOrderByIdDesc(anyString(), anyLong(), anyLong()))
				.willReturn(List.of());
		given(fieldRestorer.currentSnapshot(Kind.ADMIN_SNAPSHOT, 725L)).willReturn(objectMapper.readTree(CURRENT));
	}

	private AdminAuditLog log(AdminAction action, String before, String after) {
		AdminAuditLog log = AdminAuditLog.builder().actorId(UUID.randomUUID()).action(action)
				.targetType("SCHOLARSHIP").targetId(725L).detail("d").beforeJson(before).afterJson(after).build();
		ReflectionTestUtils.setField(log, "id", 3L);
		given(repository.findById(3L)).willReturn(Optional.of(log));
		return log;
	}

	@Test
	@DisplayName("미리보기는 이 기록에서 바뀐 필드만 보여 주고 제목은 기본 선택한다")
	void previewListsChangedFields() {
		log(AdminAction.SCHOLARSHIP_UPDATE, BEFORE, AFTER);

		AuditRestorePreviewResponse preview = service.preview(3L);

		assertThat(preview.restorable()).isTrue();
		assertThat(preview.fields()).extracting(AuditRestorePreviewResponse.FieldPreview::field)
				.containsExactly("title");
		AuditRestorePreviewResponse.FieldPreview title = preview.fields().get(0);
		assertThat(title.recordedValue().asText()).isEqualTo("[조형예술학부] 국가근로장학생 모집");
		assertThat(title.currentValue().asText()).isEqualTo("인천대학교");
		assertThat(title.defaultSelected()).isTrue();
		assertThat(title.changedSinceRecord()).isFalse();
	}

	@Test
	@DisplayName("기록 이후 배치가 바꾼 값을 감지하고, 모집 상태는 기본 선택하지 않는다")
	void detectsLaterChangeAndNeverPreselectsStatus() throws Exception {
		// 이 기록이 모집 상태를 CLOSED→OPEN 으로 바꿨고, 그 뒤 다른 변경이 다시 CLOSED 로 만든 상황.
		String before = BEFORE.replace("\"OPEN\"", "\"CLOSED\"");
		log(AdminAction.SCHOLARSHIP_UPDATE, before, AFTER);
		given(fieldRestorer.currentSnapshot(Kind.ADMIN_SNAPSHOT, 725L))
				.willReturn(objectMapper.readTree(CURRENT.replace("\"인천대학교\",\"provider\"", "\"다른 제목\",\"provider\"")));

		AuditRestorePreviewResponse preview = service.preview(3L);

		AuditRestorePreviewResponse.FieldPreview title = field(preview, "title");
		assertThat(title.changedSinceRecord()).isTrue();
		assertThat(title.defaultSelected()).isFalse();
		assertThat(title.warning()).contains("덮어씁니다");
		AuditRestorePreviewResponse.FieldPreview status = field(preview, "recruitmentStatus");
		assertThat(status.defaultSelected()).isFalse();
		assertThat(preview.changedSinceRecord()).isTrue();
	}

	@Test
	@DisplayName("고른 필드만 되돌리고, 복구를 새 감사 기록으로 남긴다")
	void restoresOnlySelectedFieldsAndRecordsRestore() {
		AdminAuditLog log = log(AdminAction.SCHOLARSHIP_UPDATE, BEFORE, AFTER);
		given(adminAuditLogService.recordChange(eq(ACTOR), eq(AdminAction.AUDIT_RESTORE), eq("SCHOLARSHIP"),
				eq(725L), anyString(), any(), any())).willReturn(99L);

		AuditRestoreResultResponse result = service.restore(3L, ACTOR, List.of("title"), "8/20 일괄 수정 정정");

		verify(fieldRestorer).restore(eq(Kind.ADMIN_SNAPSHOT), eq(725L), any(JsonNode.class), eq(Set.of("title")));
		assertThat(result.restoredFields()).containsExactly("title");
		assertThat(result.restoreLogId()).isEqualTo(99L);
		assertThat(log.getRestoredAt()).isNotNull();
		assertThat(log.getRestoredBy()).isEqualTo(ACTOR);
	}

	@Test
	@DisplayName("예전 콘솔 PATCH(필드 없음)는 기본 선택 필드만 되돌린다 — 모집 상태는 건드리지 않는다")
	void legacyRestoreUsesDefaultsOnly() {
		String before = BEFORE.replace("\"OPEN\"", "\"CLOSED\"");
		String after = AFTER;
		log(AdminAction.SCHOLARSHIP_UPDATE, before, after);

		service.restore(3L, ACTOR, null, null);

		verify(fieldRestorer).restore(eq(Kind.ADMIN_SNAPSHOT), eq(725L), any(JsonNode.class), eq(Set.of("title")));
	}

	@Test
	@DisplayName("이 기록에서 바뀌지 않은 필드는 고를 수 없다")
	void rejectsUnrelatedField() {
		log(AdminAction.SCHOLARSHIP_UPDATE, BEFORE, AFTER);

		assertThatThrownBy(() -> service.restore(3L, ACTOR, List.of("amount"), null))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.AUDIT_RESTORE_INVALID_FIELD);
		verify(fieldRestorer, never()).restore(any(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("이미 복구한 기록은 다시 복구하지 않는다")
	void rejectsSecondRestore() {
		AdminAuditLog log = log(AdminAction.SCHOLARSHIP_UPDATE, BEFORE, AFTER);
		log.markRestored(UUID.randomUUID());

		assertThatThrownBy(() -> service.restore(3L, ACTOR, List.of("title"), null))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.AUDIT_ALREADY_RESTORED);
	}

	@Test
	@DisplayName("통합 수정 기록도 복구 대상이다 — 조건 변경을 필드로 보여 준다")
	void aggregateUpdateIsRestorable() throws Exception {
		String before = """
				{"scholarship":{"title":"A","recruitmentStatus":"OPEN"},
				 "conditions":[{"id":1,"conditionType":"GRADE_LEVEL","valueString":"2학년","refs":[]}],"documents":[]}""";
		String after = """
				{"scholarship":{"title":"A","recruitmentStatus":"CLOSED"},"conditions":[],"documents":[]}""";
		log(AdminAction.SCHOLARSHIP_AGGREGATE_UPDATE, before, after);
		given(fieldRestorer.currentSnapshot(Kind.AGGREGATE, 725L)).willReturn(objectMapper.readTree(after));

		AuditRestorePreviewResponse preview = service.preview(3L);

		assertThat(preview.restorable()).isTrue();
		assertThat(preview.fields()).extracting(AuditRestorePreviewResponse.FieldPreview::field)
				.containsExactly("recruitmentStatus", "conditions");
		assertThat(field(preview, "conditions").defaultSelected()).isTrue();
		assertThat(field(preview, "recruitmentStatus").defaultSelected()).isFalse();
	}

	@Test
	@DisplayName("스냅샷이 없는 작업은 복구할 수 없다고 알려 준다")
	void reportsNotRestorable() {
		log(AdminAction.SCHOLARSHIP_MERGE, null, null);

		AuditRestorePreviewResponse preview = service.preview(3L);

		assertThat(preview.restorable()).isFalse();
		assertThat(preview.notRestorableReason()).isNotBlank();
		assertThatThrownBy(() -> service.restore(3L, ACTOR, List.of("title"), null))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.AUDIT_RESTORE_NOT_SUPPORTED);
	}

	private static AuditRestorePreviewResponse.FieldPreview field(AuditRestorePreviewResponse preview, String name) {
		return preview.fields().stream().filter(field -> field.field().equals(name)).findFirst().orElseThrow();
	}
}
