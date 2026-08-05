package com.tengencorp.tengen;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import com.tengencorp.tengen.config.NotificationDeliveryProperties;
import com.tengencorp.tengen.config.ReplayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({WebhookDeliveryProperties.class, ReplayProperties.class,
    NotificationDeliveryProperties.class})
public class TengenApplication {

	public static void main(String[] args) {
		SpringApplication.run(TengenApplication.class, args);
	}

}
