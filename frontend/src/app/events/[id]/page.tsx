"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  Divider,
  Link as MuiLink,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import DeliveryIcon from "@mui/icons-material/LocalShipping";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import {
  EventHistoryDetail,
  EventRuleActionOutcome,
  WebhookDeliveryStatus,
} from "@/lib/types";

export default function EventDetailPage() {
  const params = useParams<{ id: string }>();
  const eventId = Array.isArray(params.id) ? params.id[0] : params.id;
  const eventQuery = useQuery<EventHistoryDetail>({
    queryKey: ["event-history", eventId],
    queryFn: async () => (await api.get(`/event-history/${eventId}`)).data,
    enabled: Boolean(eventId),
  });

  if (eventQuery.isLoading) {
    return <Container maxWidth={false} sx={{ py: 4 }}><Typography>Loading event…</Typography></Container>;
  }
  if (eventQuery.error) {
    return (
      <Container maxWidth={false} sx={{ py: 4 }}>
        <Alert severity="error" sx={{ mb: 2 }}>{errorMessage(eventQuery.error)}</Alert>
        <Button component={Link} href="/events" startIcon={<ArrowBackIcon />}>Back to events</Button>
      </Container>
    );
  }
  const detail = eventQuery.data;
  if (!detail) return null;

  const summary = detail.event;
  const payload = {
    type: summary.type,
    source: summary.source,
    timestamp: summary.occurredAt,
    data: detail.data,
  };

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Button component={Link} href="/events" startIcon={<ArrowBackIcon />} sx={{ mb: 2 }}>
        Back to events
      </Button>
      <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" spacing={2} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5">Event {summary.id}</Typography>
          <Typography variant="body2" color="text.secondary">
            {summary.type} from {summary.source} · received {formatDate(summary.receivedAt)}
          </Typography>
        </Box>
        {detail.deliveries.length > 0 && (
          <Button component={Link} href={`/deliveries?eventId=${summary.id}`} variant="outlined" startIcon={<DeliveryIcon />}>
            View deliveries
          </Button>
        )}
      </Stack>

      {!summary.traceAvailable && (
        <Alert severity="info" sx={{ mb: 2 }}>
          This event predates Event Explorer trace capture. Its payload is available, but matched-rule and action outcomes cannot be reconstructed.
        </Alert>
      )}

      <Stack spacing={2}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>Event payload</Typography>
          <Box component="pre" sx={{ m: 0, p: 2, bgcolor: "grey.100", borderRadius: 1, overflow: "auto", maxHeight: 360, fontSize: 13 }}>
            {JSON.stringify(payload, null, 2)}
          </Box>
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="subtitle1">Matched rule outcomes</Typography>
            {summary.traceAvailable && <Chip label={`${detail.rules.length} matched`} size="small" color={detail.rules.length > 0 ? "success" : "default"} />}
          </Stack>
          {!detail.rules.length ? (
            <Typography variant="body2" color="text.secondary">
              {summary.traceAvailable ? "No rules matched this event." : "No trace records are available."}
            </Typography>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Rule</TableCell>
                  <TableCell>Pattern</TableCell>
                  <TableCell>Action</TableCell>
                  <TableCell>Details</TableCell>
                  <TableCell>Delivery</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {detail.rules.map((rule) => (
                  <TableRow key={rule.id}>
                    <TableCell>
                      <Typography variant="body2">{rule.ruleName}</Typography>
                      <Typography variant="caption" color="text.secondary">Revision {rule.ruleRevision} · #{rule.ruleId}</Typography>
                    </TableCell>
                    <TableCell>{rule.ruleType}</TableCell>
                    <TableCell><ActionChip outcome={rule.actionOutcome} /></TableCell>
                    <TableCell><OutcomeDetails rule={rule} /></TableCell>
                    <TableCell>
                      {rule.deliveryId ? (
                        <MuiLink component={Link} href={`/deliveries?eventId=${summary.id}`}>
                          Delivery #{rule.deliveryId}
                        </MuiLink>
                      ) : rule.suppressionReason ? (
                        <Typography variant="caption">{formatReason(rule.suppressionReason)}</Typography>
                      ) : "—"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>Webhook deliveries</Typography>
          {!detail.deliveries.length ? (
            <Typography variant="body2" color="text.secondary">No webhook deliveries are associated with this event.</Typography>
          ) : (
            <Stack divider={<Divider flexItem />}>
              {detail.deliveries.map((delivery) => (
                <Stack key={delivery.id} direction={{ xs: "column", md: "row" }} justifyContent="space-between" spacing={1} sx={{ py: 1 }}>
                  <Box>
                    <MuiLink component={Link} href={`/deliveries?eventId=${summary.id}`}>Delivery #{delivery.id}</MuiLink>
                    <Typography variant="body2">{delivery.ruleName} · event {delivery.eventId} · {delivery.destination}</Typography>
                  </Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <DeliveryStatusChip status={delivery.status} />
                    <Typography variant="caption">{delivery.attemptCount} attempt(s)</Typography>
                  </Stack>
                </Stack>
              ))}
            </Stack>
          )}
        </Paper>
      </Stack>
    </Container>
  );
}

function ActionChip({ outcome }: { outcome: EventRuleActionOutcome }) {
  const color = outcome === "LOG_ONLY" ? "default" : outcome === "WEBHOOK_QUEUED" ? "info" : "warning";
  return <Chip label={outcome.replaceAll("_", " ")} color={color} size="small" />;
}

function OutcomeDetails({ rule }: { rule: EventHistoryDetail["rules"][number] }) {
  if (rule.aggregate) {
    return (
      <Stack>
        <Typography variant="body2">{rule.aggregate.function}: {rule.aggregate.value} / threshold {rule.aggregate.threshold}</Typography>
        <Typography variant="caption" color="text.secondary">Window {rule.aggregate.windowSeconds}s · group {rule.groupKey ?? "global"}</Typography>
      </Stack>
    );
  }
  if (rule.sequence) {
    return <Typography variant="body2">Sequence completed ({rule.sequence.steps.length} steps) · group {rule.groupKey ?? "global"}</Typography>;
  }
  return <Typography variant="body2">Condition matched{rule.groupKey ? ` · group ${rule.groupKey}` : ""}</Typography>;
}

function DeliveryStatusChip({ status }: { status: WebhookDeliveryStatus }) {
  const color = status === "DELIVERED" ? "success" : status === "DEAD_LETTER" ? "error" : status === "PROCESSING" ? "info" : "warning";
  return <Chip label={status.replaceAll("_", " ")} color={color} size="small" />;
}

function formatReason(reason: string) {
  return reason.replaceAll("_", " ").toLowerCase();
}

function formatDate(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : "—";
}
