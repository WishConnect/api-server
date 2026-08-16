package com.wishconnect.domain.common.dto;

import com.wishconnect.domain.common.entity.Region;

/** 거주지역 선택지. 상위 지역이 있으면 함께 준다(시군구를 넣게 될 때를 위해). */
public record RegionResponse(Long regionId, String name, String parentName) {

	public static RegionResponse from(Region region) {
		return new RegionResponse(
				region.getId(),
				region.getName(),
				region.getParent() == null ? null : region.getParent().getName());
	}
}
