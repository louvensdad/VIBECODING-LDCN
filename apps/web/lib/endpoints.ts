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
import type { Transport } from "./http";

export interface AuthenticatedUser {
  id: string;
  email: string;
  displayName: string;
  role: "USER" | "ADMIN";
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * Every endpoint the web app knows about, written once.
 *
 * Bound to a transport at the call site, so a server component and a browser component reach the
 * same API through the same declarations.
 */
export function createApi(request: Transport) {
  const post = <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  return {
    // --- identity ---------------------------------------------------------------------------
    register: (body: RegisterRequest) => post<AuthenticatedUser>("/api/auth/register", body),
    login: (body: LoginRequest) => post<AuthenticatedUser>("/api/auth/login", body),
    logout: () => post<void>("/api/auth/logout"),
    me: () => request<AuthenticatedUser>("/api/auth/me"),

    // --- projects ---------------------------------------------------------------------------
    listProjects: () => request<ProjectResponse[]>("/api/projects"),
    getProject: (id: string) => request<ProjectResponse>(`/api/projects/${id}`),
    createProject: (body: CreateProjectRequest) => post<ProjectResponse>("/api/projects", body),

    // --- brain ------------------------------------------------------------------------------
    getBrain: (projectId: string) =>
      request<ProjectBrainResponse>(`/api/projects/${projectId}/brain`),
    addBrainEntry: (projectId: string, body: CreateBrainEntryRequest) =>
      post<ProjectBrainResponse>(`/api/projects/${projectId}/brain/entries`, body),

    // --- guided workflow --------------------------------------------------------------------
    analyzeOutput: (projectId: string, body: AnalyzeOutputRequest) =>
      post<OutputAnalysisResponse>(`/api/projects/${projectId}/outputs/analyze`, body),
    getRoadmap: (projectId: string) =>
      request<RoadmapResponse>(`/api/projects/${projectId}/roadmap`),
    getState: (projectId: string) =>
      request<ProjectStateResponse>(`/api/projects/${projectId}/state`),
    getGuide: (projectId: string) => request<GuidanceResponse>(`/api/projects/${projectId}/guide`),
    getNextStep: (projectId: string) =>
      request<NextStepResponse>(`/api/projects/${projectId}/next-step`),
    listTasks: (projectId: string) => request<TaskResponse[]>(`/api/projects/${projectId}/tasks`),
    recentEvidence: (projectId: string, limit = 5) =>
      request<RecentEvidenceResponse[]>(`/api/projects/${projectId}/evidence?limit=${limit}`),
    listTaskEvidence: (projectId: string, taskId: string) =>
      request<EvidenceResponse[]>(`/api/projects/${projectId}/tasks/${taskId}/evidence`),
    recordEvidence: (projectId: string, taskId: string, body: RecordEvidenceRequest) =>
      post<EvidenceRecordedResponse>(
        `/api/projects/${projectId}/tasks/${taskId}/evidence`,
        body,
      ),
    generatePrompt: (projectId: string, body: GeneratePromptRequest) =>
      post<GeneratedPromptResponse>(`/api/projects/${projectId}/prompts/generate`, body),
  };
}

export type Api = ReturnType<typeof createApi>;
