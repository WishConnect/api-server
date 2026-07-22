package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserFamilyType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFamilyTypeRepository extends JpaRepository<UserFamilyType, Long> {

	void deleteByUser(User user);

	List<UserFamilyType> findAllByUser_Id(UUID userId);
}
