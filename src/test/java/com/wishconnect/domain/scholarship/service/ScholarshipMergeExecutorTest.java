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

	@BeforeEach
	void setUp() {
		executor = new ScholarshipMergeExecutor(entityManager);
		primary = scholarship(10L, "남길 장학금");
		duplicate = scholarship(11L, "중복 장학금");

		given(entityManager.createQuery(anyString())).willReturn(query);
		given(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).willReturn(query);
		given(query.executeUpdate()).willReturn(0);
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

	private List<String> executedQueries() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(entityManager, org.mockito.Mockito.atLeastOnce()).createQuery(captor.capture());
		return captor.getAllValues();
	}

	// --- 누락 감지 (가장 중요) ---

	@Test
	@DisplayName("scholarship 을 참조하는 모든 테이블이 처리 결과에 보고된다")
	void reportsEveryReferencingTable() {
		Map<String, Integer> moved = executor.merge(primary, duplicate);

		// 조사 시점(2026-08) 기준 scholarship 을 참조하는 테이블 9개를 전부 다룬다.
		// 새 참조 테이블이 추가되면 이 테스트가 실패해 병합 로직 갱신을 강제한다.
		assertThat(moved.keySet()).containsExactlyInAnyOrder(
				"scrap.deletedDuplicate", "scrap.moved",
				"essay.moved", "report.moved", "dispatchLog.moved",
				"recommendation.moved", "timeline.moved", "rawScholarship.moved",
				"condition.deleted", "document.deleted");
	}

	@Test
	@DisplayName("사용자 데이터 7종은 재지정(update)하고 파생 데이터 2종은 삭제(delete)한다")
	void repointsUserDataAndDeletesDerived() {
		executor.merge(primary, duplicate);
		List<String> queries = executedQueries();

		for (String entity : List.of("Scrap", "Essay", "ScholarshipReport",
				"NotificationDispatchLog", "ScholarshipRecommendation",
				"ScholarshipTimeline", "RawScholarship")) {
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

		assertThat(moved.values()).allMatch(count -> count == 3);
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
