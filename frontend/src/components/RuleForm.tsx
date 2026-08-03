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
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import KeyboardArrowDownIcon from "@mui/icons-material/KeyboardArrowDown";
import KeyboardArrowUpIcon from "@mui/icons-material/KeyboardArrowUp";
import { useState } from "react";
import { ConditionBuilder } from "@/components/ConditionBuilder";
import {
  ConditionGroup,
  createGroup,
  generateAviator,
  parseAviator,
} from "@/lib/conditionBuilder";
import { Rule, RuleAction, RuleRequest, RuleType, SequenceStep, TriggerMode } from "@/lib/types";

const RULE_TYPES: RuleType[] = ["CONDITION", "AGGREGATE", "SEQUENCE"];
const ACTIONS: RuleAction[] = ["LOG", "WEBHOOK"];
const AGG_TYPES = ["COUNT", "SUM", "AVG", "MIN", "MAX"];
const TRIGGER_MODES: { value: TriggerMode; label: string }[] = [
  { value: "EVERY_MATCH", label: "Every match" },
  { value: "EDGE", label: "On rising edge" },
  { value: "ONCE_PER_WINDOW", label: "Once per event-time window" },
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

interface ConditionEditorProps {
  label: string;
  condition: ConditionGroup;
  mode: "builder" | "raw";
  script: string;
  onChange: (condition: ConditionGroup, mode: "builder" | "raw", script: string) => void;
  onInvalid?: (message: string) => void;
}

function ConditionEditor({ label, condition, mode, script, onChange, onInvalid }: ConditionEditorProps) {
  return (
    <Box>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        {label}
      </Typography>
      <ToggleButtonGroup
        size="small"
        exclusive
        value={mode}
        onChange={(_, nextMode) => {
          if (!nextMode || nextMode === mode) return;
          if (nextMode === "raw") {
            onChange(condition, "raw", generateAviator(condition));
            return;
          }
          const parsed = parseAviator(script);
          if (parsed) {
            onChange(parsed, "builder", script);
          } else {
            onInvalid?.("The raw expression uses syntax that the visual builder cannot represent.");
          }
        }}
        sx={{ mb: 1 }}
      >
        <ToggleButton value="builder">Visual Builder</ToggleButton>
        <ToggleButton value="raw">Raw Aviator</ToggleButton>
      </ToggleButtonGroup>
      {mode === "builder" ? (
        <ConditionBuilder
          root={condition}
          onChange={(next) => onChange(next, "builder", generateAviator(next))}
        />
      ) : (
        <TextField
          label={`${label} (Aviator)`}
          value={script}
          onChange={(event) => onChange(condition, "raw", event.target.value)}
          fullWidth
          multiline
          minRows={3}
          required
          helperText="e.g. data.amount >= 1000 && data.country == 'PH'"
        />
      )}
    </Box>
  );
}

interface DraftSequenceStep extends SequenceStep {
  condition: ConditionGroup;
  conditionMode: "builder" | "raw";
}

function draftStep(step?: SequenceStep, position = 1): DraftSequenceStep {
  const script = step?.conditionScript ?? "";
  const parsed = script ? parseAviator(script) : null;
  return {
    position,
    eventType: step?.eventType ?? "",
    source: step?.source ?? "",
    conditionScript: script,
    condition: parsed ?? createGroup("AND"),
    conditionMode: parsed || !script ? "builder" : "raw",
  };
}

function toRequest(
  values: Record<string, string>,
  condition: ConditionGroup,
  conditionMode: "builder" | "raw",
  steps: DraftSequenceStep[],
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
    eventType: ruleType === "SEQUENCE" ? null : values.eventType,
    source: ruleType === "SEQUENCE" ? null : values.source,
    conditionScript:
      ruleType === "SEQUENCE"
        ? ""
        : conditionMode === "builder"
          ? generateAviator(condition)
          : values.conditionScript.trim(),
    windowSeconds: values.windowSeconds ? Number(values.windowSeconds) : null,
    aggType: ruleType === "AGGREGATE" ? (values.aggType as RuleRequest["aggType"]) : null,
    aggField: ruleType === "AGGREGATE" ? values.aggField || null : null,
    groupBy: ruleType === "AGGREGATE" || ruleType === "SEQUENCE" ? values.groupBy || null : null,
    threshold: values.threshold ? Number(values.threshold) : 0,
    active: values.active === "true",
    sequenceSteps: ruleType === "SEQUENCE"
      ? steps.map((step) => ({
        position: step.position,
        eventType: step.eventType,
        source: step.source,
        conditionScript: step.conditionMode === "builder"
          ? generateAviator(step.condition)
          : step.conditionScript.trim(),
      }))
      : [],
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
  const [sequenceSteps, setSequenceSteps] = useState<DraftSequenceStep[]>(() => {
    const configured = initial?.sequenceSteps ?? [];
    return (configured.length >= 2 ? configured : [undefined, undefined]).map((step, index) =>
      draftStep(step, index + 1),
    );
  });
  const [error, setError] = useState<string | null>(null);

  const set = (key: string) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setValues((current) => ({ ...current, [key]: event.target.value }));

  const updateStep = (index: number, patch: Partial<DraftSequenceStep>) => {
    setSequenceSteps((current) =>
      current.map((step, stepIndex) => (stepIndex === index ? { ...step, ...patch } : step)),
    );
  };

  const addStep = () => {
    if (sequenceSteps.length >= 5) return;
    setSequenceSteps((current) => [...current, draftStep(undefined, current.length + 1)]);
  };

  const removeStep = (index: number) => {
    if (sequenceSteps.length <= 2) return;
    setSequenceSteps((current) =>
      current.filter((_, stepIndex) => stepIndex !== index).map((step, stepIndex) => ({
        ...step,
        position: stepIndex + 1,
      })),
    );
  };

  const moveStep = (index: number, direction: -1 | 1) => {
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= sequenceSteps.length) return;
    setSequenceSteps((current) => {
      const next = [...current];
      [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
      return next.map((step, stepIndex) => ({ ...step, position: stepIndex + 1 }));
    });
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    const isSequence = values.ruleType === "SEQUENCE";
    if (!isSequence && conditionMode === "builder" && !generateAviator(condition).trim()) {
      setError("Add at least one condition in the visual builder.");
      return;
    }
    if (isSequence) {
      const missing = sequenceSteps.some((step) => {
        const script = step.conditionMode === "builder"
          ? generateAviator(step.condition)
          : step.conditionScript.trim();
        return !step.eventType.trim() || !step.source.trim() || !script.trim();
      });
      if (missing) {
        setError("Complete the event type, source, and condition for every sequence step.");
        return;
      }
    }
    try {
      await onSubmit(toRequest(values, condition, conditionMode, sequenceSteps));
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "Could not save rule");
    }
  };

  const isAggregate = values.ruleType === "AGGREGATE";
  const isSequence = values.ruleType === "SEQUENCE";
  const isWebhook = values.action === "WEBHOOK";
  const triggerModes = isSequence
    ? TRIGGER_MODES.filter((mode) => mode.value !== "ONCE_PER_WINDOW")
    : TRIGGER_MODES;

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: "flex", flexDirection: "column", gap: 2.5 }}>
      <Typography variant="subtitle1">Basics</Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} sm={6}>
          <TextField label="Name" value={values.name} onChange={set("name")} fullWidth required />
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField select label="Rule Type" value={values.ruleType} onChange={set("ruleType")} fullWidth>
            {RULE_TYPES.map((type) => (
              <MenuItem key={type} value={type}>{type}</MenuItem>
            ))}
          </TextField>
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField select label="Action" value={values.action} onChange={set("action")} fullWidth>
            {ACTIONS.map((action) => (
              <MenuItem key={action} value={action}>{action}</MenuItem>
            ))}
          </TextField>
        </Grid>
        {isWebhook && (
          <Grid item xs={12} sm={6}>
            <TextField label="Callback URL" value={values.callbackUrl} onChange={set("callbackUrl")} fullWidth />
          </Grid>
        )}
        {!isSequence && (
          <>
            <Grid item xs={12} sm={6}>
              <TextField label="Event Type" value={values.eventType} onChange={set("eventType")} fullWidth required />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField label="Source" value={values.source} onChange={set("source")} fullWidth required />
            </Grid>
          </>
        )}
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
                  helperText={
                    values.triggerMode === "ONCE_PER_WINDOW"
                      ? "Aggregate webhook rules send once in each fixed event-time window; failed delivery can retry."
                      : "On rising edge sends a webhook only when the rule changes from not matching to matching."
                  }
                >
                  {triggerModes.map((mode) => (
                    <MenuItem key={mode.value} value={mode.value}>{mode.label}</MenuItem>
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
                {AGG_TYPES.map((aggregate) => <MenuItem key={aggregate} value={aggregate}>{aggregate}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Aggregate field"
                value={values.aggField}
                onChange={set("aggField")}
                fullWidth
                InputProps={{ endAdornment: <InputAdornment position="end"><FieldInfo title="Use amount or data.amount." /></InputAdornment> }}
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
                InputProps={{ endAdornment: <InputAdornment position="end"><FieldInfo title="Optional. Use data.userId or userId. Leave blank for a global aggregate." /></InputAdornment> }}
              />
            </Grid>
          </Grid>
        </>
      )}

      {isSequence && (
        <>
          <Typography variant="subtitle1">Sequence Steps</Typography>
          <Typography variant="body2" color="text.secondary">
            Steps are evaluated in order. A single event advances at most one sequence instance, and the full sequence must finish inside the window.
          </Typography>
          <Stack spacing={2}>
            {sequenceSteps.map((step, index) => (
              <Box key={`sequence-step-${step.position}`} sx={{ border: 1, borderColor: "divider", borderRadius: 1, p: 2 }}>
                <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
                  <Typography variant="subtitle2">Step {step.position}</Typography>
                  <Stack direction="row">
                    <IconButton aria-label={`Move step ${step.position} up`} onClick={() => moveStep(index, -1)} disabled={index === 0} size="small">
                      <KeyboardArrowUpIcon />
                    </IconButton>
                    <IconButton aria-label={`Move step ${step.position} down`} onClick={() => moveStep(index, 1)} disabled={index === sequenceSteps.length - 1} size="small">
                      <KeyboardArrowDownIcon />
                    </IconButton>
                    <IconButton aria-label={`Remove step ${step.position}`} onClick={() => removeStep(index)} disabled={sequenceSteps.length <= 2} size="small">
                      <DeleteOutlineIcon />
                    </IconButton>
                  </Stack>
                </Stack>
                <Grid container spacing={2}>
                  <Grid item xs={12} sm={6}>
                    <TextField label="Event type" value={step.eventType} onChange={(event) => updateStep(index, { eventType: event.target.value })} fullWidth required />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField label="Source" value={step.source} onChange={(event) => updateStep(index, { source: event.target.value })} fullWidth required />
                  </Grid>
                  <Grid item xs={12}>
                    <ConditionEditor
                      label={`Step ${step.position} condition`}
                      condition={step.condition}
                      mode={step.conditionMode}
                      script={step.conditionScript}
                      onChange={(nextCondition, nextMode, script) => updateStep(index, {
                        condition: nextCondition,
                        conditionMode: nextMode,
                        conditionScript: script,
                      })}
                      onInvalid={setError}
                    />
                  </Grid>
                </Grid>
              </Box>
            ))}
          </Stack>
          <Button startIcon={<AddIcon />} onClick={addStep} disabled={sequenceSteps.length >= 5} sx={{ alignSelf: "flex-start" }}>
            Add step
          </Button>
          <Grid container spacing={2}>
            <Grid item xs={12} sm={4}>
              <TextField label="Sequence window (seconds)" type="number" value={values.windowSeconds} onChange={set("windowSeconds")} fullWidth required />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                label="Group by field"
                value={values.groupBy}
                onChange={set("groupBy")}
                fullWidth
                InputProps={{ endAdornment: <InputAdornment position="end"><FieldInfo title="Optional shared correlation path, such as data.userId. Leave blank for a global sequence." /></InputAdornment> }}
              />
            </Grid>
          </Grid>
        </>
      )}

      {!isSequence && (
        <ConditionEditor
          label="Condition"
          condition={condition}
          mode={conditionMode}
          script={values.conditionScript}
          onChange={(nextCondition, nextMode, script) => {
            setCondition(nextCondition);
            setConditionMode(nextMode);
            setValues((current) => ({ ...current, conditionScript: script }));
          }}
          onInvalid={setError}
        />
      )}

      <FormControlLabel
        control={<Checkbox checked={values.active === "true"} onChange={(event) => setValues((current) => ({ ...current, active: String(event.target.checked) }))} />}
        label="Active"
      />

      {error && <Typography color="error" variant="body2">{error}</Typography>}

      <Box>
        <Button type="submit" variant="contained" disabled={submitting}>
          {submitting ? "Saving..." : "Save Rule"}
        </Button>
      </Box>
    </Box>
  );
}
