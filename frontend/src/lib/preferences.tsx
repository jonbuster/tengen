"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { api, errorMessage } from "@/lib/api";

export type ThemeMode = "light" | "dark" | "system";
export type AccentKey =
  | "blue"
  | "indigo"
  | "purple"
  | "teal"
  | "green"
  | "orange"
  | "yellow"
  | "red"
  | "pink"
  | "grey"
  | "black"
  | "neon";
export type TimeDisplay = "local" | "utc";

export interface AppPreferences {
  themeMode: ThemeMode;
  accentColor: AccentKey;
  timeDisplay: TimeDisplay;
}

export const DEFAULT_PREFERENCES: AppPreferences = {
  themeMode: "light",
  accentColor: "blue",
  timeDisplay: "local",
};

const PREFERENCES_STORAGE_KEY = "tengen.preferences";

interface PreferencesContextValue {
  preferences: AppPreferences;
  loading: boolean;
  saving: boolean;
  error: string | null;
  resolvedThemeMode: Exclude<ThemeMode, "system">;
  setThemeMode: (themeMode: ThemeMode) => Promise<boolean>;
  setAccentColor: (accentColor: AccentKey) => Promise<boolean>;
  setTimeDisplay: (timeDisplay: TimeDisplay) => Promise<boolean>;
  resetPreferences: () => Promise<boolean>;
}

interface PreferencesProviderProps {
  children: React.ReactNode;
  /** Authentication state is supplied by the root layout after AuthProvider mounts. */
  isAuthenticated?: boolean;
  authChecking?: boolean;
}

const PreferencesContext = createContext<PreferencesContextValue | null>(null);

function isThemeMode(value: unknown): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function isAccentKey(value: unknown): value is AccentKey {
  return value === "blue" || value === "indigo" || value === "purple"
    || value === "teal" || value === "green" || value === "orange"
    || value === "yellow" || value === "red" || value === "pink"
    || value === "grey" || value === "black" || value === "neon";
}

function isTimeDisplay(value: unknown): value is TimeDisplay {
  return value === "local" || value === "utc";
}

export function parsePreferences(value: unknown): AppPreferences | null {
  if (!value || typeof value !== "object") return null;

  const candidate = value as Partial<AppPreferences>;
  if (!isThemeMode(candidate.themeMode)
    || !isAccentKey(candidate.accentColor)
    || !isTimeDisplay(candidate.timeDisplay)) {
    return null;
  }

  return {
    themeMode: candidate.themeMode,
    accentColor: candidate.accentColor,
    timeDisplay: candidate.timeDisplay,
  };
}

function readStoredPreferences(): AppPreferences | null {
  if (typeof window === "undefined") return null;

  try {
    const stored = window.localStorage.getItem(PREFERENCES_STORAGE_KEY);
    return stored ? parsePreferences(JSON.parse(stored)) : null;
  } catch {
    return null;
  }
}

function writeStoredPreferences(preferences: AppPreferences) {
  if (typeof window === "undefined") return;

  try {
    window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(preferences));
  } catch {
    // Local storage is only a cache, so storage failures should not affect settings.
  }
}

export function PreferencesProvider({
  children,
  isAuthenticated = false,
  authChecking = false,
}: PreferencesProviderProps) {
  const [preferences, setPreferences] = useState<AppPreferences>(DEFAULT_PREFERENCES);
  const [prefersDark, setPrefersDark] = useState(false);
  const [loading, setLoading] = useState(authChecking || isAuthenticated);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authChecking) {
      setLoading(true);
      return;
    }

    if (!isAuthenticated) {
      setLoading(false);
      setError(null);
      return;
    }

    let active = true;
    const cached = readStoredPreferences();
    if (cached) setPreferences(cached);
    setLoading(true);
    setError(null);

    api.get<AppPreferences>("/settings")
      .then((response) => {
        const stored = parsePreferences(response.data);
        if (!stored) throw new Error("Backend returned invalid preferences");
        if (active) {
          setPreferences(stored);
          writeStoredPreferences(stored);
        }
      })
      .catch((reason: unknown) => {
        if (active) setError(`Unable to load preferences. ${errorMessage(reason)}`);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [authChecking, isAuthenticated]);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const updateSystemTheme = () => setPrefersDark(mediaQuery.matches);
    updateSystemTheme();
    mediaQuery.addEventListener("change", updateSystemTheme);
    return () => mediaQuery.removeEventListener("change", updateSystemTheme);
  }, []);

  const persistPreferences = useCallback(async (
    next: AppPreferences,
    previous: AppPreferences,
  ) => {
    setPreferences(next);
    setSaving(true);
    setError(null);

    try {
      const response = await api.put<AppPreferences>("/settings", next);
      const saved = parsePreferences(response.data);
      if (!saved) throw new Error("Backend returned invalid preferences");
      setPreferences(saved);
      writeStoredPreferences(saved);
      return true;
    } catch (reason) {
      setPreferences(previous);
      setError(`Unable to save preferences. ${errorMessage(reason)}`);
      return false;
    } finally {
      setSaving(false);
    }
  }, []);

  const setThemeMode = useCallback((themeMode: ThemeMode) => {
    const previous = preferences;
    return persistPreferences({ ...preferences, themeMode }, previous);
  }, [persistPreferences, preferences]);

  const setAccentColor = useCallback((accentColor: AccentKey) => {
    const previous = preferences;
    return persistPreferences({ ...preferences, accentColor }, previous);
  }, [persistPreferences, preferences]);

  const setTimeDisplay = useCallback((timeDisplay: TimeDisplay) => {
    const previous = preferences;
    return persistPreferences({ ...preferences, timeDisplay }, previous);
  }, [persistPreferences, preferences]);

  const resetPreferences = useCallback(() => {
    const previous = preferences;
    return persistPreferences(DEFAULT_PREFERENCES, previous);
  }, [persistPreferences, preferences]);

  const resolvedThemeMode = preferences.themeMode === "system"
    ? (prefersDark ? "dark" : "light")
    : preferences.themeMode;

  const value = useMemo<PreferencesContextValue>(() => ({
    preferences,
    loading,
    saving,
    error,
    resolvedThemeMode,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  }), [
    preferences,
    loading,
    saving,
    error,
    resolvedThemeMode,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  ]);

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences() {
  const context = useContext(PreferencesContext);
  if (!context) throw new Error("usePreferences must be used within PreferencesProvider");
  return context;
}
