package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.common.service.ImageStorageService;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullRequest;
import com.wishconnect.domain.scholarship.dto.ScholarshipManualFullResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("장학금 통합 수기 등록 조율")
class ScholarshipManualAggregateServiceTest {

	@Mock private ScholarshipManualAggregateStore aggregateStore;
	@Mock private ImageStorageService imageStorageService;
	private ScholarshipManualAggregateService service;

	@BeforeEach
	void setUp() {
		service = new ScholarshipManualAggregateService(aggregateStore, imageStorageService);
	}

	@Test
	@DisplayName("DB 저장 후 이미지 URL을 S3에 연결한다")
	void storesImageAfterAggregate() {
		ScholarshipManualFullRequest request = minimal("https://example.com/poster.jpg");
		given(aggregateStore.create(request)).willReturn(
				new ScholarshipManualAggregateStore.SavedAggregate(1L, 2L, 1, 1, 1, "장학금"));
		given(imageStorageService.storeFromUrl(
				"https://example.com/poster.jpg", "scholarships/manual", "SCHOLARSHIP", 1L, "장학금"))
				.willReturn("https://signed.example.com/poster.jpg");

		ScholarshipManualFullResponse response = service.create(request);

		assertThat(response.imageSaved()).isTrue();
		assertThat(response.scholarshipId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("통합 수정의 이미지 URL은 기존 이미지 교체 경로로 저장한다")
	void replacesImageAfterAggregateEdit() {
		ScholarshipManualFullRequest request = minimal("https://example.com/new.jpg");
		given(aggregateStore.update(9L, request)).willReturn(
				new ScholarshipManualAggregateStore.SavedAggregate(9L, null, 0, 0, 0, "장학금"));
		given(imageStorageService.replaceFromUrl(
				"https://example.com/new.jpg", "scholarships/admin", "SCHOLARSHIP", 9L, "장학금"))
				.willReturn("https://signed.example.com/new.jpg");

		ScholarshipManualFullResponse response = service.update(9L, request);

		assertThat(response.imageSaved()).isTrue();
		verify(imageStorageService).replaceFromUrl(
				"https://example.com/new.jpg", "scholarships/admin", "SCHOLARSHIP", 9L, "장학금");
	}

	private ScholarshipManualFullRequest minimal(String imageUrl) {
		return new ScholarshipManualFullRequest(
				"장학금", null, null, null, null, null, null, null, null, null,
				null, null, null, false, null, null, null, null,
				null, null, null, null, null, List.of(), List.of(), imageUrl);
	}
}
