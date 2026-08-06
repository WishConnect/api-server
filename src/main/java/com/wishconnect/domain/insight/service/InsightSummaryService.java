package com.wishconnect.domain.insight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wishconnect.domain.application.client.LlmClient;
import com.wishconnect.domain.application.client.dto.LlmChatRequest;
import com.wishconnect.domain.application.client.dto.LlmMessage;
import com.wishconnect.domain.application.client.dto.LlmModel;
import com.wishconnect.domain.insight.dto.InsightSummaryResult;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightSummaryService {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    private static final int MAX_CONTENT_LENGTH = 2000;

    public InsightSummaryResult summarize(String originalTitle, String rawContent) {
        String trimmedContent = trim(rawContent);

        LlmChatRequest request = LlmChatRequest.of(
                LlmModel.SUMMARY,
                buildSystemPrompt(),
                List.of(LlmMessage.user(buildUserMessage(originalTitle, trimmedContent)))
        );

        String response = llmClient.chat(request);

        return parseResponse(response);
    }

    private String trim(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > MAX_CONTENT_LENGTH
                ? content.substring(0, MAX_CONTENT_LENGTH)
                : content;
    }

    private String buildSystemPrompt() {
        return """
                당신은 장학금 관련 블로그 글을 분석해서 요약하는 역할을 합니다.
                주어진 글을 읽고 JSON 형식으로만 답변하세요. 다른 설명은 절대 하지 마세요.
                """;
    }

    private String buildUserMessage(String originalTitle, String content) {
        return """
                다음은 장학금 관련 블로그 글입니다. 아래 규칙을 지켜 분석해주세요.

                원본 제목: %s
                본문: %s

                규칙:
                1. title: 원본 제목이 낚시성이거나 이모지·특수문자·과장된 표현이 섞여 있다면
                   내용을 기반으로 20자 이내의 명확하고 담백한 제목으로 다시 작성하세요.
                   원본 제목이 이미 적절하다면 그대로 사용해도 됩니다.
                2. summary: 40자 이내 한 문장 요약. 원문 문장을 그대로 가져오지 말고
                   핵심을 재구성해서 작성.
                3. category: 아래 중 하나만 선택
                   - ACCEPTED (합격 후기)
                   - SCHOLARSHIP_INFO (장학금 정보)
                   - WRITING_TIP (작성 팁)
                   - EXPERIENCE (경험담)
                   - QNA (질문과 답변)
                4. tags: 이 글과 관련된 태그 1~3개, 배열로 (예: ["생활비지원", "자기소개서"])

                아래 JSON 형식으로만 답변하세요.
                {
                  "title": "...",
                  "summary": "...",
                  "category": "...",
                  "tags": ["...", "..."]
                }
                """.formatted(originalTitle, content);
    }

    private InsightSummaryResult parseResponse(String response) {
        try {
            String cleanJson = response
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            return objectMapper.readValue(cleanJson, InsightSummaryResult.class);
        } catch (Exception e) {
            log.error("[Insight] LLM 응답 파싱 실패 response={}", response, e);
            throw new CustomException(ErrorCode.LLM_EMPTY_RESPONSE);
        }
    }
}
