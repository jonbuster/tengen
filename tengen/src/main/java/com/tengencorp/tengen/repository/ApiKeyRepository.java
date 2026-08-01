package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.ApiKey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHash(String keyHash);
}
