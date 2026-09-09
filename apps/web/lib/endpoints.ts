import type {
  AnalyzeOutputRequest,
  AssembleContextRequest,
  AuditEventResponse,
  ContextPackResponse,
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
  ProviderAccountResponse,
  ProviderCatalogEntry,
  CreateProviderAccountRequest,
  StoreCredentialRequest,
  RecentEvidenceResponse,
  FindingDecisionRequest,
  InspectSecurityRequest,
  RecordEvidenceRequest,
  RoadmapResponse,
  SecurityAssessmentResponse,
  SecurityFindingResponse,
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
 * How many context packs the inspector asks for.
 *
 * A named number rather than a literal at the call site, because the API refuses a `limit` it will
 * not honour exactly rather than clamping it — an out-of-range page is a 400, not a shorter list.
 * The bounds are the controller's own (`ContextPackController.MIN_LIST_LIMIT` / `MAX_LIST_LIMIT`),
 * restated here for the test that pins this constant between them.
 *
 * The route below takes no `limit` argument. One constant, one call site, one spelling on the wire:
 * a `number` parameter would have admitted `1.5`, `NaN`, `1e21`, `0` and `101` — every one of them
 * a 400 — and the type checker would have seen none of it. Making the value un-passable is the only
 * version of this that a reader can verify. This is not a second copy of the server's parsing
 * rules: the client sends one decimal integer and the server decides.
 */
export const CONTEXT_PACK_LIST_LIMIT = 20;

/** The bounds the API enforces, kept here so a test can pin the constant inside them. */
export const CONTEXT_PACK_LIST_LIMIT_MIN = 1;
export const CONTEXT_PACK_LIST_LIMIT_MAX = 100;

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

  const put = <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: "PUT",
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  const del = <T>(path: string) => request<T>(path, { method: "DELETE" });

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

    // --- security guardian --------------------------------------------------------------------
    getSecurity: (projectId: string) =>
      request<SecurityAssessmentResponse>(`/api/projects/${projectId}/security`),
    listFindings: (projectId: string) =>
      request<SecurityFindingResponse[]>(`/api/projects/${projectId}/security/findings`),
    getFinding: (projectId: string, findingId: string) =>
      request<SecurityFindingResponse>(
        `/api/projects/${projectId}/security/findings/${findingId}`,
      ),
    inspectSecurity: (projectId: string, body: InspectSecurityRequest) =>
      post<SecurityFindingResponse[]>(`/api/projects/${projectId}/security/inspect`, body),
    acknowledgeFinding: (projectId: string, findingId: string) =>
      post<SecurityFindingResponse>(
        `/api/projects/${projectId}/security/findings/${findingId}/acknowledge`,
      ),
    resolveFinding: (projectId: string, findingId: string, body: FindingDecisionRequest) =>
      post<SecurityFindingResponse>(
        `/api/projects/${projectId}/security/findings/${findingId}/resolve`,
        body,
      ),
    acceptFindingRisk: (projectId: string, findingId: string, body: FindingDecisionRequest) =>
      post<SecurityFindingResponse>(
        `/api/projects/${projectId}/security/findings/${findingId}/accept-risk`,
        body,
      ),
    markFindingFalsePositive: (
      projectId: string,
      findingId: string,
      body: FindingDecisionRequest,
    ) =>
      post<SecurityFindingResponse>(
        `/api/projects/${projectId}/security/findings/${findingId}/false-positive`,
        body,
      ),

    // --- provider accounts ----------------------------------------------------------------
    // Note what is absent: there is no getCredential, and there is no endpoint to write one
    // against. The API offers no way to read stored material, so the client cannot ask.
    listProviderAccounts: () => request<ProviderAccountResponse[]>("/api/provider-accounts"),
    getProviderCatalog: () =>
      request<ProviderCatalogEntry[]>("/api/provider-accounts/catalog"),
    getProviderAccount: (id: string) =>
      request<ProviderAccountResponse>(`/api/provider-accounts/${id}`),
    createProviderAccount: (body: CreateProviderAccountRequest) =>
      post<ProviderAccountResponse>("/api/provider-accounts", body),
    storeProviderCredential: (id: string, body: StoreCredentialRequest) =>
      put<ProviderAccountResponse>(`/api/provider-accounts/${id}/credential`, body),
    rotateProviderCredential: (id: string, body: StoreCredentialRequest) =>
      post<ProviderAccountResponse>(`/api/provider-accounts/${id}/credential/rotate`, body),
    removeProviderCredential: (id: string) =>
      del<ProviderAccountResponse>(`/api/provider-accounts/${id}/credential`),
    disableProviderAccount: (id: string) =>
      post<ProviderAccountResponse>(`/api/provider-accounts/${id}/disable`),

    // --- context engine -----------------------------------------------------------------------
    // Three routes and no fourth. There is no endpoint for a candidate before policy saw it or for
    // content before redaction rewrote it, so the client cannot ask for one; what comes back here
    // is the admitted, redacted pack and nothing else.
    // Path segments are encoded, not the number: `projectId` arrives from the URL route param, and
    // a segment containing `/`, `?` or `#` would retarget the request. The limit is a constant this
    // module owns and cannot need escaping.
    listContextPacks: (projectId: string) =>
      request<ContextPackResponse[]>(
        `/api/projects/${encodeURIComponent(projectId)}/context?limit=${CONTEXT_PACK_LIST_LIMIT}`,
      ),
    getContextPack: (projectId: string, packId: string) =>
      request<ContextPackResponse>(
        `/api/projects/${encodeURIComponent(projectId)}/context/${encodeURIComponent(packId)}`,
      ),
    compileContextPack: (projectId: string, body: AssembleContextRequest) =>
      post<ContextPackResponse>(
        `/api/projects/${encodeURIComponent(projectId)}/context/compile`,
        body,
      ),

    // --- audit --------------------------------------------------------------------------------
    listAuditEvents: (projectId: string) =>
      request<AuditEventResponse[]>(`/api/projects/${projectId}/audit`),
  };
}

export type Api = ReturnType<typeof createApi>;
