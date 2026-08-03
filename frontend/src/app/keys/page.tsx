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
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
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

      <TableContainer>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Prefix</TableCell>
              <TableCell>Response Mode</TableCell>
              <TableCell>Allowed Event Types</TableCell>
              <TableCell>Allowed Sources</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {keys.map((key) => (
              <TableRow key={key.id}>
                <TableCell>{key.name}</TableCell>
                <TableCell>
                  <code>{key.prefix}...</code>
                </TableCell>
                <TableCell>
                  {key.responseMode === "FULL" ? "Full details" : "Compact summary"}
                </TableCell>
                <TableCell>
                  {key.allowedEventTypes?.join(", ") || "All"}
                </TableCell>
                <TableCell>{key.allowedSources?.join(", ") || "All"}</TableCell>
                <TableCell>
                  <Chip
                    label={key.active ? "Active" : "Revoked"}
                    color={key.active ? "success" : "default"}
                    size="small"
                  />
                </TableCell>
                <TableCell>{key.expiresAt ? formatTimestamp(key.expiresAt, preferences.timeDisplay) : "Never"}</TableCell>
                <TableCell>
                  {key.active && (
                    <Button size="small" color="error" onClick={() => revokeMutation.mutate(key.id)}>
                      Revoke
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && keys.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} align="center">
                  No API keys yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

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
