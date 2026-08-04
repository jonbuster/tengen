package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.RabbitMqConnectionTestResponse;
import com.tengencorp.tengen.dto.RabbitMqConnectorRequest;
import com.tengencorp.tengen.dto.RabbitMqConnectorResponse;
import com.tengencorp.tengen.service.RabbitMqConnectorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
        return withVersion(service.save(request, ifMatch));
    }

    @PostMapping("/test")
    public RabbitMqConnectionTestResponse test() {
        return service.test();
    }

    @PostMapping("/enable")
    public ResponseEntity<RabbitMqConnectorResponse> enable() {
        return withVersion(service.enable());
    }

    @PostMapping("/disable")
    public ResponseEntity<RabbitMqConnectorResponse> disable() {
        return withVersion(service.disable());
    }

    private ResponseEntity<RabbitMqConnectorResponse> withVersion(RabbitMqConnectorResponse response) {
        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, "\"" + response.configurationVersion() + "\"")
            .body(response);
    }
}
