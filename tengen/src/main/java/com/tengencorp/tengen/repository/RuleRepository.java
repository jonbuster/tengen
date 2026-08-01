package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.Rule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    List<Rule> findByActiveTrueOrderByNameAsc();

    Optional<Rule> findByName(String name);

    boolean existsByName(String name);
}
