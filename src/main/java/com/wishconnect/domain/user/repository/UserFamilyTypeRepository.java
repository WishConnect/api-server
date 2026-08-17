package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.UserFamilyType;
import com.wishconnect.domain.user.entity.UserProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFamilyTypeRepository extends JpaRepository<UserFamilyType, Long> {

	void deleteByUserProfile(UserProfile userProfile);

	List<UserFamilyType> findAllByUserProfile_User_Id(UUID userId);
}
