export type RuleType = "CONDITION" | "AGGREGATE";
export type RuleAction = "LOG" | "WEBHOOK";
export type AggregateType = "COUNT" | "SUM" | "AVG" | "MIN" | "MAX";
export type TriggerMode = "EVERY_MATCH" | "EDGE" | "ONCE_PER_WINDOW";

export interface Rule {
  id: number;
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl: string | null;
  cooldownSeconds: number | null;
  triggerMode: TriggerMode;
  eventType: string;
  source: string;
  conditionScript: string;
  windowSeconds: number | null;
  aggType: AggregateType | null;
  aggField: string | null;
  groupBy: string | null;
  threshold: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RuleRequest {
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl?: string | null;
  cooldownSeconds?: number | null;
  triggerMode?: TriggerMode | null;
  eventType: string;
  source: string;
  conditionScript: string;
  windowSeconds?: number | null;
  aggType?: AggregateType | null;
  aggField?: string | null;
  groupBy?: string | null;
  threshold?: number;
  active: boolean;
}

export interface RuleTestRequest {
  mode: "single" | "all";
  ruleId?: number;
  eventJson: string;
}

export interface RuleResult {
  ruleId: number;
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  matched: boolean;
  conditionMatched: boolean;
  aggregateValue: number | null;
  threshold: number | null;
  windowSeconds: number | null;
  groupKey: string | null;
}

export interface TestResult {
  rule: Rule | null;
  matched: boolean | null;
  conditionMatched: boolean | null;
  aggregateValue: number | null;
  groupKey: string | null;
  event: unknown;
  results: RuleResult[] | null;
  anyMatched: boolean | null;
}

export interface AggregateResult {
  function: string;
  value: number;
  threshold: number;
  windowSeconds: number;
  groupKey: string | null;
}

export interface EventResponse {
  event: unknown;
  status: string;
  matched: boolean;
  rules: string[];
  queuedRules: string[];
  aggregates: Record<string, AggregateResult>;
  suppressedRules: string[];
}

export type WebhookDeliveryStatus =
  | "PENDING"
  | "PROCESSING"
  | "RETRY_SCHEDULED"
  | "DELIVERED"
  | "DEAD_LETTER";

export interface WebhookDeliverySummary {
  id: number;
  status: WebhookDeliveryStatus;
  ruleId: number | null;
  ruleName: string;
  eventId: number;
  destination: string;
  scopeKey: string | null;
  triggerMode: TriggerMode;
  windowStart: string | null;
  attemptCount: number;
  nextAttemptAt: string;
  lastAttemptAt: string | null;
  deliveredAt: string | null;
  lastStatusCode: number | null;
  lastError: string | null;
  createdAt: string;
  manuallyRetriedAt: string | null;
}

export interface WebhookDeliveryPage {
  content: WebhookDeliverySummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface WebhookDeliveryDetail {
  delivery: WebhookDeliverySummary;
  callbackUrl: string;
  payload: unknown;
  deduplicationKey: string;
  leaseExpiresAt: string | null;
}

export interface ApiKey {
  id: number;
  name: string;
  prefix: string;
  allowedEventTypes: string[] | null;
  allowedSources: string[] | null;
  active: boolean;
  expiresAt: string | null;
  createdAt: string;
  rawKey?: string;
}

export interface ApiKeyRequest {
  name: string;
  allowedEventTypes?: string[];
  allowedSources?: string[];
  expiresAt?: string | null;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
