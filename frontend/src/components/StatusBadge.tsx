"use client";

import { Chip } from "@mui/material";

export function StatusBadge({ active }: { active: boolean }) {
  return (
    <Chip
      label={active ? "Active" : "Inactive"}
      color={active ? "success" : "default"}
      size="small"
    />
  );
}
