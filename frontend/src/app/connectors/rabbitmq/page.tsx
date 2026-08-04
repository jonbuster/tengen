"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  Divider,
  FormControl,
  FormHelperText,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import CableIcon from "@mui/icons-material/Cable";
import CheckCircleOutlineIcon from "@mui/icons-material/CheckCircleOutline";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import StopIcon from "@mui/icons-material/Stop";
import SaveIcon from "@mui/icons-material/Save";
import ScienceIcon from "@mui/icons-material/Science";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, errorMessage } from "@/lib/api";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import {
  ApiKey,
  RabbitMqConnectionTestResult,
  RabbitMqConnector,
  RabbitMqConnectorRequest,
  RabbitMqRuntimeState,
} from "@/lib/types";

type FormValues = Omit<RabbitMqConnectorRequest, "configurationVersion" | "apiKeyId"> & {
  apiKeyId: number | "";
  password: string;
};

const DEFAULT_FORM: FormValues = {
  displayName: "RabbitMQ connector",
  host: "",
  port: 5672,
  virtualHost: "/",
  tlsEnabled: false,
  username: "",
  password: "",
  queueName: "",
  deadLetterExchange: "",
  deadLetterRoutingKey: "",
  apiKeyId: "",
  maxBodyBytes: 1048576,
  retryAttempts: 3,
  retryInitialDelayMs: 1000,
  retryMultiplier: 2,
  retryMaxDelayMs: 30000,
};

const stateColor: Record<RabbitMqRuntimeState, "default" | "info" | "success" | "warning" | "error"> = {
  DISABLED: "default",
  TESTING: "info",
  CONNECTING: "info",
  RUNNING: "success",
  PAUSED: "warning",
  ERROR: "error",
};

export default function RabbitMqConnectorPage() {
  const { preferences } = usePreferences();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<FormValues>(DEFAULT_FORM);
  const [notice, setNotice] = useState<{ severity: "success" | "info" | "error"; text: string } | null>(null);

  const connectorQuery = useQuery<RabbitMqConnector>({
    queryKey: ["rabbitmq-connector"],
    queryFn: async () => (await api.get("/connectors/rabbitmq")).data,
  });
  const keysQuery = useQuery<ApiKey[]>({
    queryKey: ["api-keys"],
    queryFn: async () => (await api.get("/keys")).data,
  });

  const connector = connectorQuery.data;
  useEffect(() => {
    if (!connector) return;
    setForm({
      displayName: connector.displayName,
      host: connector.host,
      port: connector.port,
      virtualHost: connector.virtualHost,
      tlsEnabled: connector.tlsEnabled,
      username: connector.username,
      password: "",
      queueName: connector.queueName,
      deadLetterExchange: connector.deadLetterExchange,
      deadLetterRoutingKey: connector.deadLetterRoutingKey,
      apiKeyId: connector.apiKeyId ?? "",
      maxBodyBytes: connector.maxBodyBytes,
      retryAttempts: connector.retryAttempts,
      retryInitialDelayMs: connector.retryInitialDelayMs,
      retryMultiplier: connector.retryMultiplier,
      retryMaxDelayMs: connector.retryMaxDelayMs,
    });
  }, [connector]);

  const updateQuery = (next: RabbitMqConnector) => {
    queryClient.setQueryData(["rabbitmq-connector"], next);
  };

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!connector) throw new Error("Connector state is not loaded");
      const request: RabbitMqConnectorRequest = {
        ...form,
        apiKeyId: Number(form.apiKeyId),
        configurationVersion: connector.configurationVersion,
        ...(form.password.trim() ? { password: form.password } : {}),
      };
      return (await api.put<RabbitMqConnector>("/connectors/rabbitmq", request)).data;
    },
    onSuccess: (next) => {
      updateQuery(next);
      setNotice({ severity: "success", text: "RabbitMQ connector draft saved. Test it before enabling consumption." });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const testMutation = useMutation({
    mutationFn: async () => (await api.post<RabbitMqConnectionTestResult>("/connectors/rabbitmq/test")).data,
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ["rabbitmq-connector"] });
      setNotice({ severity: result.successful ? "success" : "error", text: result.message });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const enableMutation = useMutation({
    mutationFn: async () => (await api.post<RabbitMqConnector>("/connectors/rabbitmq/enable")).data,
    onSuccess: (next) => {
      updateQuery(next);
      setNotice({ severity: "success", text: "RabbitMQ consumption enabled." });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const disableMutation = useMutation({
    mutationFn: async () => (await api.post<RabbitMqConnector>("/connectors/rabbitmq/disable")).data,
    onSuccess: (next) => {
      updateQuery(next);
      setNotice({ severity: "info", text: "RabbitMQ consumption disabled. The saved configuration was retained." });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const setValue = <K extends keyof FormValues>(key: K, value: FormValues[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
  };

  if (connectorQuery.error) {
    return <Container maxWidth={false} sx={{ py: 4 }}><Alert severity="error">{errorMessage(connectorQuery.error)}</Alert></Container>;
  }

  if (connectorQuery.isLoading || !connector) {
    return <Container maxWidth={false} sx={{ py: 4 }}><Typography>Loading RabbitMQ connector…</Typography></Container>;
  }

  const editingLocked = connector.enabled || connector.runtimeState === "RUNNING" || connector.runtimeState === "CONNECTING" || connector.runtimeState === "TESTING";
  const busy = saveMutation.isPending || testMutation.isPending || enableMutation.isPending || disableMutation.isPending;
  const currentTest = connector.lastTestedVersion === connector.configurationVersion && connector.lastTestSucceeded === true;
  const selectedKey = keysQuery.data?.find((key) => key.id === connector.apiKeyId);

  const disable = () => {
    if (window.confirm("Disable RabbitMQ consumption? The configuration will remain saved.")) {
      disableMutation.mutate();
    }
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <CableIcon color="primary" />
        <Box>
          <Typography variant="h5">Connectors · RabbitMQ</Typography>
          <Typography variant="body2" color="text.secondary">
            Configure one backend-managed RabbitMQ consumer for an existing queue.
          </Typography>
        </Box>
      </Stack>

      <Alert severity="info" sx={{ mb: 2 }}>
        The browser never connects to RabbitMQ. Tengen stores the password encrypted and the backend opens the AMQP connection. The queue and dead-letter exchange must already exist.
      </Alert>
      {notice && <Alert severity={notice.severity} onClose={() => setNotice(null)} sx={{ mb: 2 }}>{notice.text}</Alert>}

      <Stack spacing={2}>
        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
          <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" spacing={2} sx={{ mb: 2 }}>
            <Box>
              <Typography variant="subtitle1">Runtime status</Typography>
              <Typography variant="body2" color="text.secondary">
                Desired state: {connector.enabled ? "Enabled" : "Disabled"}
                {connector.lastTransitionAt && ` · last transition ${formatTimestamp(connector.lastTransitionAt, preferences.timeDisplay)}`}
              </Typography>
            </Box>
            <Chip label={connector.runtimeState} color={stateColor[connector.runtimeState]} icon={connector.runtimeState === "RUNNING" ? <CheckCircleOutlineIcon /> : undefined} />
          </Stack>
          {connector.errorCategory && (
            <Alert severity={connector.runtimeState === "ERROR" ? "error" : "warning"} sx={{ mb: 2 }}>
              Operational category: <strong>{connector.errorCategory}</strong>. Repair the saved settings, test the current version, then enable again.
            </Alert>
          )}
          <Divider sx={{ mb: 2 }} />
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1} flexWrap="wrap" useFlexGap>
            <Button variant="outlined" startIcon={<SaveIcon />} onClick={() => saveMutation.mutate()} disabled={busy || editingLocked || !form.apiKeyId}>
              {saveMutation.isPending ? "Saving…" : "Save draft"}
            </Button>
            <Button variant="outlined" startIcon={<ScienceIcon />} onClick={() => testMutation.mutate()} disabled={busy || !connector.configured || connector.enabled}>
              {testMutation.isPending ? "Testing…" : "Test connection"}
            </Button>
            {!connector.enabled ? (
              <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={() => enableMutation.mutate()} disabled={busy || !currentTest}>
                {enableMutation.isPending ? "Enabling…" : "Enable consumption"}
              </Button>
            ) : (
              <Button variant="contained" color="warning" startIcon={<StopIcon />} onClick={disable} disabled={busy}>
                {disableMutation.isPending ? "Disabling…" : "Disable consumption"}
              </Button>
            )}
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1.5 }}>
            Configuration version {connector.configurationVersion}. {currentTest ? "The current version has a successful test." : "Enable requires a successful test of the current saved version."}
          </Typography>
        </Paper>

        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
          <Typography variant="subtitle1" sx={{ mb: 0.5 }}>Connection and queue</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Use the backend network address. For example, localhost refers to the Tengen container when deployed with Docker.
          </Typography>
          <Stack spacing={2}>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField label="Display name" value={form.displayName} onChange={(event) => setValue("displayName", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Host" value={form.host} onChange={(event) => setValue("host", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Port" type="number" value={form.port} onChange={(event) => setValue("port", Number(event.target.value))} disabled={editingLocked} required sx={{ minWidth: { md: 130 } }} />
            </Stack>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField label="Virtual host" value={form.virtualHost} onChange={(event) => setValue("virtualHost", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Username" value={form.username} onChange={(event) => setValue("username", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Password" type="password" value={form.password} onChange={(event) => setValue("password", event.target.value)} disabled={editingLocked} fullWidth helperText={connector.passwordConfigured ? "Leave blank to retain the saved password." : "Write-only; it is never returned after saving."} />
            </Stack>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField label="Existing input queue" value={form.queueName} onChange={(event) => setValue("queueName", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Dead-letter exchange" value={form.deadLetterExchange} onChange={(event) => setValue("deadLetterExchange", event.target.value)} disabled={editingLocked} required fullWidth />
              <TextField label="Dead-letter routing key" value={form.deadLetterRoutingKey} onChange={(event) => setValue("deadLetterRoutingKey", event.target.value)} disabled={editingLocked} required fullWidth />
            </Stack>
            <FormControl disabled={editingLocked} required fullWidth error={form.apiKeyId === ""}>
              <InputLabel id="rabbitmq-api-key-label">Ingestion API key</InputLabel>
              <Select labelId="rabbitmq-api-key-label" label="Ingestion API key" value={String(form.apiKeyId)} onChange={(event) => setValue("apiKeyId", event.target.value ? Number(event.target.value) : "")}>
                {keysQuery.data?.map((key) => (
                  <MenuItem key={key.id} value={key.id}>
                    {key.name} · {key.prefix} · {key.active ? "Active" : "Revoked"}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText>Select an active key whose event type/source policy should apply to broker messages.</FormHelperText>
            </FormControl>
            {selectedKey && (!selectedKey.active || (selectedKey.expiresAt && new Date(selectedKey.expiresAt).getTime() <= Date.now())) && (
              <Alert severity="warning">The selected API key is inactive or expired. Enable will remain blocked until you choose another key.</Alert>
            )}
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField label="Maximum body bytes" type="number" value={form.maxBodyBytes} onChange={(event) => setValue("maxBodyBytes", Number(event.target.value))} disabled={editingLocked} required fullWidth />
              <TextField label="Retry attempts" type="number" value={form.retryAttempts} onChange={(event) => setValue("retryAttempts", Number(event.target.value))} disabled={editingLocked} required fullWidth />
              <TextField label="Retry initial delay (ms)" type="number" value={form.retryInitialDelayMs} onChange={(event) => setValue("retryInitialDelayMs", Number(event.target.value))} disabled={editingLocked} required fullWidth />
            </Stack>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              <TextField label="Retry multiplier" type="number" inputProps={{ step: 0.1, min: 1 }} value={form.retryMultiplier} onChange={(event) => setValue("retryMultiplier", Number(event.target.value))} disabled={editingLocked} required fullWidth />
              <TextField label="Retry maximum delay (ms)" type="number" value={form.retryMaxDelayMs} onChange={(event) => setValue("retryMaxDelayMs", Number(event.target.value))} disabled={editingLocked} required fullWidth />
              <FormControl fullWidth disabled={editingLocked}>
                <InputLabel id="rabbitmq-tls-label">TLS mode</InputLabel>
                <Select labelId="rabbitmq-tls-label" label="TLS mode" value={form.tlsEnabled ? "tls" : "plain"} onChange={(event) => {
                  const enabled = event.target.value === "tls";
                  setValue("tlsEnabled", enabled);
                  if (!connector.configured && form.port === (enabled ? 5672 : 5671)) setValue("port", enabled ? 5671 : 5672);
                }}>
                  <MenuItem value="plain">Plain AMQP (5672)</MenuItem>
                  <MenuItem value="tls">TLS AMQPS (5671)</MenuItem>
                </Select>
              </FormControl>
            </Stack>
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>Last connection test</Typography>
          {connector.lastTestedAt ? (
            <Stack spacing={0.5}>
              <Typography variant="body2">
                {connector.lastTestSucceeded ? "Successful" : "Failed"} · {formatTimestamp(connector.lastTestedAt, preferences.timeDisplay)} · version {connector.lastTestedVersion ?? "not recorded"}
              </Typography>
              {connector.lastTestErrorCategory && <Typography variant="body2" color="error">Category: {connector.lastTestErrorCategory}</Typography>}
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary">No connection test has been recorded for this connector.</Typography>
          )}
        </Paper>
      </Stack>
    </Container>
  );
}
