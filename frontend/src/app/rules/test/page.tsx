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

export default function TestPage() {
  const [mode, setMode] = useState<"single" | "all">("single");
  const [ruleId, setRuleId] = useState<number | "">("");
  const [eventJson, setEventJson] = useState(DEFAULT_EVENT_JSON);

  const { data: rules = [] } = useQuery<Rule[]>({
    queryKey: ["rules"],
    queryFn: async () => (await api.get("/rules")).data,
  });

  const mutation = useMutation<TestResult, Error, { mode: "single" | "all"; ruleId?: number; eventJson: string }>({
    mutationFn: async (payload) => (await api.post("/rules/test", payload)).data,
  });

  const run = () => {
    mutation.mutate({
      mode,
      ruleId: mode === "single" ? (ruleId === "" ? undefined : ruleId) : undefined,
      eventJson,
    });
  };

  const result = mutation.data;

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Typography variant="h5" sx={{ mb: 3 }}>
        Run Test
      </Typography>

      <Stack spacing={2} sx={{ mb: 3 }}>
        <FormControl component="fieldset">
          <RadioGroup row value={mode} onChange={(e) => setMode(e.target.value as "single" | "all")}>
            <FormControlLabel value="single" control={<Radio />} label="Single rule" />
            <FormControlLabel value="all" control={<Radio />} label="All active rules" />
          </RadioGroup>
        </FormControl>

        {mode === "single" && (
          <TextField
            select
            label="Rule"
            value={ruleId}
            onChange={(e) => setRuleId(e.target.value === "" ? "" : Number(e.target.value))}
            fullWidth
          >
            {rules.map((r) => (
              <MenuItem key={r.id} value={r.id}>
                {r.name} ({r.ruleType})
              </MenuItem>
            ))}
          </TextField>
        )}

        <TextField
          label="Event JSON"
          value={eventJson}
          onChange={(e) => setEventJson(e.target.value)}
          fullWidth
          multiline
          minRows={8}
          sx={{ fontFamily: "monospace" }}
        />

        <Box>
          <Button variant="contained" onClick={run} disabled={mutation.isPending}>
            {mutation.isPending ? "Running..." : "Run Test"}
          </Button>
        </Box>
      </Stack>

      {mutation.isError && <Alert severity="error">{errorMessage(mutation.error)}</Alert>}

      {result && (
        <Box>
          {mode === "single" ? (
            <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
              <Chip
                label={result.matched ? "Matched" : "No match"}
                color={result.matched ? "success" : "default"}
              />
              <Chip
                label={`Condition: ${result.conditionMatched ? "passed" : "failed"}`}
                color={result.conditionMatched ? "success" : "default"}
                variant="outlined"
              />
              {result.aggregateValue != null && (
                <Chip label={`Aggregate: ${result.aggregateValue}`} variant="outlined" />
              )}
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
                    <TableCell>Threshold</TableCell>
                    <TableCell>Window (s)</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {result.results.map((r) => (
                    <TableRow key={r.ruleId}>
                      <TableCell>{r.name}</TableCell>
                      <TableCell>{r.ruleType}</TableCell>
                      <TableCell>{r.action}</TableCell>
                      <TableCell>
                        <Chip
                          label={r.matched ? "Matched" : "No"}
                          color={r.matched ? "success" : "default"}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>{r.conditionMatched ? "Passed" : "Failed"}</TableCell>
                      <TableCell>{r.aggregateValue ?? "-"}</TableCell>
                      <TableCell>{r.threshold ?? "-"}</TableCell>
                      <TableCell>{r.windowSeconds ?? "-"}</TableCell>
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
