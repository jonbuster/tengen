import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RuleForm } from "./RuleForm";
import { Rule } from "@/lib/types";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const rule: Rule = {
  id: 1,
  name: "Large payment",
  ruleType: "CONDITION",
  action: "LOG",
  callbackUrl: null,
  cooldownSeconds: null,
  triggerMode: "EVERY_MATCH",
  eventType: "payment",
  source: "billing",
  conditionScript: "(data.amount >= 1000)",
  expectedEventType: null,
  expectedSource: null,
  expectedConditionScript: null,
  windowSeconds: null,
  aggType: null,
  aggField: null,
  groupBy: null,
  threshold: 0,
  active: true,
  validationStatus: "VALID",
  validationError: null,
  revision: 1,
  archivedAt: null,
  createdAt: "2026-08-03T00:00:00Z",
  updatedAt: "2026-08-03T00:00:00Z",
  sequenceSteps: [],
};

describe("RuleForm", () => {
  function renderForm(element: React.ReactElement) {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    return render(<QueryClientProvider client={queryClient}>{element}</QueryClientProvider>);
  }

  it("synchronizes visual and raw condition modes", async () => {
    const user = userEvent.setup();
    renderForm(<RuleForm initial={rule} onSubmit={vi.fn()} />);

    const rawButton = screen.getByRole("button", { name: "Raw Aviator" });
    await user.click(rawButton);
    expect(rawButton).toHaveAttribute("aria-pressed", "true");
    const raw = screen.getByRole("textbox", { name: /Condition \(Aviator\)/i });
    expect(raw).toHaveValue("(data.amount >= 1000)");

    fireEvent.change(raw, { target: { value: "data.amount + 1 > 10" } });
    await user.click(screen.getByRole("button", { name: "Visual Builder" }));
    expect(screen.getByText(/cannot represent/i)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Condition \(Aviator\)/i })).toBeInTheDocument();
  });

  it("supports adding and reordering sequence steps", async () => {
    const user = userEvent.setup();
    const sequenceRule: Rule = {
      ...rule,
      ruleType: "SEQUENCE",
      eventType: null,
      source: null,
      conditionScript: null,
      windowSeconds: 300,
      groupBy: "data.userId",
      sequenceSteps: [
        { position: 1, eventType: "opened", source: "workflow", conditionScript: "(true == true)" },
        { position: 2, eventType: "approved", source: "workflow", conditionScript: "(true == true)" },
      ],
    };
    renderForm(<RuleForm initial={sequenceRule} onSubmit={vi.fn()} />);

    expect(screen.getByText("Step 1")).toBeInTheDocument();
    expect(screen.getByText("Step 2")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add step" }));
    expect(screen.getByText("Step 3")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Move step 3 up" }));
    expect(screen.getByRole("button", { name: "Move step 2 up" })).toBeInTheDocument();
  });
});
