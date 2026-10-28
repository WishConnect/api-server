package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 장학금 한 건의 데이터 이상 판정. 관리자 "데이터 이상" 목록과 검수 대기 자동 해제가 같은 규칙을 쓴다.
 *
 * <p>목록 화면은 같은 규칙을 Criteria 조건으로도 갖고 있다({@code ScholarshipAdminOverviewService}).
 * 규칙을 바꾸면 두 곳을 함께 고친다.
 */
public final class ScholarshipAnomalies {

	private ScholarshipAnomalies() {
	}

	/**
	 * @param hasCondition 자격 조건 행이 하나라도 있는지. 조회가 필요해 호출측이 넘긴다.
	 * @return 이상 유형 코드 목록. 비어 있으면 이상 없음
	 */
	public static List<String> of(Scholarship value, boolean hasCondition) {
		List<String> result = new ArrayList<>();
		if (!StringUtils.hasText(value.getTitle())) result.add("EMPTY_TITLE");
		if (!StringUtils.hasText(value.getProvider())) result.add("MISSING_PROVIDER");
		if (value.getApplicationStartAt() != null && value.getApplicationEndAt() != null
				&& value.getApplicationStartAt().isAfter(value.getApplicationEndAt())) result.add("DATE_REVERSED");
		if (value.getRecruitmentStatus() == RecruitmentStatus.OPEN && value.getApplicationEndAt() != null
				&& value.getApplicationEndAt().isBefore(LocalDateTime.now())) result.add("OPEN_BUT_ENDED");
		boolean recruiting = value.getRecruitmentStatus() == RecruitmentStatus.OPEN
				|| value.getRecruitmentStatus() == RecruitmentStatus.ALWAYS_OPEN;
		if (recruiting && !StringUtils.hasText(value.getHomepageUrl()) && !StringUtils.hasText(value.getDetailUrl())) {
			result.add("MISSING_LINK");
		}
		if (recruiting && !hasCondition) result.add("MISSING_CONDITION");
		return List.copyOf(result);
	}
}
