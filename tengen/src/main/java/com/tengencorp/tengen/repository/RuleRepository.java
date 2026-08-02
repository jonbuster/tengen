package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.Rule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    List<Rule> findByActiveTrueOrderByNameAsc();

    List<Rule> findByActiveTrueAndArchivedAtIsNullOrderByNameAsc();

    List<Rule> findByActiveTrueAndArchivedAtIsNullAndEventTypeAndSourceOrderByNameAsc(
        String eventType, String source);

    List<Rule> findByArchivedAtIsNullOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rule from Rule rule where rule.id = :id")
    Optional<Rule> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Rule> findByName(String name);

    boolean existsByName(String name);
}
