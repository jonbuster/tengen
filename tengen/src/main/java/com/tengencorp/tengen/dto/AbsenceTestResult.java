package com.tengencorp.tengen.dto;

/** Side-effect-free simulation result for an absence rule. */
public record AbsenceTestResult(
        boolean startMatched,
        boolean expectedMatched,
        boolean correlationMatched,
        boolean orderingValid,
        boolean withinWindow,
        String outcome,
        String groupKey,
        AbsenceResult absence) {
}
