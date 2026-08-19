package com.wishconnect.domain.common.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class RegionRepositoryTest {

	@Test
	@DisplayName("시군구 조회는 DTO 변환 전에 상위 시도를 함께 로딩한다")
	void childQueryFetchesParentRegion() throws NoSuchMethodException {
		Method method = RegionRepository.class.getMethod("findByParent_IdOrderByIdAsc", Long.class);

		EntityGraph entityGraph = method.getAnnotation(EntityGraph.class);

		assertThat(entityGraph).isNotNull();
		assertThat(entityGraph.attributePaths()).containsExactly("parent");
	}
}
