package com.wishconnect.domain.user.dto.response;

import com.wishconnect.domain.common.entity.MajorCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
		@Schema(description = "사용자 ID", example = "550e8400-e29b-41d4-a716-446655440000")
		UUID userId,
		@Schema(description = "사용자 이름", example = "김위시")
		String name,
		@Schema(description = "이메일", example = "wishconnect@gmail.com")
		String email,
		@Schema(description = "출생년도", example = "2004")
		String birthYear,
		@Schema(description = "연락처", example = "010-0000-0000")
		String phone,
		@Schema(description = "성별", example = "FEMALE")
		String gender,
		@Schema(description = "국적", example = "DOMESTIC")
		String nationality,
		@Schema(description = "거주 지역", example = "서울")
		String region,
		@Schema(description = "프로필 완성도", example = "85")
		int profileCompletionRate,
		@Schema(description = "온보딩 완료 여부", example = "true")
		boolean onboardingCompleted,
		Academic academic,
		Household household,
		@Schema(description = "관심 장학금 분야", example = "[\"생활비 지원\", \"등록금 지원\"]")
		List<String> interests
) {

	public record Academic(
			@Schema(description = "학교명", example = "건국대학교")
			String university,
			@Schema(description = "전공 계열", example = "공학계열")
			MajorCategory majorCategory,
			@Schema(description = "전공명", example = "컴퓨터공학")
			String majorName,
			@Schema(description = "재학 상태", example = "ENROLLED")
			String enrollmentStatus,
			@Schema(description = "학년/학기", example = "3학년")
			String grade,
			@Schema(description = "직전학기 학점", example = "4.1")
			BigDecimal semesterGpa,
			@Schema(description = "누적 학점", example = "3.8")
			BigDecimal cumulativeGpa,
			@Schema(description = "복수전공/부전공 여부", example = "DOUBLE")
			String dualMajor
	) {
	}

	public record Household(
			@Schema(description = "소득분위", example = "3분위")
			String incomeLevel,
			@Schema(description = "가구원 수", example = "4")
			Long familySize,
			@Schema(description = "가정형태", example = "[\"기초생활수급자\", \"한부모 가정\"]")
			List<String> familyTypes,
			@Schema(description = "개인 해당 항목", example = "[\"장애인\"]")
			List<String> personalStatuses
	) {
	}
}
