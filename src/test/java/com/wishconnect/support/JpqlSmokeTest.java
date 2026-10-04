package com.wishconnect.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.service.ScholarshipMergeExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;

/**
 * JPQL 문법·경로 스모크 테스트. DB 없이 Hibernate 메타모델로 해석만 해 본다({@link JpqlValidator}).
 *
 * <p>목 EntityManager 기반 단위 테스트는 쿼리 문자열이 틀려도 통과한다. 이 테스트는 그 공백을 메운다.
 */
class JpqlSmokeTest {

	private static JpqlValidator validator;

	@BeforeAll
	static void setUp() {
		validator = new JpqlValidator();
	}

	@AfterAll
	static void tearDown() {
		validator.close();
	}

	@Test
	@DisplayName("검증기 자체가 없는 연관 경로를 잡아낸다 — 2026-08-20 병합 장애 쿼리")
	void validatorRejectsTheBrokenMergeQuery() {
		assertThatThrownBy(() -> validator.validate(
				"update ScholarshipEvent e set e.scholarship.id = :to where e.scholarship.id = :from"))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("병합 실행부가 만드는 JPQL 이 모두 해석된다")
	void mergeExecutorQueriesAreValid() {
		List<String> jpql = new ArrayList<>();
		EntityManager entityManager = mock(EntityManager.class);
		Query query = mock(Query.class);
		given(entityManager.createQuery(anyString())).will(invocation -> {
			jpql.add(invocation.getArgument(0));
			return query;
		});
		given(entityManager.createNativeQuery(anyString())).willReturn(query);
		given(query.setParameter(anyString(), any())).willReturn(query);
		given(query.getSingleResult()).willReturn(0L);

		new ScholarshipMergeExecutor(entityManager).merge(scholarship(1L), scholarship(2L));

		assertThat(jpql).isNotEmpty();
		for (String q : jpql) {
			validator.validate(q);
		}
	}

	@Test
	@DisplayName("모든 리포지토리의 @Query(JPQL) 가 해석된다")
	void repositoryQueriesAreValid() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(
					org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
		int checked = 0;
		List<String> failures = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.wishconnect")) {
			Class<?> repository = Class.forName(candidate.getBeanClassName());
			for (Method method : repository.getDeclaredMethods()) {
				org.springframework.data.jpa.repository.Query annotation =
						method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
				if (annotation == null || annotation.nativeQuery()) {
					continue;
				}
				checked++;
				try {
					validator.validate(annotation.value());
				} catch (RuntimeException e) {
					failures.add(repository.getSimpleName() + "." + method.getName() + " → " + e.getMessage());
				}
			}
		}
		assertThat(checked).as("검사한 @Query 수").isPositive();
		assertThat(failures).as("해석에 실패한 @Query").isEmpty();
	}

	private static Scholarship scholarship(Long id) {
		Scholarship scholarship = Scholarship.builder()
				.title("장학금 " + id)
				.provider("기관")
				.scholarshipType(ScholarshipType.INTERNAL)
				.primarySource("MANUAL")
				.dedupKey("key-" + id)
				.build();
		try {
			Field field = Scholarship.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(scholarship, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return scholarship;
	}
}
