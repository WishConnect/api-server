package com.wishconnect.domain.inquiry.controller;

import com.wishconnect.domain.inquiry.dto.ContentInquiryRequest;
import com.wishconnect.domain.inquiry.dto.ContentInquiryResponse;
import com.wishconnect.domain.inquiry.service.ContentInquiryService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "콘텐츠 이용 문의", description = "게시 중단·저작권·정보 수정 문의 접수")
@RestController
@RequestMapping("/api/v1/content-inquiries")
@RequiredArgsConstructor
public class ContentInquiryController {

	private final ContentInquiryService contentInquiryService;

	@Operation(summary = "콘텐츠 이용 문의 접수",
			description = "비회원도 접수할 수 있습니다. request는 JSON, attachment는 선택 파일이며 PDF·PNG·JPG/JPEG만 2MB까지 허용합니다.")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<ContentInquiryResponse>> create(
			@Valid @RequestPart("request") ContentInquiryRequest request,
			@RequestPart(value = "attachment", required = false) MultipartFile attachment) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.ok(contentInquiryService.create(request, attachment)));
	}
}
