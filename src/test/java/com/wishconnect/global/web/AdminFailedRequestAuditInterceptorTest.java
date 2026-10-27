package com.wishconnect.global.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wishconnect.global.audit.AdminAction;
import com.wishconnect.global.audit.AdminAuditLogService;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.DispatcherServlet;

/** 관리자 쓰기 요청이 실패하면 작업 기록에 남는지(성공·조회·비관리자·로그인 경로는 남기지 않음). */
class AdminFailedRequestAuditInterceptorTest {

	private final AdminAuditLogService auditLogService = mock(AdminAuditLogService.class);
	private final AdminFailedRequestAuditInterceptor interceptor = new AdminFailedRequestAuditInterceptor(auditLogService);
	private final UUID admin = UUID.randomUUID();

	@BeforeEach
	void login() {
		authenticate("ROLE_ADMIN");
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("ADMIN 의 쓰기 요청이 400 이면 메서드·경로·상태·오류 코드를 남긴다(쿼리는 남기지 않음)")
	void recordsFailedWrite() throws Exception {
		MockHttpServletRequest request = request("PUT", "/api/v1/scholarships/manual/12/full");
		request.setQueryString("note=secret");
		request.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, new CustomException(ErrorCode.TIMELINE_DATE_REQUIRED));

		interceptor.afterCompletion(request, response(400), null, null);

		verify(auditLogService).record(admin, AdminAction.ADMIN_REQUEST_FAILED, null, null,
				"PUT /api/v1/scholarships/manual/12/full → 400 TIMELINE_DATE_REQUIRED ("
						+ ErrorCode.TIMELINE_DATE_REQUIRED.getMessage() + ")");
	}

	@Test
	@DisplayName("처리되지 않은 예외(500)는 예외 이름을 남긴다")
	void recordsUnhandledException() throws Exception {
		interceptor.afterCompletion(request("POST", "/api/v1/scholarships/sync"), response(500), null,
				new IllegalStateException("boom"));

		verify(auditLogService).record(admin, AdminAction.ADMIN_REQUEST_FAILED, null, null,
				"POST /api/v1/scholarships/sync → 500 IllegalStateException");
	}

	@Test
	@DisplayName("성공·조회·로그인 경로·ADMIN 이 아닌 사용자는 남기지 않는다")
	void skipsOtherRequests() throws Exception {
		interceptor.afterCompletion(request("POST", "/api/v1/scholarships/sync"), response(200), null, null);
		interceptor.afterCompletion(request("GET", "/api/v1/admin/audit-log"), response(400), null, null);
		interceptor.afterCompletion(request("POST", "/api/v1/admin/auth/login"), response(401), null, null);
		authenticate("ROLE_USER");
		interceptor.afterCompletion(request("POST", "/api/v1/scholarships/1/reports"), response(400), null, null);
		SecurityContextHolder.clearContext();
		interceptor.afterCompletion(request("POST", "/api/v1/scholarships/sync"), response(401), null, null);

		verify(auditLogService, never()).record(any(), any(), any(), any(), any());
	}

	private void authenticate(String role) {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				admin.toString(), null, List.of(new SimpleGrantedAuthority(role))));
	}

	private static MockHttpServletRequest request(String method, String uri) {
		return new MockHttpServletRequest(method, uri);
	}

	private static MockHttpServletResponse response(int status) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(status);
		return response;
	}
}
