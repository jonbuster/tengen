"use client";

import {
  Box,
  Button,
  Checkbox,
  Chip,
  Container,
  FormControlLabel,
  Stack,
  Typography,
} from "@mui/material";
import ArchiveIcon from "@mui/icons-material/Archive";
import EditIcon from "@mui/icons-material/Edit";
import HistoryIcon from "@mui/icons-material/History";
import RestoreIcon from "@mui/icons-material/Restore";
import ToggleOnIcon from "@mui/icons-material/ToggleOn";
import { DataGrid, GridActionsCellItem, GridColDef, GridRowParams } from "@mui/x-data-grid";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, errorMessage } from "@/lib/api";
import { Rule } from "@/lib/types";
import { useMemo, useState } from "react";

const EMPTY_RULES: Rule[] = [];

const getRuleId = (row: Rule) => row.id;

function revisionHeaders(rule: Rule) {
  return { headers: { "If-Match": `"${rule.revision}"` } };
}

export default function RulesPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [includeArchived, setIncludeArchived] = useState(false);

  const { data: rules = EMPTY_RULES, isLoading } = useQuery<Rule[]>({
    queryKey: ["rules", includeArchived],
    queryFn: async () =>
      (await api.get(`/rules?includeArchived=${includeArchived}`)).data,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["rules"] });
  };

  const toggleMutation = useMutation({
    mutationFn: (rule: Rule) => api.patch(`/rules/${rule.id}/toggle`, undefined, revisionHeaders(rule)),
    onSuccess: invalidate,
    onError: (err) => setError(errorMessage(err)),
  });

  const archiveMutation = useMutation({
    mutationFn: (rule: Rule) => api.delete(`/rules/${rule.id}`, revisionHeaders(rule)),
    onSuccess: invalidate,
    onError: (err) => setError(errorMessage(err)),
  });

  const unarchiveMutation = useMutation({
    mutationFn: (rule: Rule) => api.post(`/rules/${rule.id}/unarchive`, undefined, revisionHeaders(rule)),
    onSuccess: invalidate,
    onError: (err) => setError(errorMessage(err)),
  });
  const toggleRule = toggleMutation.mutate;
  const archiveRule = archiveMutation.mutate;
  const unarchiveRule = unarchiveMutation.mutate;

  const columns = useMemo<GridColDef[]>(() => [
    { field: "id", headerName: "ID", width: 70 },
    { field: "name", headerName: "Name", flex: 1, minWidth: 180 },
    { field: "revision", headerName: "Revision", width: 95 },
    {
      field: "ruleType",
      headerName: "Type",
      width: 150,
      renderCell: (params) => params.row.ruleType === "SEQUENCE"
        ? `SEQUENCE (${params.row.sequenceSteps?.length ?? 0})`
        : params.value,
    },
    {
      field: "sequenceSummary",
      headerName: "Pattern",
      flex: 1,
      minWidth: 180,
      valueGetter: (_value, row: Rule) => row.ruleType === "SEQUENCE"
        ? (row.sequenceSteps ?? []).map((step) => step.eventType).join(" → ")
        : `${row.eventType ?? ""} / ${row.source ?? ""}`,
    },
    { field: "action", headerName: "Action", width: 110 },
    {
      field: "active",
      headerName: "Status",
      width: 120,
      renderCell: (params) => {
        const archived = Boolean(params.row.archivedAt);
        const invalid = params.row.validationStatus === "INVALID";
        return (
          <Chip
            label={invalid ? "Invalid" : archived ? "Archived" : params.value ? "Active" : "Inactive"}
            color={invalid ? "error" : archived ? "warning" : params.value ? "success" : "default"}
            size="small"
            title={params.row.validationError ?? undefined}
          />
        );
      },
    },
    {
      field: "actions",
      type: "actions",
      headerName: "Actions",
      width: 220,
      getActions: (params: GridRowParams<Rule>) => {
        const rule = params.row;
        const actions = [
          <GridActionsCellItem
            key="edit"
            icon={<EditIcon />}
            label="Edit"
            onClick={() => router.push(`/rules/${rule.id}/edit`)}
          />,
          <GridActionsCellItem
            key="history"
            icon={<HistoryIcon />}
            label="History"
            onClick={() => router.push(`/rules/${rule.id}/history`)}
          />,
        ];
        if (rule.archivedAt) {
          actions.push(
            <GridActionsCellItem
              key="unarchive"
              icon={<RestoreIcon />}
              label="Unarchive"
              onClick={() => unarchiveRule(rule)}
            />,
          );
        } else {
          actions.push(
            <GridActionsCellItem
              key="toggle"
              icon={<ToggleOnIcon />}
              label={rule.active ? "Deactivate" : "Activate"}
              onClick={() => toggleRule(rule)}
            />,
            <GridActionsCellItem
              key="archive"
              icon={<ArchiveIcon />}
              label="Archive"
              onClick={() => {
                if (window.confirm(`Archive rule "${rule.name}"?`)) {
                  archiveRule(rule);
                }
              }}
            />,
          );
        }
        return actions;
      },
    },
  ], [archiveRule, router, toggleRule, unarchiveRule]);

  return (
    <Container maxWidth={false} sx={{ py: 4 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h5">Rules</Typography>
        <Stack direction="row" gap={1}>
          <Button component={Link} href="/rules/test" variant="outlined">
            Run Test
          </Button>
          <Button component={Link} href="/rules/new" variant="contained">
            New Rule
          </Button>
        </Stack>
      </Stack>

      <FormControlLabel
        control={
          <Checkbox
            checked={includeArchived}
            onChange={(event) => setIncludeArchived(event.target.checked)}
          />
        }
        label="Show archived"
        sx={{ mb: 1 }}
      />

      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error}
        </Typography>
      )}

      <Box sx={{ height: 560 }}>
        <DataGrid
          rows={rules}
          columns={columns}
          loading={isLoading}
          disableRowSelectionOnClick
          getRowId={getRuleId}
        />
      </Box>
    </Container>
  );
}
