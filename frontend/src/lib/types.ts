export type RuleType = "CONDITION" | "AGGREGATE" | "SEQUENCE" | "ABSENCE";
export type RuleAction = "LOG" | "WEBHOOK";
export type AggregateType = "COUNT" | "SUM" | "AVG" | "MIN" | "MAX";
export type TriggerMode = "EVERY_MATCH" | "EDGE" | "ONCE_PER_WINDOW";
export type RuleValidationStatus = "VALID" | "INVALID";
export type ResponseMode = "FULL" | "COMPACT";
export type EventTimeStatus = "ON_TIME" | "LATE_ACCEPTED" | "TOO_LATE";
export type AbsenceInstanceStatus = "PENDING" | "SATISFIED" | "TRIGGERED" | "CANCELLED";
export type IngestionOrigin = "HTTP" | "RABBITMQ";
export type RabbitMqRuntimeState =
  | "DISABLED"
  | "TESTING"
  | "CONNECTING"
  | "RUNNING"
  | "PAUSED"
  | "ERROR";

export interface SequenceStep {
  position: number;
  eventType: string;
  source: string;
  conditionScript: string;
}

export interface Rule {
  id: number;
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl: string | null;
  cooldownSeconds: number | null;
  triggerMode: TriggerMode;
  eventType: string | null;
  source: string | null;
  conditionScript: string | null;
  expectedEventType: string | null;
  expectedSource: string | null;
  expectedConditionScript: string | null;
  windowSeconds: number | null;
  aggType: AggregateType | null;
  aggField: string | null;
  groupBy: string | null;
  threshold: number;
  active: boolean;
  validationStatus: RuleValidationStatus;
  validationError: string | null;
  revision: number;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
  sequenceSteps: SequenceStep[];
}

export type RuleRevisionChangeType =
  | "CREATED"
  | "UPDATED"
  | "ACTIVATED"
  | "DEACTIVATED"
  | "ARCHIVED"
  | "UNARCHIVED"
  | "RESTORED";

export interface RuleRevisionSummary {
  id: number;
  ruleId: number;
  revision: number;
  changeType: RuleRevisionChangeType;
  actor: string;
  changedAt: string;
  restoredFromRevision: number | null;
}

export interface RuleSnapshot {
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl: string | null;
  cooldownSeconds: number | null;
  triggerMode: TriggerMode;
  eventType: string | null;
  source: string | null;
  conditionScript: string | null;
  expectedEventType: string | null;
  expectedSource: string | null;
  expectedConditionScript: string | null;
  windowSeconds: number | null;
  aggType: AggregateType | null;
  aggField: string | null;
  groupBy: string | null;
  threshold: number;
  active: boolean;
  archivedAt: string | null;
  sequenceSteps: SequenceStep[];
}

export interface RuleRevisionPage {
  content: RuleRevisionSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RuleRevisionDetail {
  revision: RuleRevisionSummary;
  snapshotSchemaVersion: number;
  snapshot: RuleSnapshot;
}

export type ReplayJobStatus =
  | "QUEUED"
  | "RUNNING"
  | "PAUSE_REQUESTED"
  | "PAUSED"
  | "CANCEL_REQUESTED"
  | "CANCELLED"
  | "COMPLETED"
  | "FAILED";
export type ReplayActionMode = "NO_ACTIONS";

export interface ReplayJob {
  id: number;
  status: ReplayJobStatus;
  ruleId: number;
  ruleRevision: number;
  snapshotSchemaVersion: number;
  ruleName: string;
  ruleType: RuleType;
  actionMode: ReplayActionMode;
  occurredFrom: string;
  occurredTo: string;
  warmupFrom: string;
  apiKeyId: number | null;
  totalOutputEvents: number;
  totalMaterializedEvents: number;
  processedOutputEvents: number;
  matchedEvents: number;
  errorEvents: number;
  version: number;
  attemptCount: number;
  retryable: boolean;
  progressPercentage: number;
  lastCommittedPosition: number | null;
  leaseExpiresAt: string | null;
  createdBy: string;
  createdAt: string;
  startedAt: string | null;
  updatedAt: string;
  completedAt: string | null;
  pausedAt: string | null;
  cancelledAt: string | null;
  lastRetriedAt: string | null;
  failureCategory: string | null;
  failureMessage: string | null;
}

export interface ReplayJobPage {
  content: ReplayJob[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ReplayJobTransition {
  id: number;
  sequence: number;
  fromStatus: ReplayJobStatus | null;
  toStatus: ReplayJobStatus;
  action: string;
  actor: string;
  attemptCount: number;
  reason: string | null;
  transitionedAt: string;
}

export interface ReplayAggregateResult {
  ruleType: string;
  function: string;
  value: number;
  threshold: number;
  windowSeconds: number;
  groupKey: string | null;
}

export interface ReplayJobOutcome {
  id: number;
  originalEventId: number | null;
  inputPosition: number;
  type: string;
  source: string;
  occurredAt: string;
  matched: boolean;
  groupKey: string | null;
  aggregate: ReplayAggregateResult | null;
  errorCategory: string | null;
  completedAt: string;
}

export interface ReplayJobOutcomePage {
  content: ReplayJobOutcome[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RuleRequest {
  name: string;
  ruleType: RuleType;
  action: RuleAction;
  callbackUrl?: string | null;
  cooldownSeconds?: number | null;
  triggerMode?: TriggerMode | null;
  eventType?: string | null;
  source?: string | null;
  conditionScript: string;
  expectedEventType?: string | null;
  expectedSource?: string | null;
  expectedConditionScript?: string | null;
  windowSeconds?: number | null;
  aggType?: AggregateType | null;
  aggField?: string | null;
  groupBy?: string | null;
  threshold?: number;
  active: boolean;
  sequenceSteps?: SequenceStep[];
}

export interface RuleTestRequest {
  mode: "single" | "all";
  ruleId?: number;
  eventJson: string;
  absenceExpectedEventJson?: string;
  sequenceEventJsons?: string[];
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
  sequence: SequenceResult | null;
}

export interface AbsenceResult {
  instanceId: number | null;
  groupKey: string | null;
  startEventId: number | null;
  startOccurredAt: string;
  expectedEventType: string;
  expectedSource: string;
  deadlineAt: string;
  triggeringWatermark: string | null;
}

export interface AbsenceTestResult {
  startMatched: boolean;
  expectedMatched: boolean;
  correlationMatched: boolean;
  orderingValid: boolean;
  withinWindow: boolean;
  outcome: string;
  groupKey: string | null;
  absence: AbsenceResult | null;
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
  sequenceTest: SequenceTestResult | null;
  absenceTest: AbsenceTestResult | null;
}

export interface AggregateResult {
  function: string;
  value: number;
  threshold: number;
  windowSeconds: number;
  groupKey: string | null;
}

export interface SequenceStepMatch {
  position: number;
  eventId: number | null;
  occurredAt: string;
}

export interface SequenceResult {
  groupKey: string | null;
  windowSeconds: number;
  steps: SequenceStepMatch[];
}

export interface SequenceStepTestResult {
  position: number;
  conditionMatched: boolean;
  occurredAt: string;
}

export interface SequenceTestResult {
  matched: boolean;
  correlationMatched: boolean;
  orderingValid: boolean;
  withinWindow: boolean;
  groupKey: string | null;
  steps: SequenceStepTestResult[];
  sequence: SequenceResult | null;
}

export interface EventResponse {
  event: unknown;
  status: string;
  matched: boolean;
  rules: string[];
  queuedRules: string[];
  aggregates: Record<string, AggregateResult>;
  sequences: Record<string, SequenceResult>;
  suppressedRules: string[];
  eventTimeStatus?: EventTimeStatus;
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
  ruleRevision: number;
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

export type EventRuleActionOutcome = "LOG_ONLY" | "WEBHOOK_QUEUED" | "WEBHOOK_SUPPRESSED";

export interface EventHistorySummary {
  id: number;
  type: string;
  source: string;
  occurredAt: string;
  receivedAt: string;
  apiKeyId: number | null;
  apiKeyName: string | null;
  apiKeyPrefix: string | null;
  traceAvailable: boolean;
  matchedRuleCount: number | null;
  queuedActionCount: number | null;
  suppressedActionCount: number | null;
  eventTimeStatus: EventTimeStatus | null;
  watermarkAtDecision: string | null;
  watermarkApplied?: boolean | null;
  ingestionOrigin?: IngestionOrigin | null;
  connectorId?: number | null;
  connectorName?: string | null;
}

export interface EventRuleOutcomeResponse {
  id: number;
  ruleId: number;
  ruleRevision: number;
  ruleName: string;
  ruleType: RuleType;
  groupKey: string | null;
  aggregate: AggregateResult | null;
  sequence: SequenceResult | null;
  absence: AbsenceResult | null;
  actionOutcome: EventRuleActionOutcome;
  suppressionReason: string | null;
  deliveryId: number | null;
}

export interface EventHistoryPage {
  content: EventHistorySummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EventHistoryDetail {
  event: EventHistorySummary;
  data: Record<string, unknown>;
  rules: EventRuleOutcomeResponse[];
  deliveries: WebhookDeliverySummary[];
  absenceInstances: AbsenceInstanceResponse[];
  rabbitMqMetadata?: RabbitMqBrokerMetadata | null;
}

export interface RabbitMqBrokerMetadata {
  connectorId: number | null;
  connectorKey: string | null;
  connectorName: string | null;
  queueName: string;
  sourceExchange: string | null;
  routingKey: string | null;
  messageId: string;
  processedAt: string;
}

export interface RabbitMqConnector {
  configured: boolean;
  id: number | null;
  connectorKey: string;
  displayName: string;
  host: string;
  port: number;
  virtualHost: string;
  tlsEnabled: boolean;
  username: string;
  passwordConfigured: boolean;
  queueName: string;
  deadLetterExchange: string;
  deadLetterRoutingKey: string;
  apiKeyId: number | null;
  apiKeyName: string | null;
  apiKeyActive: boolean;
  apiKeyExpiresAt: string | null;
  maxBodyBytes: number;
  retryAttempts: number;
  retryInitialDelayMs: number;
  retryMultiplier: number;
  retryMaxDelayMs: number;
  enabled: boolean;
  configurationVersion: number;
  lastTestedVersion: number | null;
  lastTestedAt: string | null;
  lastTestSucceeded: boolean | null;
  lastTestErrorCategory: string | null;
  runtimeState: RabbitMqRuntimeState;
  errorCategory: string | null;
  lastTransitionAt: string | null;
}

export interface RabbitMqConnectorRequest {
  displayName: string;
  host: string;
  port: number;
  virtualHost: string;
  tlsEnabled: boolean;
  username: string;
  password?: string;
  queueName: string;
  deadLetterExchange: string;
  deadLetterRoutingKey: string;
  apiKeyId: number;
  maxBodyBytes: number;
  retryAttempts: number;
  retryInitialDelayMs: number;
  retryMultiplier: number;
  retryMaxDelayMs: number;
  configurationVersion: number;
}

export interface RabbitMqConnectionTestResult {
  successful: boolean;
  category: string;
  message: string;
  testedAt: string;
  configurationVersion: number;
}

export interface AbsenceInstanceResponse {
  id: number;
  ruleId: number;
  ruleRevision: number;
  ruleName: string;
  scopeKey: string;
  startEventId: number;
  startOccurredAt: string;
  deadlineAt: string;
  status: AbsenceInstanceStatus;
  resolvedByEventId: number | null;
  resolvedAt: string | null;
  deliveryId: number | null;
  suppressionReason: string | null;
}

export interface ApiKey {
  id: number;
  name: string;
  prefix: string;
  allowedEventTypes: string[] | null;
  allowedSources: string[] | null;
  responseMode: ResponseMode;
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
  responseMode?: ResponseMode;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
