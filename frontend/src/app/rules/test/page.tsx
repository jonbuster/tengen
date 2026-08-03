"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  FormControl,
  FormControlLabel,
  MenuItem,
  Radio,
  RadioGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { Rule, TestResult } from "@/lib/types";

const DEFAULT_EVENT_JSON = `{
  "type": "transaction",
  "source": "payment-api",
  "timestamp": "2026-07-31T15:30:00Z",
  "data": {
    "amount": 1500,
    "country": "PH"
  }
}`;

const DEFAULT_SEQUENCE_EVENT_JSONS = [
  `{
  "type": "login.failed",
  "source": "auth",
  "timestamp": "2026-07-31T15:25:00Z",
  "data": { "userId": "user-123" }
}`,
  `{
  "type": "password.reset",
  "source": "auth",
  "timestamp": "2026-07-31T15:28:00Z",
  "data": { "userId": "user-123" }
}`,
];

export default function TestPage() {
  const [mode, setMode] = useState<"single" | "all">("single");
  const [ruleId, setRuleId] = useState<number | "">("");
  const [eventJson, setEventJson] = useState(DEFAULT_EVENT_JSON);
  const [sequenceEventJsons, setSequenceEventJsons] = useState(DEFAULT_SEQUENCE_EVENT_JSONS);

  const { data: rules = [] } = useQuery<Rule[]>({
    queryKey: ["rules"],
    queryFn: async () => (await api.get("/rules")).data,
  });

  const selectedRule = rules.find((rule) => rule.id === ruleId);
  const isSequenceTest = mode === "single" && selectedRule?.ruleType === "SEQUENCE";
  const sequenceSteps = selectedRule?.sequenceSteps ?? [];

  const mutation = useMutation<TestResult, Error, {
    mode: "single" | "all";
    ruleId?: number;
    eventJson: string;
    sequenceEventJsons?: string[];
  }>({
    mutationFn: async (payload) => (await api.post("/rules/test", payload)).data,
  });

  const run = () => {
    const sequencePayload = isSequenceTest
      ? sequenceSteps.map((_, index) => sequenceEventJsons[index] ?? "")
      : undefined;
    mutation.mutate({
      mode,
      ruleId: mode === "single" ? (ruleId === "" ? undefined : ruleId) : undefined,
      eventJson: isSequenceTest && sequencePayload?.length
        ? sequencePayload[sequencePayload.length - 1]
        : eventJson,
      sequenceEventJsons: sequencePayload,
    });
  };

  const result = mutation.data;

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Typography variant="h5" sx={{ mb: 3 }}>Run Test</Typography>

      <Stack spacing={2} sx={{ mb: 3 }}>
        <FormControl component="fieldset">
          <RadioGroup row value={mode} onChange={(event) => setMode(event.target.value as "single" | "all")}>
            <FormControlLabel value="single" control={<Radio />} label="Single rule" />
            <FormControlLabel value="all" control={<Radio />} label="All active rules" />
          </RadioGroup>
        </FormControl>

        {mode === "single" && (
          <TextField
            select
            label="Rule"
            value={ruleId}
            onChange={(event) => setRuleId(event.target.value === "" ? "" : Number(event.target.value))}
            fullWidth
          >
            {rules.map((rule) => (
              <MenuItem key={rule.id} value={rule.id}>{rule.name} ({rule.ruleType})</MenuItem>
            ))}
          </TextField>
        )}

        {isSequenceTest ? (
          <Stack spacing={2}>
            <Typography variant="body2" color="text.secondary">
              Supply one event JSON document for each configured step. This simulation is side-effect free and checks ordering, correlation, and the total window.
            </Typography>
            {sequenceSteps.map((step, index) => (
              <TextField
                key={step.position}
                label={`Step ${step.position} event JSON (${step.eventType || "event"})`}
                value={sequenceEventJsons[index] ?? ""}
                onChange={(event) => setSequenceEventJsons((current) => {
                  const next = [...current];
                  next[index] = event.target.value;
                  return next;
                })}
                fullWidth
                multiline
                minRows={6}
                sx={{ fontFamily: "monospace" }}
              />
            ))}
          </Stack>
        ) : (
          <TextField
            label="Event JSON"
            value={eventJson}
            onChange={(event) => setEventJson(event.target.value)}
            fullWidth
            multiline
            minRows={8}
            sx={{ fontFamily: "monospace" }}
          />
        )}

        <Box>
          <Button variant="contained" onClick={run} disabled={mutation.isPending}>
            {mutation.isPending ? "Running..." : "Run Test"}
          </Button>
        </Box>
      </Stack>

      {mutation.isError && <Alert severity="error">{errorMessage(mutation.error)}</Alert>}

      {result && (
        <Box>
          {((mode === "single" && result.rule?.ruleType === "AGGREGATE")
            || (mode === "all" && result.results?.some((rule) => rule.ruleType === "AGGREGATE"))) && (
            <Alert severity="info" sx={{ mb: 2 }}>
              Aggregate values include the sample event and matching events already in the window. The sample event is not saved.
            </Alert>
          )}
          {mode === "single" && result.rule?.ruleType === "SEQUENCE" && result.sequenceTest && (
            <Alert severity={result.sequenceTest.matched ? "success" : "info"} sx={{ mb: 2 }}>
              Sequence {result.sequenceTest.matched ? "matched" : "did not match"}. Conditions: {result.sequenceTest.steps.filter((step) => step.conditionMatched).length}/{result.sequenceTest.steps.length}; correlation: {result.sequenceTest.correlationMatched ? "ok" : "failed"}; ordering: {result.sequenceTest.orderingValid ? "ok" : "failed"}; window: {result.sequenceTest.withinWindow ? "ok" : "expired"}.
            </Alert>
          )}
          {mode === "single" ? (
            <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
              <Chip label={result.matched ? "Matched" : "No match"} color={result.matched ? "success" : "default"} />
              <Chip label={`Condition: ${result.conditionMatched ? "passed" : "failed"}`} color={result.conditionMatched ? "success" : "default"} variant="outlined" />
              {result.aggregateValue != null && <Chip label={`Aggregate: ${result.aggregateValue}`} variant="outlined" />}
              {result.groupKey != null && <Chip label={`Group: ${result.groupKey}`} variant="outlined" />}
            </Stack>
          ) : (
            <Alert severity={result.anyMatched ? "success" : "info"} sx={{ mb: 2 }}>
              {result.anyMatched ? "At least one rule matched." : "No rules matched."}
            </Alert>
          )}

          {mode === "all" && result.results && result.results.length > 0 && (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Rule</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Action</TableCell>
                    <TableCell>Matched</TableCell>
                    <TableCell>Condition</TableCell>
                    <TableCell>Aggregate</TableCell>
                    <TableCell>Group</TableCell>
                    <TableCell>Threshold</TableCell>
                    <TableCell>Window (s)</TableCell>
                    <TableCell>Sequence</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {result.results.map((rule) => (
                    <TableRow key={rule.ruleId}>
                      <TableCell>{rule.name}</TableCell>
                      <TableCell>{rule.ruleType}</TableCell>
                      <TableCell>{rule.action}</TableCell>
                      <TableCell><Chip label={rule.matched ? "Matched" : "No"} color={rule.matched ? "success" : "default"} size="small" /></TableCell>
                      <TableCell>{rule.conditionMatched ? "Passed" : "Failed"}</TableCell>
                      <TableCell>{rule.aggregateValue ?? "-"}</TableCell>
                      <TableCell>{rule.groupKey ?? "-"}</TableCell>
                      <TableCell>{rule.threshold ?? "-"}</TableCell>
                      <TableCell>{rule.windowSeconds ?? "-"}</TableCell>
                      <TableCell>{rule.sequence?.steps.map((step) => step.position).join(" → ") ?? "-"}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>
      )}
    </Container>
  );
}
