"use client";

import {
  Box,
  Button,
  Chip,
  Container,
  Stack,
  Typography,
} from "@mui/material";
import EditIcon from "@mui/icons-material/Edit";
import DeleteIcon from "@mui/icons-material/Delete";
import ToggleOnIcon from "@mui/icons-material/ToggleOn";
import { DataGrid, GridActionsCellItem, GridColDef, GridRowParams } from "@mui/x-data-grid";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, errorMessage } from "@/lib/api";
import { Rule } from "@/lib/types";
import { useState } from "react";

export default function RulesPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const { data: rules = [], isLoading } = useQuery<Rule[]>({
    queryKey: ["rules"],
    queryFn: async () => (await api.get("/rules")).data,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["rules"] });

  const toggleMutation = useMutation({
    mutationFn: (id: number) => api.patch(`/rules/${id}/toggle`),
    onSuccess: invalidate,
    onError: (err) => setError(errorMessage(err)),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.delete(`/rules/${id}`),
    onSuccess: invalidate,
    onError: (err) => setError(errorMessage(err)),
  });

  const columns: GridColDef[] = [
    { field: "id", headerName: "ID", width: 70 },
    { field: "name", headerName: "Name", flex: 1 },
    { field: "ruleType", headerName: "Type", width: 120 },
    { field: "action", headerName: "Action", width: 110 },
    {
      field: "active",
      headerName: "Status",
      width: 110,
      renderCell: (params) => <Chip label={params.value ? "Active" : "Inactive"} color={params.value ? "success" : "default"} size="small" />,
    },
    {
      field: "actions",
      type: "actions",
      headerName: "Actions",
      width: 150,
      getActions: (params: GridRowParams<Rule>) => [
        <GridActionsCellItem
          key="edit"
          icon={<EditIcon />}
          label="Edit"
          showInMenu={false}
          onClick={() => router.push(`/rules/${params.id}/edit`)}
        />,
        <GridActionsCellItem
          key="toggle"
          icon={<ToggleOnIcon />}
          label={params.row.active ? "Deactivate" : "Activate"}
          showInMenu={false}
          onClick={() => toggleMutation.mutate(params.row.id)}
        />,
        <GridActionsCellItem
          key="delete"
          icon={<DeleteIcon />}
          label="Delete"
          showInMenu={false}
          onClick={() => deleteMutation.mutate(params.row.id)}
        />,
      ],
    },
  ];

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
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

      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error}
        </Typography>
      )}

      <Box sx={{ height: 520 }}>
        <DataGrid
          rows={rules}
          columns={columns}
          loading={isLoading}
          disableRowSelectionOnClick
          getRowId={(row: Rule) => row.id}
        />
      </Box>
    </Container>
  );
}
