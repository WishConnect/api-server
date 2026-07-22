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

/*
온보딩 전공 입력 자동완성 API입니다.
전공명은 여러 사용자와 장학금 조건 매칭에서 공통으로 사용하므로 common 도메인에서 조회합니다.
 */
@RestController
@RequestMapping("/api/v1/majors")
@RequiredArgsConstructor
public class MajorController {

	private final MajorSearchService majorSearchService;

	@GetMapping("/search")
	public ApiResponse<List<MajorResponse>> search(@RequestParam String keyword) {
		return ApiResponse.ok(majorSearchService.search(keyword));
	}
}
