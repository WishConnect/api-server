package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.MajorResponse;
import com.wishconnect.domain.common.service.MajorSearchService;
import com.wishconnect.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/*
온보딩 전공 입력 자동완성 API입니다.
전공명은 여러 사용자와 장학금 조건 매칭에서 공통으로 사용하므로 common 도메인에서 조회합니다.
 */
@Tag(name = "공통 - 전공", description = "전공 검색")
@RestController
@RequestMapping("/api/v1/majors")
@RequiredArgsConstructor
public class MajorController {

	private final MajorSearchService majorSearchService;

	/** 전공명을 키워드로 검색한다(온보딩 전공 선택용). */
	@GetMapping("/search")
	@Operation(summary = "전공 검색", description = "온보딩·프로필에서 사용할 전공명을 키워드로 검색합니다.")
	public ApiResponse<List<MajorResponse>> search(@RequestParam String keyword) {
		return ApiResponse.ok(majorSearchService.search(keyword));
	}
}
