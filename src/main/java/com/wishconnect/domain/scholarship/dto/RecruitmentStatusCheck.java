package com.wishconnect.domain.scholarship.dto;

import com.wishconnect.domain.scholarship.entity.RecruitmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 모집 상태와 날짜가 서로 맞는지. <b>상태를 고쳐 주지 않는다</b> — 사람이 고른 값을 서버가 바꾸지 않고,
 * 판단할 재료(서버 시각, 마감 지났는지, 모순 문구)만 준다. 상태는 관리자가 직접 고른다.
 */
@Schema(description = "모집 상태·기간 모순 점검(자동 변경 없음)")
public record RecruitmentStatusCheck(
		@Schema(description = "판단 기준 서버 시각(마감 배치 closeExpired 와 같은 기준)") LocalDateTime serverNow,
		String recruitmentStatus,
		LocalDateTime applicationStartAt,
		LocalDateTime applicationEndAt,
		@Schema(description = "마감일이 지났는지(마감일 없으면 false)") boolean deadlinePassed,
		@Schema(description = "시작일 전인지(시작일 없으면 false)") boolean beforeStart,
		@Schema(description = "모순이 없으면 true") boolean consistent,
		@Schema(description = "모순 설명(화면 경고용). 없으면 빈 목록") List<String> warnings
) {

	public static RecruitmentStatusCheck of(RecruitmentStatus status, LocalDateTime startAt, LocalDateTime endAt,
			LocalDateTime now) {
		boolean deadlinePassed = endAt != null && endAt.isBefore(now);
		boolean beforeStart = startAt != null && startAt.isAfter(now);
		List<String> warnings = new ArrayList<>();
		if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
			warnings.add("마감일이 시작일보다 빠릅니다.");
		}
		if (status != null) {
			switch (status) {
				case OPEN -> {
					if (deadlinePassed) warnings.add("마감일이 지났는데 모집 중(OPEN)입니다. 사용자에게 마감 공고가 모집 중으로 보입니다.");
					if (beforeStart) warnings.add("시작일 전인데 모집 중(OPEN)입니다. 모집 예정(UPCOMING)이 맞는지 확인하세요.");
				}
				case ALWAYS_OPEN -> {
					if (deadlinePassed) warnings.add("마감일이 지났는데 상시모집(ALWAYS_OPEN)입니다.");
					else if (endAt != null) warnings.add("마감일이 있는데 상시모집(ALWAYS_OPEN)입니다. 모집 중(OPEN)이 맞는지 확인하세요.");
				}
				case UPCOMING -> {
					if (deadlinePassed) warnings.add("마감일이 지났는데 모집 예정(UPCOMING)입니다.");
					else if (startAt != null && !beforeStart) warnings.add("시작일이 지났는데 모집 예정(UPCOMING)입니다.");
				}
				case CLOSED -> {
					if (endAt != null && !deadlinePassed) warnings.add("마감일 전인데 마감(CLOSED)입니다. 조기 마감이 아니라면 확인하세요.");
				}
				default -> {
				}
			}
		}
		return new RecruitmentStatusCheck(now, status == null ? null : status.name(), startAt, endAt,
				deadlinePassed, beforeStart, warnings.isEmpty(), List.copyOf(warnings));
	}
}
