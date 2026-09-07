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
  content: string;
  contextSources: string[];
  generatedAt: string;
}
