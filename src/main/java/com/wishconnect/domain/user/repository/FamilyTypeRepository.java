package com.wishconnect.domain.user.repository;

import com.wishconnect.domain.user.entity.FamilyType;
import com.wishconnect.domain.user.entity.FamilyCategory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyTypeRepository extends JpaRepository<FamilyType, Long> {

	Optional<FamilyType> findFirstByName(String name);

	Optional<FamilyType> findFirstByNameAndCategory(String name, FamilyCategory category);

	List<FamilyType> findAllByNameIn(Collection<String> names);
}
