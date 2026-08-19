package com.wishconnect.domain.common.dto;

import com.wishconnect.domain.common.entity.Region;

/** 거주지역 선택지. 시군구라면 상위 시도의 ID와 이름을 함께 준다. */
public record RegionResponse(Long regionId, String name, Long parentId, String parentName) {

	public static RegionResponse from(Region region) {
		return new RegionResponse(
				region.getId(),
				region.getName(),
				region.getParent() == null ? null : region.getParent().getId(),
				region.getParent() == null ? null : region.getParent().getName());
	}
}
