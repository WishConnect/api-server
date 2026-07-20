package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.Interest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestRepository extends JpaRepository<Interest, Long> {

	Optional<Interest> findFirstByName(String name);

	List<Interest> findAllByNameIn(Collection<String> names);
}
