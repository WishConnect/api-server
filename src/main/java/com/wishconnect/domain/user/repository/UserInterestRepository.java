package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.UserInterest;
import com.wishconnect.domain.user.entity.UserProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

	void deleteByUserProfile(UserProfile userProfile);

	List<UserInterest> findAllByUserProfile_User_Id(UUID userId);
}
