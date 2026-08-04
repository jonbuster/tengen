"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Container,
  FormControl,
  InputLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import ReplayIcon from "@mui/icons-material/Replay";
import { type GridColDef, type GridPaginationModel } from "@mui/x-data-grid";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { ClientDataGrid } from "@/components/ClientDataGrid";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import type {
  ApiKey,
  ReplayJob,
  ReplayJobOutcome,
  ReplayJobOutcomePage,
  Rule,
  RuleRevisionDetail,
  RuleRevisionPage,
} from "@/lib/types";

const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED"]);

export default function ReplaysPage() {
  const { preferences } = usePreferences();
  const queryClient = useQueryClient();
  const [ruleId, setRuleId] = useState("");
  const [revision, setRevision] = useState("");
  const [occurredFrom, setOccurredFrom] = useState("");
  const [occurredTo, setOccurredTo] = useState("");
  const [apiKeyId, setApiKeyId] = useState("");
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [outcomeFilter, setOutcomeFilter] = useState("");
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: 0,
    pageSize: 25,
  });

  const rulesQuery = useQuery<Rule[]>({
    queryKey: ["rules", true],
    queryFn: async () => (await api.get("/rules?includeArchived=true")).data,
  });
  const keysQuery = useQuery<ApiKey[]>({
    queryKey: ["api-keys"],
    queryFn: async () => (await api.get("/keys")).data,
  });

  const selectedRuleId = ruleId ? Number(ruleId) : null;
  const revisionsQuery = useQuery<RuleRevisionPage>({
    queryKey: ["rule-revisions", selectedRuleId],
    queryFn: async () => (await api.get(`/rules/${selectedRuleId}/revisions?size=100`)).data,
    enabled: selectedRuleId !== null,
  });

  const selectedRevision = revision ? Number(revision) : null;
  const revisionDetailQuery = useQuery<RuleRevisionDetail>({
    queryKey: ["rule-revision", selectedRuleId, selectedRevision],
    queryFn: async () => (
      await api.get(`/rules/${selectedRuleId}/revisions/${selectedRevision}`)
    ).data,
    enabled: selectedRuleId !== null && selectedRevision !== null,
  });

  useEffect(() => {
    if (revision === "" && revisionsQuery.data?.content.length) {
      setRevision(String(revisionsQuery.data.content[0].revision));
    }
  }, [revision, revisionsQuery.data]);

  const jobQuery = useQuery<ReplayJob>({
    queryKey: ["replay-job", activeJobId],
    queryFn: async () => (await api.get(`/replay-jobs/${activeJobId}`)).data,
    enabled: activeJobId !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && TERMINAL_STATUSES.has(status) ? false : 3_000;
    },
  });
  const activeJob = jobQuery.data;
  const terminal = activeJob ? TERMINAL_STATUSES.has(activeJob.status) : false;

  const outcomesQuery = useQuery<ReplayJobOutcomePage>({
    queryKey: ["replay-outcomes", activeJobId, paginationModel, outcomeFilter],
    queryFn: async () => {
      const params = new URLSearchParams({
        page: String(paginationModel.page),
        size: String(paginationModel.pageSize),
      });
      if (outcomeFilter) params.set("matched", outcomeFilter);
      return (await api.get(`/replay-jobs/${activeJobId}/outcomes?${params.toString()}`)).data;
    },
    enabled: activeJobId !== null,
    placeholderData: keepPreviousData,
    refetchInterval: activeJobId !== null && !terminal ? 3_000 : false,
  });

  const createMutation = useMutation({
    mutationFn: async () => {
      const from = toIso(occurredFrom);
      const to = toIso(occurredTo);
      if (!selectedRuleId || !selectedRevision || !from || !to) {
        throw new Error("Select a rule revision and enter a valid date range.");
      }
      if (new Date(from).getTime() >= new Date(to).getTime()) {
        throw new Error("The start must be before the exclusive end.");
      }
      return api.post("/replay-jobs", {
        ruleId: selectedRuleId,
        ruleRevision: selectedRevision,
        occurredFrom: from,
        occurredTo: to,
        apiKeyId: apiKeyId ? Number(apiKeyId) : null,
      });
    },
    onSuccess: (response) => {
      const job = response.data as ReplayJob;
      setActiveJobId(job.id);
      setPaginationModel((current) => ({ ...current, page: 0 }));
      setError(null);
      queryClient.setQueryData(["replay-job", job.id], job);
    },
    onError: (reason) => setError(errorMessage(reason)),
  });

  const unsupported = revisionDetailQuery.data?.snapshot.ruleType === "SEQUENCE"
    || revisionDetailQuery.data?.snapshot.ruleType === "ABSENCE";
  const noSnapshot = !revisionDetailQuery.isLoading && !revisionDetailQuery.data;
  const columns = useMemo<GridColDef<ReplayJobOutcome>[]>(() => [
    { field: "inputPosition", headerName: "Position", width: 95 },
    { field: "originalEventId", headerName: "Event", width: 85 },
    { field: "type", headerName: "Type", minWidth: 150, flex: 1 },
    { field: "source", headerName: "Source", minWidth: 140, flex: 1 },
    {
      field: "occurredAt",
      headerName: "Occurred",
      width: 190,
      renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
    },
    {
      field: "matched",
      headerName: "Outcome",
      width: 125,
      renderCell: (params) => (
        <Chip
          label={params.value ? "Matched" : "No match"}
          color={params.value ? "success" : "default"}
          size="small"
        />
      ),
    },
    { field: "groupKey", headerName: "Group", minWidth: 140, flex: 1 },
    {
      field: "aggregate",
      headerName: "Aggregate",
      minWidth: 220,
      flex: 1.4,
      sortable: false,
      renderCell: (params) => <AggregateSummary aggregate={params.value} />,
    },
    {
      field: "errorCategory",
      headerName: "Evaluation",
      minWidth: 150,
      renderCell: (params) => params.value
        ? <Chip label={String(params.value)} color="warning" size="small" />
        : "—",
    },
  ], [preferences.timeDisplay]);

  const selectRule = (value: string) => {
    setRuleId(value);
    setRevision("");
    setError(null);
  };

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <ReplayIcon color="primary" />
        <Box>
          <Typography variant="h5">Replay and backfill</Typography>
          <Typography variant="body2" color="text.secondary">
            Evaluate persisted historical events against one immutable rule revision.
          </Typography>
        </Box>
      </Stack>

      <Alert severity="info" sx={{ mb: 3 }}>
        Analysis-only MVP: replay jobs use <strong>NO_ACTIONS</strong> and never send webhooks or change live rule state.
      </Alert>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" sx={{ mb: 2 }}>Create a replay job</Typography>
        <Stack spacing={2}>
          <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
            <FormControl fullWidth>
              <InputLabel id="replay-rule-label">Rule</InputLabel>
              <Select
                labelId="replay-rule-label"
                label="Rule"
                value={ruleId}
                onChange={(event) => selectRule(event.target.value)}
              >
                <MenuItem value=""><em>Select a rule</em></MenuItem>
                {rulesQuery.data?.map((rule) => (
                  <MenuItem key={rule.id} value={rule.id}>
                    {rule.name}{rule.archivedAt ? " (archived)" : ""}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <FormControl fullWidth disabled={!selectedRuleId || revisionsQuery.isLoading}>
              <InputLabel id="replay-revision-label">Immutable revision</InputLabel>
              <Select
                labelId="replay-revision-label"
                label="Immutable revision"
                value={revision}
                onChange={(event) => setRevision(event.target.value)}
              >
                <MenuItem value=""><em>Select a revision</em></MenuItem>
                {revisionsQuery.data?.content.map((item) => (
                  <MenuItem key={item.revision} value={item.revision}>
                    Revision {item.revision} · {item.changeType}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <FormControl fullWidth>
              <InputLabel id="replay-api-key-label">API-key filter</InputLabel>
              <Select
                labelId="replay-api-key-label"
                label="API-key filter"
                value={apiKeyId}
                onChange={(event) => setApiKeyId(event.target.value)}
              >
                <MenuItem value=""><em>All API keys / legacy</em></MenuItem>
                {keysQuery.data?.map((key) => (
                  <MenuItem key={key.id} value={key.id}>
                    {key.name} · {key.prefix}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>

          <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
            <TextField
              fullWidth
              required
              type="datetime-local"
              label="Occurred from (inclusive)"
              value={occurredFrom}
              onChange={(event) => setOccurredFrom(event.target.value)}
              InputLabelProps={{ shrink: true }}
              helperText="Displayed in your browser timezone; sent as UTC."
            />
            <TextField
              fullWidth
              required
              type="datetime-local"
              label="Occurred to (exclusive)"
              value={occurredTo}
              onChange={(event) => setOccurredTo(event.target.value)}
              InputLabelProps={{ shrink: true }}
            />
          </Stack>

          {revisionDetailQuery.isLoading && <CircularProgress size={24} />}
          {revisionDetailQuery.error && (
            <Alert severity="error">{errorMessage(revisionDetailQuery.error)}</Alert>
          )}
          {unsupported && (
            <Alert severity="warning">
              {revisionDetailQuery.data?.snapshot.ruleType} revisions are not supported by this MVP. Choose a CONDITION or AGGREGATE revision.
            </Alert>
          )}
          {revisionDetailQuery.data && !unsupported && (
            <Typography variant="body2" color="text.secondary">
              Selected {revisionDetailQuery.data.snapshot.ruleType} revision {selectedRevision}. Historical activation status is ignored.
            </Typography>
          )}
          {error && <Alert severity="error">{error}</Alert>}
          <Box>
            <Button
              variant="contained"
              onClick={() => createMutation.mutate()}
              disabled={createMutation.isPending || unsupported || noSnapshot || !selectedRuleId || !selectedRevision}
            >
              {createMutation.isPending ? "Creating…" : "Start replay job"}
            </Button>
          </Box>
        </Stack>
      </Paper>

      {activeJobId !== null && (
        <Paper sx={{ p: 3 }}>
          {jobQuery.isLoading && <CircularProgress />}
          {jobQuery.error && <Alert severity="error">{errorMessage(jobQuery.error)}</Alert>}
          {activeJob && (
            <>
              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" gap={1} sx={{ mb: 1 }}>
                <Box>
                  <Typography variant="h6">Job #{activeJob.id}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {activeJob.ruleName} · revision {activeJob.ruleRevision} · {activeJob.ruleType}
                  </Typography>
                </Box>
                <Chip label={activeJob.status} color={statusColor(activeJob.status)} />
              </Stack>
              <LinearProgress
                variant="determinate"
                value={activeJob.progressPercentage}
                sx={{ mb: 1, height: 8, borderRadius: 4 }}
              />
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                {activeJob.processedOutputEvents.toLocaleString()} of {activeJob.totalOutputEvents.toLocaleString()} output events processed ({activeJob.progressPercentage.toFixed(1)}%).
              </Typography>
              {activeJob.failureMessage && (
                <Alert severity="error" sx={{ mb: 2 }}>
                  {activeJob.failureCategory ? `${activeJob.failureCategory}: ` : ""}{activeJob.failureMessage}
                </Alert>
              )}
              <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ mb: 3 }}>
                <SummaryCard label="Matched" value={activeJob.matchedEvents} />
                <SummaryCard label="Evaluation errors" value={activeJob.errorEvents} />
                <SummaryCard label="Materialized" value={activeJob.totalMaterializedEvents} />
                <SummaryCard label="Action mode" value={activeJob.actionMode} />
              </Stack>

              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} gap={1} sx={{ mb: 1 }}>
                <Typography variant="subtitle1">Outcomes</Typography>
                <FormControl size="small" sx={{ minWidth: 150 }}>
                  <InputLabel id="replay-outcome-filter-label">Show</InputLabel>
                  <Select
                    labelId="replay-outcome-filter-label"
                    label="Show"
                    value={outcomeFilter}
                    onChange={(event) => {
                      setOutcomeFilter(event.target.value);
                      setPaginationModel((current) => ({ ...current, page: 0 }));
                    }}
                  >
                    <MenuItem value="">All outcomes</MenuItem>
                    <MenuItem value="true">Matched only</MenuItem>
                    <MenuItem value="false">No match only</MenuItem>
                  </Select>
                </FormControl>
              </Stack>
              <Box sx={{ height: 560, width: "100%" }}>
                <ClientDataGrid
                  rows={outcomesQuery.data?.content ?? []}
                  columns={columns}
                  loading={outcomesQuery.isLoading}
                  getRowId={(row) => row.id}
                  pagination
                  paginationMode="server"
                  paginationModel={paginationModel}
                  onPaginationModelChange={setPaginationModel}
                  rowCount={outcomesQuery.data?.totalElements ?? 0}
                  pageSizeOptions={[10, 25, 50, 100]}
                  disableRowSelectionOnClick
                />
              </Box>
              {outcomesQuery.error && <Alert severity="error" sx={{ mt: 2 }}>{errorMessage(outcomesQuery.error)}</Alert>}
              {!terminal && (
                <Typography variant="caption" color="text.secondary">
                  This selected job refreshes automatically until it reaches a terminal state.
                </Typography>
              )}
            </>
          )}
        </Paper>
      )}
    </Container>
  );
}

function SummaryCard({ label, value }: { label: string; value: number | string }) {
  return (
    <Card variant="outlined" sx={{ minWidth: 135, flex: 1 }}>
      <CardContent sx={{ "&:last-child": { pb: 1.5 } }}>
        <Typography variant="caption" color="text.secondary">{label}</Typography>
        <Typography variant="h6">{typeof value === "number" ? value.toLocaleString() : value}</Typography>
      </CardContent>
    </Card>
  );
}

function AggregateSummary({ aggregate }: { aggregate: unknown }) {
  if (!aggregate || typeof aggregate !== "object") return "—";
  const value = aggregate as { function?: string; value?: number; threshold?: number; windowSeconds?: number };
  if (value.function === undefined || value.value === undefined) return "—";
  return `${value.function} ${value.value} / ${value.threshold ?? "?"} (${value.windowSeconds ?? "?"}s)`;
}

function statusColor(status: ReplayJob["status"]): "default" | "info" | "success" | "error" {
  if (status === "COMPLETED") return "success";
  if (status === "FAILED") return "error";
  if (status === "RUNNING") return "info";
  return "default";
}

function toIso(value: string) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
