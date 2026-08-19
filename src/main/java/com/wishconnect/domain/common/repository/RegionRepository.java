package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Region;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {

	/** 시도(최상위) 목록. */
	List<Region> findByParentIsNullOrderByIdAsc();

	/** 특정 시도의 시군구 목록. */
	@EntityGraph(attributePaths = "parent")
	List<Region> findByParent_IdOrderByIdAsc(Long parentId);

	/**
	 * 시도를 이름으로 찾는다.
	 *
	 * <p>시군구가 들어오면서 {@code 중구}·{@code 동구} 처럼 이름이 여러 시도에 중복되므로,
	 * 시도를 찾을 때는 반드시 최상위로 한정해야 한다. 한정하지 않으면 결과가 여러 건이라
	 * {@code Optional} 반환에서 예외가 난다.
	 */
	Optional<Region> findByNameAndParentIsNull(String name);

	/** 이름이 같은 지역 전체. 시군구는 이름이 중복될 수 있어 목록으로 받는다. */
	List<Region> findAllByName(String name);

	/** 시도명 + 시군구명으로 정확히 한 건을 찾는다. */
	Optional<Region> findByNameAndParent_Name(String name, String parentName);
}
