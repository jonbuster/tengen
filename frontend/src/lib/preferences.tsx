"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";

export const PREFERENCES_STORAGE_KEY = "tengen-ui-preferences";
export const PREFERENCES_VERSION = 1 as const;

export type ThemeMode = "light" | "dark" | "system";
export type AccentKey = "blue" | "indigo" | "purple" | "teal" | "green" | "orange";
export type TimeDisplay = "local" | "utc";

export interface AppPreferences {
  version: typeof PREFERENCES_VERSION;
  themeMode: ThemeMode;
  accentColor: AccentKey;
  timeDisplay: TimeDisplay;
}

export const DEFAULT_PREFERENCES: AppPreferences = {
  version: PREFERENCES_VERSION,
  themeMode: "light",
  accentColor: "blue",
  timeDisplay: "local",
};

interface PreferencesContextValue {
  preferences: AppPreferences;
  resolvedThemeMode: Exclude<ThemeMode, "system">;
  setThemeMode: (themeMode: ThemeMode) => void;
  setAccentColor: (accentColor: AccentKey) => void;
  setTimeDisplay: (timeDisplay: TimeDisplay) => void;
  resetPreferences: () => void;
}

const PreferencesContext = createContext<PreferencesContextValue | null>(null);

function isThemeMode(value: unknown): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function isAccentKey(value: unknown): value is AccentKey {
  return value === "blue" || value === "indigo" || value === "purple"
    || value === "teal" || value === "green" || value === "orange";
}

function isTimeDisplay(value: unknown): value is TimeDisplay {
  return value === "local" || value === "utc";
}

export function parsePreferences(raw: string | null): AppPreferences | null {
  if (!raw) return null;

  try {
    const value = JSON.parse(raw) as Partial<AppPreferences> | null;
    if (!value || value.version !== PREFERENCES_VERSION) return null;
    if (!isThemeMode(value.themeMode) || !isAccentKey(value.accentColor) || !isTimeDisplay(value.timeDisplay)) {
      return null;
    }
    return {
      version: PREFERENCES_VERSION,
      themeMode: value.themeMode,
      accentColor: value.accentColor,
      timeDisplay: value.timeDisplay,
    };
  } catch {
    return null;
  }
}

function savePreferences(preferences: AppPreferences) {
  try {
    window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(preferences));
  } catch {
    // Keep the updated value in memory when storage is unavailable.
  }
}

export function PreferencesProvider({ children }: { children: React.ReactNode }) {
  const [preferences, setPreferences] = useState<AppPreferences>(DEFAULT_PREFERENCES);
  const [prefersDark, setPrefersDark] = useState(false);

  useEffect(() => {
    try {
      const stored = parsePreferences(window.localStorage.getItem(PREFERENCES_STORAGE_KEY));
      if (stored) setPreferences(stored);
    } catch {
      // Keep defaults when storage is unavailable.
    }

    if (typeof window.matchMedia !== "function") return;

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const updateSystemTheme = () => setPrefersDark(mediaQuery.matches);
    updateSystemTheme();
    mediaQuery.addEventListener("change", updateSystemTheme);
    return () => mediaQuery.removeEventListener("change", updateSystemTheme);
  }, []);

  const updatePreferences = useCallback((update: (current: AppPreferences) => AppPreferences) => {
    setPreferences((current) => {
      const next = update(current);
      savePreferences(next);
      return next;
    });
  }, []);

  const setThemeMode = useCallback((themeMode: ThemeMode) => {
    updatePreferences((current) => ({ ...current, themeMode }));
  }, [updatePreferences]);

  const setAccentColor = useCallback((accentColor: AccentKey) => {
    updatePreferences((current) => ({ ...current, accentColor }));
  }, [updatePreferences]);

  const setTimeDisplay = useCallback((timeDisplay: TimeDisplay) => {
    updatePreferences((current) => ({ ...current, timeDisplay }));
  }, [updatePreferences]);

  const resetPreferences = useCallback(() => {
    setPreferences(DEFAULT_PREFERENCES);
    try {
      window.localStorage.removeItem(PREFERENCES_STORAGE_KEY);
    } catch {
      // Keep defaults in memory when storage is unavailable.
    }
  }, []);

  const resolvedThemeMode = preferences.themeMode === "system"
    ? (prefersDark ? "dark" : "light")
    : preferences.themeMode;

  const value = useMemo<PreferencesContextValue>(() => ({
    preferences,
    resolvedThemeMode,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  }), [preferences, resolvedThemeMode, setThemeMode, setAccentColor, setTimeDisplay, resetPreferences]);

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences() {
  const context = useContext(PreferencesContext);
  if (!context) throw new Error("usePreferences must be used within PreferencesProvider");
  return context;
}
