package com.wishconnect.global.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 짧은 관리자 주소를 실제 보호 대상 HTML로 연결한다. */
@Controller
public class AdminPageController {

	@GetMapping("/admin")
	public String admin() {
		return "redirect:/admin/index.html";
	}
}
