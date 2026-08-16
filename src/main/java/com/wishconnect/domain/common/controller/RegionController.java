package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.RegionResponse;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거주지역 목록.
 *
 * <p>회원가입·프로필 화면의 거주지역 드롭다운을 채운다. 지금까지 마스터 테이블이 비어 있었고
 * 목록 API 도 없어서, 프론트가 지역을 고를 방법 자체가 없었다(그래서 {@code region_id} 가 전 건 NULL).
 *
 * <p>가입 전에도 필요하므로 인증 없이 연다.
 */
@Tag(name = "공통 - 지역", description = "거주지역 마스터 조회")
@RestController
@RequestMapping("/api/v1/regions")
@RequiredArgsConstructor
public class RegionController {

	private final RegionRepository regionRepository;

	@Operation(summary = "거주지역 목록", description = "시도 단위 목록을 이름순으로 준다.")
	@GetMapping
	public ApiResponse<List<RegionResponse>> getRegions() {
		return ApiResponse.ok(regionRepository.findAll().stream()
				.map(RegionResponse::from)
				.sorted((a, b) -> a.name().compareTo(b.name()))
				.toList());
	}
}
