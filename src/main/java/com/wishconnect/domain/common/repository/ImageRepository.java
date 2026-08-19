package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Image;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
이미지 메타(image) Repository. 장학금 포스터 등 엔티티에 연결된 S3 이미지 조회에 사용합니다.
 */
public interface ImageRepository extends JpaRepository<Image, Long> {

	Optional<Image> findFirstByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);

	Optional<Image> findFirstByEntityTypeAndEntityIdOrderByIdDesc(String entityType, Long entityId);

	List<Image> findAllByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);

	boolean existsByEntityTypeAndEntityId(String entityType, Long entityId);

	/**
	 * 관리자 화면에서 "포스터가 붙은 장학금"을 한 번에 가리기 위한 조회.
	 * Image 는 엔티티 연관 없이 (entityType, entityId) 로만 묶여 있어 JPQL 조인이 안 되므로,
	 * id 집합을 받아 서비스에서 대조한다.
	 */
	@Query("select distinct i.entityId from Image i where i.entityType = :entityType")
	List<Long> findEntityIdsByEntityType(@Param("entityType") String entityType);

	@Query("SELECT i FROM Image i " +
			"WHERE i.entityType = :entityType " +
			"AND i.entityId IN :entityIds " +
			"ORDER BY i.id ASC")
	List<Image> findAllByEntityTypeAndEntityIdIn(
			@Param("entityType") String entityType,
			@Param("entityIds") List<Long> entityIds
	);
}
