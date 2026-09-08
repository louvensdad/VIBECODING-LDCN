/** The workspace routes, in the order they appear in the sidebar. */
export interface NavItem {
  label: string;
  /** Path segment under /projects/[id], or null for a top-level route. */
  segment: string | null;
  href?: string;
}

export const projectNav: NavItem[] = [
  { label: "Overview", segment: null },
  { label: "Roadmap", segment: "roadmap" },
  { label: "Outputs", segment: "outputs" },
  { label: "Prompts", segment: "prompts" },
  { label: "Project Brain", segment: "brain" },
  { label: "Guide", segment: "guide" },
  { label: "Models", segment: "models" },
  { label: "Usage", segment: "usage" },
  { label: "Security", segment: "security" },
  { label: "Audit", segment: "audit" },
  { label: "Terminal", segment: "terminal" },
];

export function projectHref(projectId: string, segment: string | null): string {
  return segment ? `/projects/${projectId}/${segment}` : `/projects/${projectId}`;
}

/** Descriptions used by the module placeholder pages, so each one says what it will hold. */
export const moduleDescriptions: Record<string, string> = {
  brain: "The official memory of this project: vision, decisions, rules and current state.",
  roadmap: "Phases and steps, with progress measured by completed work rather than elapsed time.",
  guide: "Where the project stands, what is missing, and what to do next — with the reasoning.",
  prompts: "Prompts built from official context, ready to paste into any model.",
  outputs: "Results you bring back, analyzed for evidence before anything advances.",
  models:
    "Which model this project uses. The provider accounts themselves belong to you, not to a " +
    "project, and live under AI Connections.",
  usage: "Tokens, cost and runway — with facts and estimates kept apart.",
  security: "Guardian findings and the risks worth acting on.",
  terminal: "Sandboxed commands. Nothing executes on the platform itself.",
};
