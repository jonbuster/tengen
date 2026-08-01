"use client";

import { Box, Breadcrumbs, Container, Link, Typography } from "@mui/material";
import NextLink from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { RuleForm } from "@/components/RuleForm";
import { api } from "@/lib/api";
import { RuleRequest } from "@/lib/types";
import { useState } from "react";

export default function NewRulePage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (request: RuleRequest) => api.post("/rules", request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["rules"] });
      router.push("/rules");
    },
  });

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Breadcrumbs sx={{ mb: 2 }}>
        <Link component={NextLink} href="/rules" underline="hover" color="inherit">
          Rules
        </Link>
        <Typography color="text.primary">New Rule</Typography>
      </Breadcrumbs>
      <Typography variant="h5" sx={{ mb: 3 }}>
        New Rule
      </Typography>
      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error}
        </Typography>
      )}
      <Box>
        <RuleForm
          onSubmit={async (request) => {
            try {
              await mutation.mutateAsync(request);
            } catch (err) {
              setError(err instanceof Error ? err.message : "Could not create rule");
              throw err;
            }
          }}
          submitting={mutation.isPending}
        />
      </Box>
    </Container>
  );
}
