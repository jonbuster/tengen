import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SettingsPage from "./page";
import { DEFAULT_PREFERENCES, PreferencesProvider } from "@/lib/preferences";

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

function renderSettings() {
  return render(
    <PreferencesProvider isAuthenticated>
      <SettingsPage />
    </PreferencesProvider>,
  );
}

describe("Settings page", () => {
  beforeEach(() => {
    window.localStorage.clear();
    getMock.mockResolvedValue({ data: DEFAULT_PREFERENCES });
    putMock.mockImplementation((_path: string, data: unknown) => Promise.resolve({ data }));
  });

  afterEach(() => {
    cleanup();
    window.localStorage.clear();
    vi.clearAllMocks();
  });

  it("exposes accessible controls and applies changes immediately", async () => {
    renderSettings();
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());

    expect(screen.getByRole("heading", { name: "Settings" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dark theme" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Teal accent" })).toHaveAttribute("aria-pressed", "false");
    for (const color of ["Yellow", "Red", "Pink", "Grey", "Black", "Neon"]) {
      expect(screen.getByRole("button", { name: `${color} accent` })).toBeInTheDocument();
    }
    expect(screen.getByRole("radio", { name: "UTC" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dark theme" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());
    fireEvent.click(screen.getByRole("button", { name: "Teal accent" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Teal accent" })).not.toBeDisabled());
    fireEvent.click(screen.getByRole("radio", { name: "UTC" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());

    expect(screen.getByRole("button", { name: "Teal accent" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/Preview: .* UTC/)).toBeInTheDocument();
  });

  it("disables controls while loading and saving", async () => {
    let resolveLoad: (value: { data: typeof DEFAULT_PREFERENCES }) => void = () => {};
    getMock.mockReturnValue(new Promise((resolve) => {
      resolveLoad = resolve;
    }));
    renderSettings();

    expect(screen.getByRole("button", { name: "Dark theme" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reset to defaults" })).toBeDisabled();

    resolveLoad({ data: DEFAULT_PREFERENCES });
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());

    let resolveSave: (value: { data: typeof DEFAULT_PREFERENCES }) => void = () => {};
    putMock.mockReturnValue(new Promise((resolve) => {
      resolveSave = resolve;
    }));
    fireEvent.click(screen.getByRole("button", { name: "Dark theme" }));

    expect(screen.getByRole("button", { name: "Dark theme" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reset to defaults" })).toBeDisabled();

    resolveSave({ data: { ...DEFAULT_PREFERENCES, themeMode: "dark" } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());
  });

  it("restores the defaults and shows confirmation after saving", async () => {
    renderSettings();
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());
    fireEvent.click(screen.getByRole("button", { name: "Dark theme" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Dark theme" })).not.toBeDisabled());
    fireEvent.click(screen.getByRole("button", { name: "Reset to defaults" }));

    await waitFor(() => expect(screen.getByText("Preferences reset to their defaults.")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Light theme" })).toHaveAttribute("aria-pressed", "true");
    expect(putMock).toHaveBeenLastCalledWith("/settings", DEFAULT_PREFERENCES);
  });
});
