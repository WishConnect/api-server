package com.wishconnect.domain.scholarship.util;

import com.wishconnect.domain.common.entity.Region;
import com.wishconnect.domain.user.entity.EnrollmentStatus;
import com.wishconnect.domain.user.entity.UserFamilyType;
import com.wishconnect.domain.user.entity.UserInterest;
import com.wishconnect.domain.user.entity.UserProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 매칭에 쓰는 사용자 값들을 <b>조건과 같은 모양</b>으로 정리해 둔 것.
 *
 * <p>조건은 마스터 ID·코드로 저장돼 있는데({@code scholarship_condition_ref}) 사용자 값은
 * 프로필·연결 테이블에 흩어져 있다. 대조할 때마다 꺼내오면 장학금 수백 건 × 조건 수만큼
 * 쿼리가 나가므로, 요청당 한 번 모아 두고 돌려 쓴다.
 *
 * <p>거주지역은 <b>시군구와 상위 시도를 함께</b> 담는다. 조건은 {@code "대구"} 처럼 시도로만
 * 걸릴 때가 많은데, 사용자가 {@code "대구 서구"} 를 골랐다고 탈락시키면 안 된다.
 */
public record MatchProfile(

		UserProfile profile,

		/** 본인 지역 + 상위 시도의 {@code region.id}. */
		Set<Long> regionIds,

		/** 본인해당·가정형태의 {@code family_type.id}. */
		Set<Long> familyTypeIds,

		/** 관심 지원분야의 {@code interest.id}. */
		Set<Long> interestIds,

		/** 전공 계열 코드({@code MajorCategory.name()}). 전공이 없거나 계열 미상이면 null. */
		String majorCategoryCode,

		/** 재학 상태 코드. 없으면 null. */
		String enrollmentStatusCode) {

	/** 프로필만 아는 경우(연결 테이블을 읽지 않은 호출). 참조 대조는 판정 불가로 넘어간다. */
	public static MatchProfile of(UserProfile profile) {
		return of(profile, List.of(), List.of());
	}

	public static MatchProfile of(UserProfile profile,
			List<UserFamilyType> familyTypes, List<UserInterest> interests) {
		if (profile == null) {
			return new MatchProfile(null, Set.of(), Set.of(), Set.of(), null, null);
		}
		EnrollmentStatus status = profile.getEnrollmentStatus();
		return new MatchProfile(
				profile,
				regionIdsOf(profile.getRegion()),
				familyTypes.stream()
						.map(userFamilyType -> userFamilyType.getFamilyType().getId())
						.collect(Collectors.toUnmodifiableSet()),
				interests.stream()
						.map(userInterest -> userInterest.getInterest().getId())
						.collect(Collectors.toUnmodifiableSet()),
				profile.getMajor() == null || profile.getMajor().getCategory() == null
						? null : profile.getMajor().getCategory().name(),
				status == null ? null : status.name());
	}

	private static Set<Long> regionIdsOf(Region region) {
		if (region == null) {
			return Set.of();
		}
		// 아직 저장되지 않은 지역은 id 가 없다. 그대로 담으면 집합을 만들다 터진다.
		Set<Long> ids = new LinkedHashSet<>();
		if (region.getId() != null) {
			ids.add(region.getId());
		}
		if (region.getParent() != null && region.getParent().getId() != null) {
			ids.add(region.getParent().getId());
		}
		return Set.copyOf(ids);
	}
}
