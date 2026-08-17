package com.wishconnect.domain.archive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.entity.ScholarshipType;
import com.wishconnect.domain.scholarship.entity.Scrap;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.repository.ScrapRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipEventService;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScrapServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();

	@Mock
	private ScrapRepository scrapRepository;

	@Mock
	private ScholarshipRepository scholarshipRepository;

	@Mock
	private UserRepository userRepository;

	/** 추천 품질 측정용 기록. 스크랩 동작과는 무관하므로 아무것도 스텁하지 않는다. */
	@Mock
	private ScholarshipEventService scholarshipEventService;

	@InjectMocks
	private ScrapService scrapService;

	private Scholarship scholarship() {
		return Scholarship.builder()
				.title("테스트")
				.scholarshipType(ScholarshipType.EXTERNAL)
				.recruitmentStatus(RecruitmentStatus.OPEN)
				.build();
	}

	@Test
	@DisplayName("스크랩 등록: 정상 저장")
	void scrap() {
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship()));
		given(scrapRepository.existsByUserIdAndScholarshipId(USER_ID, 1L)).willReturn(false);
		given(userRepository.getReferenceById(USER_ID)).willReturn(org.mockito.Mockito.mock(User.class));

		scrapService.scrap(USER_ID, 1L);

		verify(scrapRepository).save(any(Scrap.class));
	}

	@Test
	@DisplayName("중복 스크랩이면 ALREADY_SCRAPPED")
	void duplicateScrap() {
		given(scholarshipRepository.findById(1L)).willReturn(Optional.of(scholarship()));
		given(scrapRepository.existsByUserIdAndScholarshipId(USER_ID, 1L)).willReturn(true);

		assertThatThrownBy(() -> scrapService.scrap(USER_ID, 1L))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.ALREADY_SCRAPPED);
	}

	@Test
	@DisplayName("없는 장학금 스크랩이면 SCHOLARSHIP_NOT_FOUND")
	void scholarshipNotFound() {
		given(scholarshipRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> scrapService.scrap(USER_ID, 99L))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCHOLARSHIP_NOT_FOUND);
	}

	@Test
	@DisplayName("스크랩 안 한 장학금 해제면 SCRAP_NOT_FOUND")
	void unscrapNotFound() {
		given(scrapRepository.findByUserIdAndScholarshipId(USER_ID, 1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> scrapService.unscrap(USER_ID, 1L))
				.isInstanceOf(CustomException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.SCRAP_NOT_FOUND);
	}
}
