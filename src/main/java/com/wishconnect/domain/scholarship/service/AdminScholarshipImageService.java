package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.AdminImageSnapshot;
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
	private final ImageRepository imageRepository;

	public String replaceFromUrl(Long scholarshipId, String imageUrl) {
		return replaceFromUrlWithSnapshot(scholarshipId, imageUrl).url();
	}

	public String replaceFromUpload(Long scholarshipId, MultipartFile file) {
		return replaceFromUploadWithSnapshot(scholarshipId, file).url();
	}

	/** URL 로 교체하고, 작업 기록에 남길 교체 전후 대표 이미지(S3 키·원본 주소)를 함께 돌려준다. */
	public AdminImageSnapshot.Replaced replaceFromUrlWithSnapshot(Long scholarshipId, String imageUrl) {
		String title = title(scholarshipId);
		AdminImageSnapshot before = current(scholarshipId);
		// 실패하면 원인별 ADMIN_IMAGE_* 예외가 그대로 올라간다. 성공했는데 조회 URL 서명만 실패하면 null 이다.
		String url = imageStorageService.replaceFromUrl(imageUrl, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId, title);
		return new AdminImageSnapshot.Replaced(url, before, current(scholarshipId));
	}

	public AdminImageSnapshot.Replaced replaceFromUploadWithSnapshot(Long scholarshipId, MultipartFile file) {
		title(scholarshipId);
		AdminImageSnapshot before = current(scholarshipId);
		String url = imageStorageService.replaceFromUpload(file, "scholarships/admin",
				ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId);
		return new AdminImageSnapshot.Replaced(url, before, current(scholarshipId));
	}

	/** 사용자 화면에 보이는 대표 이미지(없으면 null). */
	private AdminImageSnapshot current(Long scholarshipId) {
		return imageRepository.findRepresentative(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, scholarshipId)
				.map(AdminImageSnapshot::from).orElse(null);
	}

	private String title(Long scholarshipId) {
		return scholarshipRepository.findById(scholarshipId)
				.filter(value -> !value.isDeleted())
				.orElseThrow(() -> new CustomException(ErrorCode.SCHOLARSHIP_NOT_FOUND))
				.getTitle();
	}
}
