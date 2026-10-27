package com.wishconnect.global.web;

import com.wishconnect.global.audit.AdminAuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리자 쓰기 요청 실패 기록 인터셉터 등록.
 *
 * <p>가장 먼저 실행되게 둔다. 뒤에 오는 중복 요청 방지 인터셉터가 preHandle 에서 409 를 던져도, 먼저 통과한 인터셉터의
 * afterCompletion 은 불리므로 그 실패도 남는다. 감사 서비스 빈이 없는 슬라이스 테스트에서는 등록하지 않는다.
 */
@Configuration
public class AdminFailedRequestAuditConfig implements WebMvcConfigurer {

	private final ObjectProvider<AdminAuditLogService> adminAuditLogService;

	public AdminFailedRequestAuditConfig(ObjectProvider<AdminAuditLogService> adminAuditLogService) {
		this.adminAuditLogService = adminAuditLogService;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		AdminAuditLogService service = adminAuditLogService.getIfAvailable();
		if (service == null) {
			return;
		}
		registry.addInterceptor(new AdminFailedRequestAuditInterceptor(service))
				.addPathPatterns("/api/v1/**")
				.order(Ordered.HIGHEST_PRECEDENCE);
	}
}
