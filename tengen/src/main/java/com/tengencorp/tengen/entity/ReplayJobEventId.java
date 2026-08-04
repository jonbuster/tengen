package com.tengencorp.tengen.entity;

import java.io.Serializable;
import java.util.Objects;

public class ReplayJobEventId implements Serializable {

    private Long jobId;
    private Long position;

    public ReplayJobEventId() {
    }

    public ReplayJobEventId(Long jobId, Long position) {
        this.jobId = jobId;
        this.position = position;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReplayJobEventId that)) return false;
        return Objects.equals(jobId, that.jobId) && Objects.equals(position, that.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, position);
    }
}
