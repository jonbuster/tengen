"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import BlockIcon from "@mui/icons-material/Block";
import { GridActionsCellItem, type GridColDef, type GridRowParams } from "@mui/x-data-grid";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { ClientDataGrid } from "@/components/ClientDataGrid";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import { ApiKey, ApiKeyRequest, ResponseMode } from "@/lib/types";

export default function ApiKeysPage() {
  const { preferences } = usePreferences();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [createdKey, setCreatedKey] = useState<ApiKey | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: keys = [], isLoading } = useQuery<ApiKey[]>({
    queryKey: ["api-keys"],
    queryFn: async () => (await api.get("/keys")).data,
  });

  const createMutation = useMutation({
    mutationFn: (request: ApiKeyRequest) => api.post("/keys", request),
    onSuccess: (res) => {
      setCreatedKey(res.data as ApiKey);
      setDialogOpen(false);
      queryClient.invalidateQueries({ queryKey: ["api-keys"] });
    },
    onError: (err) => setError(errorMessage(err)),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: number) => api.post(`/keys/${id}/revoke`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["api-keys"] }),
    onError: (err) => setError(errorMessage(err)),
  });
  const revokeKey = revokeMutation.mutate;

  const columns = useMemo<GridColDef<ApiKey>[]>(() => [
    { field: "id", headerName: "ID", width: 70 },
    { field: "name", headerName: "Name", flex: 1, minWidth: 180 },
    {
      field: "prefix",
      headerName: "Prefix",
      width: 120,
      renderCell: (params) => <code>{params.value}...</code>,
    },
    {
      field: "responseMode",
      headerName: "Response Mode",
      width: 155,
      valueGetter: (_value, row: ApiKey) => row.responseMode === "FULL" ? "Full details" : "Compact summary",
    },
    {
      field: "allowedEventTypes",
      headerName: "Allowed Event Types",
      flex: 1,
      minWidth: 190,
      valueGetter: (_value, row: ApiKey) => row.allowedEventTypes?.join(", ") || "All",
    },
    {
      field: "allowedSources",
      headerName: "Allowed Sources",
      flex: 1,
      minWidth: 160,
      valueGetter: (_value, row: ApiKey) => row.allowedSources?.join(", ") || "All",
    },
    {
      field: "active",
      headerName: "Status",
      width: 120,
      renderCell: (params) => (
        <Chip
          label={params.value ? "Active" : "Revoked"}
          color={params.value ? "success" : "default"}
          size="small"
        />
      ),
    },
    {
      field: "expiresAt",
      headerName: "Expires",
      width: 180,
      valueGetter: (_value, row: ApiKey) => row.expiresAt
        ? formatTimestamp(row.expiresAt, preferences.timeDisplay)
        : "Never",
    },
    {
      field: "actions",
      type: "actions",
      headerName: "Actions",
      width: 90,
      getActions: (params: GridRowParams<ApiKey>) => params.row.active
        ? [
            <GridActionsCellItem
              key="revoke"
              icon={<BlockIcon />}
              label="Revoke"
              onClick={() => revokeKey(params.row.id)}
            />,
          ]
        : [],
    },
  ], [preferences.timeDisplay, revokeKey]);

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h5">API Keys</Typography>
        <Button variant="contained" onClick={() => setDialogOpen(true)}>
          New API Key
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {createdKey && (
        <Alert severity="success" sx={{ mb: 2 }}>
          <Typography variant="body2" sx={{ mb: 1 }}>
            Copy this key now — it will not be shown again.
          </Typography>
          <Box component="code" sx={{ p: 1, bgcolor: "background.paper", borderRadius: 1, display: "inline-block" }}>
            {createdKey.rawKey}
          </Box>
          <Button size="small" sx={{ ml: 2 }} onClick={() => setCreatedKey(null)}>
            Dismiss
          </Button>
        </Alert>
      )}

      <Box sx={{ height: 560, width: "100%" }}>
        <ClientDataGrid
          rows={keys}
          columns={columns}
          loading={isLoading}
          getRowId={(row) => row.id}
          disableRowSelectionOnClick
        />
      </Box>

      <CreateKeyDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreate={(request) => createMutation.mutate(request)}
        submitting={createMutation.isPending}
      />
    </Container>
  );
}

function CreateKeyDialog({
  open,
  onClose,
  onCreate,
  submitting,
}: {
  open: boolean;
  onClose: () => void;
  onCreate: (request: ApiKeyRequest) => void;
  submitting: boolean;
}) {
  const [name, setName] = useState("");
  const [eventTypes, setEventTypes] = useState("");
  const [sources, setSources] = useState("");
  const [responseMode, setResponseMode] = useState<ResponseMode>("COMPACT");

  const submit = () => {
    onCreate({
      name,
      allowedEventTypes: eventTypes
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      allowedSources: sources
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      expiresAt: null,
      responseMode,
    });
    setName("");
    setEventTypes("");
    setSources("");
    setResponseMode("COMPACT");
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>New API Key</DialogTitle>
      <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2, pt: 2 }}>
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} fullWidth required />
        <TextField
          label="Allowed Event Types (comma-separated, blank = all)"
          value={eventTypes}
          onChange={(e) => setEventTypes(e.target.value)}
          fullWidth
        />
        <TextField
          label="Allowed Sources (comma-separated, blank = all)"
          value={sources}
          onChange={(e) => setSources(e.target.value)}
          fullWidth
        />
        <FormControl fullWidth>
          <InputLabel id="response-mode-label">Response mode</InputLabel>
          <Select
            labelId="response-mode-label"
            label="Response mode"
            value={responseMode}
            onChange={(event) => setResponseMode(event.target.value as ResponseMode)}
          >
            <MenuItem value="COMPACT">Compact summary</MenuItem>
            <MenuItem value="FULL">Full details</MenuItem>
          </Select>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
            Compact is recommended for new producers; full details includes the event and rule calculations.
          </Typography>
        </FormControl>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={submitting || !name.trim()}>
          {submitting ? "Creating..." : "Create"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
