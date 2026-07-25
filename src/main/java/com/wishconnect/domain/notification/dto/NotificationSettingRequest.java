package com.wishconnect.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 설정 변경 요청")
public record NotificationSettingRequest(
		boolean notificationEnabled,
		boolean matchingEnabled,
		boolean scheduleEnabled,
		boolean essayEnabled,
		boolean etcEnabled
) {
}
