"use client";

import {
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  Grid,
  MenuItem,
  TextField,
  Typography,
} from "@mui/material";
import { useState } from "react";
import { Rule, RuleAction, RuleRequest, RuleType } from "@/lib/types";

const RULE_TYPES: RuleType[] = ["CONDITION", "AGGREGATE"];
const ACTIONS: RuleAction[] = ["LOG", "WEBHOOK"];
const AGG_TYPES = ["COUNT", "SUM", "AVG", "MIN", "MAX"];

interface RuleFormProps {
  initial?: Rule;
  onSubmit: (request: RuleRequest) => Promise<void>;
  submitting?: boolean;
}

function toRequest(initial: Rule | undefined, values: Record<string, string>): RuleRequest {
  const ruleType = values.ruleType as RuleType;
  const action = values.action as RuleAction;
  return {
    name: values.name,
    ruleType,
    action,
    callbackUrl: action === "WEBHOOK" ? values.callbackUrl || null : null,
    eventType: values.eventType,
    source: values.source,
    conditionScript: values.conditionScript,
    windowSeconds: values.windowSeconds ? Number(values.windowSeconds) : null,
    aggType: ruleType === "AGGREGATE" ? (values.aggType as RuleRequest["aggType"]) : null,
    aggField: ruleType === "AGGREGATE" ? values.aggField || null : null,
    threshold: values.threshold ? Number(values.threshold) : 0,
    active: values.active === "true",
  };
}

export function RuleForm({ initial, onSubmit, submitting }: RuleFormProps) {
  const [values, setValues] = useState<Record<string, string>>(() => ({
    name: initial?.name ?? "",
    ruleType: initial?.ruleType ?? "CONDITION",
    action: initial?.action ?? "LOG",
    callbackUrl: initial?.callbackUrl ?? "",
    eventType: initial?.eventType ?? "",
    source: initial?.source ?? "",
    conditionScript: initial?.conditionScript ?? "",
    windowSeconds: initial?.windowSeconds?.toString() ?? "",
    aggType: initial?.aggType ?? "",
    aggField: initial?.aggField ?? "",
    threshold: initial?.threshold?.toString() ?? "0",
    active: String(initial?.active ?? true),
  }));
  const [error, setError] = useState<string | null>(null);

  const set = (key: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setValues((v) => ({ ...v, [key]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await onSubmit(toRequest(initial, values));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save rule");
    }
  };

  const isAggregate = values.ruleType === "AGGREGATE";
  const isWebhook = values.action === "WEBHOOK";

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
      <Typography variant="subtitle1">Basics</Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} sm={6}>
          <TextField label="Name" value={values.name} onChange={set("name")} fullWidth required />
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField select label="Rule Type" value={values.ruleType} onChange={set("ruleType")} fullWidth>
            {RULE_TYPES.map((t) => (
              <MenuItem key={t} value={t}>
                {t}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField select label="Action" value={values.action} onChange={set("action")} fullWidth>
            {ACTIONS.map((a) => (
              <MenuItem key={a} value={a}>
                {a}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
        {isWebhook && (
          <Grid item xs={12} sm={6}>
            <TextField label="Callback URL" value={values.callbackUrl} onChange={set("callbackUrl")} fullWidth />
          </Grid>
        )}
        <Grid item xs={12} sm={6}>
          <TextField label="Event Type" value={values.eventType} onChange={set("eventType")} fullWidth required />
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField label="Source" value={values.source} onChange={set("source")} fullWidth required />
        </Grid>
      </Grid>

      {isAggregate && (
        <>
          <Typography variant="subtitle1">Aggregate Section</Typography>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={4}>
              <TextField select label="Aggregate Type" value={values.aggType} onChange={set("aggType")} fullWidth>
                {AGG_TYPES.map((a) => (
                  <MenuItem key={a} value={a}>
                    {a}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Aggregate Field (e.g. data.amount)" value={values.aggField} onChange={set("aggField")} fullWidth />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Window (seconds)" type="number" value={values.windowSeconds} onChange={set("windowSeconds")} fullWidth />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Threshold" type="number" value={values.threshold} onChange={set("threshold")} fullWidth />
            </Grid>
          </Grid>
        </>
      )}

      <Typography variant="subtitle1">Condition</Typography>
      <TextField
        label="Condition Script (Aviator)"
        value={values.conditionScript}
        onChange={set("conditionScript")}
        fullWidth
        multiline
        minRows={4}
        required
        helperText="e.g. data.amount >= 1000 && data.country == 'PH'"
      />

      <FormControlLabel
        control={
          <Checkbox
            checked={values.active === "true"}
            onChange={(e) => setValues((v) => ({ ...v, active: String(e.target.checked) }))}
          />
        }
        label="Active"
      />

      {error && (
        <Typography color="error" variant="body2">
          {error}
        </Typography>
      )}

      <Box>
        <Button type="submit" variant="contained" disabled={submitting}>
          {submitting ? "Saving..." : "Save Rule"}
        </Button>
      </Box>
    </Box>
  );
}
