package com.wishconnect.domain.notification.repository;

import com.wishconnect.domain.notification.entity.Notification;
import com.wishconnect.domain.notification.entity.NotificationType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	Page<Notification> findByUser_Id(UUID userId, Pageable pageable);

	Page<Notification> findByUser_IdAndType(UUID userId, NotificationType type, Pageable pageable);

	@Query("select count(n) from Notification n where n.user.id = :userId and n.isRead = false")
	long countUnreadByUserId(@Param("userId") UUID userId);

	void deleteByUser_Id(UUID userId);
}
