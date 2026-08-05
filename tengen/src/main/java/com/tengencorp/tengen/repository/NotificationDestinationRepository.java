package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationDestination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDestinationRepository extends JpaRepository<NotificationDestination, Long> {

    List<NotificationDestination> findByChannelAndEnabledTrueOrderByDisplayNameAsc(NotificationChannel channel);

    List<NotificationDestination> findAllByOrderByDisplayNameAsc();
}
