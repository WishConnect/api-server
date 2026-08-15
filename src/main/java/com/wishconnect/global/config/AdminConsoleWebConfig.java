package com.wishconnect.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리자 콘솔 정적 화면의 진입 경로.
 *
 * <p>Spring Boot 는 welcome page 규칙을 루트(<code>/</code>)에만 적용한다. 그래서
 * <code>/admin/</code> 로 들어오면 <code>static/admin/</code> 디렉터리를 파일로 찾지 못해 404 가 난다
 * (<code>/admin/index.html</code> 을 끝까지 쳐야만 열렸다).
 *
 * <p>팀원들이 "화면이 안 뜬다"고 한 원인이라 두 형태 모두 index.html 로 포워딩한다.
 */
@Configuration
public class AdminConsoleWebConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/admin").setViewName("forward:/admin/index.html");
		registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
	}
}
