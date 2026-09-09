import type {
  ContextItemResponse,
  ContextPackResponse,
  EstimatedTokens,
} from "@vibecode/contracts";
import type { ContextPackRow } from "@/components/context-inspector";

/**
 * Fixtures for the Context Inspector tests.
 *
 * Every value is typed as the real contract, so a field the API stops sending — or renames — makes
 * this file stop compiling. That is the point: a fixture typed as `any` would let the tests keep
 * passing against a shape the backend no longer produces.
 *
 * On secrets: nothing here is a credential, and nothing here is a redacted form of one that this
 * repository also stores in raw form. `[REDACTED]` appears because that is the marker the backend's
 * redactor writes, and a test that renders it proves the marker survives to the screen intact. The
 * frontend has no redactor and these tests do not pretend to exercise one.
 */

/**
 * The estimate is branded in the contract, so a bare number is not assignable.
 *
 * The cast is confined to this helper rather than sprinkled through the fixtures: the brand exists
 * to stop an estimate being confused with a measurement in application code, and a test that needs
 * to name a literal is exactly the place it does not apply.
 */
export function estimated(tokens: number): EstimatedTokens {
  return tokens as EstimatedTokens;
}

export const brainItem: ContextItemResponse = {
  id: "item-brain-1",
  kind: "ARCHITECTURE",
  label: "Backend em Java 21",
  content: "Backend Java 21 com Spring Boot. Persistência em PostgreSQL via Flyway.",
  provenance: {
    source: { type: "BRAIN_ENTRY", sourceId: "3f9a1c22-0d0e-4f6b-9a0c-1b2c3d4e5f60", version: 4 },
    projectId: "11111111-1111-4111-8111-111111111111",
    recordedAt: "2026-09-01T12:30:00Z",
  },
  admission: {
    policyRuleId: "BRAIN_ENTRY_ALWAYS",
    explanation: "Entradas oficiais do Project Brain entram sempre.",
  },
  characterCount: 71,
  byteCount: 74,
};

export const taskItem: ContextItemResponse = {
  id: "item-task-1",
  kind: "NEXT_STEP",
  label: "Tarefa atual",
  content: "Implementar autenticação de sessão.",
  provenance: {
    source: { type: "CURRENT_TASK", sourceId: "task-88", version: null },
    projectId: "11111111-1111-4111-8111-111111111111",
    recordedAt: "2026-09-02T09:00:00Z",
  },
  admission: {
    policyRuleId: "CURRENT_TASK_ALWAYS",
    explanation: "A tarefa em andamento é o que o contexto existe para servir.",
  },
  characterCount: 35,
  byteCount: 36,
};

/**
 * An item whose content is markup and a javascript: URL.
 *
 * This is what an attacker gets to control: content arrives from records a user pasted in, and the
 * engine stores text, not sanitised HTML. The inspector must show these characters.
 */
export const hostileItem: ContextItemResponse = {
  id: "item-hostile-1",
  kind: "NOTE",
  label: '<img src=x onerror="alert(1)">',
  content:
    '<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n<a href="javascript:alert(1)">x</a>',
  provenance: {
    source: { type: "ACTIVE_ERRORS", sourceId: "err-1", version: null },
    projectId: "11111111-1111-4111-8111-111111111111",
    recordedAt: "2026-09-03T10:00:00Z",
  },
  admission: { policyRuleId: "ERROR_RECENT", explanation: "Erro recente ainda aberto." },
  characterCount: 88,
  byteCount: 88,
};

/** Content large enough that rendering it whole would be the wrong thing to do. */
export const hugeItem: ContextItemResponse = {
  id: "item-huge-1",
  kind: "EVIDENCE",
  label: "Log de build",
  content: "L".repeat(500_000),
  provenance: {
    source: { type: "LATEST_EVIDENCE", sourceId: "evidence-9", version: 2 },
    projectId: "11111111-1111-4111-8111-111111111111",
    recordedAt: "2026-09-04T08:00:00Z",
  },
  admission: { policyRuleId: "EVIDENCE_LATEST", explanation: "Evidência mais recente da tarefa." },
  characterCount: 500_000,
  byteCount: 500_000,
};

/** An item carrying the marker the backend writes where it recognised a secret. */
export const redactedItem: ContextItemResponse = {
  id: "item-redacted-1",
  kind: "TECHNOLOGY",
  label: "Configuração de banco",
  content: "VIBECODE_DB_PASSWORD=[REDACTED]\nVIBECODE_DB_HOST=db.internal",
  provenance: {
    source: { type: "BRAIN_ENTRY", sourceId: "cfg-1", version: 1 },
    projectId: "11111111-1111-4111-8111-111111111111",
    recordedAt: "2026-09-05T08:00:00Z",
  },
  admission: { policyRuleId: "BRAIN_ENTRY_ALWAYS", explanation: "Entrada oficial." },
  characterCount: 59,
  byteCount: 59,
};

export function packWith(
  items: ContextItemResponse[],
  overrides: Partial<ContextPackResponse> = {},
): ContextPackResponse {
  const characters = items.reduce((total, item) => total + item.characterCount, 0);
  const bytes = items.reduce((total, item) => total + item.byteCount, 0);
  return {
    packId: "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa",
    projectId: "11111111-1111-4111-8111-111111111111",
    taskReference: "Implementar autenticação",
    assembledAt: "2026-09-06T14:05:09Z",
    budget: { maxItems: 50, maxCharacters: 200_000, maxBytes: 400_000 },
    usage: {
      items: items.length,
      characters,
      bytes,
      estimatedTokenCount: {
        estimatedTokens: estimated(2_310),
        heuristic: "characters/4",
        isExact: false,
      },
    },
    items,
    contentFingerprint: "sha256:9f2b7c1d4e5a6b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c",
    ...overrides,
  };
}

export const defaultPack = packWith([brainItem, taskItem]);

/** The pack index rows the page projects before handing the list to the client component. */
export function rowsFor(packs: ContextPackResponse[]): ContextPackRow[] {
  return packs.map((pack) => ({
    packId: pack.packId,
    taskReference: pack.taskReference,
    assembledAt: pack.assembledAt,
    items: pack.usage.items,
    characters: pack.usage.characters,
  }));
}
