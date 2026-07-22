package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.User;
import com.wishconnect.domain.user.entity.UserInterest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

	void deleteByUser(User user);

	List<UserInterest> findAllByUser_Id(UUID userId);
}
