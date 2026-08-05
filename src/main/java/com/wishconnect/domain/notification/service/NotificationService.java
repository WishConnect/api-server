package com.wishconnect.domain.notification.service;

import com.wishconnect.domain.application.entity.Essay;
import com.wishconnect.domain.notification.dto.NotificationListResponse;
import com.wishconnect.domain.notification.dto.NotificationListResponse.Pagination;
import com.wishconnect.domain.notification.dto.NotificationReadResponse;
import com.wishconnect.domain.notification.dto.NotificationSettingRequest;
import com.wishconnect.domain.notification.dto.NotificationSettingResponse;
import com.wishconnect.domain.notification.dto.UpdateResponse;
import com.wishconnect.domain.notification.entity.Notification;
import com.wishconnect.domain.notification.entity.NotificationDispatchLog;
import com.wishconnect.domain.notification.entity.NotificationSetting;
import com.wishconnect.domain.notification.entity.NotificationType;
import com.wishconnect.domain.notification.repository.NotificationDispatchLogRepository;
import com.wishconnect.domain.notification.repository.NotificationRepository;
import com.wishconnect.domain.notification.repository.NotificationSettingRepository;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private static final String RELATED_SCHOLARSHIP = "SCHOLARSHIP";
	private static final String RELATED_ESSAY = "ESSAY";

	private final NotificationRepository notificationRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final NotificationDispatchLogRepository dispatchLogRepository;
	private final UserRepository userRepository;

	@Transactional(readOnly = true)
	public NotificationListResponse getNotifications(UUID userId, String type, int page, int size) {
		NotificationType notificationType = parseType(type);
		int safePage = Math.max(page, 1);
		int safeSize = Math.max(size, 1);
		PageRequest pageable = PageRequest.of(
				safePage - 1,
				safeSize,
				Sort.by(Sort.Direction.DESC, "createdAt")
		);

		Page<Notification> notifications = notificationType == null
				? notificationRepository.findByUser_Id(userId, pageable)
				: notificationRepository.findByUser_IdAndType(userId, notificationType, pageable);

		return new NotificationListResponse(
				notificationRepository.countUnreadByUserId(userId),
				notifications.getContent().stream()
						.map(NotificationListResponse.NotificationItemResponse::from)
						.toList(),
				new Pagination(safePage, safeSize, notifications.getTotalElements(), notifications.getTotalPages())
		);
	}

	@Transactional
	public NotificationReadResponse markAsRead(UUID userId, Long notificationId) {
		Notification notification = notificationRepository.findById(notificationId)
				.filter(item -> item.getUser().getId().equals(userId))
				.orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
		notification.markAsRead();
		return new NotificationReadResponse(notification.isRead());
	}

	@Transactional
	public void deleteAll(UUID userId) {
		notificationRepository.deleteByUser_Id(userId);
	}

	@Transactional
	public NotificationSettingResponse getSetting(UUID userId) {
		return NotificationSettingResponse.from(getOrCreateSetting(userId));
	}

	@Transactional
	public UpdateResponse updateSetting(UUID userId, NotificationSettingRequest request) {
		NotificationSetting setting = getOrCreateSetting(userId);
		setting.update(
				request.notificationEnabled(),
				request.matchingEnabled(),
				request.scheduleEnabled(),
				request.essayEnabled(),
				request.etcEnabled()
		);
		return new UpdateResponse(true);
	}

	/**
	 * 추천 결과가 저장형으로 전환되거나 추천 배치가 생겼을 때 호출할 맞춤 장학금 알림 생성 메서드입니다.
	 */
	@Transactional
	public void createRecommendationNotification(User user, Scholarship scholarship) {
		if (!isDispatchable(scholarship)) {
			return;
		}
		if (dispatchLogRepository.existsByUser_IdAndScholarship_IdAndType(
				user.getId(), scholarship.getId(), NotificationType.RECOMMENDATION)) {
			return;
		}
		createScholarshipNotificationIfAllowed(
				user,
				scholarship,
				NotificationType.RECOMMENDATION,
				"맞춤 장학금",
				"조건에 맞는 신규 장학금이 등록됐어요!"
		);
	}

	@Transactional
	public void createDeadlineNotification(User user, Scholarship scholarship, long dDay) {
		if (!isDispatchable(scholarship)) {
			return;
		}
		String title = dDay == 0 ? "오늘 마감" : "마감 임박";
		String content = dDay == 0
				? "오늘 마감인 장학금이 있어요. 지원을 확인해보세요."
				: "D-" + dDay + " 마감 임박! 지원을 서둘러주세요.";
		createScholarshipNotificationIfAllowed(user, scholarship, NotificationType.SCHEDULE, title, content);
	}

	/**
	 * 지원서/자기소개서 도메인에서 작성 이벤트가 발생했을 때 호출합니다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void createWritingNotification(Essay essay) {
		User user = essay.getUser();
		NotificationSetting setting = getOrCreateSetting(user.getId());
		if (!setting.isEnabled(NotificationType.WRITING)) {
			return;
		}
		if (alreadyDispatchedToday(user.getId(), essay.getScholarship().getId(), NotificationType.WRITING)) {
			return;
		}
		notificationRepository.save(Notification.create(
				user,
				NotificationType.WRITING,
				"작성 이어쓰기",
				"작성 중인 지원서가 있어요. 이어서 작성해볼까요?",
				RELATED_ESSAY,
				essay.getId()
		));
		saveDispatchLog(user, essay.getScholarship(), NotificationType.WRITING);
	}

	private void createScholarshipNotificationIfAllowed(User user, Scholarship scholarship, NotificationType type,
			String title, String content) {
		if (!isDispatchable(scholarship)) {
			return;
		}
		NotificationSetting setting = getOrCreateSetting(user.getId());
		if (!setting.isEnabled(type)) {
			return;
		}
		if (alreadyDispatchedToday(user.getId(), scholarship.getId(), type)) {
			return;
		}
		notificationRepository.save(Notification.create(
				user,
				type,
				title,
				content,
				RELATED_SCHOLARSHIP,
				scholarship.getId()
		));
		saveDispatchLog(user, scholarship, type);
	}

	private NotificationSetting getOrCreateSetting(UUID userId) {
		return notificationSettingRepository.findByUser_Id(userId)
				.orElseGet(() -> {
					User user = userRepository.findById(userId)
							.filter(found -> !found.isDeleted())
							.orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
					return notificationSettingRepository.save(NotificationSetting.createDefault(user));
				});
	}

	private boolean alreadyDispatchedToday(UUID userId, Long scholarshipId, NotificationType type) {
		return dispatchLogRepository.existsByUser_IdAndScholarship_IdAndTypeAndSentDate(
				userId,
				scholarshipId,
				type,
				LocalDate.now()
		);
	}

	private void saveDispatchLog(User user, Scholarship scholarship, NotificationType type) {
		dispatchLogRepository.save(NotificationDispatchLog.builder()
				.user(user)
				.scholarship(scholarship)
				.type(type)
				.sentDate(LocalDate.now())
				.build());
	}

	private boolean isDispatchable(Scholarship scholarship) {
		return scholarship != null && scholarship.isActive() && scholarship.getDeletedAt() == null;
	}

	private NotificationType parseType(String type) {
		if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
			return null;
		}
		try {
			return NotificationType.valueOf(type.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new CustomException(ErrorCode.INVALID_INPUT);
		}
	}
}
