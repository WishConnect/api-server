package com.wishconnect.domain.notification.repository;

import com.wishconnect.domain.notification.entity.NotificationDispatchLog;
import com.wishconnect.domain.notification.entity.NotificationType;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDispatchLogRepository extends JpaRepository<NotificationDispatchLog, Long> {

	boolean existsByUser_IdAndScholarship_IdAndTypeAndSentDate(
			UUID userId,
			Long scholarshipId,
			NotificationType type,
			LocalDate sentDate
	);

	boolean existsByUser_IdAndScholarship_IdAndType(
			UUID userId,
			Long scholarshipId,
			NotificationType type
	);
}
