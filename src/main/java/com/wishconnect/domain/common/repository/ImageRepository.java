package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Image;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
이미지 메타(image) Repository. 장학금 포스터 등 엔티티에 연결된 S3 이미지 조회에 사용합니다.
 */
public interface ImageRepository extends JpaRepository<Image, Long> {

	Optional<Image> findFirstByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);

	boolean existsByEntityTypeAndEntityId(String entityType, Long entityId);
}
