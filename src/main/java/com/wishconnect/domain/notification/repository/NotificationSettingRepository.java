package com.wishconnect.domain.notification.repository;

import com.wishconnect.domain.notification.entity.NotificationSetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

	Optional<NotificationSetting> findByUser_Id(UUID userId);
}
