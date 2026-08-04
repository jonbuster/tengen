"use client";

import {
  Alert,
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Button,
  Chip,
  Container,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import HistoryIcon from "@mui/icons-material/History";
import FilterAltIcon from "@mui/icons-material/FilterAlt";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import RefreshIcon from "@mui/icons-material/Refresh";
import ReplayIcon from "@mui/icons-material/Replay";
import Link from "next/link";
import { type GridColDef, type GridPaginationModel, type GridRowParams } from "@mui/x-data-grid";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useMemo, useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { ClientDataGrid } from "@/components/ClientDataGrid";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import {
  WebhookDeliveryDetail,
  WebhookDeliveryPage,
  WebhookDeliveryStatus,
  WebhookDeliverySummary,
} from "@/lib/types";

const ACTIVE_STATUSES: WebhookDeliveryStatus[] = [
  "PENDING",
  "PROCESSING",
  "RETRY_SCHEDULED",
];

const STATUS_OPTIONS: WebhookDeliveryStatus[] = [
  "PENDING",
  "PROCESSING",
  "RETRY_SCHEDULED",
  "DELIVERED",
  "DEAD_LETTER",
];

type Filters = {
  status: string;
  ruleId: string;
  eventId: string;
  from: string;
  to: string;
  search: string;
};

const INITIAL_FILTERS: Filters = {
  status: "",
  ruleId: "",
  eventId: "",
  from: "",
  to: "",
  search: "",
};

export default function DeliveriesPage() {
  const queryClient = useQueryClient();
  const { preferences } = usePreferences();
  const [filters, setFilters] = useState<Filters>(INITIAL_FILTERS);
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: 0,
    pageSize: 25,
  });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);

  useEffect(() => {
    const eventId = new URLSearchParams(window.location.search).get("eventId") ?? "";
    setFilters((current) => current.eventId === eventId ? current : { ...current, eventId });
    setPaginationModel((current) => current.page === 0 ? current : { ...current, page: 0 });
  }, []);

  useEffect(() => {
    const stored = window.localStorage.getItem("tengen-delivery-auto-refresh");
    if (stored !== null) {
      setAutoRefresh(stored === "true");
    }
  }, []);

  const queryKey = ["webhook-deliveries", paginationModel, filters];
  const deliveryQuery = useQuery<WebhookDeliveryPage>({
    queryKey,
    queryFn: async () => {
      const params = new URLSearchParams({
        page: String(paginationModel.page),
        size: String(paginationModel.pageSize),
      });
      addParam(params, "status", filters.status);
      addParam(params, "ruleId", filters.ruleId);
      addParam(params, "eventId", filters.eventId);
      addParam(params, "from", toIso(filters.from));
      addParam(params, "to", toIso(filters.to));
      addParam(params, "search", filters.search);
      return (await api.get(`/webhook-deliveries?${params.toString()}`)).data;
    },
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      if (!autoRefresh) return false;
      const rows = (query.state.data as WebhookDeliveryPage | undefined)?.content ?? [];
      return rows.some((row) => ACTIVE_STATUSES.includes(row.status)) ? 5000 : false;
    },
  });

  useEffect(() => {
    if (deliveryQuery.isSuccess) {
      setLastUpdated(new Date(deliveryQuery.dataUpdatedAt));
    }
  }, [deliveryQuery.dataUpdatedAt, deliveryQuery.isSuccess]);

  const detailQuery = useQuery<WebhookDeliveryDetail>({
    queryKey: ["webhook-delivery", selectedId],
    queryFn: async () => (await api.get(`/webhook-deliveries/${selectedId}`)).data,
    enabled: selectedId !== null,
  });

  const retryMutation = useMutation({
    mutationFn: (id: number) => api.post(`/webhook-deliveries/${id}/retry`),
    onSuccess: () => {
      setError(null);
      queryClient.invalidateQueries({ queryKey: ["webhook-deliveries"] });
      queryClient.invalidateQueries({ queryKey: ["webhook-delivery", selectedId] });
    },
    onError: (err) => setError(errorMessage(err)),
  });

  const rows = deliveryQuery.data?.content ?? [];
  const selectedDelivery = detailQuery.data?.delivery;
  const activeFilterCount = Object.values(filters).filter(Boolean).length;

  const columns = useMemo<GridColDef<WebhookDeliverySummary>[]>(
    () => [
      {
        field: "status",
        headerName: "Status",
        width: 165,
        renderCell: (params) => <StatusChip status={params.value as WebhookDeliveryStatus} />,
      },
      { field: "ruleName", headerName: "Rule", minWidth: 190, flex: 1 },
      {
        field: "eventId",
        headerName: "Event",
        width: 90,
        renderCell: (params) => (
          <Link href={`/events/${params.value as number}`}>{params.value as number}</Link>
        ),
      },
      { field: "destination", headerName: "Destination", minWidth: 220, flex: 1 },
      { field: "attemptCount", headerName: "Attempts", width: 95 },
      {
        field: "nextAttemptAt",
        headerName: "Next attempt",
        width: 180,
        renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
      },
      {
        field: "createdAt",
        headerName: "Created",
        width: 180,
        renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
      },
      {
        field: "lastStatusCode",
        headerName: "HTTP",
        width: 75,
        renderCell: (params) => params.value ?? "—",
      },
      {
        field: "lastError",
        headerName: "Latest error",
        minWidth: 220,
        flex: 1,
        renderCell: (params) => params.value || "—",
      },
    ],
    [preferences.timeDisplay],
  );

  const setFilter = (key: keyof Filters, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }));
    setPaginationModel((current) => ({ ...current, page: 0 }));
  };

  const toggleAutoRefresh = (enabled: boolean) => {
    setAutoRefresh(enabled);
    window.localStorage.setItem("tengen-delivery-auto-refresh", String(enabled));
  };

  const refreshNow = () => {
    setError(null);
    void deliveryQuery.refetch();
  };

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <HistoryIcon color="primary" />
          <Typography variant="h5">Webhook Deliveries</Typography>
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center">
          <Button
            variant="outlined"
            size="small"
            startIcon={<FilterAltIcon />}
            onClick={() => setFiltersOpen((current) => !current)}
            aria-expanded={filtersOpen}
            aria-controls="delivery-filters"
          >
            {filtersOpen ? "Hide filters" : "Show filters"}
            {activeFilterCount > 0 ? ` (${activeFilterCount})` : ""}
          </Button>
          <FormControlLabel
            control={<Switch checked={autoRefresh} onChange={(event) => toggleAutoRefresh(event.target.checked)} />}
            label="Auto-refresh"
          />
          <Button variant="outlined" startIcon={<RefreshIcon />} onClick={refreshNow}>
            Refresh now
          </Button>
        </Stack>
      </Stack>

      <Collapse in={filtersOpen} unmountOnExit>
        <Stack id="delivery-filters" direction={{ xs: "column", md: "row" }} spacing={1.5} sx={{ mb: 2 }}>
          <FormControl size="small" sx={{ minWidth: 180 }}>
            <InputLabel>Status</InputLabel>
            <Select label="Status" value={filters.status} onChange={(event) => setFilter("status", event.target.value)}>
              <MenuItem value="">All statuses</MenuItem>
              {STATUS_OPTIONS.map((status) => (
                <MenuItem key={status} value={status}>{statusLabel(status)}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField size="small" label="Rule ID" value={filters.ruleId} onChange={(event) => setFilter("ruleId", event.target.value)} />
          <TextField size="small" label="Event ID" value={filters.eventId} onChange={(event) => setFilter("eventId", event.target.value)} />
          <TextField size="small" label="Search rule or destination" value={filters.search} onChange={(event) => setFilter("search", event.target.value)} sx={{ minWidth: 230 }} />
          <TextField size="small" type="datetime-local" label="From" value={filters.from} onChange={(event) => setFilter("from", event.target.value)} InputLabelProps={{ shrink: true }} />
          <TextField size="small" type="datetime-local" label="To" value={filters.to} onChange={(event) => setFilter("to", event.target.value)} InputLabelProps={{ shrink: true }} />
        </Stack>
      </Collapse>

      <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
        <Typography variant="body2" color="text.secondary">
          {autoRefresh ? "Auto-refresh monitors active deliveries." : "Auto-refresh is off."}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Last updated: {formatTimestamp(lastUpdated, preferences.timeDisplay)}
        </Typography>
      </Stack>

      {(error || deliveryQuery.error) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error ?? errorMessage(deliveryQuery.error)}
        </Alert>
      )}

      <Box sx={{ height: 620, width: "100%" }}>
        <ClientDataGrid
          rows={rows}
          columns={columns}
          loading={deliveryQuery.isLoading}
          getRowId={(row) => row.id}
          pagination
          paginationMode="server"
          paginationModel={paginationModel}
          onPaginationModelChange={setPaginationModel}
          rowCount={deliveryQuery.data?.totalElements ?? 0}
          pageSizeOptions={[10, 25, 50, 100]}
          disableRowSelectionOnClick
          onRowClick={(params: GridRowParams<WebhookDeliverySummary>) => setSelectedId(params.row.id)}
        />
      </Box>

      <Dialog open={selectedId !== null} onClose={() => setSelectedId(null)} maxWidth="md" fullWidth>
        <DialogTitle sx={{ pb: 1.5 }}>
          {selectedDelivery ? (
            <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} gap={1}>
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <StatusChip status={selectedDelivery.status} />
                  <Typography variant="h6" noWrap>{selectedDelivery.ruleName}</Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  Delivery #{selectedDelivery.id} · Event <Link href={`/events/${selectedDelivery.eventId}`}>{selectedDelivery.eventId}</Link>
                </Typography>
              </Box>
              <Chip label={`${selectedDelivery.attemptCount} attempt(s)`} size="small" variant="outlined" />
            </Stack>
          ) : "Webhook delivery details"}
        </DialogTitle>
        <DialogContent dividers>
          {detailQuery.isLoading && <Typography>Loading delivery…</Typography>}
          {detailQuery.error && <Alert severity="error">{errorMessage(detailQuery.error)}</Alert>}
          {selectedDelivery && detailQuery.data && (
            <Stack spacing={2}>
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" gap={1}>
                  <Typography variant="subtitle1">Delivery overview</Typography>
                  <Typography variant="caption" color="text.secondary">Webhook delivery</Typography>
                </Stack>
                <InfoGrid>
                  <InfoField wide label="Destination" value={selectedDelivery.destination} />
                  <InfoField label="Event" value={<Link href={`/events/${selectedDelivery.eventId}`}>{selectedDelivery.eventId}</Link>} />
                  <InfoField label="HTTP status" value={selectedDelivery.lastStatusCode ?? "—"} />
                  <InfoField label="Trigger mode" value={selectedDelivery.triggerMode} />
                  <InfoField label="Scope" value={selectedDelivery.scopeKey ?? "global"} />
                  <InfoField label="Window start" value={formatTimestamp(selectedDelivery.windowStart, preferences.timeDisplay)} />
                </InfoGrid>
              </Paper>

              <Paper variant="outlined" sx={{ p: 2 }}>
                <Typography variant="subtitle1" sx={{ mb: 1.25 }}>Timeline</Typography>
                <Stack direction={{ xs: "column", sm: "row" }} spacing={{ xs: 1.25, sm: 0 }}>
                  <TimelineItem label="Created" value={formatTimestamp(selectedDelivery.createdAt, preferences.timeDisplay)} />
                  <TimelineItem label="Next attempt" value={formatTimestamp(selectedDelivery.nextAttemptAt, preferences.timeDisplay)} />
                  <TimelineItem label="Last attempt" value={formatTimestamp(selectedDelivery.lastAttemptAt, preferences.timeDisplay)} />
                  <TimelineItem label="Delivered" value={formatTimestamp(selectedDelivery.deliveredAt, preferences.timeDisplay)} />
                </Stack>
              </Paper>

              {selectedDelivery.lastError && <Alert severity="warning">{selectedDelivery.lastError}</Alert>}

              <Accordion disableGutters variant="outlined" sx={{ "&::before": { display: "none" } }}>
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Typography variant="subtitle2">Technical details</Typography>
                </AccordionSummary>
                <AccordionDetails>
                  <InfoGrid>
                    <InfoField wide label="Deduplication key" value={detailQuery.data.deduplicationKey} />
                    <InfoField label="Manually retried" value={formatTimestamp(selectedDelivery.manuallyRetriedAt, preferences.timeDisplay)} />
                    <InfoField label="Lease expires" value={formatTimestamp(detailQuery.data.leaseExpiresAt, preferences.timeDisplay)} />
                  </InfoGrid>
                </AccordionDetails>
              </Accordion>

              <Paper variant="outlined" sx={{ p: 2 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.25 }}>
                  <Typography variant="subtitle1">Payload</Typography>
                  <Typography variant="caption" color="text.secondary">JSON</Typography>
                </Stack>
                <Box component="pre" sx={{ m: 0, p: 2, bgcolor: "action.hover", borderRadius: 1, overflow: "auto", maxHeight: 320, fontSize: 13 }}>
                  {JSON.stringify(detailQuery.data.payload, null, 2)}
                </Box>
              </Paper>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSelectedId(null)}>Close</Button>
          {selectedDelivery?.status === "DEAD_LETTER" && (
            <Button
              variant="contained"
              color="warning"
              startIcon={<ReplayIcon />}
              disabled={retryMutation.isPending}
              onClick={() => {
                if (selectedId !== null && window.confirm("Requeue this webhook delivery?")) {
                  retryMutation.mutate(selectedId);
                }
              }}
            >
              {retryMutation.isPending ? "Requeuing…" : "Retry delivery"}
            </Button>
          )}
        </DialogActions>
      </Dialog>
    </Container>
  );
}

function StatusChip({ status }: { status: WebhookDeliveryStatus }) {
  const color = status === "DELIVERED" ? "success" : status === "DEAD_LETTER" ? "error" : status === "PROCESSING" ? "info" : "warning";
  return <Chip label={statusLabel(status)} color={color} size="small" />;
}

function InfoGrid({ children }: { children: ReactNode }) {
  return (
    <Box sx={{
      display: "grid",
      gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" },
      columnGap: 2,
      rowGap: 0.5,
      mt: 1,
    }}>
      {children}
    </Box>
  );
}

function InfoField({ label, value, wide = false }: { label: string; value: ReactNode; wide?: boolean }) {
  return (
    <Box sx={{
      minWidth: 0,
      py: 1,
      borderBottom: 1,
      borderColor: "divider",
      ...(wide ? { gridColumn: { xs: "auto", sm: "1 / -1" } } : {}),
    }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography component="div" variant="body2" sx={{ overflowWrap: "anywhere" }}>{value}</Typography>
    </Box>
  );
}

function TimelineItem({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0, px: { sm: 1.25 }, borderLeft: { sm: 1 }, borderColor: { sm: "divider" } }}>
      <Stack direction="row" spacing={0.75} alignItems="center">
        <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "primary.main", flex: "0 0 auto" }} />
        <Typography variant="caption" color="text.secondary">{label}</Typography>
      </Stack>
      <Typography variant="body2" sx={{ mt: 0.35, overflowWrap: "anywhere" }}>{value}</Typography>
    </Box>
  );
}

function statusLabel(status: WebhookDeliveryStatus) {
  return status.replaceAll("_", " ");
}

function toIso(value: string) {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function addParam(params: URLSearchParams, key: string, value: string | undefined) {
  if (value) params.set(key, value);
}
