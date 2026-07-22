package com.wishconnect.domain.common.service;

import com.wishconnect.domain.common.dto.MajorResponse;
import com.wishconnect.domain.common.repository.MajorRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MajorSearchService {

	private final MajorRepository majorRepository;

	// 전공명 일부를 받아 자동완성 후보를 최대 10개 반환합니다.
	public List<MajorResponse> search(String keyword) {
		if (!StringUtils.hasText(keyword)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return majorRepository.findTop10ByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim())
				.stream()
				.map(major -> new MajorResponse(major.getId(), major.getName(), major.getCategory()))
				.toList();
	}
}
