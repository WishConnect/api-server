package com.wishconnect.global.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

/** 작업 기록 목록의 이름표: 관리자 이름·장학금 제목. 개인정보(이메일·연락처)는 싣지 않는다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuditLogViewAssemblerTest {

	@Mock private UserRepository userRepository;
	@Mock private ScholarshipRepository scholarshipRepository;

	private AdminAuditLogViewAssembler assembler;
	private final UUID named = UUID.randomUUID();
	private final UUID loginOnly = UUID.randomUUID();
	private final UUID gone = UUID.randomUUID();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		assembler = new AdminAuditLogViewAssembler(userRepository, scholarshipRepository);
		User withName = user(named, "김관리", "admin1");
		User withoutName = user(loginOnly, " ", "admin2");
		given(userRepository.findAllById(any())).willAnswer(invocation -> {
			Collection<UUID> ids = invocation.getArgument(0);
			return List.of(withName, withoutName).stream().filter(u -> ids.contains(u.getId())).toList();
		});
		Scholarship keep = scholarship(683L, "국가장학금 1유형");
		Scholarship dropped = scholarship(307L, "국가장학금 1유형(중복)");
		dropped.softDelete();
		Scholarship target = scholarship(725L, "교내 성적 장학금");
		given(scholarshipRepository.findAllById(any())).willAnswer(invocation -> {
			Collection<Long> ids = invocation.getArgument(0);
			return List.of(keep, dropped, target).stream().filter(s -> ids.contains(s.getId())).toList();
		});
	}

	@Test
	@DisplayName("관리자 칸: 이름, 이름이 비면 로그인 아이디, 계정이 없으면 null(화면이 UUID 앞 8자리)")
	void actorNames() {
		List<AdminAuditLogResponse> rows = assembler.assemble(List.of(
				log(1L, named, AdminAction.SYNC_TRIGGER, null, null, "동기화"),
				log(2L, loginOnly, AdminAction.SYNC_TRIGGER, null, null, "동기화"),
				log(3L, gone, AdminAction.SYNC_TRIGGER, null, null, "동기화")));

		assertThat(rows).extracting(AdminAuditLogResponse::actorName).containsExactly("김관리", "admin2", null);
		assertThat(rows.get(2).actorId()).isEqualTo(gone);
	}

	@Test
	@DisplayName("대상 칸: 장학금이면 지금 제목과 내려짐 여부, 장학금이 아니면 빈 맵")
	void targetTitles() {
		List<AdminAuditLogResponse> rows = assembler.assemble(List.of(
				log(1L, named, AdminAction.SCHOLARSHIP_AGGREGATE_UPDATE, "SCHOLARSHIP", 725L, "통합 수정"),
				log(2L, named, AdminAction.REPORT_RESOLVE, "REPORT", 725L, "RESOLVED")));

		assertThat(rows.get(0).scholarships()).containsOnlyKeys(725L);
		assertThat(rows.get(0).scholarships().get(725L).title()).isEqualTo("교내 성적 장학금");
		assertThat(rows.get(0).scholarships().get(725L).deleted()).isFalse();
		assertThat(rows.get(1).scholarships()).isEmpty();
	}

	@Test
	@DisplayName("병합·중복 아님 기록은 요약에 적힌 두 장학금(유지·내린 쪽)의 제목도 붙인다")
	void mergeDetailScholarships() {
		List<AdminAuditLogResponse> rows = assembler.assemble(List.of(
				log(1L, named, AdminAction.SCHOLARSHIP_MERGE, "SCHOLARSHIP", 683L,
						"일괄 병합 2건 중 1번째 · 중복 307 를 683 로 병합. {scrap.moved=1}"),
				log(2L, named, AdminAction.SCHOLARSHIP_MERGE_REJECT, "SCHOLARSHIP", 683L,
						"중복 후보 12 반려 (683 vs 725). 캠퍼스가 다름")));

		assertThat(rows.get(0).scholarships()).containsOnlyKeys(683L, 307L);
		assertThat(rows.get(0).scholarships().get(307L).deleted()).isTrue();
		assertThat(rows.get(1).scholarships()).containsOnlyKeys(683L, 725L);
	}

	@Test
	@DisplayName("빈 목록이면 사용자·장학금을 조회하지 않는다")
	void emptyPage() {
		assertThat(assembler.assemble(List.of())).isEmpty();
		verify(userRepository, never()).findAllById(any());
		verify(scholarshipRepository, never()).findAllById(any());
	}

	@Test
	@DisplayName("응답에 이메일·연락처 칸이 없다")
	void noPersonalContactFields() {
		assertThat(Arrays.stream(AdminAuditLogResponse.class.getRecordComponents()).map(RecordComponent::getName))
				.doesNotContain("email", "phone", "actorEmail", "actorPhone")
				.contains("actorName", "scholarships");
	}

	@Test
	@DisplayName("관리자 필터 목록: 이름(없으면 로그인 아이디, 계정이 없으면 UUID 앞 8자리)으로 정렬")
	void actorList() {
		var actors = assembler.actors(List.of(named, gone, loginOnly, named));

		assertThat(actors).extracting(AdminAuditLogViewAssembler.Actor::actorId).containsExactlyInAnyOrder(named, gone, loginOnly);
		assertThat(actors).extracting(AdminAuditLogViewAssembler.Actor::name)
				.contains("김관리", "admin2", gone.toString().substring(0, 8)).isSorted();
	}

	@Test
	@DisplayName("일괄 병합 실패 기록도 요약의 두 장학금 제목을 붙인다")
	void mergeFailureScholarships() {
		List<AdminAuditLogResponse> rows = assembler.assemble(List.of(log(1L, named, AdminAction.MERGE_CANDIDATE_FAILED,
				"SCHOLARSHIP", 683L, "일괄 병합 3건 중 2번째 · 중복 후보 12 (683 vs 307) 실패: MERGE_FAILED · ...")));

		assertThat(rows.get(0).scholarships()).containsOnlyKeys(683L, 307L);
	}

	private static AdminAuditLog log(Long id, UUID actor, AdminAction action, String targetType, Long targetId,
			String detail) {
		AdminAuditLog log = AdminAuditLog.builder().actorId(actor).action(action).targetType(targetType)
				.targetId(targetId).detail(detail).build();
		ReflectionTestUtils.setField(log, "id", id);
		return log;
	}

	private static User user(UUID id, String name, String loginId) {
		User user = mock(User.class);
		given(user.getId()).willReturn(id);
		given(user.getName()).willReturn(name);
		given(user.getLoginId()).willReturn(loginId);
		return user;
	}

	private static Scholarship scholarship(Long id, String title) {
		Scholarship scholarship = Scholarship.builder().title(title).scholarshipType(ScholarshipType.EXTERNAL).build();
		ReflectionTestUtils.setField(scholarship, "id", id);
		return scholarship;
	}
}
