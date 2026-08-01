"use client";

import { Box, Breadcrumbs, CircularProgress, Container, Link, Typography } from "@mui/material";
import NextLink from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RuleForm } from "@/components/RuleForm";
import { api } from "@/lib/api";
import { Rule, RuleRequest } from "@/lib/types";
import { useState } from "react";

export default function EditRulePage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const { data: rule, isLoading } = useQuery<Rule>({
    queryKey: ["rule", id],
    queryFn: async () => (await api.get(`/rules/${id}`)).data,
  });

  const mutation = useMutation({
    mutationFn: (request: RuleRequest) => api.put(`/rules/${id}`, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["rules"] });
      router.push("/rules");
    },
  });

  if (isLoading) {
    return (
      <Container maxWidth="md" sx={{ py: 8, display: "flex", justifyContent: "center" }}>
        <CircularProgress />
      </Container>
    );
  }

  if (!rule) {
    return (
      <Container maxWidth="md" sx={{ py: 4 }}>
        <Typography color="error">Rule not found</Typography>
      </Container>
    );
  }

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Breadcrumbs sx={{ mb: 2 }}>
        <Link component={NextLink} href="/rules" underline="hover" color="inherit">
          Rules
        </Link>
        <Typography color="text.primary">{rule.name}</Typography>
      </Breadcrumbs>
      <Typography variant="h5" sx={{ mb: 3 }}>
        Edit Rule
      </Typography>
      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error}
        </Typography>
      )}
      <Box>
        <RuleForm
          initial={rule}
          onSubmit={async (request) => {
            try {
              await mutation.mutateAsync(request);
            } catch (err) {
              setError(err instanceof Error ? err.message : "Could not update rule");
              throw err;
            }
          }}
          submitting={mutation.isPending}
        />
      </Box>
    </Container>
  );
}
