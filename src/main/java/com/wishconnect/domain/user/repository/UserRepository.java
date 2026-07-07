package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	Optional<User> findByKakaoId(Long kakaoId);

	boolean existsByEmail(String email);
}
