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
		String result = imageStorageService.replaceFromUrl(imageUrl, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId, title);
		if (result == null) throw new CustomException(ErrorCode.ADMIN_IMAGE_SAVE_FAILED);
		return result;
	}

	public String replaceFromUpload(Long scholarshipId, MultipartFile file) {
		title(scholarshipId);
		String result = imageStorageService.replaceFromUpload(file, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId);
		if (result == null) throw new CustomException(ErrorCode.ADMIN_IMAGE_SAVE_FAILED);
		return result;
	}

	private String title(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.filter(value -> !value.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND))
				.getTitle();
	}
}
