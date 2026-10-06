package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 병합 실행부 검증.
 *
 * <p>이 프로젝트의 테스트 프로필은 JPA 자동구성을 제외하고, {@code raw_json jsonb} 컬럼 때문에
 * H2 로 대체할 수도 없다. CI 에도 Postgres 가 없다. 그래서 여기서는 EntityManager 를 목으로 두고
 * <b>"참조 테이블을 하나도 빠뜨리지 않았는지"</b>를 검증한다. 이것이 이 클래스에서 가장 위험한
 * 실패 모드다 — 빠뜨린 테이블의 사용자 데이터가 병합 후 사라진 장학금을 가리킨 채 남는다.
 *
 * <p>JPQL 이 실제로 실행되는지는 로컬 Postgres 로 별도 검증했다(PR 본문 참고).
 * 목 테스트만으로는 쿼리 오타를 잡을 수 없으므로 두 검증이 모두 필요하다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScholarshipMergeExecutorTest {

	@Mock private EntityManager entityManager;
	@Mock private Query query;

	private ScholarshipMergeExecutor executor;
	private Scholarship primary;
	private Scholarship duplicate;
	private final List<String> issuedQueries = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		executor = new ScholarshipMergeExecutor(entityManager);
		primary = scholarship(10L, "남길 장학금");
		duplicate = scholarship(11L, "중복 장학금");

		issuedQueries.clear();
		given(entityManager.createQuery(anyString())).will(invocation -> {
			issuedQueries.add(invocation.getArgument(0));
			return query;
		});
		given(entityManager.createNativeQuery(anyString())).will(invocation -> {
			issuedQueries.add(invocation.getArgument(0));
			return query;
		});
		given(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).willReturn(query);
		given(query.executeUpdate()).willReturn(0);
		// 남길 쪽에 이미지·면접 질문이 없는 상태가 기본값이다.
		given(query.getSingleResult()).willReturn(0L);
	}

	private Scholarship scholarship(Long id, String title) {
		Scholarship scholarship = Scholarship.builder()
				.title(title)
				.provider("경희대학교")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("UNIV_KHU")
				.dedupKey("key-" + id)
				.build();
		setField(scholarship, "id", id);
		return scholarship;
	}

	/**
	 * 실행된 쿼리를 <b>호출 순서대로</b> 돌려준다.
	 *
	 * <p>ArgumentCaptor 로는 JPQL(createQuery)과 네이티브(createNativeQuery)의 상대 순서를 알 수 없다.
	 * 조건 참조를 조건보다 먼저 지웠는지 검증하려면 그 순서가 필요해서 직접 기록한다.
	 */
	private List<String> executedQueries() {
		return issuedQueries;
	}

	// --- 누락 감지 (가장 중요) ---

	@Test
	@DisplayName("scholarship 을 참조하는 모든 테이블이 처리 결과에 보고된다")
	void reportsEveryReferencingTable() {
		Map<String, Integer> moved = executor.merge(primary, duplicate);

		// scholarship 을 참조하는 테이블을 전부 다룬다.
		// 새 참조 테이블이 추가되면 이 테스트가 실패해 병합 로직 갱신을 강제한다.
		assertThat(moved.keySet()).containsExactlyInAnyOrder(
				"scrap.deletedDuplicate", "scrap.moved",
				"essay.moved", "report.moved", "dispatchLog.moved",
				"recommendation.moved", "timeline.moved", "timeline.deletedOnDuplicate", "event.moved",
				"rawScholarship.moved",
				"image.moved", "image.keptOnDuplicate",
				"interviewPrep.moved", "interviewPrep.keptOnDuplicate",
				"conditionRef.deleted", "condition.deleted", "document.deleted");
	}

	@Test
	@DisplayName("추천 이벤트는 scholarshipId 필드로 한 번만 옮긴다 — e.scholarship.id 경로는 존재하지 않는다")
	void repointsEventsByScholarshipIdOnlyOnce() {
		executor.merge(primary, duplicate);
		List<String> eventQueries = executedQueries().stream()
				.filter(q -> q.contains("ScholarshipEvent"))
				.toList();

		// ScholarshipEvent 에는 scholarship 연관이 없고 Long scholarshipId 만 있다.
		// 2026-08-20 에 두 갈래 수정이 머지되며 잘못된 쿼리가 함께 남아 모든 병합 승인이 500 이었다.
		assertThat(eventQueries).containsExactly(
				"update ScholarshipEvent e set e.scholarshipId = :to where e.scholarshipId = :from");
	}

	@Test
	@DisplayName("사용자 데이터는 재지정(update)하고 파생 데이터 2종은 삭제(delete)한다")
	void repointsUserDataAndDeletesDerived() {
		executor.merge(primary, duplicate);
		List<String> queries = executedQueries();

		for (String entity : List.of("Scrap", "Essay", "ScholarshipReport",
				"NotificationDispatchLog", "ScholarshipRecommendation",
				"ScholarshipTimeline", "RawScholarship", "InterviewPrepQuestion")) {
			assertThat(queries)
					.as(entity + " 재지정")
					.anyMatch(q -> q.startsWith("update " + entity + " e set e.scholarship.id"));
		}
		for (String entity : List.of("ScholarshipCondition", "ScholarshipDocument")) {
			assertThat(queries)
					.as(entity + " 삭제")
					.anyMatch(q -> q.startsWith("delete from " + entity + " e"));
		}
	}

	@Test
	@DisplayName("조건 참조를 조건보다 먼저 지운다 — 순서가 뒤바뀌면 FK 위반으로 병합이 통째로 롤백된다")
	void deletesConditionRefsBeforeConditions() {
		executor.merge(primary, duplicate);
		List<String> queries = executedQueries();

		int refDelete = indexOfMatch(queries, q -> q.contains("delete from scholarship_condition_ref"));
		int conditionDelete = indexOfMatch(queries, q -> q.startsWith("delete from ScholarshipCondition e"));

		assertThat(refDelete).as("scholarship_condition_ref 삭제 쿼리").isNotNegative();
		assertThat(conditionDelete).as("ScholarshipCondition 삭제 쿼리").isNotNegative();
		assertThat(refDelete).as("참조를 먼저 지워야 한다").isLessThan(conditionDelete);
	}

	private static int indexOfMatch(List<String> queries, java.util.function.Predicate<String> predicate) {
		for (int i = 0; i < queries.size(); i++) {
			if (predicate.test(queries.get(i))) {
				return i;
			}
		}
		return -1;
	}

	@Test
	@DisplayName("스크랩은 중복 삭제를 재지정보다 먼저 한다 — 순서가 뒤바뀌면 중복이 남는다")
	void deletesDuplicateScrapBeforeRepointing() {
		executor.merge(primary, duplicate);
		List<String> queries = executedQueries();

		int deleteIndex = -1;
		int updateIndex = -1;
		for (int i = 0; i < queries.size(); i++) {
			String q = queries.get(i);
			if (q.startsWith("\ndelete from Scrap") || q.trim().startsWith("delete from Scrap")) {
				deleteIndex = i;
			} else if (q.startsWith("update Scrap e set")) {
				updateIndex = i;
			}
		}
		assertThat(deleteIndex).as("스크랩 중복 삭제 쿼리").isNotNegative();
		assertThat(updateIndex).as("스크랩 재지정 쿼리").isNotNegative();
		assertThat(deleteIndex).isLessThan(updateIndex);
	}

	@Test
	@DisplayName("자소서는 중복을 지우지 않는다 — 사용자 작성물 보존")
	void doesNotDeleteEssays() {
		executor.merge(primary, duplicate);

		assertThat(executedQueries())
				.noneMatch(q -> q.contains("delete from Essay"));
	}

	// --- 선발 일정 ---

	@Test
	@DisplayName("남길 쪽에 일정이 있으면 중복 쪽 일정은 옮기지 않고 지운다")
	void deletesDuplicateTimelineWhenPrimaryHasOne() {
		Query timelineCount = org.mockito.Mockito.mock(Query.class);
		given(timelineCount.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).willReturn(timelineCount);
		given(timelineCount.getSingleResult()).willReturn(2L);
		given(entityManager.createQuery(anyString())).will(invocation -> {
			String jpql = invocation.getArgument(0);
			issuedQueries.add(jpql);
			return jpql.equals("select count(e) from ScholarshipTimeline e where e.scholarship.id = :id")
					? timelineCount : query;
		});
		given(query.executeUpdate()).willReturn(4);

		Map<String, Integer> moved = executor.merge(primary, duplicate);

		assertThat(moved).containsEntry("timeline.moved", 0).containsEntry("timeline.deletedOnDuplicate", 4);
		assertThat(executedQueries()).contains("delete from ScholarshipTimeline e where e.scholarship.id = :id")
				.noneMatch(q -> q.startsWith("update ScholarshipTimeline"));
		verify(timelineCount).setParameter("id", 10L);
	}

	@Test
	@DisplayName("남길 쪽에 일정이 없으면 옮긴 뒤 순서를 0부터 다시 매긴다")
	void movesAndRenumbersTimelineWhenPrimaryHasNone() {
		Query ids = org.mockito.Mockito.mock(Query.class);
		Query renumber = org.mockito.Mockito.mock(Query.class);
		given(ids.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).willReturn(ids);
		given(ids.getResultList()).willReturn(List.of(501L, 502L, 503L));
		given(renumber.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).willReturn(renumber);
		given(entityManager.createQuery(anyString())).will(invocation -> {
			String jpql = invocation.getArgument(0);
			issuedQueries.add(jpql);
			if (jpql.startsWith("select t.id from ScholarshipTimeline")) return ids;
			if (jpql.startsWith("update ScholarshipTimeline t set t.displayOrder")) return renumber;
			return query;
		});

		Map<String, Integer> moved = executor.merge(primary, duplicate);

		assertThat(moved).containsEntry("timeline.deletedOnDuplicate", 0);
		assertThat(executedQueries()).contains(
				"update ScholarshipTimeline e set e.scholarship.id = :to where e.scholarship.id = :from")
				.noneMatch(q -> q.startsWith("delete from ScholarshipTimeline"));
		verify(ids).setParameter("id", 10L);
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(renumber);
		order.verify(renumber).setParameter("order", 0);
		order.verify(renumber).setParameter("id", 501L);
		order.verify(renumber).setParameter("order", 1);
		order.verify(renumber).setParameter("id", 502L);
		order.verify(renumber).setParameter("order", 2);
		order.verify(renumber).setParameter("id", 503L);
		verify(renumber, org.mockito.Mockito.times(3)).executeUpdate();
		// 옮기기가 순서 매기기보다 먼저다.
		assertThat(indexOfMatch(executedQueries(), q -> q.startsWith("update ScholarshipTimeline e set e.scholarship.id")))
				.isLessThan(indexOfMatch(executedQueries(), q -> q.startsWith("select t.id from ScholarshipTimeline")));
	}

	// --- 소프트 삭제 ---

	@Test
	@DisplayName("중복 장학금만 소프트 삭제하고 남길 쪽은 건드리지 않는다")
	void softDeletesOnlyDuplicate() {
		executor.merge(primary, duplicate);

		assertThat(duplicate.isDeleted()).isTrue();
		assertThat(duplicate.isActive()).isFalse();
		assertThat(primary.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("승인자가 있으면 중복 쪽을 '병합으로 내림' 으로 표시한다 — 다시 수집돼도 되살아나지 않게")
	void marksDuplicateAsMerged() {
		java.util.UUID reviewer = java.util.UUID.randomUUID();

		executor.merge(primary, duplicate, reviewer);

		assertThat(duplicate.isDeletedByAdmin()).isTrue();
		assertThat(duplicate.getDeletedBy()).isEqualTo(reviewer);
		assertThat(duplicate.getDeleteReason()).contains("#10");
	}

	@Test
	@DisplayName("벌크 연산 후 영속성 컨텍스트를 비운다 — 이후 조회가 옛 상태를 보지 않도록")
	void clearsPersistenceContext() {
		executor.merge(primary, duplicate);

		verify(entityManager).flush();
		verify(entityManager).clear();
	}

	// --- 방어 ---

	@Test
	@DisplayName("같은 장학금끼리는 병합을 거부한다 — 쿼리도 실행하지 않는다")
	void rejectsSelfMerge() {
		assertThatThrownBy(() -> executor.merge(primary, primary))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("같은 장학금");

		verify(entityManager, never()).createQuery(anyString());
		assertThat(primary.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("처리 건수를 테이블별로 돌려준다 — 감사 로그·어드민 응답에 남긴다")
	void returnsPerTableCounts() {
		given(query.executeUpdate()).willReturn(3);

		Map<String, Integer> moved = executor.merge(primary, duplicate);

		moved.forEach((key, count) -> {
			if (key.endsWith("keptOnDuplicate") || key.endsWith("deletedOnDuplicate")) {
				assertThat(count).as(key).isZero();
			} else {
				assertThat(count).as(key).isEqualTo(3);
			}
		});
	}

	// --- Reflection helper (엔티티 ID 는 setter 가 없어 리플렉션으로 주입) ---

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = findField(target.getClass(), name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> current = clazz;
		while (current != null && current != Object.class) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
