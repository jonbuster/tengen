import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { formatTimestamp } from "./formatters";
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_STORAGE_KEY,
  PreferencesProvider,
  parsePreferences,
  usePreferences,
} from "./preferences";

function Probe() {
  const {
    preferences,
    resolvedThemeMode,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  } = usePreferences();

  return (
    <div>
      <span data-testid="preferences">{JSON.stringify(preferences)}</span>
      <span data-testid="resolved-theme">{resolvedThemeMode}</span>
      <button onClick={() => setThemeMode("dark")}>Dark</button>
      <button onClick={() => setThemeMode("system")}>System</button>
      <button onClick={() => setAccentColor("teal")}>Teal</button>
      <button onClick={() => setTimeDisplay("utc")}>UTC</button>
      <button onClick={resetPreferences}>Reset</button>
    </div>
  );
}

function renderProbe() {
  return render(
    <PreferencesProvider>
      <Probe />
    </PreferencesProvider>,
  );
}

describe("preferences", () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      value: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
        clear: () => values.clear(),
      },
    });
    window.localStorage.clear();
    vi.stubGlobal("matchMedia", vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("uses defaults and persists each preference update", async () => {
    renderProbe();
    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}"))
      .toEqual(DEFAULT_PREFERENCES);

    fireEvent.click(screen.getByRole("button", { name: "Dark" }));
    fireEvent.click(screen.getByRole("button", { name: "Teal" }));
    fireEvent.click(screen.getByRole("button", { name: "UTC" }));

    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toMatchObject({
      version: 1,
      themeMode: "dark",
      accentColor: "teal",
      timeDisplay: "utc",
    });
    expect(JSON.parse(window.localStorage.getItem(PREFERENCES_STORAGE_KEY) ?? "{}")).toMatchObject({
      themeMode: "dark",
      accentColor: "teal",
      timeDisplay: "utc",
    });
  });

  it("loads a valid stored value and ignores malformed values", async () => {
    window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify({
      version: 1,
      themeMode: "system",
      accentColor: "purple",
      timeDisplay: "utc",
    }));
    renderProbe();
    await act(async () => {});
    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toMatchObject({
      themeMode: "system",
      accentColor: "purple",
      timeDisplay: "utc",
    });

    expect(parsePreferences("not-json")).toBeNull();
    expect(parsePreferences(JSON.stringify({ version: 1, themeMode: "invalid" }))).toBeNull();
    expect(parsePreferences(JSON.stringify({ version: 2, themeMode: "light", accentColor: "blue", timeDisplay: "local" }))).toBeNull();
  });

  it("resets state and removes the stored value", () => {
    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "Dark" }));
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));

    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual(DEFAULT_PREFERENCES);
    expect(window.localStorage.getItem(PREFERENCES_STORAGE_KEY)).toBeNull();
  });

  it("follows the operating system when system mode is selected", () => {
    let matches = false;
    let listener: (() => void) | undefined;
    vi.stubGlobal("matchMedia", vi.fn(() => ({
      get matches() {
        return matches;
      },
      addEventListener: (_event: string, callback: () => void) => {
        listener = callback;
      },
      removeEventListener: vi.fn(),
    })));

    renderProbe();
    fireEvent.click(screen.getByRole("button", { name: "System" }));
    expect(screen.getByTestId("resolved-theme")).toHaveTextContent("light");

    act(() => {
      matches = true;
      listener?.();
    });
    expect(screen.getByTestId("resolved-theme")).toHaveTextContent("dark");
  });
});

describe("formatTimestamp", () => {
  it("formats a timestamp in local time or UTC and handles invalid values", () => {
    const value = "2026-08-03T12:34:56Z";
    expect(formatTimestamp(value, "utc")).toContain("UTC");
    expect(formatTimestamp(value, "local")).not.toContain(" UTC");
    expect(formatTimestamp(null, "utc")).toBe("—");
    expect(formatTimestamp("invalid", "local")).toBe("—");
  });
});
