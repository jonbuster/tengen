import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { formatTimestamp } from "./formatters";
import {
  DEFAULT_PREFERENCES,
  PreferencesProvider,
  parsePreferences,
  usePreferences,
} from "./preferences";

const { getMock, putMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  putMock: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    get: getMock,
    put: putMock,
  },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : "Unknown error"),
}));

function Probe() {
  const {
    preferences,
    loading,
    saving,
    error,
    resolvedThemeMode,
    setThemeMode,
    setAccentColor,
    setTimeDisplay,
    resetPreferences,
  } = usePreferences();

  return (
    <div>
      <span data-testid="preferences">{JSON.stringify(preferences)}</span>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="saving">{String(saving)}</span>
      <span data-testid="error">{error ?? ""}</span>
      <span data-testid="resolved-theme">{resolvedThemeMode}</span>
      <button onClick={() => void setThemeMode("dark")}>Dark</button>
      <button onClick={() => void setThemeMode("system")}>System</button>
      <button onClick={() => void setAccentColor("teal")}>Teal</button>
      <button onClick={() => void setTimeDisplay("utc")}>UTC</button>
      <button onClick={() => void resetPreferences()}>Reset</button>
    </div>
  );
}

function renderProbe() {
  return render(
    <PreferencesProvider isAuthenticated>
      <Probe />
    </PreferencesProvider>,
  );
}

async function waitForLoaded() {
  await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
}

async function waitForSaved() {
  await waitFor(() => expect(screen.getByTestId("saving")).toHaveTextContent("false"));
}

describe("preferences", () => {
  beforeEach(() => {
    getMock.mockResolvedValue({ data: DEFAULT_PREFERENCES });
    putMock.mockImplementation((_path: string, data: unknown) => Promise.resolve({ data }));
    vi.stubGlobal("matchMedia", vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })));
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads preferences from the API and persists each update", async () => {
    renderProbe();
    await waitForLoaded();
    expect(getMock).toHaveBeenCalledWith("/settings");
    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual(DEFAULT_PREFERENCES);

    fireEvent.click(screen.getByRole("button", { name: "Dark" }));
    await waitForSaved();
    fireEvent.click(screen.getByRole("button", { name: "Teal" }));
    await waitForSaved();
    fireEvent.click(screen.getByRole("button", { name: "UTC" }));
    await waitForSaved();

    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual({
      themeMode: "dark",
      accentColor: "teal",
      timeDisplay: "utc",
    });
    expect(putMock).toHaveBeenLastCalledWith("/settings", {
      themeMode: "dark",
      accentColor: "teal",
      timeDisplay: "utc",
    });
  });

  it("keeps defaults and reports malformed API data", async () => {
    getMock.mockResolvedValue({ data: { themeMode: "invalid" } });
    renderProbe();

    await waitFor(() => expect(screen.getByTestId("error")).toHaveTextContent("Backend returned invalid preferences"));
    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual(DEFAULT_PREFERENCES);
    expect(parsePreferences({ themeMode: "invalid" })).toBeNull();
    expect(parsePreferences({ themeMode: "light", accentColor: "blue", timeDisplay: "local" }))
      .toEqual(DEFAULT_PREFERENCES);
  });

  it("rolls back an optimistic update when saving fails", async () => {
    putMock.mockRejectedValue(new Error("database unavailable"));
    renderProbe();
    await waitForLoaded();

    fireEvent.click(screen.getByRole("button", { name: "Dark" }));
    await waitForSaved();

    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual(DEFAULT_PREFERENCES);
    expect(screen.getByTestId("error")).toHaveTextContent("database unavailable");
  });

  it("resets preferences through the API", async () => {
    renderProbe();
    await waitForLoaded();
    fireEvent.click(screen.getByRole("button", { name: "Dark" }));
    await waitForSaved();
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));
    await waitForSaved();

    expect(JSON.parse(screen.getByTestId("preferences").textContent ?? "{}")).toEqual(DEFAULT_PREFERENCES);
    expect(putMock).toHaveBeenLastCalledWith("/settings", DEFAULT_PREFERENCES);
  });

  it("follows the operating system when system mode is selected", async () => {
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
    await waitForLoaded();
    fireEvent.click(screen.getByRole("button", { name: "System" }));
    expect(screen.getByTestId("resolved-theme")).toHaveTextContent("light");
    await waitForSaved();

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
