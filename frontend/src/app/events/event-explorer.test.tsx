import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EventDetailPage from "./[id]/page";
import { api } from "@/lib/api";
import { PreferencesProvider } from "@/lib/preferences";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "42" }),
}));

vi.mock("next/link", () => ({
  default: ({ children, ...props }: { children: React.ReactNode; href: string }) => (
    <a {...props}>{children}</a>
  ),
}));

vi.mock("@/lib/api", () => ({
  api: { get: vi.fn() },
  errorMessage: () => "Request failed",
}));

const getMock = vi.mocked(api.get);

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <PreferencesProvider>
        <EventDetailPage />
      </PreferencesProvider>
    </QueryClientProvider>,
  );
}

describe("Event Explorer detail", () => {
  beforeEach(() => {
    cleanup();
    getMock.mockReset();
  });

  it("renders payload, exact rule outcome, and delivery link", async () => {
    getMock.mockResolvedValue({
      data: {
        event: {
          id: 42,
          type: "payment",
          source: "billing",
          occurredAt: "2026-08-03T10:00:00Z",
          receivedAt: "2026-08-03T10:00:01Z",
          apiKeyId: 3,
          apiKeyName: "billing-key",
          apiKeyPrefix: "tg_abc",
          traceAvailable: true,
          matchedRuleCount: 1,
          queuedActionCount: 1,
          suppressedActionCount: 0,
        },
        data: { paymentId: "p-1", amount: 1250 },
        rules: [{
          id: 9,
          ruleId: 4,
          ruleRevision: 2,
          ruleName: "Large payment",
          ruleType: "AGGREGATE",
          groupKey: "customer-1",
          aggregate: {
            ruleType: "AGGREGATE",
            function: "SUM",
            value: 1250,
            threshold: 1000,
            windowSeconds: 300,
            groupKey: "customer-1",
          },
          sequence: null,
          actionOutcome: "WEBHOOK_QUEUED",
          suppressionReason: null,
          deliveryId: 7,
        }],
        deliveries: [{
          id: 7,
          status: "PENDING",
          ruleId: 4,
          ruleRevision: 2,
          ruleName: "Large payment",
          eventId: 42,
          destination: "https://example.com/webhook",
          scopeKey: "customer-1",
          triggerMode: "EVERY_MATCH",
          windowStart: null,
          attemptCount: 0,
          nextAttemptAt: "2026-08-03T10:00:01Z",
          lastAttemptAt: null,
          deliveredAt: null,
          lastStatusCode: null,
          lastError: null,
          createdAt: "2026-08-03T10:00:01Z",
          manuallyRetriedAt: null,
        }],
      },
    } as never);

    renderPage();

    expect(await screen.findByText("Large payment")).toBeInTheDocument();
    expect(screen.getByText(/paymentId/)).toBeInTheDocument();
    expect(screen.getByText("WEBHOOK QUEUED")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Delivery #7" })[0]).toHaveAttribute(
      "href",
      "/deliveries?eventId=42",
    );
  });

  it("warns when a legacy event has no persisted trace", async () => {
    getMock.mockResolvedValue({
      data: {
        event: {
          id: 42,
          type: "login",
          source: "auth",
          occurredAt: "2026-08-03T10:00:00Z",
          receivedAt: "2026-08-03T10:00:01Z",
          apiKeyId: null,
          apiKeyName: null,
          apiKeyPrefix: null,
          traceAvailable: false,
          matchedRuleCount: null,
          queuedActionCount: null,
          suppressedActionCount: null,
        },
        data: { userId: "user-1" },
        rules: [],
        deliveries: [],
      },
    } as never);

    renderPage();

    expect(await screen.findByText(/predates Event Explorer trace capture/i)).toBeInTheDocument();
    expect(screen.getByText("No trace records are available.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "View deliveries" })).not.toBeInTheDocument();
  });
});
