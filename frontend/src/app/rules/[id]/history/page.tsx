"use client";

import {
  Alert,
  Box,
  Breadcrumbs,
  Button,
  CircularProgress,
  Container,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import RestoreIcon from "@mui/icons-material/Restore";
import NextLink from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import { Rule, RuleRevisionDetail, RuleRevisionPage } from "@/lib/types";

export default function RuleHistoryPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const router = useRouter();
  const { preferences } = usePreferences();
  const queryClient = useQueryClient();
  const [selectedRevision, setSelectedRevision] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: rule, isLoading: ruleLoading } = useQuery<Rule>({
    queryKey: ["rule", id],
    queryFn: async () => (await api.get(`/rules/${id}`)).data,
  });

  const { data: history, isLoading: historyLoading } = useQuery<RuleRevisionPage>({
    queryKey: ["rule-revisions", id],
    queryFn: async () => (await api.get(`/rules/${id}/revisions?size=100`)).data,
  });

  useEffect(() => {
    if (selectedRevision === null && history?.content.length) {
      setSelectedRevision(history.content[0].revision);
    }
  }, [history, selectedRevision]);

  const { data: detail, isLoading: detailLoading } = useQuery<RuleRevisionDetail>({
    queryKey: ["rule-revision", id, selectedRevision],
    queryFn: async () => (await api.get(`/rules/${id}/revisions/${selectedRevision}`)).data,
    enabled: selectedRevision !== null,
  });

  const restoreMutation = useMutation({
    mutationFn: () =>
      api.post(`/rules/${id}/revisions/${selectedRevision}/restore`, undefined, {
        headers: { "If-Match": `"${rule?.revision ?? 0}"` },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["rules"] });
      queryClient.invalidateQueries({ queryKey: ["rule", id] });
      queryClient.invalidateQueries({ queryKey: ["rule-revisions", id] });
      router.push(`/rules/${id}/edit`);
    },
    onError: (err) => setError(errorMessage(err)),
  });

  if (ruleLoading || historyLoading) {
    return (
      <Container maxWidth={false} sx={{ py: 8, display: "flex", justifyContent: "center" }}>
        <CircularProgress />
      </Container>
    );
  }

  if (!rule || !history) {
    return (
      <Container maxWidth={false} sx={{ py: 4 }}>
        <Alert severity="error">Rule history could not be loaded.</Alert>
      </Container>
    );
  }

  const selected = history.content.find((item) => item.revision === selectedRevision);

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Breadcrumbs sx={{ mb: 2 }}>
        <Typography component={NextLink} href="/rules" color="inherit" sx={{ textDecoration: "none" }}>
          Rules
        </Typography>
        <Typography component={NextLink} href={`/rules/${id}/edit`} color="inherit" sx={{ textDecoration: "none" }}>
          {rule.name}
        </Typography>
        <Typography color="text.primary">History</Typography>
      </Breadcrumbs>

      <Stack direction={{ xs: "column", md: "row" }} spacing={3} alignItems="stretch">
        <Paper sx={{ width: { xs: "100%", md: 360 }, maxHeight: 620, overflow: "auto" }}>
          <Typography variant="h6" sx={{ p: 2 }}>
            Revision history
          </Typography>
          <Divider />
          <List disablePadding>
            {history.content.map((item) => (
              <ListItemButton
                key={item.revision}
                selected={item.revision === selectedRevision}
                onClick={() => setSelectedRevision(item.revision)}
              >
                <ListItemText
                  primary={`Revision ${item.revision} · ${item.changeType}`}
                  secondary={`${item.actor} · ${formatTimestamp(item.changedAt, preferences.timeDisplay)}`}
                />
              </ListItemButton>
            ))}
          </List>
        </Paper>

        <Paper sx={{ flex: 1, p: 3, minHeight: 420 }}>
          {detailLoading || !detail ? (
            <Box sx={{ display: "flex", justifyContent: "center", py: 8 }}>
              <CircularProgress />
            </Box>
          ) : (
            <>
              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" gap={2}>
                <Box>
                  <Typography variant="h6">
                    Revision {detail.revision.revision}
                  </Typography>
                  <Typography color="text.secondary">
                    {detail.revision.changeType} by {detail.revision.actor} on {formatTimestamp(detail.revision.changedAt, preferences.timeDisplay)}
                  </Typography>
                </Box>
                {selected && selected.revision !== rule.revision && (
                  <Button
                    variant="contained"
                    startIcon={<RestoreIcon />}
                    onClick={() => {
                      if (window.confirm(`Restore revision ${selected.revision}? It will create a new revision and remain inactive.`)) {
                        restoreMutation.mutate();
                      }
                    }}
                    disabled={restoreMutation.isPending}
                  >
                    Restore this revision
                  </Button>
                )}
              </Stack>
              {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
              <Typography variant="subtitle1" sx={{ mt: 3, mb: 1 }}>
                Snapshot
              </Typography>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 2,
                  bgcolor: "action.hover",
                  borderRadius: 1,
                  overflow: "auto",
                  fontSize: 13,
                }}
              >
                {JSON.stringify(detail.snapshot, null, 2)}
              </Box>
            </>
          )}
        </Paper>
      </Stack>
    </Container>
  );
}
