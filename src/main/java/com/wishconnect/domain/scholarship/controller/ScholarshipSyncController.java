package com.wishconnect.domain.scholarship.controller;

import com.wishconnect.domain.scholarship.dto.ScholarshipSyncResponse;
import com.wishconnect.domain.scholarship.service.ScholarshipSyncService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
장학금 외부 API 동기화를 수동으로 실행하는 컨트롤러입니다.
현재는 개발/관리용으로 raw_scholarship 수집 결과를 확인하는 진입점 역할을 합니다.
(나중에는 자동 동기화로 가져오게 할 것입니다.)
 */
@RestController
@RequestMapping("/v1/scholarships")
@Profile("!test")
public class ScholarshipSyncController {

	private final ScholarshipSyncService scholarshipSyncService;

	public ScholarshipSyncController(ScholarshipSyncService scholarshipSyncService) {
		this.scholarshipSyncService = scholarshipSyncService;
	}

	@PostMapping("/sync")
	public ScholarshipSyncResponse syncScholarships() {
		return scholarshipSyncService.sync();
	}
}
