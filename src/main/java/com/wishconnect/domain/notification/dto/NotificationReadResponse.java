package com.wishconnect.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 읽음 처리 응답")
public record NotificationReadResponse(
		boolean isRead
) {
}
