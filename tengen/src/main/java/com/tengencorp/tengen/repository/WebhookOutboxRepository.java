package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.WebhookOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {

    Optional<WebhookOutbox> findByDeduplicationKey(String deduplicationKey);
}
