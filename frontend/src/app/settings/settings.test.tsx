import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import SettingsPage from "./page";
import { PreferencesProvider } from "@/lib/preferences";

function renderSettings() {
  return render(
    <PreferencesProvider>
      <SettingsPage />
    </PreferencesProvider>,
  );
}

describe("Settings page", () => {
  afterEach(() => cleanup());

  it("exposes accessible controls and applies changes immediately", () => {
    renderSettings();

    expect(screen.getByRole("heading", { name: "Settings" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Dark theme" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Teal accent" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("radio", { name: "UTC" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dark theme" }));
    fireEvent.click(screen.getByRole("button", { name: "Teal accent" }));
    fireEvent.click(screen.getByRole("radio", { name: "UTC" }));

    expect(screen.getByRole("button", { name: "Teal accent" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText(/Preview: .* UTC/)).toBeInTheDocument();
  });

  it("restores the defaults and shows confirmation", () => {
    renderSettings();
    fireEvent.click(screen.getByRole("button", { name: "Dark theme" }));
    fireEvent.click(screen.getByRole("button", { name: "Reset to defaults" }));

    expect(screen.getByRole("button", { name: "Light theme" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Preferences reset to their defaults.")).toBeInTheDocument();
  });
});
