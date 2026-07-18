package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.UserProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

	Optional<UserProfile> findByUserId(UUID userId);
}
