"use client";

import { CssBaseline, ThemeProvider, createTheme, darken } from "@mui/material";
import { useMemo } from "react";
import { AccentKey, usePreferences } from "@/lib/preferences";

export const ACCENT_OPTIONS: ReadonlyArray<{ key: AccentKey; label: string; main: string }> = [
  { key: "blue", label: "Blue", main: "#1976d2" },
  { key: "indigo", label: "Indigo", main: "#3f51b5" },
  { key: "purple", label: "Purple", main: "#7b1fa2" },
  { key: "teal", label: "Teal", main: "#00796b" },
  { key: "green", label: "Green", main: "#2e7d32" },
  { key: "orange", label: "Orange", main: "#ed6c02" },
];

export function accentMainColor(accentColor: AccentKey) {
  return ACCENT_OPTIONS.find((option) => option.key === accentColor)?.main ?? ACCENT_OPTIONS[0].main;
}

export function createAppTheme(
  mode: "light" | "dark",
  accentColor: AccentKey,
) {
  const primary = accentMainColor(accentColor);
  return createTheme({
    palette: {
      mode,
      primary: { main: primary },
      secondary: { main: "#9c27b0" },
      background: {
        default: mode === "light" ? "#eceff1" : "#121212",
        paper: mode === "light" ? "#ffffff" : "#1e1e1e",
      },
    },
    typography: {
      h6: { fontWeight: 600 },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          "*": { scrollbarColor: `${darken(primary, 0.25)} transparent` },
        },
      },
    },
  });
}

export function AppThemeProvider({ children }: { children: React.ReactNode }) {
  const { preferences, resolvedThemeMode } = usePreferences();
  const theme = useMemo(
    () => createAppTheme(resolvedThemeMode, preferences.accentColor),
    [resolvedThemeMode, preferences.accentColor],
  );

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {children}
    </ThemeProvider>
  );
}
