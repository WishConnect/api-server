package com.wishconnect.domain.scholarship.service;

import com.wishconnect.domain.scholarship.dto.TimelineFieldErrorResponse;
import com.wishconnect.domain.scholarship.dto.TimelineItemRequest;
import com.wishconnect.domain.scholarship.entity.TimelineDateType;
import com.wishconnect.domain.scholarship.entity.TimelineStageCode;
import com.wishconnect.global.exception.CustomDetailException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 선발 일정 입력을 검증하고 저장할 모양으로 다듬는다.
 *
 * <p>규칙은 DB CHECK({@code V20261006_01})와 같고, 콘솔 화면 검증(admin-console.js)도 같은 규칙을 쓴다.
 * 위반하면 행 번호·필드를 담은 {@link CustomDetailException} 을 던진다.
 *
 * <p>같은 단계 중복, 날짜 역순(단계 사이), 모집 마감보다 이른 단계는 <b>오류가 아니다</b>. 트랙별 접수처럼
 * 실제 공고에 있는 모양이라 화면에서 경고만 한다.
 */
public final class ScholarshipTimelineValidator {

	public static final int MAX_ITEMS = 10;
	public static final int MAX_TITLE = 50;
	public static final int MAX_DATE_TEXT = 100;
	public static final int MAX_NOTE = 200;
	public static final int MAX_EVIDENCE = 2000;

	private ScholarshipTimelineValidator() {
	}

	/** 검증을 통과한 한 행. 표시명은 기본값까지 채운 상태, 날짜는 형태에 맞게 정리된 상태다. */
	public record Item(TimelineStageCode stageCode, String title, TimelineDateType dateType,
			LocalDate startDate, LocalDate endDate, String dateText, String note, String evidence) {
	}

	/**
	 * @return null 이면 null(= 기존 일정 유지). 빈 목록이면 빈 목록(= 모두 삭제)
	 */
	public static List<Item> normalize(List<TimelineItemRequest> requests) {
		if (requests == null) {
			return null;
		}
		if (requests.size() > MAX_ITEMS) {
			throw error(ErrorCode.TIMELINE_TOO_MANY, null, "timeline", MAX_ITEMS);
		}
		List<Item> items = new ArrayList<>(requests.size());
		for (int i = 0; i < requests.size(); i++) {
			items.add(normalize(i, requests.get(i)));
		}
		return items;
	}

	private static Item normalize(int index, TimelineItemRequest request) {
		if (request == null) {
			throw error(ErrorCode.TIMELINE_STAGE_CODE_INVALID, index, "stageCode", null);
		}
		TimelineStageCode stageCode = parse(TimelineStageCode.class, request.stageCode());
		if (stageCode == null) {
			throw error(ErrorCode.TIMELINE_STAGE_CODE_INVALID, index, "stageCode", null);
		}
		TimelineDateType dateType = parse(TimelineDateType.class, request.dateType());
		if (dateType == null) {
			throw error(ErrorCode.TIMELINE_DATE_TYPE_INVALID, index, "dateType", null);
		}

		String title = trimToNull(request.title());
		if (title == null) {
			if (stageCode == TimelineStageCode.CUSTOM) {
				throw error(ErrorCode.TIMELINE_TITLE_REQUIRED, index, "title", null);
			}
			title = stageCode.defaultTitle();
		}
		// 미정이 아니면 문구를 쓰지 않는다(화면도 날짜로 그린다). 남겨 두면 형태를 바꿨을 때 옛 문구가 섞인다.
		String dateText = dateType == TimelineDateType.TBD ? trimToNull(request.dateText()) : null;
		String note = trimToNull(request.note());
		String evidence = trimToNull(request.evidence());
		checkLength(index, "title", title, MAX_TITLE);
		checkLength(index, "dateText", dateText, MAX_DATE_TEXT);
		checkLength(index, "note", note, MAX_NOTE);
		checkLength(index, "evidence", evidence, MAX_EVIDENCE);

		LocalDate start = request.startDate();
		LocalDate end = request.endDate();
		switch (dateType) {
			case TBD -> {
				if (start != null) {
					throw error(ErrorCode.TIMELINE_TBD_DATE_NOT_ALLOWED, index, "startDate", null);
				}
				if (end != null) {
					throw error(ErrorCode.TIMELINE_TBD_DATE_NOT_ALLOWED, index, "endDate", null);
				}
				if (dateText == null) {
					throw error(ErrorCode.TIMELINE_DATE_TEXT_REQUIRED, index, "dateText", null);
				}
			}
			case SINGLE -> {
				if (start == null) {
					throw error(ErrorCode.TIMELINE_DATE_REQUIRED, index, "startDate", null);
				}
				if (end == null) {
					end = start;
				} else if (!end.equals(start)) {
					throw error(ErrorCode.TIMELINE_SINGLE_DATE_MISMATCH, index, "endDate", null);
				}
			}
			case RANGE -> {
				if (start == null) {
					throw error(ErrorCode.TIMELINE_DATE_REQUIRED, index, "startDate", null);
				}
				if (end == null) {
					throw error(ErrorCode.TIMELINE_DATE_REQUIRED, index, "endDate", null);
				}
				if (end.isBefore(start)) {
					throw error(ErrorCode.TIMELINE_RANGE_REVERSED, index, "endDate", null);
				}
			}
		}
		return new Item(stageCode, title, dateType, start, end, dateText, note, evidence);
	}

	private static void checkLength(int index, String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw error(ErrorCode.TIMELINE_FIELD_TOO_LONG, index, field, max);
		}
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static CustomDetailException error(ErrorCode code, Integer index, String field, Integer max) {
		return new CustomDetailException(code, new TimelineFieldErrorResponse(index, field, max));
	}
}
