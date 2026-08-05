package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    List<NotificationTemplate> findByChannelAndActiveTrueOrderByNameAscVersionDesc(NotificationChannel channel);

    List<NotificationTemplate> findAllByOrderByNameAscVersionDesc();

    Optional<NotificationTemplate> findFirstByNameOrderByVersionDesc(String name);
}
