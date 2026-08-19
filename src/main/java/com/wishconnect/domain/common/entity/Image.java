package com.wishconnect.domain.common.entity;

import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 다형(polymorphic) 이미지. entityType/entityId 로 대상 엔티티를 가리키므로 FK 매핑을 두지 않는다.
 */
@Entity
@Getter
@Table(name = "image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Image extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 연결 대상 엔티티 종류 (예: SCHOLARSHIP, USER). 값 확정 필요 시 enum 으로 승격 */
	@Column(nullable = false)
	private String entityType;

	@Column(nullable = false)
	private Long entityId;

	@Column(nullable = false)
	private String s3Key;

	@Column
	private String originalName;

	@Column
	private String contentType;

	@Column
	private Long fileSize;

	/** 이미지 용도(썸네일/본문 등). 값 확정 필요 시 enum 으로 승격 */
	@Column
	private String imageType;

	/**
	 * 원본 이미지 주소. 자동 수집한 포스터의 출처를 남긴다.
	 * 저작권 문의가 오면 어디서 가져왔는지 확인하고 개별 삭제할 수 있어야 한다.
	 */
	@Column(name = "source_url", length = 1000)
	private String sourceUrl;

	/** 자동 수집한 포스터의 출처. 저작권 문의 대응과 개별 삭제를 위해 남긴다. */
	public void updateSourceUrl(String sourceUrl) {
		this.sourceUrl = sourceUrl;
	}

	/** 관리자 교체 시 행 ID와 연결 대상은 유지하고 새 S3 객체의 메타데이터로 바꾼다. */
	public void replaceStorage(String s3Key, String originalName, String contentType,
			Long fileSize, String imageType, String sourceUrl) {
		this.s3Key = s3Key;
		this.originalName = originalName;
		this.contentType = contentType;
		this.fileSize = fileSize;
		this.imageType = imageType;
		this.sourceUrl = sourceUrl;
	}
}
