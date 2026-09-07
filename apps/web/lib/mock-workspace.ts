import type {
  BrainEntryType,
  CreditConfidence,
  OutputAnalysisStatus,
  ProjectResponse,
} from "@vibecode/contracts";

/**
 * PLACEHOLDER DATA — the only mocked values in the web app.
 *
 * Every export here stands in for something the API will serve once its module is built. They are
 * typed against the real contracts, so replacing a mock is deleting an export and calling `api`
 * instead; nothing in the components has to change shape.
 *
 * Nothing outside this file may hard-code workspace content.
 */

export const MOCKED = true;

export const demoProject: ProjectResponse = {
  id: "demo",
  name: "VibeCode foundation",
  description: "A durable source of truth for an evolving codebase.",
  originalIdea:
    "A platform that keeps the real state of my project while I build it with different LLMs.",
  status: "ACTIVE",
  currentPhase: "Foundation",
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

export const demoProjects: ProjectResponse[] = [demoProject];

export interface ProgressSummary {
  completedTasks: number;
  totalTasks: number;
  currentPhase: string;
}

export const demoProgress: ProgressSummary = {
  completedTasks: 2,
  totalTasks: 6,
  currentPhase: "Foundation",
};

export interface HealthSummary {
  label: string;
  detail: string;
  tone: "ok" | "warn" | "bad" | "idle";
}

/** Health stays idle until a guardian actually runs. An unmeasured project is not a healthy one. */
export const demoHealth: HealthSummary = {
  label: "Not measured",
  detail: "No guardian has inspected this project yet.",
  tone: "idle",
};

export interface CurrentStep {
  title: string;
  objective: string;
  status: string;
  completionCriteria: string[];
}

export const demoCurrentStep: CurrentStep = {
  title: "Record the project vision",
  objective: "Give the Project Brain its first official entry so later steps have something to sit on.",
  status: "IN_PROGRESS",
  completionCriteria: [
    "A VISION entry exists in the Project Brain",
    "The entry names a source",
  ],
};

export interface BrainSummaryItem {
  type: BrainEntryType;
  count: number;
}

export const demoBrainSummary: BrainSummaryItem[] = [
  { type: "VISION", count: 1 },
  { type: "DECISION", count: 3 },
  { type: "RULE", count: 2 },
  { type: "CURRENT_STATE", count: 1 },
];

export interface RoadmapPhaseSummary {
  name: string;
  done: number;
  total: number;
}

export const demoRoadmap: RoadmapPhaseSummary[] = [
  { name: "Foundation", done: 2, total: 3 },
  { name: "Guided execution", done: 0, total: 5 },
  { name: "Providers and usage", done: 0, total: 4 },
];

export interface RecentOutput {
  label: string;
  status: OutputAnalysisStatus;
  receivedAt: string;
}

export const demoRecentOutputs: RecentOutput[] = [
  { label: "Backend build log", status: "SUCCESS", receivedAt: "2 hours ago" },
  { label: "Model answer: schema draft", status: "NEEDS_VALIDATION", receivedAt: "3 hours ago" },
  { label: "Migration run", status: "PARTIAL", receivedAt: "yesterday" },
];

export interface Recommendation {
  action: string;
  rationale: string;
  basedOn: string[];
}

export const demoRecommendation: Recommendation = {
  action: "Write the first VISION entry into the Project Brain",
  rationale:
    "The roadmap has no completed steps and memory is empty, so every later prompt would be built on nothing.",
  basedOn: ["Roadmap: Foundation phase", "Brain: 0 entries"],
};

export interface AiAccount {
  provider: string;
  label: string;
  connected: boolean;
  credit: { amount: string | null; confidence: CreditConfidence };
}

/**
 * No account is connected, so no balance may be shown. `UNKNOWN` carries no amount by contract —
 * showing a plausible number here would be inventing one.
 */
export const demoAccounts: AiAccount[] = [
  {
    provider: "Anthropic",
    label: "Not connected",
    connected: false,
    credit: { amount: null, confidence: "UNKNOWN" },
  },
  {
    provider: "OpenAI",
    label: "Not connected",
    connected: false,
    credit: { amount: null, confidence: "UNKNOWN" },
  },
];

export interface UsageSummary {
  tokensObserved: number;
  estimatedSpend: string | null;
  confidence: CreditConfidence;
}

export const demoUsage: UsageSummary = {
  tokensObserved: 0,
  estimatedSpend: null,
  confidence: "UNKNOWN",
};
