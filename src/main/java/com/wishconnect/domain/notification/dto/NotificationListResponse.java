package com.wishconnect.domain.notification.dto;

import com.wishconnect.domain.notification.entity.Notification;
import com.wishconnect.domain.notification.entity.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "알림 목록 응답")
public record NotificationListResponse(
		long unreadCount,
		List<NotificationItemResponse> notifications,
		Pagination pagination
) {

	public record NotificationItemResponse(
			Long notificationId,
			NotificationType type,
			String title,
			String message,
			String relatedType,
			Long relatedId,
			boolean isRead,
			LocalDateTime createdAt
	) {

		public static NotificationItemResponse from(Notification notification) {
			return new NotificationItemResponse(
					notification.getId(),
					notification.getType(),
					notification.getTitle(),
					notification.getContent(),
					notification.getRelatedType(),
					notification.getRelatedId(),
					notification.isRead(),
					notification.getCreatedAt()
			);
		}
	}

	public record Pagination(
			int page,
			int size,
			long totalCount,
			int totalPages
	) {
	}
}
