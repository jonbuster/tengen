"use client";

import {
  Alert,
  Box,
  Chip,
  Container,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import EventNoteIcon from "@mui/icons-material/EventNote";
import { DataGrid, GridColDef, GridPaginationModel, GridRowParams } from "@mui/x-data-grid";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { api, errorMessage } from "@/lib/api";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import { EventHistoryPage, EventHistorySummary } from "@/lib/types";

type Filters = {
  eventId: string;
  type: string;
  source: string;
  apiKeyId: string;
  matched: string;
  traceAvailable: string;
  from: string;
  to: string;
};

const INITIAL_FILTERS: Filters = {
  eventId: "",
  type: "",
  source: "",
  apiKeyId: "",
  matched: "",
  traceAvailable: "",
  from: "",
  to: "",
};

export default function EventsPage() {
  const router = useRouter();
  const { preferences } = usePreferences();
  const [filters, setFilters] = useState<Filters>(INITIAL_FILTERS);
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({
    page: 0,
    pageSize: 25,
  });

  const eventQuery = useQuery<EventHistoryPage>({
    queryKey: ["event-history", paginationModel, filters],
    queryFn: async () => {
      const params = new URLSearchParams({
        page: String(paginationModel.page),
        size: String(paginationModel.pageSize),
      });
      addParam(params, "eventId", filters.eventId);
      addParam(params, "type", filters.type);
      addParam(params, "source", filters.source);
      addParam(params, "apiKeyId", filters.apiKeyId);
      addParam(params, "matched", filters.matched);
      addParam(params, "traceAvailable", filters.traceAvailable);
      addParam(params, "from", toIso(filters.from));
      addParam(params, "to", toIso(filters.to));
      return (await api.get(`/event-history?${params.toString()}`)).data;
    },
    placeholderData: keepPreviousData,
  });

  const setFilter = (key: keyof Filters, value: string) => {
    setFilters((current) => ({ ...current, [key]: value }));
    setPaginationModel((current) => ({ ...current, page: 0 }));
  };

  const columns = useMemo<GridColDef<EventHistorySummary>[]>(
    () => [
      { field: "id", headerName: "Event", width: 90 },
      { field: "type", headerName: "Type", minWidth: 180, flex: 1 },
      { field: "source", headerName: "Source", minWidth: 150, flex: 1 },
      {
        field: "outcome",
        headerName: "Outcome",
        minWidth: 190,
        flex: 1,
        align: "center",
        headerAlign: "center",
        sortable: false,
        renderCell: (params) => <OutcomeSummary event={params.row} />,
      },
      {
        field: "apiKeyName",
        headerName: "API key",
        minWidth: 150,
        flex: 1,
        valueGetter: (_value, row) => row.apiKeyName ?? row.apiKeyPrefix ?? "Legacy / unknown",
      },
      {
        field: "occurredAt",
        headerName: "Occurred",
        width: 185,
        renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
      },
      {
        field: "receivedAt",
        headerName: "Received",
        width: 185,
        renderCell: (params) => formatTimestamp(params.value as string, preferences.timeDisplay),
      },
    ],
    [preferences.timeDisplay],
  );

  const rows = eventQuery.data?.content ?? [];

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        <EventNoteIcon color="primary" />
        <Box>
          <Typography variant="h5">Event Explorer</Typography>
          <Typography variant="body2" color="text.secondary">
            Trace accepted events through matched rules, webhook actions, and delivery history.
          </Typography>
        </Box>
      </Stack>

      <Stack direction={{ xs: "column", md: "row" }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField size="small" label="Event ID" value={filters.eventId} onChange={(event) => setFilter("eventId", event.target.value)} />
        <TextField size="small" label="Type" value={filters.type} onChange={(event) => setFilter("type", event.target.value)} />
        <TextField size="small" label="Source" value={filters.source} onChange={(event) => setFilter("source", event.target.value)} />
        <TextField size="small" label="API key ID" value={filters.apiKeyId} onChange={(event) => setFilter("apiKeyId", event.target.value)} />
        <FormControl size="small" sx={{ minWidth: 145 }}>
          <InputLabel>Matched</InputLabel>
          <Select label="Matched" value={filters.matched} onChange={(event) => setFilter("matched", event.target.value)}>
            <MenuItem value="">All</MenuItem>
            <MenuItem value="true">Matched</MenuItem>
            <MenuItem value="false">No match</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 170 }}>
          <InputLabel>Trace</InputLabel>
          <Select label="Trace" value={filters.traceAvailable} onChange={(event) => setFilter("traceAvailable", event.target.value)}>
            <MenuItem value="">All events</MenuItem>
            <MenuItem value="true">Trace available</MenuItem>
            <MenuItem value="false">Legacy / unavailable</MenuItem>
          </Select>
        </FormControl>
      </Stack>

      <Stack direction={{ xs: "column", md: "row" }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField size="small" type="datetime-local" label="Received from" value={filters.from} onChange={(event) => setFilter("from", event.target.value)} InputLabelProps={{ shrink: true }} />
        <TextField size="small" type="datetime-local" label="Received to" value={filters.to} onChange={(event) => setFilter("to", event.target.value)} InputLabelProps={{ shrink: true }} />
      </Stack>

      {eventQuery.error && <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(eventQuery.error)}</Alert>}

      <Box sx={{ height: 620, width: "100%" }}>
        <DataGrid
          rows={rows}
          columns={columns}
          loading={eventQuery.isLoading}
          getRowId={(row) => row.id}
          pagination
          paginationMode="server"
          paginationModel={paginationModel}
          onPaginationModelChange={setPaginationModel}
          rowCount={eventQuery.data?.totalElements ?? 0}
          pageSizeOptions={[10, 25, 50, 100]}
          disableRowSelectionOnClick
          onRowClick={(params: GridRowParams<EventHistorySummary>) => router.push(`/events/${params.row.id}`)}
        />
      </Box>
    </Container>
  );
}

function OutcomeSummary({ event }: { event: EventHistorySummary }) {
  if (!event.traceAvailable) {
    return (
      <Stack direction="row" alignItems="center" justifyContent="center" sx={{ width: "100%", height: "100%" }}>
        <Chip label="Trace unavailable" size="small" variant="outlined" />
      </Stack>
    );
  }
  const matched = event.matchedRuleCount ?? 0;
  const queued = event.queuedActionCount ?? 0;
  const suppressed = event.suppressedActionCount ?? 0;
  return (
    <Stack direction="row" spacing={0.5} alignItems="center" justifyContent="center" sx={{ width: "100%", height: "100%" }}>
      <Chip label={matched > 0 ? `${matched} matched` : "No match"} color={matched > 0 ? "success" : "default"} size="small" />
      {queued > 0 && <Chip label={`${queued} queued`} color="info" size="small" />}
      {suppressed > 0 && <Chip label={`${suppressed} suppressed`} color="warning" size="small" />}
    </Stack>
  );
}

function toIso(value: string) {
  if (!value) return undefined;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function addParam(params: URLSearchParams, key: string, value: string | undefined) {
  if (value) params.set(key, value);
}
