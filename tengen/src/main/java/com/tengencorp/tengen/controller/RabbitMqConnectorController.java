package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.RabbitMqConnectionTestResponse;
import com.tengencorp.tengen.dto.RabbitMqConnectorRequest;
import com.tengencorp.tengen.dto.RabbitMqConnectorResponse;
import com.tengencorp.tengen.service.RabbitMqConnectorService;
import com.tengencorp.tengen.helper.LogSafe;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JWT-protected admin API for the single UI-managed RabbitMQ connector. */
@RestController
@RequestMapping("/api/connectors/rabbitmq")
public class RabbitMqConnectorController {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConnectorController.class);

    private final RabbitMqConnectorService service;

    public RabbitMqConnectorController(RabbitMqConnectorService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<RabbitMqConnectorResponse> get() {
        return withVersion(service.get());
    }

    @PutMapping
    public ResponseEntity<RabbitMqConnectorResponse> save(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RabbitMqConnectorRequest request) {
        RabbitMqConnectorResponse response = service.save(request, ifMatch);
        log.info(
            "event=admin_mutation action=rabbitmq_connector_save entity=connector entityId={} actor={} configurationVersion={}",
            response.id(), LogSafe.text(actor()), response.configurationVersion());
        return withVersion(response);
    }

    @PostMapping("/test")
    public RabbitMqConnectionTestResponse test() {
        RabbitMqConnectionTestResponse response = service.test();
        if (response.successful()) {
            log.info(
                "event=admin_mutation action=rabbitmq_connector_test entity=connector actor={} result=success configurationVersion={}",
                LogSafe.text(actor()), response.configurationVersion());
        } else {
            log.warn(
                "event=admin_mutation action=rabbitmq_connector_test entity=connector actor={} result=failure category={}",
                LogSafe.text(actor()), LogSafe.text(response.category()));
        }
        return response;
    }

    @PostMapping("/enable")
    public ResponseEntity<RabbitMqConnectorResponse> enable() {
        RabbitMqConnectorResponse response = service.enable();
        log.info("event=admin_mutation action=rabbitmq_connector_enable entity=connector entityId={} actor={} enabled={}",
            response.id(), LogSafe.text(actor()), response.enabled());
        return withVersion(response);
    }

    @PostMapping("/disable")
    public ResponseEntity<RabbitMqConnectorResponse> disable() {
        RabbitMqConnectorResponse response = service.disable();
        log.info("event=admin_mutation action=rabbitmq_connector_disable entity=connector entityId={} actor={} enabled={}",
            response.id(), LogSafe.text(actor()), response.enabled());
        return withVersion(response);
    }

    private ResponseEntity<RabbitMqConnectorResponse> withVersion(RabbitMqConnectorResponse response) {
        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, "\"" + response.configurationVersion() + "\"")
            .body(response);
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
            ? authentication.getName() : "system";
    }
}
