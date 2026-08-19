package com.wishconnect.domain.application.controller;

import com.wishconnect.domain.application.dto.response.InterviewPrepResponse;
import com.wishconnect.domain.application.service.InterviewPrepService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 면접 예상 질문 API.
 *
 * <p>경로는 장학금 하위지만 구현은 자기소개서(application) 도메인에 둔다. 질문 생성이 AI 파트라
 * 이쪽이 관리 주체이고, 장학금 도메인은 면접 여부({@code interviewRequirement})만 제공한다.
 */
@Tag(name = "면접 예상 질문",
		description = "면접관이 물어볼 법한 질문을 미리 보여주는 준비 자료. 사전 인터뷰(자기소개서 재료 수집)와 다른 기능이다.")
@RestController
@RequestMapping("/api/v1/scholarships/{scholarshipId}/interview-questions")
@RequiredArgsConstructor
public class InterviewPrepController {

	private final InterviewPrepService interviewPrepService;

	@Operation(summary = "면접 예상 질문 조회",
			description = """
					저장된 면접 예상 질문을 조회한다. **없으면 빈 배열을 반환하고 생성하지 않는다.**

					조회에 LLM 을 태우면 화면을 열기만 해도 크레딧이 나가므로 생성과 분리했다.
					비어 있으면 생성 API 를 호출할 것.

					**응답의 interviewRequirement 로 화면을 나눌 것**
					- `REQUIRED` / `CONDITIONAL` : 면접이 있다. 질문을 보여준다
					- `NOT_REQUIRED` : 공고가 면접 없다고 밝혔다. "면접 없음"으로 표시
					- `null` : 공고에 언급이 없어 판단하지 못했다. "공고 확인 필요"로 표시.
					  NOT_REQUIRED 와 다르게 그려야 한다

					`interviewEvidence` 는 판단 근거가 된 공고 원문이다. 우리 판단만 보여주면
					틀렸을 때 사용자가 확인할 방법이 없으므로 함께 내려준다.
					""")
	@GetMapping
	public ApiResponse<InterviewPrepResponse> getInterviewQuestions(@PathVariable Long scholarshipId) {
		return ApiResponse.ok(interviewPrepService.get(scholarshipId));
	}

	@Operation(summary = "면접 예상 질문 생성",
			description = """
					면접 예상 질문을 생성한다. **이미 있으면 재생성하지 않고 그대로 돌려주므로
					여러 번 호출해도 안전하다.** LLM 은 첫 호출에만 탄다.

					질문은 **장학금 단위로 한 번 만들어 공유**한다. 같은 장학금을 준비하는 사용자끼리
					질문이 같아도 무방하고, 사용자마다 만들면 LLM 비용이 사용자 수만큼 늘어난다.
					지원서(applicationId)와 무관하므로 자소서를 쓰지 않는 장학금에도 쓸 수 있다.

					**동시 호출도 안전하다.** 같은 장학금에 두 요청이 동시에 들어오면 LLM 은 한 번만
					타고, 늦은 요청은 먼저 만들어진 질문을 받는다(최대 5초 대기). 그래도 못 받으면
					빈 목록이 오므로 화면은 잠시 후 조회를 다시 하면 된다.

					**비용 제한**: 새로 생성하는 요청만 사용자당 24시간 10건으로 제한한다.
					이미 만들어진 질문을 받는 호출은 제한에 걸리지 않는다.

					**에러**
					- 400 : 공고가 면접을 보지 않는다고 밝힘 (`interviewRequirement=NOT_REQUIRED`)
					- 400 : 마감된 장학금
					- 404 : 존재하지 않는 장학금
					- 429 : 생성 한도 초과
					- 503 : LLM 이 질문을 하나도 만들지 못함. 재시도 안내 필요
					""")
	@PostMapping
	public ApiResponse<InterviewPrepResponse> generateInterviewQuestions(
			@AuthenticationPrincipal String userId,
			@PathVariable Long scholarshipId) {
		return ApiResponse.ok(interviewPrepService.generate(UUID.fromString(userId), scholarshipId));
	}

	@Operation(summary = "면접 예상 질문 삭제 (재생성용)",
			description = """
					저장된 질문을 지운다. 다음 생성 요청이 새로 만든다.

					공고가 재파싱돼 설명·자격조건·면접 근거가 바뀌어도 질문은 자동으로 갱신되지
					않는다. 재파싱은 값이 그대로여도 일어나므로 자동 무효화 기준을 두면 멀쩡한
					질문을 계속 다시 만들어 크레딧만 쓴다. 사람이 판단해 지우게 한다. (ADMIN 전용)
					""")
	@PreAuthorize("hasRole('ADMIN')")
	@DeleteMapping
	public ApiResponse<Void> clearInterviewQuestions(@PathVariable Long scholarshipId) {
		interviewPrepService.clear(scholarshipId);
		return ApiResponse.ok(null);
	}
}
