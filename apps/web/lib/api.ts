import type {
  AnalyzeOutputRequest,
  CreateBrainEntryRequest,
  CreateProjectRequest,
  OutputAnalysisResponse,
  ProjectBrainResponse,
  ProjectResponse,
} from "@vibecode/contracts";

/**
 * The one place the web app talks to the API.
 *
 * Nothing else in the app knows the base URL or the response shapes, so swapping mock data for
 * live data is a change here and nowhere else.
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
    cache: "no-store",
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiRequestError(
      response.status,
      body?.code ?? "UNKNOWN_ERROR",
      body?.message ?? `Request to ${path} failed`,
    );
  }

  return (await response.json()) as T;
}

export const api = {
  listProjects: () => request<ProjectResponse[]>("/api/projects"),

  getProject: (id: string) => request<ProjectResponse>(`/api/projects/${id}`),

  createProject: (body: CreateProjectRequest) =>
    request<ProjectResponse>("/api/projects", { method: "POST", body: JSON.stringify(body) }),

  getBrain: (projectId: string) =>
    request<ProjectBrainResponse>(`/api/projects/${projectId}/brain`),

  addBrainEntry: (projectId: string, body: CreateBrainEntryRequest) =>
    request<ProjectBrainResponse>(`/api/projects/${projectId}/brain/entries`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  analyzeOutput: (projectId: string, body: AnalyzeOutputRequest) =>
    request<OutputAnalysisResponse>(`/api/projects/${projectId}/outputs/analyze`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
};
