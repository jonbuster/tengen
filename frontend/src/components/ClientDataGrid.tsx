"use client";

import dynamic from "next/dynamic";
import type { DataGridProps, GridValidRowModel } from "@mui/x-data-grid";
import type { JSX } from "react";

/**
 * MUI X DataGrid initializes an internal store during its first render. Keep
 * that browser-only initialization out of Next's server render to avoid the
 * React 19 pre-mount state-update warning.
 */
const DataGrid = dynamic(
  () => import("@mui/x-data-grid").then((module) => module.DataGrid),
  { ssr: false },
);

type ClientDataGridComponent = <R extends GridValidRowModel = GridValidRowModel>(
  props: DataGridProps<R>,
) => JSX.Element;

export const ClientDataGrid = DataGrid as unknown as ClientDataGridComponent;
