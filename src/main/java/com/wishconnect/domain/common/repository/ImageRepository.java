package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Image;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

/*
이미지 메타(image) Repository. 장학금 포스터 등 엔티티에 연결된 S3 이미지 조회에 사용합니다.
 */
public interface ImageRepository extends JpaRepository<Image, Long> {

    /** 관리자 검수 포스터 우선, 같은 출처에서는 최신 행. 모든 노출·교체 경로가 이 정렬을 쓴다. */
    String REPRESENTATIVE_ORDER = " ORDER BY CASE WHEN i.s3Key LIKE 'scholarships/admin/%' "
            + "OR i.s3Key LIKE 'scholarships/manual/%' THEN 0 ELSE 1 END, i.id DESC";

    @Query("SELECT i FROM Image i WHERE i.entityType = :entityType AND i.entityId = :entityId" + REPRESENTATIVE_ORDER)
    List<Image> findRepresentativeCandidates(@Param("entityType") String entityType,
            @Param("entityId") Long entityId, Pageable pageable);

    default Optional<Image> findRepresentative(String entityType, Long entityId) {
        return findRepresentativeCandidates(entityType, entityId, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Image i WHERE i.entityType = :entityType AND i.entityId = :entityId" + REPRESENTATIVE_ORDER)
    List<Image> findRepresentativeCandidatesForUpdate(@Param("entityType") String entityType,
            @Param("entityId") Long entityId, Pageable pageable);

    default Optional<Image> findRepresentativeForUpdate(String entityType, Long entityId) {
        return findRepresentativeCandidatesForUpdate(entityType, entityId, PageRequest.of(0, 1)).stream().findFirst();
    }

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
            REPRESENTATIVE_ORDER)
	List<Image> findAllByEntityTypeAndEntityIdIn(
			@Param("entityType") String entityType,
			@Param("entityIds") List<Long> entityIds
	);
}
