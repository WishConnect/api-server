package com.wishconnect.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuditLogServiceTest {

	private static final UUID ACTOR = UUID.randomUUID();

	@Mock
	private AdminAuditLogRepository adminAuditLogRepository;

	@InjectMocks
	private AdminAuditLogService service;

	@Test
	@DisplayName("행위자·행위·대상·내용을 그대로 남긴다")
	void recordsWhatHappened() {
		service.record(ACTOR, AdminAction.SCHOLARSHIP_UPDATE, "SCHOLARSHIP", 101L, "미래인재 장학금");

		ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
		verify(adminAuditLogRepository).save(captor.capture());
		AdminAuditLog saved = captor.getValue();
		assertThat(saved.getActorId()).isEqualTo(ACTOR);
		assertThat(saved.getAction()).isEqualTo(AdminAction.SCHOLARSHIP_UPDATE);
		assertThat(saved.getTargetType()).isEqualTo("SCHOLARSHIP");
		assertThat(saved.getTargetId()).isEqualTo(101L);
		assertThat(saved.getDetail()).isEqualTo("미래인재 장학금");
	}

	/** 감사 기록이 실패했다고 관리자 작업 자체를 실패시키면 안 된다. */
	@Test
	@DisplayName("기록에 실패해도 예외를 밖으로 내보내지 않는다")
	void swallowsFailureSoCallerIsNotBroken() {
		willThrow(new RuntimeException("DB 다운")).given(adminAuditLogRepository).save(any());

		assertThatCode(() -> service.record(ACTOR, AdminAction.EXCEL_IMPORT, null, null, "반영 34행"))
				.doesNotThrowAnyException();
	}

	/** detail 이 컬럼 길이를 넘치면 저장 자체가 실패한다. 기록이 사라지느니 잘라서 남긴다. */
	@Test
	@DisplayName("detail 이 너무 길면 잘라서 저장한다")
	void truncatesOverlongDetail() {
		service.record(ACTOR, AdminAction.EXCEL_IMPORT, null, null, "가".repeat(2000));

		ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
		verify(adminAuditLogRepository).save(captor.capture());
		assertThat(captor.getValue().getDetail()).hasSize(1000).endsWith("...");
	}

	@Test
	@DisplayName("action 을 주지 않으면 전체를 최신순으로 조회한다")
	void findsAllWhenActionIsNull() {
		given(adminAuditLogRepository.findAllByOrderByIdDesc(any())).willReturn(Page.empty());

		service.find(null, PageRequest.of(0, 50));

		verify(adminAuditLogRepository).findAllByOrderByIdDesc(any());
	}

	@Test
	@DisplayName("action 을 주면 그 행위만 조회한다")
	void findsByActionWhenGiven() {
		given(adminAuditLogRepository.findAllByActionOrderByIdDesc(any(), any())).willReturn(Page.empty());

		service.find(AdminAction.EXCEL_IMPORT, PageRequest.of(0, 50));

		verify(adminAuditLogRepository).findAllByActionOrderByIdDesc(
				org.mockito.ArgumentMatchers.eq(AdminAction.EXCEL_IMPORT), any());
	}
}
