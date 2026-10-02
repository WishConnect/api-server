package com.wishconnect.domain.application.client;

import com.anthropic.errors.CredentialResolutionException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import com.wishconnect.global.operation.AdminJobFailureType;
import java.util.Locale;

/**
 * LLM 호출 실패를 배치 실패 유형으로 나눈다.
 *
 * <p>크레딧 부족과 인증 오류는 재처리해도 같은 결과라 사람이 먼저 조치해야 한다. 나머지(타임아웃·5xx·형식 오류)는
 * 다음 배치나 재처리로 풀릴 수 있다. {@link AnthropicLlmClient} 가 원인 예외를 보존하므로 cause 를 따라가 본다.
 *
 * <p>크레딧 부족은 Anthropic 이 전용 상태 코드 없이 400(invalid_request_error)과
 * "Your credit balance is too low ..." 문구로 알려 주므로 문구로 판별한다.
 */
public final class LlmFailureClassifier {

	private LlmFailureClassifier() {
	}

	/** LLM 관련 실패면 LLM_CREDIT / LLM_AUTH / LLM_ERROR, 아니면 {@code null}. */
	public static AdminJobFailureType classify(Throwable throwable) {
		boolean llmRelated = false;
		Throwable cursor = throwable;
		int depth = 0;
		while (cursor != null && depth++ < 10) {
			if (cursor instanceof UnauthorizedException || cursor instanceof PermissionDeniedException
					|| cursor instanceof NoCredentialsException || cursor instanceof CredentialResolutionException) {
				return AdminJobFailureType.LLM_AUTH;
			}
			String message = cursor.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains("credit balance")) {
				return AdminJobFailureType.LLM_CREDIT;
			}
			if (cursor instanceof CustomException custom && isLlmCode(custom.getErrorCode())) {
				llmRelated = true;
			}
			if (cursor.getClass().getName().startsWith("com.anthropic.")) {
				llmRelated = true;
			}
			cursor = cursor.getCause() == cursor ? null : cursor.getCause();
		}
		return llmRelated ? AdminJobFailureType.LLM_ERROR : null;
	}

	/** {@link #classify} 결과를 사람이 읽을 말머리로. LLM 과 무관하면 빈 문자열. */
	public static String prefix(AdminJobFailureType type) {
		if (type == null) {
			return "";
		}
		return switch (type) {
			case LLM_CREDIT -> "[크레딧 부족] ";
			case LLM_AUTH -> "[LLM 인증 오류] ";
			default -> "";
		};
	}

	private static boolean isLlmCode(ErrorCode code) {
		return code == ErrorCode.LLM_CALL_FAILED || code == ErrorCode.LLM_EMPTY_RESPONSE
				|| code == ErrorCode.LLM_RESPONSE_TRUNCATED;
	}
}
