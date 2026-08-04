package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.EventTimeStatus;

import java.time.Instant;

/** Classification and prior watermark captured for one ingested event. */
public record EventTimeDecision(EventTimeStatus status, Instant watermarkAtDecision) {
}
