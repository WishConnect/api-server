package com.wishconnect.domain.common.repository;

import com.wishconnect.domain.common.entity.Region;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {

	Optional<Region> findByName(String name);
}
