package com.wishconnect.domain.insight.repository;

import com.wishconnect.domain.insight.entity.InsightCategory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsightCategoryRepository extends JpaRepository<InsightCategory, Long> {
    Optional<InsightCategory> findByName(String name);
}
