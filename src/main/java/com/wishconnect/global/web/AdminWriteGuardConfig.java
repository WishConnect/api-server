package com.wishconnect.global.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리자 쓰기 API 중 중복 실행이 해로운 곳에만 {@link AdminWriteDedupeInterceptor} 를 건다.
 *
 * <p>대상: 새 행을 만드는 수기 등록·엑셀 반영·수기 중복 후보, 외부 호출·LLM 비용이 드는 수동 트리거.
 * 승인·반려·반려 취소는 후보 행 잠금과 상태 검사로 막으므로 여기서 빼고, 수정(PUT/PATCH)은 같은 값을 두 번 써도
 * 결과가 같아 넣지 않았다. 서비스 사용자 API 는 대상이 아니다.
 *
 * <p>Redis 빈이 없는 슬라이스 테스트에서는 등록하지 않는다.
 */
@Configuration
public class AdminWriteGuardConfig implements WebMvcConfigurer {

	static final String[] GUARDED_POST_PATHS = {
			"/api/v1/scholarships/manual",
			"/api/v1/scholarships/manual/full",
			"/api/v1/scholarships/admin/raw/*/manual",
			"/api/v1/scholarships/admin/manual-excel",
			"/api/v1/scholarships/admin/excel",
			"/api/v1/scholarships/merge/candidates/manual",
			"/api/v1/scholarships/merge/detect",
			"/api/v1/scholarships/sync",
			"/api/v1/scholarships/collect/univ/*",
			"/api/v1/scholarships/enrich",
			"/api/v1/scholarships/parse/univ-llm",
			"/api/v1/scholarships/parse/kosaf-conditions",
			"/api/v1/scholarships/conditions/extract",
			"/api/v1/scholarships/conditions/refs",
			"/api/v1/scholarships/conditions/region-backfill"
	};

	private final ObjectProvider<StringRedisTemplate> redisTemplate;

	public AdminWriteGuardConfig(ObjectProvider<StringRedisTemplate> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		StringRedisTemplate template = redisTemplate.getIfAvailable();
		if (template == null) {
			return;
		}
		registry.addInterceptor(new AdminWriteDedupeInterceptor(template)).addPathPatterns(GUARDED_POST_PATHS);
	}
}
