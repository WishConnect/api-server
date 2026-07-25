package com.wishconnect.domain.notification.scheduler;

import com.wishconnect.domain.notification.service.NotificationService;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse;
import com.wishconnect.domain.scholarship.dto.CuratedScholarshipResponse.ScholarshipCard;
import com.wishconnect.domain.scholarship.entity.Scholarship;
import com.wishconnect.domain.scholarship.repository.ScholarshipRepository;
import com.wishconnect.domain.scholarship.service.ScholarshipRecommendationService;
import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.scheduled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

	private static final List<Integer> DEADLINE_DAYS = List.of(7, 3, 1, 0);

	private final ScholarshipRepository scholarshipRepository;
	private final UserRepository userRepository;
	private final NotificationService notificationService;
	private final ScholarshipRecommendationService scholarshipRecommendationService;

	@Scheduled(cron = "${notification.deadline.cron:0 20 11 * * *}", zone = "Asia/Seoul")
	public void createDeadlineNotifications() {
		List<User> users = userRepository.findAll().stream()
				.filter(user -> !user.isDeleted())
				.toList();
		if (users.isEmpty()) {
			return;
		}

		int createdTargetCount = 0;
		for (Integer dDay : DEADLINE_DAYS) {
			LocalDate targetDate = LocalDate.now().plusDays(dDay);
			LocalDateTime start = targetDate.atStartOfDay();
			LocalDateTime end = targetDate.plusDays(1).atStartOfDay();
			List<Scholarship> scholarships = scholarshipRepository.findOpenByApplicationEndAtBetween(start, end);
			for (Scholarship scholarship : scholarships) {
				long actualDday = ChronoUnit.DAYS.between(LocalDate.now(),
						scholarship.getApplicationEndAt().toLocalDate());
				for (User user : users) {
					notificationService.createDeadlineNotification(user, scholarship, actualDday);
				}
			}
			createdTargetCount += scholarships.size();
		}
		log.info("[NotificationBatch] 마감 임박 알림 대상 공고 조회 완료. targetScholarships={}", createdTargetCount);
	}

	@Scheduled(cron = "${notification.recommendation.cron:0 30 11 * * *}", zone = "Asia/Seoul")
	public void createRecommendationNotifications() {
		List<User> users = userRepository.findAll().stream()
				.filter(user -> !user.isDeleted())
				.toList();
		int targetCount = 0;
		for (User user : users) {
			CuratedScholarshipResponse curated = scholarshipRecommendationService
					.getCuratedScholarships(user.getId(), 1, 20);
			List<Long> scholarshipIds = curated.otherScholarships().stream()
					.filter(ScholarshipCard::eligible)
					.map(ScholarshipCard::scholarshipId)
					.toList();
			Map<Long, Scholarship> scholarships = scholarshipRepository.findAllById(scholarshipIds).stream()
					.collect(Collectors.toMap(Scholarship::getId, Function.identity()));
			for (Long scholarshipId : scholarshipIds) {
				Scholarship scholarship = scholarships.get(scholarshipId);
				if (scholarship != null) {
					notificationService.createRecommendationNotification(user, scholarship);
					targetCount++;
				}
			}
		}
		log.info("[NotificationBatch] 맞춤 장학금 알림 생성 시도 완료. targetRecommendations={}", targetCount);
	}
}
