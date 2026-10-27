package com.wishconnect.global.web;

import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자의 쓰기 요청이 400·500 으로 실패하면 작업 기록(ADMIN_REQUEST_FAILED)에 남긴다.
 *
 * <p>작업 기록은 컨트롤러가 성공한 뒤에만 남겨 왔다. 그래서 "누가 무엇을 하려다 막혔는지"(권한·검증·중복 요청·서버 오류)가
 * 앱 로그에만 흩어져 있었다. 실패한 시도는 아무것도 바꾸지 않았다는 기록이다.
 *
 * <ul>
 *   <li>대상: ADMIN 권한으로 인증된 POST·PUT·PATCH·DELETE 중 응답이 400 이상인 것</li>
 *   <li>제외: 관리자 로그인·로그아웃·연장(로그인 기록이 따로 있다), 인증 전 요청(누군지 모름)</li>
 *   <li>오류 코드: 예외 처리기({@code @RestControllerAdvice})가 처리한 예외를 DispatcherServlet 이 요청 속성에 남기므로 거기서 읽는다</li>
 * </ul>
 *
 * <p>쿼리 문자열은 남기지 않는다(예전 반려 API 처럼 사유가 쿼리로 오는 경우가 있다).
 */
@RequiredArgsConstructor
public class AdminFailedRequestAuditInterceptor implements HandlerInterceptor {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
	private static final String AUTH_PATH = "/api/v1/admin/auth/";

	private final AdminAuditLogService adminAuditLogService;

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		if (!WRITE_METHODS.contains(request.getMethod()) || response.getStatus() < 400
				|| request.getRequestURI().startsWith(AUTH_PATH)) {
			return;
		}
		UUID actor = adminActor();
		if (actor == null) {
			return;
		}
		Object handled = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
		Throwable cause = ex != null ? ex : handled instanceof Throwable throwable ? throwable : null;
		adminAuditLogService.record(actor, AdminAction.ADMIN_REQUEST_FAILED, null, null,
				detail(request.getMethod(), request.getRequestURI(), response.getStatus(), cause));
	}

	/** 예: "PUT /api/v1/scholarships/manual/12/full → 400 TIMELINE_DATE_REQUIRED (선발 일정 날짜를 입력해주세요.)" */
	static String detail(String method, String uri, int status, Throwable cause) {
		StringBuilder text = new StringBuilder(method).append(' ').append(uri).append(" → ").append(status);
		if (cause instanceof CustomException custom) {
			text.append(' ').append(custom.getErrorCode().name()).append(" (")
					.append(custom.getErrorCode().getMessage()).append(')');
		} else if (cause != null) {
			text.append(' ').append(cause.getClass().getSimpleName());
		}
		return text.toString();
	}

	/** ADMIN 으로 인증된 요청의 관리자 ID. 아니면 null. */
	private static UUID adminActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof String principal)
				|| authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			return null;
		}
		try {
			return UUID.fromString(principal);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
