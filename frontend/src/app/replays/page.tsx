"use client";

import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Collapse,
  Container,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  InputLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Select,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import CancelIcon from "@mui/icons-material/Cancel";
import FilterAltIcon from "@mui/icons-material/FilterAlt";
import PauseCircleOutlineIcon from "@mui/icons-material/PauseCircleOutline";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import RefreshIcon from "@mui/icons-material/Refresh";
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
  ReplayJobPage,
  ReplayJobStatus,
  ReplayJobTransition,
  Rule,
  RuleRevisionDetail,
  RuleRevisionPage,
} from "@/lib/types";

const ACTIVE_STATUSES = new Set<ReplayJobStatus>([
  "QUEUED",
  "RUNNING",
  "PAUSE_REQUESTED",
  "CANCEL_REQUESTED",
]);
const STATUS_OPTIONS: ReplayJobStatus[] = [
  "QUEUED",
  "RUNNING",
  "PAUSE_REQUESTED",
  "PAUSED",
  "CANCEL_REQUESTED",
  "CANCELLED",
  "COMPLETED",
  "FAILED",
];

type Filters = {
  status: string;
  ruleId: string;
  ruleRevision: string;
  createdBy: string;
  jobId: string;
  from: string;
  to: string;
};

type ControlAction = "pause" | "resume" | "cancel" | "retry";

const INITIAL_FILTERS: Filters = {
  status: "",
  ruleId: "",
  ruleRevision: "",
  createdBy: "",
  jobId: "",
  from: "",
  to: "",
};

export default function ReplaysPage() {
  const { preferences } = usePreferences();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Filters>(INITIAL_FILTERS);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [urlReady, setUrlReady] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [pendingControl, setPendingControl] = useState<{
    action: ControlAction;
    job: ReplayJob;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [ruleId, setRuleId] = useState("");
  const [revision, setRevision] = useState("");
  const [occurredFrom, setOccurredFrom] = useState("");
  const [occurredTo, setOccurredTo] = useState("");
  const [apiKeyId, setApiKeyId] = useState("");
  const [outcomeFilter, setOutcomeFilter] = useState("");
  const [jobPaginationModel, setJobPaginationModel] = useState<GridPaginationModel>({
    page: 0,
    pageSize: 25,
  });
  const [outcomePaginationModel, setOutcomePaginationModel] = useState<GridPaginationModel>({
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

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    setFilters({
      status: params.get("status") ?? "",
      ruleId: params.get("ruleId") ?? "",
      ruleRevision: params.get("ruleRevision") ?? "",
      createdBy: params.get("createdBy") ?? "",
      jobId: params.get("jobId") ?? "",
      from: params.get("from") ?? "",
      to: params.get("to") ?? "",
    });
    setJobPaginationModel({
      page: parsePage(params.get("page")),
      pageSize: parsePageSize(params.get("size")),
    });
    setUrlReady(true);
  }, []);

  useEffect(() => {
    const stored = window.localStorage.getItem("tengen-replay-auto-refresh");
    if (stored !== null) {
      setAutoRefresh(stored === "true");
    }
  }, []);

  useEffect(() => {
    if (!urlReady) return;
    const params = new URLSearchParams(window.location.search);
    params.set("page", String(jobPaginationModel.page));
    params.set("size", String(jobPaginationModel.pageSize));
    setOrDeleteParam(params, "status", filters.status);
    setOrDeleteParam(params, "ruleId", filters.ruleId);
    setOrDeleteParam(params, "ruleRevision", filters.ruleRevision);
    setOrDeleteParam(params, "createdBy", filters.createdBy);
    setOrDeleteParam(params, "jobId", filters.jobId);
    setOrDeleteParam(params, "from", filters.from);
    setOrDeleteParam(params, "to", filters.to);
    const query = params.toString();
    window.history.replaceState(null, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
  }, [filters, jobPaginationModel, urlReady]);

  const jobsQuery = useQuery<ReplayJobPage>({
    queryKey: ["replay-jobs", jobPaginationModel, filters],
    queryFn: async () => {
      const params = new URLSearchParams({
        page: String(jobPaginationModel.page),
        size: String(jobPaginationModel.pageSize),
      });
      addParam(params, "status", filters.status);
      addParam(params, "ruleId", filters.ruleId);
      addParam(params, "ruleRevision", filters.ruleRevision);
      addParam(params, "createdBy", filters.createdBy);
      addParam(params, "jobId", filters.jobId);
      addParam(params, "from", toIso(filters.from));
      addParam(params, "to", toIso(filters.to));
      return (await api.get(`/replay-jobs?${params.toString()}`)).data;
    },
    placeholderData: keepPreviousData,
    enabled: urlReady,
    refetchInterval: (query) => {
      if (!autoRefresh) return false;
      const rows = (query.state.data as ReplayJobPage | undefined)?.content ?? [];
      return rows.some((row) => ACTIVE_STATUSES.has(row.status)) ? 5_000 : false;
    },
  });

  useEffect(() => {
    if (jobsQuery.isSuccess) {
      setLastUpdated(new Date(jobsQuery.dataUpdatedAt));
    }
  }, [jobsQuery.dataUpdatedAt, jobsQuery.isSuccess]);

  const selectedJobQuery = useQuery<ReplayJob>({
    queryKey: ["replay-job", selectedId],
    queryFn: async () => (await api.get(`/replay-jobs/${selectedId}`)).data,
    enabled: selectedId !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status as ReplayJobStatus | undefined;
      return status && ACTIVE_STATUSES.has(status) ? 3_000 : false;
    },
  });
  const selectedJob = selectedJobQuery.data;

  const transitionsQuery = useQuery<ReplayJobTransition[]>({
    queryKey: ["replay-job-transitions", selectedId],
    queryFn: async () => (await api.get(`/replay-jobs/${selectedId}/transitions`)).data,
    enabled: selectedId !== null,
    refetchInterval: selectedJob && ACTIVE_STATUSES.has(selectedJob.status) ? 3_000 : false,
  });

  const outcomesQuery = useQuery<ReplayJobOutcomePage>({
    queryKey: ["replay-outcomes", selectedId, outcomePaginationModel, outcomeFilter],
    queryFn: async () => {
      const params = new URLSearchParams({
        page: String(outcomePaginationModel.page),
        size: String(outcomePaginationModel.pageSize),
      });
      if (outcomeFilter) params.set("matched", outcomeFilter);
      return (await api.get(`/replay-jobs/${selectedId}/outcomes?${params.toString()}`)).data;
    },
    enabled: selectedId !== null,
    placeholderData: keepPreviousData,
    refetchInterval: selectedJob && ACTIVE_STATUSES.has(selectedJob.status) ? 3_000 : false,
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
      setSelectedId(job.id);
      setOutcomePaginationModel((current) => ({ ...current, page: 0 }));
      setError(null);
      queryClient.setQueryData(["replay-job", job.id], job);
      void queryClient.invalidateQueries({ queryKey: ["replay-jobs"] });
    },
    onError: (reason) => setError(errorMessage(reason)),
  });

  const controlMutation = useMutation({
    mutationFn: async ({ action, job }: { action: ControlAction; job: ReplayJob }) => (
      api.post(`/replay-jobs/${job.id}/${action}`, undefined, {
        headers: { "If-Match": `"${job.version}"` },
      })
    ),
    onSuccess: (response) => {
      const job = response.data as ReplayJob;
      setPendingControl(null);
      setError(null);
      queryClient.setQueryData(["replay-job", job.id], job);
      void queryClient.invalidateQueries({ queryKey: ["replay-jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["replay-job-transitions", job.id] });
    },
    onError: (reason) => {
      setPendingControl(null);
      setError(errorMessage(reason));
      if (selectedId !== null) {
        void queryClient.invalidateQueries({ queryKey: ["replay-job", selectedId] });
      }
    },
  });

  const unsupported = revisionDetailQuery.data?.snapshot.ruleType === "SEQUENCE"
    || revisionDetailQuery.data?.snapshot.ruleType === "ABSENCE";
  const noSnapshot = !revisionDetailQuery.isLoading && !revisionDetailQuery.data;
  const rows = jobsQuery.data?.content ?? [];

  const outcomeColumns = useMemo<GridColDef<ReplayJobOutcome>[]>(() => [
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

  const jobColumns = useMemo<GridColDef<ReplayJob>[]>(() => [
    {
      field: "status",
      headerName: "Status",
      width: 175,
      renderCell: (params) => <StatusChip status={params.value as ReplayJobStatus} />,
    },
    { field: "ruleName", headerName: "Rule", minWidth: 190, flex: 1 },
    { field: "ruleRevision", headerName: "Revision", width: 95 },
    {
      field: "range",
      headerName: "Requested range",
      minWidth: 260,
      flex: 1.2,
      sortable: false,
      valueGetter: (_value, row) => `${formatTimestamp(row.occurredFrom, preferences.timeDisplay)} → ${formatTimestamp(row.occurredTo, preferences.timeDisplay)}`,
    },
    {
      field: "progress",
      headerName: "Progress",
      width: 155,
      sortable: false,
      renderCell: (params) => (
        <Stack
          direction="row"
          spacing={1}
          alignItems="center"
          justifyContent="center"
          sx={{ width: "100%", height: "100%", alignSelf: "stretch", boxSizing: "border-box" }}
        >
          <LinearProgress
            variant="determinate"
            value={params.row.progressPercentage}
            sx={{ width: 72, height: 7, borderRadius: 4, flex: "0 0 72px" }}
          />
          <Typography variant="caption" sx={{ minWidth: "3.5ch", textAlign: "right" }}>
            {params.row.progressPercentage.toFixed(0)}%
          </Typography>
        </Stack>
      ),
    },
    { field: "matchedEvents", headerName: "Matched", width: 90 },
    { field: "errorEvents", headerName: "Errors", width: 80 },
    { field: "attemptCount", headerName: "Attempts", width: 90 },
    { field: "createdBy", headerName: "Created by", width: 120 },
    {
      field: "createdAt",
      headerName: "Created",
      width: 180,
      renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
    },
    {
      field: "finishedAt",
      headerName: "Finished / paused",
      width: 180,
      sortable: false,
      renderCell: (params) => formatTimestamp(
        params.row.completedAt ?? params.row.pausedAt ?? params.row.cancelledAt,
        preferences.timeDisplay,
      ),
    },
  ], [preferences.timeDisplay]);

  const setFilter = (key: keyof Filters, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }));
    setJobPaginationModel((current) => ({ ...current, page: 0 }));
  };

  const toggleAutoRefresh = (enabled: boolean) => {
    setAutoRefresh(enabled);
    window.localStorage.setItem("tengen-replay-auto-refresh", String(enabled));
  };

  const requestControl = (action: ControlAction, job: ReplayJob) => {
    if (action === "cancel" || action === "retry") {
      setPendingControl({ action, job });
      return;
    }
    controlMutation.mutate({ action, job });
  };

  const confirmControl = () => {
    if (pendingControl) {
      controlMutation.mutate(pendingControl);
    }
  };

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" gap={1} sx={{ mb: 1 }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <ReplayIcon color="primary" />
          <Box>
            <Typography variant="h5">Replay and backfill</Typography>
            <Typography variant="body2" color="text.secondary">
              Evaluate persisted historical events against one immutable rule revision.
            </Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center">
          <FormControlLabel
            control={<Switch checked={autoRefresh} onChange={(event) => toggleAutoRefresh(event.target.checked)} />}
            label="Auto-refresh"
          />
          <Button
            size="small"
            startIcon={<RefreshIcon />}
            onClick={() => void jobsQuery.refetch()}
          >
            Refresh
          </Button>
        </Stack>
      </Stack>

      <Alert severity="info" sx={{ mb: 3 }}>
        Analysis-only replay: jobs use <strong>NO_ACTIONS</strong>. They never send webhooks or change live rule state. Controls pause or manage the analysis job itself.
      </Alert>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

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
                onChange={(event) => {
                  setRuleId(event.target.value);
                  setRevision("");
                }}
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
          {revisionDetailQuery.error && <Alert severity="error">{errorMessage(revisionDetailQuery.error)}</Alert>}
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

      <Paper sx={{ p: 2, mb: 2 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Stack direction="row" spacing={1} alignItems="center">
            <FilterAltIcon color="primary" />
            <Typography variant="h6">Replay job history</Typography>
            {jobsQuery.data && <Chip label={`${jobsQuery.data.totalElements} jobs`} size="small" />}
          </Stack>
          <Button size="small" onClick={() => setFiltersOpen((open) => !open)}>
            {filtersOpen ? "Hide filters" : "Show filters"}
          </Button>
        </Stack>
        <Collapse in={filtersOpen}>
          <Stack spacing={2} sx={{ pt: 2 }}>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <FormControl fullWidth size="small">
                <InputLabel id="replay-status-filter-label">Status</InputLabel>
                <Select
                  labelId="replay-status-filter-label"
                  label="Status"
                  value={filters.status}
                  onChange={(event) => setFilter("status", event.target.value)}
                >
                  <MenuItem value="">All statuses</MenuItem>
                  {STATUS_OPTIONS.map((status) => <MenuItem key={status} value={status}>{status}</MenuItem>)}
                </Select>
              </FormControl>
              <TextField size="small" fullWidth label="Rule ID" value={filters.ruleId} onChange={(event) => setFilter("ruleId", event.target.value)} />
              <TextField size="small" fullWidth label="Revision" value={filters.ruleRevision} onChange={(event) => setFilter("ruleRevision", event.target.value)} />
              <TextField size="small" fullWidth label="Created by" value={filters.createdBy} onChange={(event) => setFilter("createdBy", event.target.value)} />
              <TextField size="small" fullWidth label="Job ID" value={filters.jobId} onChange={(event) => setFilter("jobId", event.target.value)} />
            </Stack>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField
                size="small"
                fullWidth
                type="datetime-local"
                label="Created from"
                value={filters.from}
                onChange={(event) => setFilter("from", event.target.value)}
                InputLabelProps={{ shrink: true }}
              />
              <TextField
                size="small"
                fullWidth
                type="datetime-local"
                label="Created to"
                value={filters.to}
                onChange={(event) => setFilter("to", event.target.value)}
                InputLabelProps={{ shrink: true }}
              />
              <Button onClick={() => { setFilters(INITIAL_FILTERS); setJobPaginationModel((current) => ({ ...current, page: 0 })); }}>
                Clear filters
              </Button>
            </Stack>
          </Stack>
        </Collapse>
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
          {lastUpdated ? `Last updated ${formatTimestamp(lastUpdated, preferences.timeDisplay)}` : "History is retained independently of your browser session."}
        </Typography>
        <Box sx={{ height: 560, width: "100%", mt: 1 }}>
          <ClientDataGrid
            rows={rows}
            columns={jobColumns}
            loading={jobsQuery.isLoading}
            getRowId={(row) => row.id}
            pagination
            paginationMode="server"
            paginationModel={jobPaginationModel}
            onPaginationModelChange={setJobPaginationModel}
            rowCount={jobsQuery.data?.totalElements ?? 0}
            pageSizeOptions={[10, 25, 50, 100]}
            onRowClick={(params) => {
              setSelectedId(params.row.id);
              setOutcomePaginationModel((current) => ({ ...current, page: 0 }));
            }}
            disableRowSelectionOnClick
          />
        </Box>
        {jobsQuery.error && <Alert severity="error" sx={{ mt: 2 }}>{errorMessage(jobsQuery.error)}</Alert>}
      </Paper>

      <Dialog
        open={selectedId !== null}
        onClose={() => setSelectedId(null)}
        fullWidth
        maxWidth="xl"
      >
        <DialogTitle>
          {selectedJob ? `Replay job #${selectedJob.id}` : "Replay job"}
        </DialogTitle>
        <DialogContent dividers>
          {selectedJobQuery.isLoading && <CircularProgress />}
          {selectedJobQuery.error && <Alert severity="error">{errorMessage(selectedJobQuery.error)}</Alert>}
          {selectedJob && (
            <Stack spacing={2}>
              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" gap={1}>
                <Box>
                  <Typography variant="h6">{selectedJob.ruleName}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    Rule {selectedJob.ruleId} · revision {selectedJob.ruleRevision} · {selectedJob.ruleType}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={1} alignItems="center">
                  <StatusChip status={selectedJob.status} />
                  <JobControls
                    job={selectedJob}
                    disabled={controlMutation.isPending}
                    onAction={requestControl}
                  />
                </Stack>
              </Stack>

              <LinearProgress
                variant="determinate"
                value={selectedJob.progressPercentage}
                sx={{ height: 8, borderRadius: 4 }}
              />
              <Typography variant="body2" color="text.secondary">
                {selectedJob.processedOutputEvents.toLocaleString()} of {selectedJob.totalOutputEvents.toLocaleString()} output events processed ({selectedJob.progressPercentage.toFixed(1)}%).
              </Typography>
              {selectedJob.failureMessage && (
                <Alert severity="error">
                  {selectedJob.failureCategory ? `${selectedJob.failureCategory}: ` : ""}{selectedJob.failureMessage}
                  {selectedJob.retryable ? " This failure can be retried." : " This failure is not retryable."}
                </Alert>
              )}

              <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
                <SummaryCard label="Matched" value={selectedJob.matchedEvents} />
                <SummaryCard label="Evaluation errors" value={selectedJob.errorEvents} />
                <SummaryCard label="Materialized" value={selectedJob.totalMaterializedEvents} />
                <SummaryCard label="Attempt" value={selectedJob.attemptCount} />
                <SummaryCard label="Action mode" value={selectedJob.actionMode} />
              </Stack>

              <Paper variant="outlined" sx={{ p: 2, bgcolor: "background.default" }}>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1}>
                  <Box>
                    <Typography variant="subtitle1">Job settings</Typography>
                    <Typography variant="caption" color="text.secondary">
                      The replay request is immutable; lifecycle values are read-only status context.
                    </Typography>
                  </Box>
                  <Chip label="Immutable request" size="small" variant="outlined" />
                </Stack>
                <Stack spacing={2} sx={{ mt: 2 }}>
                  <Box>
                    <Typography variant="overline" color="text.secondary">Request</Typography>
                    <Box sx={{
                      display: "grid",
                      gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(3, minmax(0, 1fr))" },
                      gap: 1.25,
                      mt: 0.5,
                    }}>
                      <DetailValue
                        wide
                        label="Occurred range"
                        value={`${formatTimestamp(selectedJob.occurredFrom, preferences.timeDisplay)} → ${formatTimestamp(selectedJob.occurredTo, preferences.timeDisplay)}`}
                      />
                      <DetailValue label="Aggregate warmup" value={formatTimestamp(selectedJob.warmupFrom, preferences.timeDisplay)} />
                      <DetailValue label="API-key filter" value={selectedJob.apiKeyId === null ? "All keys / legacy" : String(selectedJob.apiKeyId)} />
                    </Box>
                  </Box>
                  <Box>
                    <Typography variant="overline" color="text.secondary">Lifecycle</Typography>
                    <Box sx={{
                      display: "grid",
                      gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(3, minmax(0, 1fr))" },
                      gap: 1.25,
                      mt: 0.5,
                    }}>
                      <DetailValue label="Created by" value={selectedJob.createdBy} />
                      <DetailValue label="Created" value={formatTimestamp(selectedJob.createdAt, preferences.timeDisplay)} />
                      <DetailValue label="Started" value={formatTimestamp(selectedJob.startedAt, preferences.timeDisplay)} />
                      <DetailValue label="Lease expires" value={formatTimestamp(selectedJob.leaseExpiresAt, preferences.timeDisplay)} />
                      <DetailValue label="Updated" value={formatTimestamp(selectedJob.updatedAt, preferences.timeDisplay)} />
                      <DetailValue label="Completed" value={formatTimestamp(selectedJob.completedAt, preferences.timeDisplay)} />
                      <DetailValue label="Paused" value={formatTimestamp(selectedJob.pausedAt, preferences.timeDisplay)} />
                      <DetailValue label="Cancelled" value={formatTimestamp(selectedJob.cancelledAt, preferences.timeDisplay)} />
                      <DetailValue label="Last checkpoint" value={selectedJob.lastCommittedPosition === null ? "Not started" : String(selectedJob.lastCommittedPosition)} />
                    </Box>
                  </Box>
                </Stack>
              </Paper>

              <Box>
                <Typography variant="subtitle1" sx={{ mb: 1 }}>Transition history</Typography>
                {transitionsQuery.isLoading && <CircularProgress size={20} />}
                <Stack spacing={1}>
                  {transitionsQuery.data?.map((transition) => (
                    <Stack key={transition.id} direction={{ xs: "column", sm: "row" }} spacing={1} alignItems={{ sm: "center" }}>
                      <Chip label={transition.action} size="small" />
                      <Typography variant="body2">
                        {transition.fromStatus ?? "NEW"} → {transition.toStatus} · {transition.actor} · attempt {transition.attemptCount}
                        {transition.reason ? ` · ${transition.reason}` : ""}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {formatTimestamp(transition.transitionedAt, preferences.timeDisplay)}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </Box>

              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} gap={1}>
                <Typography variant="subtitle1">Outcomes</Typography>
                <FormControl size="small" sx={{ minWidth: 160 }}>
                  <InputLabel id="replay-outcome-filter-label">Show</InputLabel>
                  <Select
                    labelId="replay-outcome-filter-label"
                    label="Show"
                    value={outcomeFilter}
                    onChange={(event) => {
                      setOutcomeFilter(event.target.value);
                      setOutcomePaginationModel((current) => ({ ...current, page: 0 }));
                    }}
                  >
                    <MenuItem value="">All outcomes</MenuItem>
                    <MenuItem value="true">Matched only</MenuItem>
                    <MenuItem value="false">No match only</MenuItem>
                  </Select>
                </FormControl>
              </Stack>
              <Box sx={{ height: 440, width: "100%" }}>
                <ClientDataGrid
                  rows={outcomesQuery.data?.content ?? []}
                  columns={outcomeColumns}
                  loading={outcomesQuery.isLoading}
                  getRowId={(row) => row.id}
                  pagination
                  paginationMode="server"
                  paginationModel={outcomePaginationModel}
                  onPaginationModelChange={setOutcomePaginationModel}
                  rowCount={outcomesQuery.data?.totalElements ?? 0}
                  pageSizeOptions={[10, 25, 50, 100]}
                  disableRowSelectionOnClick
                />
              </Box>
              {outcomesQuery.error && <Alert severity="error">{errorMessage(outcomesQuery.error)}</Alert>}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelectedId(null)}>Close</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={pendingControl !== null} onClose={() => setPendingControl(null)}>
        <DialogTitle>{pendingControl?.action === "cancel" ? "Cancel replay job?" : "Retry replay job?"}</DialogTitle>
        <DialogContent>
          <Typography>
            {pendingControl?.action === "cancel"
              ? "Cancellation preserves committed outcomes but stops future processing."
              : "Retry resumes a retryable worker failure from the last committed checkpoint. It does not send webhooks or re-trigger live events."}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingControl(null)}>Keep job</Button>
          <Button color={pendingControl?.action === "cancel" ? "error" : "primary"} variant="contained" onClick={confirmControl}>
            {pendingControl?.action === "cancel" ? "Cancel job" : "Retry job"}
          </Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}

function JobControls({
  job,
  disabled,
  onAction,
}: {
  job: ReplayJob;
  disabled: boolean;
  onAction: (action: ControlAction, job: ReplayJob) => void;
}) {
  return (
    <Stack direction="row" spacing={0.5}>
      {(job.status === "QUEUED" || job.status === "RUNNING") && (
        <Button size="small" startIcon={<PauseCircleOutlineIcon />} onClick={() => onAction("pause", job)} disabled={disabled}>
          Pause
        </Button>
      )}
      {job.status === "PAUSED" && (
        <Button size="small" startIcon={<PlayArrowIcon />} onClick={() => onAction("resume", job)} disabled={disabled}>
          Resume
        </Button>
      )}
      {["QUEUED", "RUNNING", "PAUSE_REQUESTED", "PAUSED", "FAILED"].includes(job.status) && (
        <Button size="small" color="error" startIcon={<CancelIcon />} onClick={() => onAction("cancel", job)} disabled={disabled}>
          Cancel
        </Button>
      )}
      {job.status === "FAILED" && job.retryable && (
        <Button size="small" startIcon={<ReplayIcon />} onClick={() => onAction("retry", job)} disabled={disabled}>
          Retry
        </Button>
      )}
    </Stack>
  );
}

function StatusChip({ status }: { status: ReplayJobStatus }) {
  return <Chip label={status.replaceAll("_", " ")} color={statusColor(status)} size="small" />;
}

function statusColor(status: ReplayJobStatus): "default" | "info" | "success" | "warning" | "error" {
  if (status === "COMPLETED") return "success";
  if (status === "FAILED" || status === "CANCELLED") return status === "FAILED" ? "error" : "default";
  if (status === "RUNNING") return "info";
  if (status === "PAUSE_REQUESTED" || status === "CANCEL_REQUESTED") return "warning";
  return "default";
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

function DetailValue({ label, value, wide = false }: { label: string; value: string; wide?: boolean }) {
  return (
    <Box sx={{
      minWidth: 0,
      p: 1.25,
      border: 1,
      borderColor: "divider",
      borderRadius: 1,
      bgcolor: "background.paper",
      ...(wide ? { gridColumn: { xs: "auto", sm: "1 / -1" } } : {}),
    }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ overflowWrap: "anywhere" }}>{value}</Typography>
    </Box>
  );
}

function AggregateSummary({ aggregate }: { aggregate: unknown }) {
  if (!aggregate || typeof aggregate !== "object") return "—";
  const value = aggregate as { function?: string; value?: number; threshold?: number; windowSeconds?: number };
  if (value.function === undefined || value.value === undefined) return "—";
  return `${value.function} ${value.value} / ${value.threshold ?? "?"} (${value.windowSeconds ?? "?"}s)`;
}

function addParam(params: URLSearchParams, key: string, value: string | null) {
  if (value) params.set(key, value);
}

function setOrDeleteParam(params: URLSearchParams, key: string, value: string) {
  if (value) {
    params.set(key, value);
  } else {
    params.delete(key);
  }
}

function parsePage(value: string | null) {
  const page = value ? Number(value) : 0;
  return Number.isInteger(page) && page >= 0 ? page : 0;
}

function parsePageSize(value: string | null) {
  const size = value ? Number(value) : 25;
  return [10, 25, 50, 100].includes(size) ? size : 25;
}

function toIso(value: string | null) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
