package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.Event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long>,
                                         org.springframework.data.jpa.repository.JpaSpecificationExecutor<Event> {

    @Override
    @EntityGraph(attributePaths = {"apiKey", "rabbitMqConnector"})
    Page<Event> findAll(Specification<Event> specification, Pageable pageable);
}
