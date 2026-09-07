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
