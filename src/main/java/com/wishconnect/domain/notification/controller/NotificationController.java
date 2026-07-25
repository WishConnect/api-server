package com.wishconnect.domain.notification.controller;

import com.wishconnect.domain.notification.dto.NotificationListResponse;
import com.wishconnect.domain.notification.dto.NotificationReadResponse;
import com.wishconnect.domain.notification.dto.NotificationSettingRequest;
import com.wishconnect.domain.notification.dto.NotificationSettingResponse;
import com.wishconnect.domain.notification.dto.UpdateResponse;
import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "알림센터 조회, 읽음 처리, 삭제, 설정")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notificationService;

	@Operation(summary = "알림 목록", description = "사용자의 알림 목록과 안 읽은 개수를 조회합니다. type은 ALL/RECOMMENDATION/SCHEDULE/WRITING/ETC를 지원합니다.")
	@GetMapping
	public ApiResponse<NotificationListResponse> getNotifications(
			@AuthenticationPrincipal String userId,
			@RequestParam(defaultValue = "ALL") String type,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int size
	) {
		return ApiResponse.ok(notificationService.getNotifications(UUID.fromString(userId), type, page, size));
	}

	@Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 상태로 변경합니다.")
	@PatchMapping("/{notificationId}/read")
	public ApiResponse<NotificationReadResponse> markAsRead(
			@AuthenticationPrincipal String userId,
			@PathVariable Long notificationId
	) {
		return ApiResponse.ok(notificationService.markAsRead(UUID.fromString(userId), notificationId));
	}

	@Operation(summary = "알림 전체 삭제", description = "로그인한 사용자의 알림을 모두 삭제합니다.")
	@DeleteMapping
	public ApiResponse<Void> deleteAll(@AuthenticationPrincipal String userId) {
		notificationService.deleteAll(UUID.fromString(userId));
		return ApiResponse.ok(null);
	}

	@Operation(summary = "알림 설정 조회", description = "사용자의 알림 표시 및 유형별 토글 설정을 조회합니다.")
	@GetMapping("/settings")
	public ApiResponse<NotificationSettingResponse> getSetting(@AuthenticationPrincipal String userId) {
		return ApiResponse.ok(notificationService.getSetting(UUID.fromString(userId)));
	}

	@Operation(summary = "알림 설정 변경", description = "사용자의 알림 표시 및 유형별 토글 설정을 저장합니다.")
	@PutMapping("/settings")
	public ApiResponse<UpdateResponse> updateSetting(
			@AuthenticationPrincipal String userId,
			@RequestBody NotificationSettingRequest request
	) {
		return ApiResponse.ok(notificationService.updateSetting(UUID.fromString(userId), request));
	}
}
