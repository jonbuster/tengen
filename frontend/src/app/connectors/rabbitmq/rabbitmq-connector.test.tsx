import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import RabbitMqConnectorPage from "./page";
import { PreferencesProvider } from "@/lib/preferences";

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));

vi.mock("@/lib/api", () => ({
  api: { get: getMock, put: vi.fn(), post: vi.fn() },
  errorMessage: () => "Request failed",
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PreferencesProvider>
        <RabbitMqConnectorPage />
      </PreferencesProvider>
    </QueryClientProvider>,
  );
}

describe("RabbitMQ connector settings", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("keeps the saved password replace-only", async () => {
    getMock.mockImplementation((path: string) => {
      if (path === "/keys") return Promise.resolve({ data: [{ id: 4, name: "Billing", prefix: "tg_bill", active: true, expiresAt: null }] });
      return Promise.resolve({ data: {
        configured: true,
        id: 1,
        connectorKey: "rabbitmq-primary",
        displayName: "Billing queue",
        host: "rabbitmq",
        port: 5672,
        virtualHost: "/",
        tlsEnabled: false,
        username: "tengen",
        passwordConfigured: true,
        queueName: "events",
        deadLetterExchange: "events.dlx",
        deadLetterRoutingKey: "events.failed",
        apiKeyId: 4,
        apiKeyName: "Billing",
        apiKeyActive: true,
        apiKeyExpiresAt: null,
        maxBodyBytes: 1048576,
        retryAttempts: 3,
        retryInitialDelayMs: 1000,
        retryMultiplier: 2,
        retryMaxDelayMs: 30000,
        enabled: false,
        configurationVersion: 2,
        lastTestedVersion: null,
        lastTestedAt: null,
        lastTestSucceeded: null,
        lastTestErrorCategory: null,
        runtimeState: "DISABLED",
        errorCategory: null,
        lastTransitionAt: null,
      } });
    });

    renderPage();

    await waitFor(() => expect(screen.getByLabelText("Password")).toHaveValue(""));
    expect(screen.getByText("Leave blank to retain the saved password.")).toBeInTheDocument();
  });
});
