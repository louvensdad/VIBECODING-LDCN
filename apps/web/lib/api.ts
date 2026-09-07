import type {
  AnalyzeOutputRequest,
  CreateBrainEntryRequest,
  CreateProjectRequest,
  EvidenceRecordedResponse,
  EvidenceResponse,
  GeneratePromptRequest,
  GeneratedPromptResponse,
  GuidanceResponse,
  NextStepResponse,
  OutputAnalysisResponse,
  ProjectBrainResponse,
  ProjectResponse,
  ProjectStateResponse,
  RecentEvidenceResponse,
  RecordEvidenceRequest,
  RoadmapResponse,
  TaskResponse,
} from "@vibecode/contracts";

/**
 * The one place the web app talks to the API.
 *
 * Nothing else knows the base URL or the response shapes, so a change in the contract is a change
 * here and in `packages/contracts` — never scattered through components.
 */
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

/** The API being down is an expected state for a local tool, not a crash. */
export class ApiUnreachableError extends Error {
  constructor(cause: unknown) {
    super(`Could not reach the VibeCode API at ${API_BASE_URL}`);
    this.name = "ApiUnreachableError";
    this.cause = cause;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...init?.headers },
      cache: "no-store",
    });
  } catch (cause) {
    throw new ApiUnreachableError(cause);
  }

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiRequestError(
      response.status,
      body?.code ?? "UNKNOWN_ERROR",
      body?.message ?? `Request to ${path} failed`,
    );
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const api = {
  listProjects: () => request<ProjectResponse[]>("/api/projects"),

  getProject: (id: string) => request<ProjectResponse>(`/api/projects/${id}`),

  createProject: (body: CreateProjectRequest) =>
    post<ProjectResponse>("/api/projects", body),

  getBrain: (projectId: string) =>
    request<ProjectBrainResponse>(`/api/projects/${projectId}/brain`),

  addBrainEntry: (projectId: string, body: CreateBrainEntryRequest) =>
    post<ProjectBrainResponse>(`/api/projects/${projectId}/brain/entries`, body),

  /** Stateless: analyses a snippet without storing it or touching a task. */
  analyzeOutput: (projectId: string, body: AnalyzeOutputRequest) =>
    post<OutputAnalysisResponse>(`/api/projects/${projectId}/outputs/analyze`, body),

  getRoadmap: (projectId: string) =>
    request<RoadmapResponse>(`/api/projects/${projectId}/roadmap`),

  getState: (projectId: string) =>
    request<ProjectStateResponse>(`/api/projects/${projectId}/state`),

  getGuide: (projectId: string) =>
    request<GuidanceResponse>(`/api/projects/${projectId}/guide`),

  getNextStep: (projectId: string) =>
    request<NextStepResponse>(`/api/projects/${projectId}/next-step`),

  listTasks: (projectId: string) => request<TaskResponse[]>(`/api/projects/${projectId}/tasks`),

  getTask: (projectId: string, taskId: string) =>
    request<TaskResponse>(`/api/projects/${projectId}/tasks/${taskId}`),

  recentEvidence: (projectId: string, limit = 5) =>
    request<RecentEvidenceResponse[]>(`/api/projects/${projectId}/evidence?limit=${limit}`),

  listTaskEvidence: (projectId: string, taskId: string) =>
    request<EvidenceResponse[]>(`/api/projects/${projectId}/tasks/${taskId}/evidence`),

  /** Stores the output as evidence and lets the verdict move the task. */
  recordEvidence: (projectId: string, taskId: string, body: RecordEvidenceRequest) =>
    post<EvidenceRecordedResponse>(
      `/api/projects/${projectId}/tasks/${taskId}/evidence`,
      body,
    ),

  generatePrompt: (projectId: string, body: GeneratePromptRequest) =>
    post<GeneratedPromptResponse>(`/api/projects/${projectId}/prompts/generate`, body),
};

/**
 * Runs a read and reports unreachability instead of throwing, so a page can render a useful
 * "start the API" state rather than an error boundary.
 */
export async function tryLoad<T>(load: () => Promise<T>): Promise<
  { ok: true; data: T } | { ok: false; unreachable: boolean; message: string }
> {
  try {
    return { ok: true, data: await load() };
  } catch (error) {
    if (error instanceof ApiUnreachableError) {
      return { ok: false, unreachable: true, message: error.message };
    }
    if (error instanceof ApiRequestError) {
      return { ok: false, unreachable: false, message: error.message };
    }
    throw error;
  }
}
