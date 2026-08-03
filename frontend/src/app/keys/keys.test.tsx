import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import ApiKeysPage from "./page";
import { PreferencesProvider } from "@/lib/preferences";

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
}));

vi.mock("@/lib/api", () => ({
  api: {
    get: getMock,
    post: postMock,
  },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : "Unknown error"),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <PreferencesProvider>
        <ApiKeysPage />
      </PreferencesProvider>
    </QueryClientProvider>,
  );
}

describe("API keys response mode", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("defaults new keys to compact responses", async () => {
    getMock.mockResolvedValue({ data: [] });
    postMock.mockResolvedValue({
      data: {
        id: 1,
        name: "Payments",
        prefix: "tg_abc",
        allowedEventTypes: null,
        allowedSources: null,
        responseMode: "COMPACT",
        active: true,
        expiresAt: null,
        createdAt: "2026-08-03T00:00:00Z",
        rawKey: "tg_secret",
      },
    });

    renderPage();
    await waitFor(() => expect(getMock).toHaveBeenCalledWith("/keys"));
    fireEvent.click(screen.getByRole("button", { name: "New API Key" }));

    expect(screen.getByRole("combobox", { name: "Response mode" })).toHaveTextContent("Compact summary");

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), { target: { value: "Payments" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(postMock).toHaveBeenCalledWith("/keys", expect.objectContaining({
      responseMode: "COMPACT",
    })));
  });
});
