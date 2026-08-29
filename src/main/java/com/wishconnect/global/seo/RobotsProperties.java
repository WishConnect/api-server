package com.wishconnect.global.seo;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * robots.txt 설정.
 *
 * <p>규칙은 <b>파일이 놓인 호스트</b>에만 적용된다(네이버 가이드: {@code www.example.com/robots.txt} 의
 * 내용은 {@code example.com} 에 적용되지 않는다). 그래서 이 응답은 프론트 도메인에서 프록시로
 * 서빙돼야 의미가 있고, 그 전제로 {@code disallowPaths} 도 프론트 경로 기준으로 적는다.
 *
 * @param disallowPaths 수집을 막을 경로 목록. 로그인·마이페이지처럼 색인될 이유가 없는 곳을 적는다.
 *                      API·Swagger 경로도 함께 막아, 프록시 없이 API 도메인에서 그대로 노출돼도 안전하게 둔다.
 */
@ConfigurationProperties(prefix = "app.robots")
public record RobotsProperties(List<String> disallowPaths) {

	public RobotsProperties {
		disallowPaths = disallowPaths == null ? List.of() : List.copyOf(disallowPaths);
	}
}
