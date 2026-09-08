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

/* ------------------------------------------------------------------ *
 * Deterministic Context Engine — sources, kinds, items, budget, packs.
 *
 * These mirror `com.vibecode.context.domain`. The engine reads only records the Project Brain
 * already owns, calls no provider and runs no tokenizer, so everything below describes state that
 * was read — never anything a model produced.
 * ------------------------------------------------------------------ */

/**
 * Where a context item was read from — the record's origin, never its meaning.
 *
 * Availability is not inclusion. A constant here says the engine *may* draw an item from that
 * source; selection stays deny-by-default and is decided elsewhere, so this list is not "what a
 * pack contains".
 *
 * This axis is independent of {@link ContextKind}, and the two must not be collapsed. All official
 * memory arrives through the single `BRAIN_ENTRY` source whatever it says; what it says is the
 * item's kind. A brain entry recording a technology choice is
 * `{ sourceType: "BRAIN_ENTRY", sourceId: "<entry id>", kind: "TECHNOLOGY" }` — there is
 * deliberately no `BRAIN_ARCHITECTURE` or `BRAIN_DECISION` constant, because folding the meaning
 * into the origin would make the provenance state an origin the item never had.
 *
 * Because the axes are independent, a word appears on both and means different things.
 * `ContextSourceType.CURRENT_STATE` is the computed project state as a place to read from;
 * `ContextKind.CURRENT_STATE` is what a record says about where things stand. So
 * `{ sourceType: "BRAIN_ENTRY", kind: "CURRENT_STATE" }` (state as remembered) and
 * `{ sourceType: "CURRENT_STATE", kind: "CURRENT_STATE" }` (state as computed) are both meaningful
 * and are not the same item. The same holds for `ACTIVE_ERRORS` here against `ContextKind.ERROR`.
 *
 * There is no `FREE_TEXT`, `SCRATCH` or `MODEL_OUTPUT`: context no recorded state can vouch for has
 * no provenance, and an item without provenance cannot be built.
 */
export type ContextSourceType =
  | "PROJECT"
  | "CURRENT_STATE"
  | "BRAIN_ENTRY"
  | "ROADMAP"
  | "CURRENT_PHASE"
  | "CURRENT_TASK"
  | "ACCEPTANCE_CRITERIA"
  | "LATEST_EVIDENCE"
  | "LATEST_OUTPUT_ANALYSIS"
  | "ACTIVE_ERRORS"
  | "SECURITY_SUMMARY";

/**
 * What a context item *means*, as opposed to where it came from.
 *
 * Thirteen of these carry the same names as {@link BrainEntryType} so every kind of official memory
 * has a faithful home; the other four — `OBJECTIVE`, `CONSTRAINT`, `EVIDENCE`, `SECURITY_NOTE` —
 * cover meanings no brain entry type expresses.
 *
 * There is no `UNKNOWN` and no `OTHER`. A fallback constant is how unmapped content quietly enters
 * a pack wearing the wrong meaning; if something has no kind here, the vocabulary is wrong and must
 * be changed deliberately, on both sides.
 *
 * The declared order matches the Java enum, which fixes how a pack reads. It is presentation and
 * determinism only — never priority, and never a drop order. Which item to leave out when a budget
 * binds is a separate decision the selection step owns under its own name.
 */
export type ContextKind =
  | "OBJECTIVE"
  | "CONSTRAINT"
  | "VISION"
  | "REQUIREMENT"
  | "ARCHITECTURE"
  | "TECHNOLOGY"
  | "DECISION"
  | "RULE"
  | "CURRENT_STATE"
  | "COMPLETED_STEP"
  | "NEXT_STEP"
  | "EVIDENCE"
  | "PROMPT_RESULT"
  | "ERROR"
  | "SOLUTION"
  | "SECURITY_NOTE"
  | "NOTE";

/** Fails to compile when its argument is not `true` — the only way to spend a type-level check. */
type Assert<T extends true> = T;

/**
 * A brain entry keeps its own name when it becomes context: a `TECHNOLOGY` entry enters as
 * `kind: "TECHNOLOGY"`, never as the nearest available constant. That is what
 * `BrainEntryContextMapping.kindOf` does in Java, name for name.
 *
 * This alias is the compile-time half of the same guarantee: rename or drop a kind that a brain
 * entry type still needs and this line stops compiling — the TypeScript equivalent of the
 * exhaustive switch with no `default` branch. It is a check, not a lookup table; the mapping itself
 * runs on the API side, and nothing here emits code that could perform it.
 */
export type BrainEntryKindsAreContextKinds = Assert<
  BrainEntryType extends ContextKind ? true : false
>;

/**
 * The kind a brain entry of type `T` carries into a pack. Its source type is always `BRAIN_ENTRY` —
 * the entry type says what the entry means, not where it was read.
 */
export type ContextKindOfBrainEntry<T extends BrainEntryType> = Extract<ContextKind, T>;

declare const ESTIMATE_BRAND: unique symbol;

/**
 * A token figure that is a guess, and that cannot be handed to code expecting a measured one.
 *
 * The brand exists because a bare `number` would be indistinguishable from the exact input and
 * output token counts a provider reports. Those are measurements; this is `characters / 4`. No
 * number literal produces this type, so an exact count cannot drift into an estimate's place
 * without a cast a reviewer can see.
 *
 * Honest limit: the brand is erased at runtime and the wire carries a plain number, so this
 * constrains TypeScript callers, not JSON. What it buys is that the mistake has to be written down.
 */
export type EstimatedTokens = number & { readonly [ESTIMATE_BRAND]: "estimate" };

/**
 * A guess at how many tokens some text would become — labelled as a guess, permanently.
 *
 * This phase calls no provider and runs no tokenizer, so no real token count exists here. The
 * number is still worth showing as a warning in the Context Inspector, so the caveat travels in the
 * type rather than in the caller's memory: the figure is reachable only through this object, it is
 * branded, `heuristic` says how it was produced, and `isExact` is the literal `false`. A careless
 * destructure cannot strip the label off, and no exact count satisfies the shape.
 *
 * It is not a budget dimension. {@link ContextBudgetResponse} admits no token limit, because a
 * limit enforced against a heuristic is wrong by an unknown amount while reading as authoritative.
 */
export interface EstimatedTokenCount {
  /** The estimate. Never a measurement. */
  estimatedTokens: EstimatedTokens;
  /** The rule that produced the number, so a reader can judge how wrong it might be. */
  heuristic: string;
  /** Always `false`, and always will be. Stated so consumers can check rather than assume. */
  isExact: false;
}

/**
 * The addressable origin of a context item: a type plus the identifier of the one record within it.
 *
 * The identifier matters as much as the type. "This came from a brain decision" is not auditable;
 * "this came from brain decision 7f3c…, version 4" can be looked up, disagreed with and superseded.
 * That is the difference between provenance and a label.
 */
export interface ContextSourceResponse {
  type: ContextSourceType;
  /** Identifier of the specific record, unique within its type. */
  sourceId: string;
  /** The record's revision, or `null` where the underlying record carries none. */
  version: number | null;
}

/**
 * Where one context item came from, and when that was true.
 *
 * `recordedAt` is the moment the underlying state was read, not the moment the pack was assembled.
 * Two packs built minutes apart from the same unchanged record carry the same `recordedAt`, which
 * is what lets the inspector show a stale item as stale.
 */
export interface ContextProvenanceResponse {
  source: ContextSourceResponse;
  /** The project owning that record; carried explicitly so an item cannot be read as another's. */
  projectId: string;
  /** ISO-8601 instant at which the source state was observed. */
  recordedAt: string;
}

/**
 * One unit of context, with the record it came from attached.
 *
 * `provenance` is not optional and must not be made so. An item nobody can trace back to official
 * state is the failure this whole engine exists to prevent, and an inspector that can render such
 * an item is not an inspector.
 *
 * The counts are exact and measured over `content` alone — `id` and `label` are handles for the
 * inspector, not payload, and whatever separators a later assembly step puts between items are that
 * step's cost to account for. `characterCount` is in UTF-16 code units, the unit the budget is
 * measured against, so "😀" counts 2; anything that trims content must trim in the same unit.
 *
 * Nothing here may ever hold secret material — not a credential, not a token, not a vault handle.
 * Secrets are references, never context.
 */
export interface ContextItemResponse {
  /** Identifier within its pack, unique there and stable across rebuilds. */
  id: string;
  kind: ContextKind;
  /** A short human handle, for the inspector. */
  label: string;
  content: string;
  provenance: ContextProvenanceResponse;
  /** Exact, in UTF-16 code units. */
  characterCount: number;
  /** Exact, in UTF-8 bytes. */
  byteCount: number;
}

/**
 * The ceiling a pack was held to, in the dimensions that can be counted exactly.
 *
 * There is deliberately no token dimension; see {@link EstimatedTokenCount}. The two size
 * dimensions are independent and neither implies the other.
 *
 * A budget is a ceiling, not a target. The engine's rule is minimum necessary context: a pack that
 * used a tenth of its budget because a tenth was what the task needed is the better pack.
 */
export interface ContextBudgetResponse {
  maxItems: number;
  /** Content UTF-16 code units, the unit `characterCount` counts. */
  maxCharacters: number;
  /** Content UTF-8 bytes. */
  maxBytes: number;
}

/**
 * What a pack actually costs. Every figure here is counted, not projected — except
 * `estimatedTokenCount`, which is derived from `characters` and says so in its own type.
 */
export interface ContextUsageResponse {
  items: number;
  characters: number;
  bytes: number;
  estimatedTokenCount: EstimatedTokenCount;
}

/**
 * A finished, immutable snapshot of the context selected for one task.
 *
 * A pack is the record of a decision already made, not a workspace: it cannot be appended to and it
 * cannot exist over its own budget. Everything the Context Inspector needs to explain an item is
 * here — what would enter (`content`, `label`), why and from where (`provenance`), what it means
 * (`kind`), how big it is (`characterCount`, `byteCount`) and in what order.
 *
 * `items` arrives in the engine's canonical order — source type, then kind, then source id, then
 * item id — and the array index *is* that order. Re-sorting it discards the property the ordering
 * exists for: two runs over the same inputs render identically. Sort a *view* if a user asks for
 * one; do not treat the result as the pack.
 */
export interface ContextPackResponse {
  /** The pack's identity, and the only one: packs are found, referenced and stored by this UUID. */
  packId: string;
  /** The project the context describes; every item's provenance agrees with it. */
  projectId: string;
  /** The task the pack was assembled for — part of what "same inputs" means. */
  taskReference: string;
  /** ISO-8601 instant at which the snapshot was taken. */
  assembledAt: string;
  budget: ContextBudgetResponse;
  usage: ContextUsageResponse;
  items: ContextItemResponse[];
  /**
   * A digest over the ordered content of this pack, so "the same inputs produced the same pack" is
   * one comparison instead of a walk over two lists.
   *
   * It is a content digest, never an identifier. Two packs assembled at different times from
   * unchanged state share it by design, and any change to what it covers changes every digest ever
   * computed. Do not key, route or de-duplicate stored records by it, and do not read it as a
   * security control — it proves nothing about who produced the pack. Identity is `packId`.
   */
  contentFingerprint: string;
}

/**
 * A caller-supplied ceiling. Separate from {@link ContextBudgetResponse} on purpose: the response
 * states the budget a finished pack was actually held to, every dimension resolved, while every
 * field here is optional. One shape for both would let a half-filled request read as a pack's real
 * limits.
 */
export interface ContextBudgetRequest {
  maxItems?: number;
  maxCharacters?: number;
  maxBytes?: number;
}

/**
 * Ask the engine to assemble a pack. A request type and only that — what comes back is
 * {@link ContextPackResponse}, whose measured figures and fingerprint no caller may set.
 */
export interface AssembleContextRequest {
  /** The task the context is for. Required: context assembled for nothing cannot be selected. */
  taskReference: string;
  /** Omitted dimensions fall back to the engine's configured ceiling. */
  budget?: ContextBudgetRequest;
}
