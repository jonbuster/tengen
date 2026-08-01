export type RuleType = "CONDITION" | "AGGREGATE";
export type RuleAction = "LOG" | "WEBHOOK";
export type AggregateType = "COUNT" | "SUM" | "AVG" | "MIN" | "MAX";

export interface Rule {
  id: number;
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl: string | null;
  eventType: string;
  source: string;
  conditionScript: string;
  windowSeconds: number | null;
  aggType: AggregateType | null;
  aggField: string | null;
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
  eventType: string;
  source: string;
  conditionScript: string;
  windowSeconds?: number | null;
  aggType?: AggregateType | null;
  aggField?: string | null;
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
}

export interface TestResult {
  rule: Rule | null;
  matched: boolean | null;
  conditionMatched: boolean | null;
  aggregateValue: number | null;
  event: unknown;
  results: RuleResult[] | null;
  anyMatched: boolean | null;
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
