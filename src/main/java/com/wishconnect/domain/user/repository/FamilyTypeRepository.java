package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.FamilyType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyTypeRepository extends JpaRepository<FamilyType, Long> {

	Optional<FamilyType> findFirstByName(String name);

	List<FamilyType> findAllByNameIn(Collection<String> names);
}
