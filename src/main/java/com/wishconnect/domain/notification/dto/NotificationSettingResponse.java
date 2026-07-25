package com.wishconnect.domain.notification.dto;

import com.wishconnect.domain.notification.entity.NotificationSetting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 설정 응답")
public record NotificationSettingResponse(
		boolean notificationEnabled,
		boolean matchingEnabled,
		boolean scheduleEnabled,
		boolean essayEnabled,
		boolean etcEnabled
) {

	public static NotificationSettingResponse from(NotificationSetting setting) {
		return new NotificationSettingResponse(
				setting.isNotificationEnabled(),
				setting.isMatchingEnabled(),
				setting.isScheduleEnabled(),
				setting.isEssayEnabled(),
				setting.isEtcEnabled()
		);
	}
}
