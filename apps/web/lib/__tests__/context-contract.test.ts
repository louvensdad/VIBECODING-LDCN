import { describe, expect, it, vi } from "vitest";
import type { ContextItemResponse, ContextPackResponse } from "@vibecode/contracts";
import {
  CONTEXT_PACK_LIST_LIMIT,
  CONTEXT_PACK_LIST_LIMIT_MAX,
  CONTEXT_PACK_LIST_LIMIT_MIN,
  createApi,
} from "@/lib/endpoints";
import { defaultPack } from "@/components/__tests__/context-fixtures";

/**
 * The contract this screen is built on, pinned.
 *
 * Two kinds of check live here. The runtime ones assert what the client sends. The compile-time
 * ones are the point of the file: each `@ts-expect-error` below asserts that a shape the UI must
 * never accept is *rejected* by the real contract. If a required field ever becomes optional, or is
 * removed, the rejection stops happening, the expectation goes unused, and `tsc` fails the build
 * with "Unused '@ts-expect-error' directive". A test that merely accepted an object literal would
 * go on passing through exactly that change, which is why none of these are written that way.
 */

describe("the UI is typed against the real contract", () => {
  it("rejects a pack missing usage", () => {
    // @ts-expect-error usage is required: the summary and the budget are read from it.
    const pack: ContextPackResponse = {
      packId: defaultPack.packId,
      projectId: defaultPack.projectId,
      taskReference: defaultPack.taskReference,
      assembledAt: defaultPack.assembledAt,
      budget: defaultPack.budget,
      items: [],
      contentFingerprint: defaultPack.contentFingerprint,
    };
    expect(pack.packId).toBe(defaultPack.packId);
  });

  it("rejects a pack missing budget", () => {
    // @ts-expect-error budget is required: without a ceiling there is nothing to show usage against.
    const pack: ContextPackResponse = {
      packId: defaultPack.packId,
      projectId: defaultPack.projectId,
      taskReference: defaultPack.taskReference,
      assembledAt: defaultPack.assembledAt,
      usage: defaultPack.usage,
      items: [],
      contentFingerprint: defaultPack.contentFingerprint,
    };
    expect(pack.packId).toBe(defaultPack.packId);
  });

  it("rejects an item missing provenance", () => {
    // @ts-expect-error provenance is required: an item with no traceable origin is the one thing
    // this inspector exists to make impossible to display.
    const item: ContextItemResponse = {
      id: "x",
      kind: "NOTE",
      label: "sem origem",
      content: "…",
      admission: { policyRuleId: "R", explanation: "e" },
      characterCount: 1,
      byteCount: 1,
    };
    expect(item.id).toBe("x");
  });

  it("rejects an item missing its admission reason", () => {
    // @ts-expect-error admission is required: the rule that let an item in is part of the record.
    const item: ContextItemResponse = {
      id: "x",
      kind: "NOTE",
      label: "sem regra",
      content: "…",
      provenance: {
        source: { type: "BRAIN_ENTRY", sourceId: "s", version: null },
        projectId: "p",
        recordedAt: "2026-09-06T14:05:09Z",
      },
      characterCount: 1,
      byteCount: 1,
    };
    expect(item.id).toBe("x");
  });

  it("rejects a source type the engine does not define", () => {
    // @ts-expect-error the union is closed; a friendly label invented in the UI is not a source.
    const type: ContextItemResponse["provenance"]["source"]["type"] = "VAULT_SECRET";
    expect(type).toBe("VAULT_SECRET");
  });

  it("rejects a token estimate presented as exact", () => {
    // @ts-expect-error isExact is the literal false: the contract refuses to describe an estimate
    // as a measurement, and so must anything built on it.
    const isExact: ContextPackResponse["usage"]["estimatedTokenCount"]["isExact"] = true;
    expect(isExact).toBe(true);
  });
});

describe("the list limit the client sends", () => {
  /** Captures the path the endpoint builds, without any network. */
  function capturePath() {
    const transport = vi.fn().mockResolvedValue([]);
    return { api: createApi(transport), transport };
  }

  it("sits inside the range the API accepts", () => {
    expect(Number.isInteger(CONTEXT_PACK_LIST_LIMIT)).toBe(true);
    expect(CONTEXT_PACK_LIST_LIMIT).toBeGreaterThanOrEqual(CONTEXT_PACK_LIST_LIMIT_MIN);
    expect(CONTEXT_PACK_LIST_LIMIT).toBeLessThanOrEqual(CONTEXT_PACK_LIST_LIMIT_MAX);
  });

  it("is spelled as a plain decimal integer", async () => {
    const { api, transport } = capturePath();

    await api.listContextPacks("p1");

    const path = transport.mock.calls[0][0] as string;
    const limit = new URL(path, "https://example.test").searchParams.get("limit");
    expect(limit).toBe(String(CONTEXT_PACK_LIST_LIMIT));
    expect(limit).toMatch(/^[0-9]{1,10}$/);
  });

  it("never emits the spellings the API refuses", async () => {
    const { api, transport } = capturePath();

    await api.listContextPacks("p1");

    const path = transport.mock.calls[0][0] as string;
    expect(path).not.toMatch(/limit=$/);
    expect(path).not.toMatch(/limit=0x/i);
    expect(path).not.toMatch(/limit=[+\s]/);
    expect(path).not.toMatch(/limit=[a-z]/i);
  });

  it("escapes path segments so an id cannot retarget the request", async () => {
    const transport = vi.fn().mockResolvedValue({});
    const api = createApi(transport);

    // `projectId` reaches the client from the URL route param. A segment carrying `/`, `?` or `#`
    // must not be able to point the request somewhere else. Every fixture id elsewhere encodes to
    // itself, so without this case the escaping could be deleted with every test still green.
    await api.getContextPack("a/../b", "x?y#z");

    const path = transport.mock.calls[0][0] as string;
    expect(path).toBe("/api/projects/a%2F..%2Fb/context/x%3Fy%23z");
    expect(path.split("/context/")[0]).toBe("/api/projects/a%2F..%2Fb");
  });

  it("addresses the three context routes and no fourth", async () => {
    const transport = vi.fn().mockResolvedValue({});
    const api = createApi(transport);

    await api.listContextPacks("p1");
    await api.getContextPack("p1", "pack-1");
    await api.compileContextPack("p1", { taskReference: "t" });

    const paths = transport.mock.calls.map((call) => call[0] as string);
    expect(paths[0]).toBe(`/api/projects/p1/context?limit=${CONTEXT_PACK_LIST_LIMIT}`);
    expect(paths[1]).toBe("/api/projects/p1/context/pack-1");
    expect(paths[2]).toBe("/api/projects/p1/context/compile");

    // Nothing in the client can ask for a candidate, a pre-redaction body or a policy trace,
    // because no such route is declared.
    const client = createApi(transport) as Record<string, unknown>;
    const contextRoutes = Object.keys(client).filter((name) => /context/i.test(name));
    expect(contextRoutes.sort()).toEqual([
      "compileContextPack",
      "getContextPack",
      "listContextPacks",
    ]);
  });
});
