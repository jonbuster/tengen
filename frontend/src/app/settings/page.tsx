"use client";

import {
  Alert,
  Box,
  Button,
  Container,
  Divider,
  FormControl,
  FormControlLabel,
  FormLabel,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import SettingsIcon from "@mui/icons-material/Settings";
import { useState } from "react";
import { formatTimestamp } from "@/lib/formatters";
import { usePreferences } from "@/lib/preferences";
import { ACCENT_OPTIONS } from "@/theme";

export default function SettingsPage() {
  const {
    preferences,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  } = usePreferences();
  const [resetNotice, setResetNotice] = useState(false);

  const handleReset = () => {
    resetPreferences();
    setResetNotice(true);
  };

  return (
    <Container maxWidth="md" sx={{ py: 4 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 3 }}>
        <SettingsIcon color="primary" />
        <Box>
          <Typography variant="h5">Settings</Typography>
          <Typography variant="body2" color="text.secondary">
            Customize this console for your browser.
          </Typography>
        </Box>
      </Stack>

      {resetNotice && (
        <Alert severity="success" onClose={() => setResetNotice(false)} sx={{ mb: 2 }}>
          Preferences reset to their defaults.
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
        <Stack spacing={3} divider={<Divider flexItem />}>
          <FormControl component="fieldset">
            <FormLabel component="legend">Theme</FormLabel>
            <ToggleButtonGroup
              exclusive
              value={preferences.themeMode}
              onChange={(_event, value) => {
                if (value) setThemeMode(value);
              }}
              aria-label="Theme mode"
              sx={{ mt: 1, alignSelf: "flex-start" }}
            >
              <ToggleButton value="light" aria-label="Light theme">Light</ToggleButton>
              <ToggleButton value="dark" aria-label="Dark theme">Dark</ToggleButton>
              <ToggleButton value="system" aria-label="System theme">System</ToggleButton>
            </ToggleButtonGroup>
          </FormControl>

          <FormControl component="fieldset">
            <FormLabel component="legend">Accent color</FormLabel>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              This updates primary controls and the navigation sidebar.
            </Typography>
            <Stack
              direction="row"
              spacing={1.5}
              role="group"
              aria-label="Accent color"
              sx={{ mt: 1.5, flexWrap: "wrap", rowGap: 1.5 }}
            >
              {ACCENT_OPTIONS.map((option) => {
                const selected = preferences.accentColor === option.key;
                return (
                  <Box
                    key={option.key}
                    component="button"
                    type="button"
                    onClick={() => setAccentColor(option.key)}
                    aria-label={`${option.label} accent`}
                    aria-pressed={selected}
                    sx={{
                      width: 42,
                      height: 42,
                      p: 0,
                      borderRadius: "50%",
                      border: "2px solid",
                      borderColor: selected ? "text.primary" : "transparent",
                      backgroundColor: "transparent",
                      cursor: "pointer",
                      display: "grid",
                      placeItems: "center",
                      outlineOffset: 3,
                    }}
                  >
                    <Box
                      aria-hidden="true"
                      sx={{
                        width: 30,
                        height: 30,
                        borderRadius: "50%",
                        bgcolor: option.main,
                        boxShadow: selected ? 2 : 0,
                      }}
                    />
                  </Box>
                );
              })}
            </Stack>
          </FormControl>

          <FormControl component="fieldset">
            <FormLabel component="legend">Time display</FormLabel>
            <RadioGroup
              row
              value={preferences.timeDisplay}
              onChange={(event) => setTimeDisplay(event.target.value as "local" | "utc")}
              aria-label="Time display"
              sx={{ mt: 0.5 }}
            >
              <FormControlLabel value="local" control={<Radio />} label="Local time" />
              <FormControlLabel value="utc" control={<Radio />} label="UTC" />
            </RadioGroup>
            <Typography variant="body2" color="text.secondary">
              Preview: {formatTimestamp(new Date(), preferences.timeDisplay)}
            </Typography>
          </FormControl>

          <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" spacing={2}>
            <Box>
              <Typography variant="subtitle1">Reset preferences</Typography>
              <Typography variant="body2" color="text.secondary">
                Restore the light theme, blue accent, and local time defaults.
              </Typography>
            </Box>
            <Button variant="outlined" onClick={handleReset} sx={{ alignSelf: { xs: "flex-start", sm: "center" } }}>
              Reset to defaults
            </Button>
          </Stack>
        </Stack>
      </Paper>
    </Container>
  );
}
