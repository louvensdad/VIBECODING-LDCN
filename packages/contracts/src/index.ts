/**
 * The API contract shared by the VibeCode backend and web app.
 *
 * Types only — nothing here emits JavaScript, so importing this package cannot pull runtime code
 * into either side. These shapes mirror the DTOs in `apps/api`; when a DTO changes, this file is
 * the second half of that change.
 */

export type ProjectStatus = "ACTIVE" | "PAUSED" | "ARCHIVED";

export interface ProjectResponse {
  id: string;
  name: string;
  description: string | null;
  originalIdea: string;
  status: ProjectStatus;
  currentPhase: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
  originalIdea: string;
}

export type BrainEntryType =
  | "VISION"
  | "REQUIREMENT"
  | "ARCHITECTURE"
  | "TECHNOLOGY"
  | "DECISION"
  | "RULE"
  | "CURRENT_STATE"
  | "COMPLETED_STEP"
  | "ERROR"
  | "SOLUTION"
  | "NEXT_STEP"
  | "PROMPT_RESULT"
  | "NOTE";

export interface BrainEntryResponse {
  id: string;
  projectId: string;
  type: BrainEntryType;
  title: string;
  content: string;
  /** Where the knowledge came from — a person, a named model, a build. */
  source: string;
  version: number;
  createdAt: string;
}

export interface CreateBrainEntryRequest {
  type: BrainEntryType;
  title: string;
  content: string;
  source: string;
}

export interface ProjectBrainResponse {
  projectId: string;
  entryCount: number;
  countByType: Partial<Record<BrainEntryType, number>>;
  entries: BrainEntryResponse[];
}

export type OutputAnalysisStatus =
  | "SUCCESS"
  | "PARTIAL"
  | "FAILURE"
  | "BLOCKED"
  | "NEEDS_VALIDATION"
  | "UNKNOWN";

export type OutputKind =
  | "LLM_RESPONSE"
  | "TERMINAL"
  | "STACK_TRACE"
  | "BUILD"
  | "TEST"
  | "LOG"
  | "HTTP_RESPONSE"
  | "SQL"
  | "DEPLOY"
  | "GENERIC_TEXT";

export interface AnalyzeOutputRequest {
  content: string;
  kind?: OutputKind;
}

export interface OutputAnalysisResponse {
  status: OutputAnalysisStatus;
  summary: string;
  signals: string[];
  /** True only when the output carried technical evidence of success. */
  shouldContinue: boolean;
  requiresCorrection: boolean;
  kind: OutputKind;
}

/** The single error shape every endpoint returns. */
export interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  violations: { field: string; message: string }[];
}

/**
 * Credit figures must state how much they can be trusted. An `UNKNOWN` snapshot has no amount, and
 * an estimate is never rendered as a balance.
 */
export type CreditConfidence = "EXACT" | "ESTIMATED" | "UNKNOWN";

/* ------------------------------------------------------------------ *
 * Guided workflow — roadmap, tasks, evidence, guidance and prompts.
 * ------------------------------------------------------------------ */

export type PhaseStatus =
  | "PLANNED"
  | "READY"
  | "IN_PROGRESS"
  | "BLOCKED"
  | "COMPLETED"
  | "SKIPPED";

export type TaskStatus =
  | "PLANNED"
  | "READY"
  | "IN_PROGRESS"
  | "BLOCKED"
  | "NEEDS_VALIDATION"
  | "COMPLETED"
  | "SKIPPED";

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type CriterionStatus = "PENDING" | "SATISFIED" | "FAILED" | "UNKNOWN";

export type EvidenceType =
  | "LLM_RESPONSE"
  | "TERMINAL_OUTPUT"
  | "BUILD_RESULT"
  | "TEST_RESULT"
  | "ERROR_LOG"
  | "HTTP_RESPONSE"
  | "DATABASE_RESULT"
  | "USER_CONFIRMATION"
  | "GENERIC_OUTPUT";

export interface PhaseTaskResponse {
  id: string;
  position: number;
  title: string;
  status: TaskStatus;
}

export interface PhaseResponse {
  id: string;
  position: number;
  title: string;
  description: string | null;
  status: PhaseStatus;
  totalTasks: number;
  completedTasks: number;
  tasks: PhaseTaskResponse[];
  updatedAt: string;
}

export interface RoadmapResponse {
  id: string;
  projectId: string;
  totalPhases: number;
  completedPhases: number;
  phases: PhaseResponse[];
  createdAt: string;
}

export interface CriterionResponse {
  id: string;
  description: string;
  required: boolean;
  status: CriterionStatus;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface TaskResponse {
  id: string;
  projectId: string;
  phaseId: string;
  position: number;
  title: string;
  objective: string;
  status: TaskStatus;
  riskLevel: RiskLevel;
  dependsOn: string[];
  acceptanceCriteria: CriterionResponse[];
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PhaseSummary {
  id: string;
  title: string;
  status: PhaseStatus;
}

export interface TaskSummary {
  id: string;
  title: string;
  status: TaskStatus;
}

export interface EvidenceSummary {
  id: string;
  taskId: string;
  type: EvidenceType;
  source: string;
  createdAt: string;
  status: OutputAnalysisStatus | null;
  signals: string[];
}

/** `progressPercentage` is computed by the API on every read, never stored. */
export interface ProjectStateResponse {
  projectId: string;
  currentPhase: PhaseSummary | null;
  currentTask: TaskSummary | null;
  completedTasks: number;
  totalTasks: number;
  blockedTasks: number;
  progressPercentage: number;
  activeProblems: string[];
  lastEvidence: EvidenceSummary | null;
  nextCandidateTasks: TaskSummary[];
}

export interface RecentEvidenceResponse {
  id: string;
  taskId: string;
  type: EvidenceType;
  source: string;
  createdAt: string;
  status: OutputAnalysisStatus | null;
  summary: string | null;
}

export type NextStepType =
  | "START_TASK"
  | "CONTINUE_TASK"
  | "FIX_ERROR"
  | "VALIDATE_RESULT"
  | "RESOLVE_BLOCKER"
  | "REVIEW_SECURITY"
  | "WAIT_FOR_USER"
  | "PROJECT_COMPLETE";

export type NextStepPriority = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type PromptType =
  | "START_TASK"
  | "CONTINUE_TASK"
  | "FIX_ERROR"
  | "VALIDATE_RESULT"
  | "MODEL_HANDOFF"
  | "ASK_FOR_EVIDENCE"
  | "RESOLVE_BLOCKER";

export interface NextStepResponse {
  type: NextStepType;
  title: string;
  /** Always present: a recommendation the user cannot audit is a guess. */
  reason: string;
  taskId: string | null;
  priority: NextStepPriority;
  blockingIssues: string[];
  requiredActions: string[];
  suggestedPromptType: PromptType;
}

export interface GuidanceResponse {
  projectId: string;
  whereYouAre: string;
  whatWasCompleted: string[];
  whatIsMissing: string[];
  activeProblems: string[];
  recommendedNextStep: NextStepResponse;
  reason: string;
  progressPercentage: number;
}

export interface AnalysisResponse {
  status: OutputAnalysisStatus;
  summary: string;
  signals: string[];
  shouldContinue: boolean;
  requiresCorrection: boolean;
}

export interface EvidenceResponse {
  id: string;
  taskId: string;
  type: EvidenceType;
  rawContent: string;
  source: string;
  createdAt: string;
  analysis: AnalysisResponse | null;
}

export interface EvidenceRecordedResponse {
  evidence: EvidenceResponse;
  analysis: AnalysisResponse;
  taskStatus: TaskStatus;
  taskCompleted: boolean;
  missingForCompletion: string[];
}

export interface RecordEvidenceRequest {
  type: EvidenceType;
  rawContent: string;
  source: string;
}

export interface GeneratePromptRequest {
  taskId?: string | null;
  type?: PromptType | null;
}

export interface GeneratedPromptResponse {
  type: PromptType;
  taskId: string | null;
  taskTitle: string | null;
  /** Always redacted. A blocked prompt still must not display a live secret. */
  content: string;
  contextSources: string[];
  securityStatus: PromptSecurityStatus;
  /** False when the prompt must not leave the platform. */
  copyAllowed: boolean;
  securityFindings: string[];
  generatedAt: string;
}

/* ------------------------------------------------------------------ *
 * Security Guardian and audit trail.
 * ------------------------------------------------------------------ */

export type SecuritySeverity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";

export type SecurityFindingStatus =
  | "OPEN"
  | "ACKNOWLEDGED"
  | "RESOLVED"
  | "FALSE_POSITIVE"
  | "ACCEPTED_RISK";

export type SecurityGateStatus =
  | "PASS"
  | "WARNING"
  | "REQUIRES_APPROVAL"
  | "BLOCKED";

export type SecuritySourceType =
  | "TASK_EVIDENCE"
  | "PROMPT"
  | "GENERIC_TEXT"
  | "BRAIN_ENTRY";

export type SecurityCategory =
  | "SECRET_EXPOSURE"
  | "INSECURE_CONFIGURATION"
  | "DANGEROUS_OPERATION"
  | "SENSITIVE_DATA_HANDLING";

/**
 * A recorded security problem.
 *
 * `evidence` is always the redacted form — the raw value is removed before the finding is
 * stored, so there is no shape of this type that could carry a live secret.
 */
export interface SecurityFindingResponse {
  id: string;
  projectId: string;
  sourceType: SecuritySourceType;
  sourceId: string | null;
  category: SecurityCategory;
  severity: SecuritySeverity;
  status: SecurityFindingStatus;
  title: string;
  description: string;
  evidence: string;
  location: string | null;
  recommendation: string;
  ruleId: string;
  /** How many times the same problem was seen; the finding itself stays single. */
  occurrenceCount: number;
  resolutionReason: string | null;
  firstDetectedAt: string;
  lastDetectedAt: string;
  resolvedAt: string | null;
}

/** `score` is an operational indicator, not a percentage of safety. */
export interface SecurityAssessmentResponse {
  projectId: string;
  score: number;
  openFindings: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  gateStatus: SecurityGateStatus;
  canProceed: boolean;
  blockingReasons: string[];
  warnings: string[];
  evaluatedAt: string;
}

export interface InspectSecurityRequest {
  sourceType: SecuritySourceType;
  sourceId?: string | null;
  content: string;
}

export interface FindingDecisionRequest {
  reason?: string | null;
}

export type AuditEventType =
  | "SECURITY_FINDING_CREATED"
  | "SECURITY_FINDING_ACKNOWLEDGED"
  | "SECURITY_FINDING_RESOLVED"
  | "SECURITY_RISK_ACCEPTED"
  | "SECURITY_GATE_BLOCKED"
  | "PROMPT_BLOCKED"
  | "CROSS_USER_ACCESS_DENIED"
  | "LOGIN_SUCCESS"
  | "LOGIN_FAILURE"
  | "LOGOUT"
  | "AUTH_RATE_LIMITED"
  | "REGISTRATION_RATE_LIMITED"
  | "PROVIDER_ACCOUNT_CREATED"
  | "PROVIDER_ACCOUNT_DISABLED"
  | "PROVIDER_CREDENTIAL_STORED"
  | "PROVIDER_CREDENTIAL_ROTATED"
  | "PROVIDER_CREDENTIAL_REMOVED"
  | "VAULT_DECRYPTION_FAILED";

/** Append-only. Never carries a credential, cookie, token or session id. */
export interface AuditEventResponse {
  id: string;
  projectId: string | null;
  actorUserId: string | null;
  eventType: AuditEventType;
  targetType: string | null;
  targetId: string | null;
  result: string | null;
  metadata: string | null;
  createdAt: string;
}

export type PromptSecurityStatus = "SAFE" | "WARNING" | "BLOCKED";

// --- provider accounts --------------------------------------------------------------------

export type ProviderId = "OPENAI" | "ANTHROPIC" | "GOOGLE_GEMINI" | "DEEPSEEK" | "CUSTOM";

/** Only `API_KEY` is implemented. The rest name shapes the model already anticipates. */
export type ProviderAuthenticationType =
  | "API_KEY"
  | "OAUTH"
  | "SERVICE_ACCOUNT"
  | "CUSTOM_TOKEN";

/**
 * There is no CONNECTED and no VALID.
 *
 * Storing a credential proves only that it was stored. Nothing has been sent to the provider, so
 * the platform does not know whether the key works — and a status that implied otherwise would be
 * the interface asserting something nobody checked.
 */
export type ProviderAccountStatus =
  | "PENDING_CREDENTIAL"
  | "CREDENTIAL_STORED_UNVERIFIED"
  | "DISABLED";

export interface CreateProviderAccountRequest {
  provider: ProviderId;
  displayName: string;
  authenticationType: ProviderAuthenticationType;
}

/**
 * Write-only, and deliberately not part of any response type.
 *
 * A credential travels in exactly one direction. If this interface ever appears in a response
 * position, that is the bug.
 */
export interface StoreCredentialRequest {
  credential: string;
}

/**
 * What the client is told about a connection.
 *
 * `hasCredential` is a boolean and there is nothing next to it: no masked value, no prefix, no
 * last four characters, no fingerprint. Each of those is a piece of a secret, and the pieces are
 * what let someone confirm which key they are looking at.
 */
export interface ProviderAccountResponse {
  id: string;
  provider: ProviderId;
  displayName: string;
  authenticationType: ProviderAuthenticationType;
  status: ProviderAccountStatus;
  hasCredential: boolean;
  credentialUpdatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProviderCatalogEntry {
  id: ProviderId;
  displayName: string;
  supportedAuthenticationTypes: ProviderAuthenticationType[];
}
