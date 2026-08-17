package com.wishconnect.domain.common.controller;

import com.wishconnect.domain.common.dto.RegionResponse;
import com.wishconnect.domain.common.repository.RegionRepository;
import com.wishconnect.global.common.ApiResponse;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거주지역 목록.
 *
 * <p>회원가입·프로필 화면의 거주지역 드롭다운을 채운다. 시도 → 시군구 2단계로 고르므로
 * 목록도 두 단계로 나눠 준다. 한 번에 전부 내려주지 않는 이유는, 시군구까지 합치면 228건이라
 * 대부분 쓰지 않을 데이터를 매번 실어 보내게 되기 때문이다.
 *
 * <p>가입 전에도 필요하므로 인증 없이 연다.
 */
@Tag(name = "공통 - 지역", description = "거주지역 마스터 조회")
@RestController
@RequestMapping("/api/v1/regions")
@RequiredArgsConstructor
public class RegionController {

	private final RegionRepository regionRepository;

	@Operation(summary = "시도 목록",
			description = "거주지역 1단계. 서울·경기 등 17개 시도를 준다. "
					+ "선택한 항목의 regionId 로 시군구 목록을 조회한다.")
	@GetMapping
	public ApiResponse<List<RegionResponse>> getSidoList() {
		return ApiResponse.ok(regionRepository.findByParentIsNullOrderByIdAsc().stream()
				.map(RegionResponse::from)
				.toList());
	}

	@Operation(summary = "시군구 목록",
			description = "거주지역 2단계. 해당 시도의 시군구를 준다(예: 서울 → 광진구·성동구 …). "
					+ "세종특별자치시는 하위 행정구역이 없어 빈 배열이 정상이다.")
	@GetMapping("/{regionId}/children")
	public ApiResponse<List<RegionResponse>> getSigunguList(@PathVariable Long regionId) {
		if (!regionRepository.existsById(regionId)) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
		return ApiResponse.ok(regionRepository.findByParent_IdOrderByIdAsc(regionId).stream()
				.map(RegionResponse::from)
				.toList());
	}
}
