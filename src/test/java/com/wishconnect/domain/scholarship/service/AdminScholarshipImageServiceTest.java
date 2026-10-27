package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.wishconnect.domain.common.entity.Image;
import com.wishconnect.domain.common.repository.ImageRepository;
import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.AdminImageSnapshot;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

/** 포스터 교체 기록: 교체 전후 대표 이미지(S3 키·원본 주소)를 함께 돌려준다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminScholarshipImageServiceTest {

	@Mock private ScholarshipRepository scholarshipRepository;
	@Mock private ImageStorageService imageStorageService;
	@Mock private ImageRepository imageRepository;
	@InjectMocks private AdminScholarshipImageService service;

	@Test
	@DisplayName("URL 교체는 교체 전·후 대표 이미지 스냅샷을 돌려준다")
	void urlReplaceReturnsBeforeAndAfter() {
		given(scholarshipRepository.findById(7L)).willReturn(Optional.of(
				Scholarship.builder().title("장학금").scholarshipType(ScholarshipType.EXTERNAL).build()));
		Image old = image(3L, "scholarships/admin/old.png", "old.png", null);
		Image fresh = image(3L, "scholarships/admin/new.png", "new.png", "https://x.test/new.png");
		given(imageRepository.findRepresentative(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, 7L))
				.willReturn(Optional.of(old), Optional.of(fresh));
		given(imageStorageService.replaceFromUrl(eq("https://x.test/new.png"), anyString(), anyString(), eq(7L), anyString()))
				.willReturn("https://signed/new.png");

		AdminImageSnapshot.Replaced replaced = service.replaceFromUrlWithSnapshot(7L, "https://x.test/new.png");

		assertThat(replaced.url()).isEqualTo("https://signed/new.png");
		assertThat(replaced.before().s3Key()).isEqualTo("scholarships/admin/old.png");
		assertThat(replaced.after().s3Key()).isEqualTo("scholarships/admin/new.png");
		assertThat(replaced.after().sourceUrl()).isEqualTo("https://x.test/new.png");
	}

	@Test
	@DisplayName("처음 등록이면 before 는 null")
	void firstUploadHasNoBefore() {
		given(scholarshipRepository.findById(7L)).willReturn(Optional.of(
				Scholarship.builder().title("장학금").scholarshipType(ScholarshipType.EXTERNAL).build()));
		Image created = image(9L, "scholarships/admin/p.png", "p.png", null);
		given(imageRepository.findRepresentative(ImageStorageService.ENTITY_TYPE_SCHOLARSHIP, 7L))
				.willReturn(Optional.empty(), Optional.of(created));
		given(imageStorageService.replaceFromUpload(any(), anyString(), anyString(), eq(7L))).willReturn("u");

		AdminImageSnapshot.Replaced replaced = service.replaceFromUploadWithSnapshot(7L,
				new MockMultipartFile("file", "p.png", "image/png", new byte[] {1}));

		assertThat(replaced.before()).isNull();
		assertThat(replaced.after().imageId()).isEqualTo(9L);
	}

	private static Image image(Long id, String key, String name, String sourceUrl) {
		Image image = mock(Image.class);
		given(image.getId()).willReturn(id);
		given(image.getS3Key()).willReturn(key);
		given(image.getOriginalName()).willReturn(name);
		given(image.getSourceUrl()).willReturn(sourceUrl);
		given(image.getContentType()).willReturn("image/png");
		given(image.getFileSize()).willReturn(100L);
		return image;
	}
}
