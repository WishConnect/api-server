package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.scholarship.dto.ConditionExtractionResponse;
import com.wishconnect.domain.scholarship.entity.ConditionOperator;
import com.wishconnect.domain.scholarship.entity.ConditionType;
import com.wishconnect.domain.scholarship.entity.ScholarshipCondition;
import com.wishconnect.domain.scholarship.repository.ScholarshipConditionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConditionExtractionServiceTest {

	@Mock
	private ScholarshipConditionRepository scholarshipConditionRepository;

	@Mock
	private LlmClient llmClient;

	private ConditionExtractionService conditionExtractionService;

	@BeforeEach
	void setUp() {
		conditionExtractionService = new ConditionExtractionService(
				scholarshipConditionRepository, llmClient, new ObjectMapper());
	}

	private ScholarshipCondition condition(long id, ConditionType type, String raw) {
		ScholarshipCondition condition = ScholarshipCondition.builder()
				.conditionType(type)
				.operator(ConditionOperator.EQ)
				.valueString(raw)
				.autoExtracted(false)
				.build();
		ReflectionTestUtils.setField(condition, "id", id);
		return condition;
	}

	private void stubTargets(ScholarshipCondition... conditions) {
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of(conditions));
	}

	@Test
	@DisplayName("정형 패턴은 룰 파서가 처리하고 LLM은 호출하지 않는다")
	void ruleOnlyWithoutLlm() {
		ScholarshipCondition income = condition(1L, ConditionType.INCOME_CRITERIA, "소득 8분위 이하");
		ScholarshipCondition gpa = condition(2L, ConditionType.ACADEMIC_CRITERIA, "평점 2.75 이상");
		stubTargets(income, gpa);

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(2);
		assertThat(income.getValueInt()).isEqualTo(8);
		assertThat(income.getOperator()).isEqualTo(ConditionOperator.LTE);
		assertThat(gpa.getValueInt()).isEqualTo(275);
		assertThat(income.isAutoExtracted()).isTrue();
		verifyNoInteractions(llmClient);
	}

	@Test
	@DisplayName("룰로 못 푼 비정형 조건만 LLM에 위임한다")
	void delegatesUnresolvedToLlm() {
		ScholarshipCondition ruled = condition(1L, ConditionType.INCOME_CRITERIA, "소득 8분위 이하");
		ScholarshipCondition fuzzy = condition(2L, ConditionType.ACADEMIC_CRITERIA,
				"직전학기 성적이 3.0(4.5 만점) 아래로 떨어지지 않은 자");
		stubTargets(ruled, fuzzy);
		given(llmClient.chat(any())).willReturn("[{\"id\":2,\"operator\":\"GTE\",\"valueInt\":300}]");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(2);
		assertThat(fuzzy.getValueInt()).isEqualTo(300);
	}

	@Test
	@DisplayName("LLM 실패(크레딧 부족 등)해도 룰 추출 결과는 유지된다")
	void keepsRuleResultsWhenLlmFails() {
		ScholarshipCondition ruled = condition(1L, ConditionType.INCOME_CRITERIA, "소득 4분위 이내");
		ScholarshipCondition fuzzy = condition(2L, ConditionType.ACADEMIC_CRITERIA, "성적 우수자");
		stubTargets(ruled, fuzzy);
		given(llmClient.chat(any())).willThrow(new RuntimeException("credit balance too low"));

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(1);
		assertThat(response.skippedCount()).isEqualTo(1);
		assertThat(ruled.getValueInt()).isEqualTo(4);
		assertThat(ruled.isAutoExtracted()).isTrue();
	}

	@Test
	@DisplayName("LLM의 범위 밖 오추출 값은 반영하지 않는다")
	void rejectsOutOfRangeLlmValues() {
		ScholarshipCondition fuzzy = condition(1L, ConditionType.INCOME_CRITERIA, "저소득 가구 우대");
		stubTargets(fuzzy);
		given(llmClient.chat(any())).willReturn("[{\"id\":1,\"operator\":\"LTE\",\"valueInt\":99}]");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isZero();
		assertThat(fuzzy.isAutoExtracted()).isFalse();
	}

	@Test
	@DisplayName("코드펜스로 감싼 LLM 응답도 파싱한다")
	void parsesFencedJson() {
		ScholarshipCondition fuzzy = condition(1L, ConditionType.GRADE_LEVEL,
				"학부 2년차부터 4년차까지 지원 가능");
		stubTargets(fuzzy);
		given(llmClient.chat(any()))
				.willReturn("```json\n[{\"id\":1,\"operator\":\"BETWEEN\",\"valueInt\":2,\"valueIntMax\":8}]\n```");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(1);
		assertThat(fuzzy.getValueIntMax()).isEqualTo(8);
	}

	@Test
	@DisplayName("원문에 없는 숫자는 범위 안이어도 버린다 — 이 단계는 본문을 못 봐서 지어내기 쉽다")
	void rejectsNumbersAbsentFromTheSourceText() {
		ScholarshipCondition fuzzy = condition(1L, ConditionType.INCOME_CRITERIA, "가계 곤란 학생 우선 선발");
		stubTargets(fuzzy);
		// 6분위는 멀쩡한 값이라 범위 검사로는 못 잡는다. 원문에 6이 없다는 것만이 근거다.
		given(llmClient.chat(any())).willReturn("[{\"id\":1,\"operator\":\"LTE\",\"valueInt\":6}]");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isZero();
		assertThat(fuzzy.getValueInt()).isNull();
		assertThat(fuzzy.isAutoExtracted()).isFalse();
	}

	@Test
	@DisplayName("'3년차'를 5~6학기로 환산한 값은 원문에 5·6이 없어도 인정한다")
	void acceptsSemesterConversionFromGradeNumber() {
		ScholarshipCondition grade = condition(1L, ConditionType.GRADE_LEVEL, "학부 3년차 재학생 대상");
		stubTargets(grade);
		given(llmClient.chat(any()))
				.willReturn("[{\"id\":1,\"operator\":\"BETWEEN\",\"valueInt\":5,\"valueIntMax\":6}]");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(1);
		assertThat(grade.getValueInt()).isEqualTo(5);
		assertThat(grade.getValueIntMax()).isEqualTo(6);
	}

	@Test
	@DisplayName("추출 대상이 없으면 아무것도 하지 않는다")
	void skipsWhenNoTargets() {
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of());

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response).isEqualTo(new ConditionExtractionResponse(0, 0, 0));
		verifyNoInteractions(llmClient);
	}
}
