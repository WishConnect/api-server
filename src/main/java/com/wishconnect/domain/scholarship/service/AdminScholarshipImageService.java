package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 관리자 화면의 장학금 포스터 등록·교체. 기존 S3 객체는 복구를 위해 삭제하지 않는다. */
@Service
@RequiredArgsConstructor
public class AdminScholarshipImageService {

	private final ScholarshipRepository scholarshipRepository;
	private final ImageStorageService imageStorageService;

	public String replaceFromUrl(Long scholarshipId, String imageUrl) {
		String title = title(scholarshipId);
		// 실패하면 원인별 ADMIN_IMAGE_* 예외가 그대로 올라간다. 성공했는데 조회 URL 서명만 실패하면 null 이다.
		return imageStorageService.replaceFromUrl(imageUrl, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId, title);
	}

	public String replaceFromUpload(Long scholarshipId, MultipartFile file) {
		title(scholarshipId);
		return imageStorageService.replaceFromUpload(file, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId);
	}

	private String title(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.filter(value -> !value.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND))
				.getTitle();
	}
}
