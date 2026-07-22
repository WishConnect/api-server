package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.dto.UniversityResponse;
import com.wishconnect.domain.common.repository.SchoolRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UniversitySearchService {

	private final SchoolRepository schoolRepository;

	// 학교명 일부를 받아 자동완성 후보를 최대 10개 반환합니다.
	@Transactional(readOnly = true)
	public List<UniversityResponse> search(String keyword) {
		if (!StringUtils.hasText(keyword)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return schoolRepository.findTop10ByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim())
				.stream()
				.map(school -> new UniversityResponse(
						school.getId(),
						school.getName(),
						school.getRegion() == null ? null : school.getRegion().getName()
				))
				.toList();
	}
}
