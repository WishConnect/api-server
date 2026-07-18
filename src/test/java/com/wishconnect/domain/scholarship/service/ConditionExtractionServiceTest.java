package com.wishconnect.domain.scholarship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

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

	@Test
	@DisplayName("LLM 추출값이 조건에 반영되고 autoExtracted=true 마킹된다")
	void appliesExtractedValues() {
		ScholarshipCondition income = condition(1L, ConditionType.INCOME_CRITERIA, "소득 8분위 이하");
		ScholarshipCondition gpa = condition(2L, ConditionType.ACADEMIC_CRITERIA, "평점 2.75 이상");
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of(income, gpa));
		given(llmClient.chat(any())).willReturn("""
				[{"id":1,"operator":"LTE","valueInt":8,"valueIntMax":null},
				 {"id":2,"operator":"GTE","valueInt":275,"valueIntMax":null}]
				""");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(2);
		assertThat(income.getValueInt()).isEqualTo(8);
		assertThat(income.getOperator()).isEqualTo(ConditionOperator.LTE);
		assertThat(income.isAutoExtracted()).isTrue();
		assertThat(gpa.getValueInt()).isEqualTo(275);
	}

	@Test
	@DisplayName("범위를 벗어난 오추출 값은 반영하지 않는다 (분위 99 등)")
	void rejectsOutOfRangeValues() {
		ScholarshipCondition income = condition(1L, ConditionType.INCOME_CRITERIA, "소득 관련 조건");
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of(income));
		given(llmClient.chat(any())).willReturn("[{\"id\":1,\"operator\":\"LTE\",\"valueInt\":99}]");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isZero();
		assertThat(response.skippedCount()).isEqualTo(1);
		assertThat(income.getValueInt()).isNull();
		assertThat(income.isAutoExtracted()).isFalse();
	}

	@Test
	@DisplayName("코드펜스로 감싼 응답도 파싱한다")
	void parsesFencedJson() {
		ScholarshipCondition grade = condition(1L, ConditionType.GRADE_LEVEL, "대학2학기부터 대학8학기까지");
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of(grade));
		given(llmClient.chat(any()))
				.willReturn("```json\n[{\"id\":1,\"operator\":\"BETWEEN\",\"valueInt\":2,\"valueIntMax\":8}]\n```");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isEqualTo(1);
		assertThat(grade.getValueIntMax()).isEqualTo(8);
	}

	@Test
	@DisplayName("LLM 응답이 JSON이 아니면 전부 스킵하고 실패하지 않는다")
	void gracefulOnMalformedResponse() {
		ScholarshipCondition income = condition(1L, ConditionType.INCOME_CRITERIA, "소득 8분위 이하");
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of(income));
		given(llmClient.chat(any())).willReturn("죄송합니다. 추출할 수 없습니다.");

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response.extractedCount()).isZero();
		assertThat(income.isAutoExtracted()).isFalse();
	}

	@Test
	@DisplayName("추출 대상이 없으면 LLM을 호출하지 않는다")
	void skipsWhenNoTargets() {
		given(scholarshipConditionRepository
				.findTop50ByAutoExtractedFalseAndValueIntIsNullAndConditionTypeIn(anyList()))
				.willReturn(List.of());

		ConditionExtractionResponse response = conditionExtractionService.extract();

		assertThat(response).isEqualTo(new ConditionExtractionResponse(0, 0, 0));
	}
}
