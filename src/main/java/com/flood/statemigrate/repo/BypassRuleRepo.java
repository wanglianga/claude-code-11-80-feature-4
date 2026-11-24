package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.BypassRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BypassRuleRepo extends JpaRepository<BypassRule, Long> {
    List<BypassRule> findByStreamIdOrderByIdAsc(Long streamId);
}
