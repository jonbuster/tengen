"use client";

import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  Grid,
  InputAdornment,
  MenuItem,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from "@mui/material";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { useState } from "react";
import { ConditionBuilder } from "@/components/ConditionBuilder";
import {
  ConditionGroup,
  createGroup,
  generateAviator,
  parseAviator,
} from "@/lib/conditionBuilder";
import { Rule, RuleAction, RuleRequest, RuleType, TriggerMode } from "@/lib/types";

const RULE_TYPES: RuleType[] = ["CONDITION", "AGGREGATE"];
const ACTIONS: RuleAction[] = ["LOG", "WEBHOOK"];
const AGG_TYPES = ["COUNT", "SUM", "AVG", "MIN", "MAX"];
const TRIGGER_MODES: { value: TriggerMode; label: string }[] = [
  { value: "EVERY_MATCH", label: "Every match" },
  { value: "EDGE", label: "On rising edge" },
];

function FieldInfo({ title }: { title: string }) {
  return (
    <Tooltip title={title} arrow>
      <InfoOutlinedIcon fontSize="small" sx={{ color: "text.secondary" }} />
    </Tooltip>
  );
}

interface RuleFormProps {
  initial?: Rule;
  onSubmit: (request: RuleRequest) => Promise<void>;
  submitting?: boolean;
}

function toRequest(
  initial: Rule | undefined,
  values: Record<string, string>,
  condition: ConditionGroup,
  conditionMode: "builder" | "raw",
): RuleRequest {
  const ruleType = values.ruleType as RuleType;
  const action = values.action as RuleAction;
  return {
    name: values.name,
    ruleType,
    action,
    callbackUrl: action === "WEBHOOK" ? values.callbackUrl || null : null,
    cooldownSeconds:
      action === "WEBHOOK" && values.cooldownSeconds !== ""
        ? Number(values.cooldownSeconds)
        : null,
    triggerMode: action === "WEBHOOK" ? (values.triggerMode as TriggerMode) : "EVERY_MATCH",
    eventType: values.eventType,
    source: values.source,
    conditionScript:
      conditionMode === "builder" ? generateAviator(condition) : values.conditionScript.trim(),
    windowSeconds: values.windowSeconds ? Number(values.windowSeconds) : null,
    aggType: ruleType === "AGGREGATE" ? (values.aggType as RuleRequest["aggType"]) : null,
    aggField: ruleType === "AGGREGATE" ? values.aggField || null : null,
    groupBy: ruleType === "AGGREGATE" ? values.groupBy || null : null,
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
    cooldownSeconds: initial?.cooldownSeconds?.toString() ?? "",
    triggerMode: initial?.triggerMode ?? "EVERY_MATCH",
    eventType: initial?.eventType ?? "",
    source: initial?.source ?? "",
    conditionScript: initial?.conditionScript ?? "",
    windowSeconds: initial?.windowSeconds?.toString() ?? "",
    aggType: initial?.aggType ?? "",
    aggField: initial?.aggField ?? "",
    groupBy: initial?.groupBy ?? "",
    threshold: initial?.threshold?.toString() ?? "0",
    active: String(initial?.active ?? true),
  }));
  const [condition, setCondition] = useState<ConditionGroup>(() => {
    if (initial?.conditionScript) {
      return parseAviator(initial.conditionScript) ?? createGroup("AND");
    }
    return createGroup("AND");
  });
  const [conditionMode, setConditionMode] = useState<"builder" | "raw">(() => {
    if (!initial?.conditionScript) return "builder";
    return parseAviator(initial.conditionScript) ? "builder" : "raw";
  });
  const [error, setError] = useState<string | null>(null);

  const set = (key: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setValues((v) => ({ ...v, [key]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (conditionMode === "builder" && !generateAviator(condition).trim()) {
      setError("Add at least one condition in the visual builder.");
      return;
    }
    try {
      await onSubmit(toRequest(initial, values, condition, conditionMode));
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
          <>
            <Grid item xs={12} sm={6}>
              <TextField label="Callback URL" value={values.callbackUrl} onChange={set("callbackUrl")} fullWidth />
            </Grid>
          </>
        )}
        <Grid item xs={12} sm={6}>
          <TextField label="Event Type" value={values.eventType} onChange={set("eventType")} fullWidth required />
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField label="Source" value={values.source} onChange={set("source")} fullWidth required />
        </Grid>
      </Grid>

      {isWebhook && (
        <Accordion disableGutters>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography>Advanced</Typography>
          </AccordionSummary>
          <AccordionDetails>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <TextField
                  select
                  label="Trigger mode"
                  value={values.triggerMode || "EVERY_MATCH"}
                  onChange={set("triggerMode")}
                  fullWidth
                  helperText="On rising edge sends a webhook only when the rule changes from not matching to matching."
                >
                  {TRIGGER_MODES.map((mode) => (
                    <MenuItem key={mode.value} value={mode.value}>
                      {mode.label}
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  label="Cooldown (seconds)"
                  type="number"
                  inputProps={{ min: 0 }}
                  value={values.cooldownSeconds}
                  onChange={set("cooldownSeconds")}
                  fullWidth
                  InputProps={{
                    endAdornment: (
                      <InputAdornment position="end">
                        <FieldInfo title="Controls repeated webhook delivery, not rule detection. Leave blank or use 0 to disable." />
                      </InputAdornment>
                    ),
                  }}
                />
              </Grid>
            </Grid>
          </AccordionDetails>
        </Accordion>
      )}

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
              <TextField
                label="Aggregate field"
                value={values.aggField}
                onChange={set("aggField")}
                fullWidth
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      <FieldInfo title="Use amount or data.amount." />
                    </InputAdornment>
                  ),
                }}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Window (seconds)" type="number" value={values.windowSeconds} onChange={set("windowSeconds")} fullWidth />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Threshold" type="number" value={values.threshold} onChange={set("threshold")} fullWidth />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Group by field"
                value={values.groupBy}
                onChange={set("groupBy")}
                fullWidth
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      <FieldInfo title="Optional. Use data.userId or userId. Leave blank for a global aggregate." />
                    </InputAdornment>
                  ),
                }}
              />
            </Grid>
          </Grid>
        </>
      )}

      <Typography variant="subtitle1">Condition</Typography>
      <ToggleButtonGroup
        size="small"
        exclusive
        value={conditionMode}
        onChange={(_, mode) => {
          if (mode) setConditionMode(mode);
        }}
        sx={{ mb: 1 }}
      >
        <ToggleButton value="builder">Visual Builder</ToggleButton>
        <ToggleButton value="raw">Raw Aviator</ToggleButton>
      </ToggleButtonGroup>
      {conditionMode === "builder" ? (
        <ConditionBuilder root={condition} onChange={setCondition} />
      ) : (
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
      )}

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
