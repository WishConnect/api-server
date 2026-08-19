package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.*;
import com.wishconnect.domain.scholarship.service.ScholarshipCalendarService;
import com.wishconnect.domain.scholarship.service.ScholarshipDetailService;
import com.wishconnect.domain.scholarship.service.ScholarshipEventService;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.domain.scholarship.service.ScholarshipService;
import com.wishconnect.global.common.ApiResponse;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
장학금 큐레이팅(추천/매칭)·상세 API 컨트롤러입니다. (동기화는 ScholarshipSyncController 담당)
 */
@Tag(name = "장학금", description = "큐레이팅·상세·검색·홈 요약")
@RestController
@RequestMapping("/api/v1/scholarships")
@RequiredArgsConstructor
public class ScholarshipController {

	private final ScholarshipRecommendationService scholarshipRecommendationService;
	private final ScholarshipDetailService scholarshipDetailService;
	private final ScholarshipCalendarService scholarshipCalendarService;
	private final ScholarshipService scholarshipService;
	private final ScholarshipEventService scholarshipEventService;

	/**
	 * 추천 노출·클릭 기록.
	 *
	 * <p>지금은 추천이 맞는지 알 방법이 없다. 점수식을 바꿔도 좋아졌는지 나빠졌는지 말할 근거가
	 * 없어 고치는 것마다 취향 논쟁이 된다. 노출 대비 클릭을 보려면 노출부터 남아야 한다.
	 *
	 * <p>화면 단위로 모아서 한 번에 보낸다(최대 100건). 카드마다 요청하면 목록 한 번에 수십 번이다.
	 * 스크랩·작성 착수는 서버가 직접 남기므로 보낼 필요 없다.
	 *
	 * <p>기록 실패는 응답을 실패시키지 않는다. 저장된 건수를 돌려준다.
	 */
	@Operation(summary = "추천 노출·클릭 기록", description = "큐레이팅 응답의 rankerVersion과 각 카드 section을 그대로 실어 최대 100건을 한 번에 기록합니다. 스크랩·지원서 작성 시작은 서버가 직접 기록하므로 보내지 않습니다.")
	@PostMapping("/events")
	public ApiResponse<Integer> recordEvents(
			@AuthenticationPrincipal String userId,
			@Valid @RequestBody ScholarshipEventRequest request) {
		return ApiResponse.ok(scholarshipEventService.record(UUID.fromString(userId), request));
	}

	
	/**
	 * 큐레이팅 메인. 응답의 {@code viewMode} 가 세 화면 중 어느 것인지 알려준다.
	 *
	 * <p>비로그인도 볼 수 있다. 가입 전에 "여기 뭐가 있는지" 를 보여주지 못하면 가입할 이유가
	 * 생기지 않는다. 이때는 추천 없이 {@code sort} 기준으로만 정렬한다.
	 *
	 * <p>{@code sort} 는 비로그인 화면의 드롭다운(최신 등록순/마감 임박순)이다. 로그인 상태에는
	 * 화면에 드롭다운이 없어 무시된다. category 필터는 태그 데이터 확보 전까지 미적용(파라미터만 수용).
	 */
	@GetMapping("/curated")
	@Operation(summary = "사용자 상태별 장학금 큐레이팅", description = """
			비로그인·온보딩 미완료·온보딩 완료 사용자가 모두 호출하는 동일 API입니다.
			- GUEST: 개인화 없이 모집 중 장학금을 sort 기준으로 제공합니다.
			- ONBOARDING_REQUIRED: 마감 임박 featured만 제공하고 개인화 영역은 잠금 처리합니다.
			- PERSONALIZED: 프로필 기반 자격 판정·점수 계산 후 featured, campus, other, ineligible로 나눕니다.
			응답의 rankerVersion과 카드별 section은 POST /events에 그대로 전달해야 합니다. category는 현재 수용만 하고 필터링하지 않으며, sort는 GUEST에서만 사용합니다.
			""")
	public ApiResponse<CuratedScholarshipResponse> getCurated(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "DEADLINE") CuratedSort sort,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ApiResponse.ok(scholarshipRecommendationService.getCuratedScholarships(
				resolveUserId(userIdStr), sort, page, size));
	}

	/** 홈 - 오늘의 장학금 소식 요약(신규 맞춤/이번 주 마감 건수). */
	@GetMapping("/home-summary")
	@Operation(summary = "홈 장학금 소식 요약", description = "로그인 사용자의 신규 맞춤 장학금, 이번 주 마감, 작성 중 지원서, 새 인사이트 건수를 반환합니다.")
	public ApiResponse<HomeSummaryResponse> getHomeSummary(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(scholarshipRecommendationService.getHomeSummary(UUID.fromString(userId)));
	}

	/**
	 * 홈 - 이번 달 일정 달력. 모집 시작·마감을 각각 이벤트로 준다.
	 *
	 * <p>year·month 를 생략하면 이번 달. scope 는 MATCHED(기본, 지원 가능) / SCRAPPED / ALL.
	 * 전체를 다 띄우면 달력이 빽빽해 쓸모없어서 기본은 지원 가능한 것만 준다.
	 */
	@GetMapping("/calendar")
	@Operation(summary = "장학금 일정 달력", description = "모집 시작·마감을 달력 이벤트로 반환합니다. year·month 생략 시 이번 달이며 scope는 MATCHED(기본), SCRAPPED, ALL입니다.")
	public ApiResponse<ScholarshipCalendarResponse> getCalendar(
			@AuthenticationPrincipal String userId,
			@RequestParam(required = false) Integer year,
			@RequestParam(required = false) Integer month,
			@RequestParam(required = false) CalendarScope scope) {
		return ApiResponse.ok(
				scholarshipCalendarService.getCalendar(UUID.fromString(userId), year, month, scope));
	}

	/** 장학금 상세(요약 테이블 + 선발 일정 타임라인 + 제출 서류 + 매칭 사유). */
	@GetMapping("/{scholarshipId}")
	@Operation(summary = "장학금 상세 조회", description = "요약 정보, 모집 기간, 제출 서류, 자소서·면접 필요 여부와 근거, 조건별 자격 판정, 추천 이유를 반환합니다.")
	public ApiResponse<ScholarshipDetailResponse> getDetail(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		return ApiResponse.ok(scholarshipDetailService.getDetail(UUID.fromString(userId), scholarshipId));
	}

	/** 장학금을 키워드·카테고리로 검색한다. 비로그인도 조회 가능하며 이때 isScrapped 는 항상 false 다. */
	@GetMapping("/search")
	@Operation(summary = "장학금 검색", description = "키워드·카테고리·정렬·스크랩 여부로 장학금을 검색합니다. 비로그인도 호출할 수 있으며 이때 isScrapped는 false입니다.")
	public ApiResponse<ScholarshipSearchResponse> searchScholarships(
			@AuthenticationPrincipal String userIdStr,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "deadline") String sort,
			@RequestParam(defaultValue = "false") boolean scrappedOnly,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size
	) {
		UUID userId = resolveUserId(userIdStr);
		return ApiResponse.ok(scholarshipService.search(userId, keyword, category, sort, scrappedOnly, page, size));
	}


	private UUID resolveUserId(String userIdStr) {
		if (userIdStr == null || "anonymousUser".equals(userIdStr)) {
			return null;
		}
		return UUID.fromString(userIdStr);
	}
}
