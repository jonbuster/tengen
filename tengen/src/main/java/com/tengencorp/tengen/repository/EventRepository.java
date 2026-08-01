package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.Event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}
