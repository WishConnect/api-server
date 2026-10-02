package com.wishconnect.global.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 짧은 관리자 주소를 실제 보호 대상 HTML로 연결한다. */
@Controller
public class AdminPageController {

	/**
	 * 콘솔로 보내는 주소들.
	 *
	 * <p>{@code /admin/index.html} 은 토큰을 붙여넣던 예전 콘솔이고(삭제됨),
	 * {@code /admin/layout-preview.html} 은 현재 콘솔 파일이 정적 경로로도 그대로 열리던 것이다.
	 * 북마크가 남아 있을 수 있어 404 대신 콘솔로 돌려보낸다. Nginx 에서도 같은 경로를 막는다
	 * (deploy/nginx/).
	 */
	@GetMapping({"/admin", "/admin/", "/admin/index.html", "/admin/layout-preview.html"})
	public String admin() {
		return "redirect:/admin/console";
	}

	@GetMapping("/admin/console")
	public ResponseEntity<Resource> console() {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.body(new ClassPathResource("static/admin/layout-preview.html"));
	}
}
