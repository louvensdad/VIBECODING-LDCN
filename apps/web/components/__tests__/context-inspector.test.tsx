import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError, ApiUnreachableError } from "@/lib/http";
import { ContextInspector } from "@/components/context-inspector";
import {
  brainItem,
  defaultPack,
  hostileItem,
  hugeItem,
  packWith,
  redactedItem,
  rowsFor,
  taskItem,
} from "./context-fixtures";

// Hoisted with the mock: `vi.mock` runs before the module body, so the spies have to exist by then.
const { getContextPack, compileContextPack, listContextPacks } = vi.hoisted(() => ({
  getContextPack: vi.fn(),
  compileContextPack: vi.fn(),
  listContextPacks: vi.fn(),
}));

// Only the transport is faked. The error classes are the real ones, so a test that asserts on a
// 404 is asserting on the type the application actually throws.
vi.mock("@/lib/api-client", async () => {
  const http = await import("@/lib/http");
  return {
    api: { getContextPack, compileContextPack, listContextPacks },
    ApiRequestError: http.ApiRequestError,
    ApiUnreachableError: http.ApiUnreachableError,
  };
});

const PROJECT_ID = "11111111-1111-4111-8111-111111111111";

/**
 * The narrow no-break space the UI groups digits with, so a long number never wraps mid-value.
 * Named rather than pasted: an invisible character in a string literal is not reviewable, and the
 * obvious "fix" when one of these fails is to replace it with a normal space, which would make the
 * assertion pass against the wrong output.
 */
const NNBSP = " ";

beforeEach(() => {
  getContextPack.mockReset();
  compileContextPack.mockReset();
  listContextPacks.mockReset();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

/** Renders the inspector and waits for the selected pack's detail request to be issued. */
async function renderInspector(packs = [defaultPack]) {
  const view = render(<ContextInspector projectId={PROJECT_ID} initialPacks={rowsFor(packs)} />);
  if (packs.length > 0) {
    await waitFor(() => expect(getContextPack).toHaveBeenCalled());
  }
  return view;
}

function typeInto(element: HTMLElement, value: string) {
  fireEvent.change(element, { target: { value } });
}

describe("pack list", () => {
  it("renders every pack the API returned, in the order it returned them", async () => {
    const older = packWith([brainItem], {
      packId: "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb",
      taskReference: "Modelar o domínio",
    });
    getContextPack.mockResolvedValue(defaultPack);

    await renderInspector([defaultPack, older]);

    // Position, not mere presence. The API returns packs newest-first and re-imposes that order
    // deliberately; a list that reversed or re-sorted its input would pass a "both are on screen"
    // assertion unchanged, which is the failure this test exists to catch.
    const list = screen.getByTestId("pack-list");
    const labels = within(list)
      .getAllByRole("button")
      .map((button) => button.textContent ?? "");
    expect(labels).toHaveLength(2);
    expect(labels[0]).toContain("Implementar autenticação");
    expect(labels[1]).toContain("Modelar o domínio");
    expect(list.textContent).toContain("pacotes (2)");
  });

  it("reads the selected pack from the pack route rather than trusting the list payload", async () => {
    getContextPack.mockResolvedValue(defaultPack);

    await renderInspector();

    expect(getContextPack).toHaveBeenCalledWith(PROJECT_ID, defaultPack.packId);
  });

  it("keeps the newest selection when an earlier request resolves late", async () => {
    const other = packWith([taskItem], {
      packId: "cccccccc-3333-4333-8333-cccccccccccc",
      taskReference: "Segunda tarefa",
    });

    let resolveFirst: (pack: unknown) => void = () => {};
    getContextPack
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce(other);

    render(<ContextInspector projectId={PROJECT_ID} initialPacks={rowsFor([defaultPack, other])} />);
    await waitFor(() => expect(getContextPack).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByText("Segunda tarefa"));
    await waitFor(() => expect(screen.getByTestId("pack-detail")).toBeInTheDocument());

    // The first request lands last. It must not repaint the pack the user moved away from.
    resolveFirst(defaultPack);

    await waitFor(() =>
      expect(screen.getByTestId("pack-summary").textContent).toContain(other.packId),
    );
    expect(screen.getByTestId("pack-summary").textContent).not.toContain(defaultPack.packId);
  });
});

describe("pack detail", () => {
  it("renders the summary: id, instant, sizes and fingerprint", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    const summary = screen.getByTestId("pack-summary").textContent ?? "";
    expect(summary).toContain(defaultPack.packId);
    expect(summary).toContain("2026-09-06 14:05:09 UTC");
    expect(summary).toContain(defaultPack.contentFingerprint);
    expect(summary).toContain("Implementar autenticação");
  });

  it("renders each item with its kind, label and position in the pack", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    const items = screen.getAllByTestId("context-item");
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText("ARCHITECTURE")).toBeInTheDocument();
    expect(within(items[0]).getByText("Backend em Java 21")).toBeInTheDocument();
    expect(items[0].textContent).toContain("1/2");
    expect(items[1].textContent).toContain("2/2");
  });

  it("renders provenance exactly as the backend reported it", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    const items = screen.getAllByTestId("context-item");
    const first = items[0].textContent ?? "";
    expect(first).toContain("BRAIN_ENTRY");
    expect(first).toContain(brainItem.provenance.source.sourceId);
    expect(first).toContain("v4");
    expect(first).toContain(brainItem.admission.policyRuleId);
    expect(first).toContain(brainItem.admission.explanation);
    expect(first).toContain("2026-09-01 12:30:00 UTC");

    // A source the record carries no revision for must not be given one.
    expect(items[1].textContent ?? "").not.toMatch(/·\s*v\d/);
  });

  it("counts the origins present in the pack, most frequent first", async () => {
    // Two BRAIN_ENTRY items against one CURRENT_TASK, so the count and the ordering both have
    // something to be wrong about.
    const pack = packWith([brainItem, taskItem, redactedItem]);
    getContextPack.mockResolvedValue(pack);
    await renderInspector([pack]);

    // The counts and their order, not just the labels: a breakdown that showed every origin as 1,
    // or sorted them arbitrarily, would pass an assertion that only looked for the names.
    const entries = within(screen.getByTestId("source-breakdown"))
      .getAllByRole("listitem")
      .map((row) => row.textContent ?? "");
    expect(entries).toEqual(["BRAIN_ENTRY2", "CURRENT_TASK1"]);
  });
});

describe("budget", () => {
  it("shows used against the ceiling for all three dimensions", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    // Digits are grouped with U+202F (narrow no-break space) so a number never wraps mid-value.
    // Spelled as an escape here so the expectation cannot be "fixed" by pasting a normal space.
    const budget = screen.getByTestId("budget-panel").textContent ?? "";
    expect(budget).toContain("2 / 50");
    expect(budget).toContain(`106 / 200${NNBSP}000`);
    expect(budget).toContain(`110 / 400${NNBSP}000`);
  });

  it("never presents the token number as a measurement", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    expect(screen.getByTestId("estimated-tokens")).toHaveTextContent("~2 310");
    expect(screen.getByText("ESTIMADO")).toBeInTheDocument();

    const summary = screen.getByTestId("pack-summary").textContent ?? "";
    expect(summary).toContain("não medido");
    expect(summary).toContain("characters/4");

    // The budget has no token dimension, and the screen must not imply one.
    expect(screen.getByTestId("budget-panel").textContent ?? "").not.toMatch(/\/ *\d+ *tokens/i);
  });
});

describe("security wording", () => {
  it("says the pack is redacted without claiming it is safe", async () => {
    getContextPack.mockResolvedValue(packWith([redactedItem]));
    await renderInspector();

    const status = screen.getByTestId("redaction-status").textContent ?? "";
    expect(status).toContain("REDIGIDO");
    expect(status).toContain("detecção é por padrões");
    expect(status).toContain("não é garantia");

    // Claims the pattern-based redactor cannot support must not appear anywhere on the screen.
    const page = document.body.textContent ?? "";
    expect(page).not.toMatch(/100% segur/i);
    expect(page).not.toMatch(/nenhum dado sensível/i);
    expect(page).not.toMatch(/à prova de segredos/i);
  });

  it("displays the backend's content verbatim and redacts nothing itself", async () => {
    getContextPack.mockResolvedValue(packWith([redactedItem]));
    await renderInspector();

    // The guarantee this test can actually make: what is on screen is exactly the string the API
    // returned. The frontend runs no redactor of its own, and removes nothing from the response.
    // Proving that the backend redacted correctly is the backend suite's job, not this one's.
    expect(screen.getByTestId("item-content").textContent).toBe(redactedItem.content);
    expect(screen.getByTestId("item-content").textContent).toContain("[REDACTED]");
  });
});

describe("provider execution", () => {
  it("states that the pack has not been sent to a provider", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    const status = screen.getByTestId("provider-status").textContent ?? "";
    expect(status).toContain("NÃO ATIVA");
    expect(status).toContain("Não há execução por provedor nesta versão");
    expect(status).toContain("não envia contexto a nenhum modelo");
  });

  it("exposes no control that could run a model", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    const forbidden = /enviar|send|executar|execute|run ai|autopilot|claude|gpt|gemini|deepseek/i;
    for (const control of screen.getAllByRole("button")) {
      expect(control.textContent ?? "").not.toMatch(forbidden);
    }
  });
});

describe("untrusted content", () => {
  it("renders script and handler markup as text, never as DOM", async () => {
    getContextPack.mockResolvedValue(packWith([hostileItem]));
    await renderInspector();

    const content = screen.getByTestId("item-content");
    expect(content.textContent).toBe(hostileItem.content);
    expect(content.querySelector("script")).toBeNull();
    expect(content.querySelector("img")).toBeNull();
    expect(content.querySelector("a")).toBeNull();

    // Nothing this content contains was parsed into an executable node anywhere in the document.
    expect(document.querySelectorAll("script")).toHaveLength(0);
    expect(document.querySelector("img[onerror]")).toBeNull();
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull();
  });

  it("shows a hostile label as text too", async () => {
    getContextPack.mockResolvedValue(packWith([hostileItem]));
    await renderInspector();

    expect(screen.getByText(hostileItem.label)).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
  });
});

describe("large content", () => {
  it("previews a huge item instead of rendering it whole", async () => {
    getContextPack.mockResolvedValue(packWith([hugeItem]));
    await renderInspector();

    expect(screen.getByTestId("item-content").textContent).toHaveLength(600);
    expect(screen.getByTestId("content-extent").textContent ?? "").toContain(
      `prévia de 600 de 500${NNBSP}000 caracteres`,
    );
  });

  it("bounds the expanded view and says how much of the item is shown", async () => {
    getContextPack.mockResolvedValue(packWith([hugeItem]));
    await renderInspector();

    fireEvent.click(screen.getByRole("button", { name: "Expandir" }));

    expect(screen.getByTestId("item-content").textContent).toHaveLength(20_000);
    expect(screen.getByTestId("content-extent").textContent ?? "").toContain(
      `exibindo 20${NNBSP}000 de 500${NNBSP}000 caracteres`,
    );
  });

  it("does not offer expansion for content that already fits", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    expect(screen.queryByRole("button", { name: "Expandir" })).not.toBeInTheDocument();
  });
});

describe("states", () => {
  it("renders the empty state and asks for no pack when the project has none", async () => {
    await renderInspector([]);

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
    expect(screen.queryByTestId("pack-detail")).not.toBeInTheDocument();
    expect(getContextPack).not.toHaveBeenCalled();
  });

  it("shows a loading state that resolves, never a permanent spinner", async () => {
    let resolve: (pack: unknown) => void = () => {};
    getContextPack.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );

    render(<ContextInspector projectId={PROJECT_ID} initialPacks={rowsFor([defaultPack])} />);
    expect(await screen.findByTestId("detail-loading")).toBeInTheDocument();

    resolve(defaultPack);
    await waitFor(() => expect(screen.getByTestId("pack-detail")).toBeInTheDocument());
    expect(screen.queryByTestId("detail-loading")).not.toBeInTheDocument();
  });

  it("leaves the loading state when the API never answers", async () => {
    vi.useFakeTimers();
    try {
      // A connection the API accepts and never answers. Without a bound this is the one state with
      // no way out: the loading pane has no retry, so the user's only recovery would be a reload.
      getContextPack.mockImplementation(() => new Promise(() => {}));

      render(<ContextInspector projectId={PROJECT_ID} initialPacks={rowsFor([defaultPack])} />);
      // Wrapped in act: the timeout rejects inside a promise, and the state update it triggers has
      // to be flushed before the assertion looks at the DOM.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(20_000);
      });

      const error = screen.getByTestId("detail-error");
      expect(error).toBeInTheDocument();
      expect(within(error).getByRole("button", { name: /tentar de novo/i })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a 404 as a missing pack, with no stack trace", async () => {
    getContextPack.mockRejectedValue(
      new ApiRequestError(404, "NOT_FOUND", "Context pack not found: abc"),
    );
    await renderInspector();

    const error = await screen.findByTestId("detail-error");
    expect(error.textContent ?? "").toContain("não existe neste projeto");
    expect(error.textContent ?? "").not.toMatch(/\bat \w|\.java|Exception|SQLSTATE/);
  });

  it("reports a 500 without rendering a trace", async () => {
    getContextPack.mockRejectedValue(new ApiRequestError(500, "INTERNAL", "A requisição falhou"));
    await renderInspector();

    const error = await screen.findByTestId("detail-error");
    expect(error.textContent ?? "").toContain("A requisição falhou");
    expect(error.textContent ?? "").not.toMatch(/\bat \w|\.java|Exception|SQLSTATE/);
  });

  it("reports an unreachable API and can retry", async () => {
    getContextPack.mockRejectedValueOnce(new ApiUnreachableError(new Error("ECONNREFUSED")));
    await renderInspector();

    expect(await screen.findByTestId("detail-error")).toBeInTheDocument();

    getContextPack.mockResolvedValueOnce(defaultPack);
    fireEvent.click(screen.getByRole("button", { name: /tentar de novo/i }));

    await waitFor(() => expect(screen.getByTestId("pack-detail")).toBeInTheDocument());
  });
});

describe("compile", () => {
  it("compiles through the compile route only, and selects the result", async () => {
    const compiled = packWith([taskItem], {
      packId: "dddddddd-4444-4444-8444-dddddddddddd",
      taskReference: "Nova tarefa",
    });
    getContextPack.mockResolvedValue(compiled);
    compileContextPack.mockResolvedValue(compiled);

    await renderInspector([]);

    typeInto(screen.getByLabelText(/tarefa para a qual/i), "Nova tarefa");
    fireEvent.click(screen.getByRole("button", { name: "Compilar" }));

    await waitFor(() =>
      expect(compileContextPack).toHaveBeenCalledWith(PROJECT_ID, {
        taskReference: "Nova tarefa",
      }),
    );
    await waitFor(() => expect(screen.getByTestId("pack-detail")).toBeInTheDocument());
  });

  it("surfaces a refused compile as a message, not a crash", async () => {
    compileContextPack.mockRejectedValue(
      new ApiRequestError(422, "INVALID_STATE", "Redaction emptied the task reference"),
    );

    await renderInspector([]);

    typeInto(screen.getByLabelText(/tarefa para a qual/i), "x");
    fireEvent.click(screen.getByRole("button", { name: "Compilar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Redaction emptied the task reference",
    );
  });
});

describe("bounded rendering", () => {
  it("caps how many item cards are rendered and says where it cut", async () => {
    const many = Array.from({ length: 140 }, (_, index) => ({
      ...taskItem,
      id: `item-${index}`,
      label: `Item ${index}`,
    }));
    getContextPack.mockResolvedValue(packWith(many));
    await renderInspector([packWith(many)]);

    expect(screen.getAllByTestId("context-item")).toHaveLength(100);
    expect(screen.getByTestId("item-list-extent").textContent ?? "").toContain(
      `exibindo 100 de 140 itens`,
    );
    // The count in the heading is the pack's, not the rendered subset's: the cap is a view
    // decision and must not be reported as the pack being smaller than it is.
    expect(screen.getByTestId("pack-detail").textContent ?? "").toContain("itens do pacote (140)");
  });

  it("does not announce a cut when every item is rendered", async () => {
    getContextPack.mockResolvedValue(defaultPack);
    await renderInspector();

    expect(screen.queryByTestId("item-list-extent")).not.toBeInTheDocument();
  });
});

describe("project scoping", () => {
  it("refuses to display a pack belonging to another project", async () => {
    getContextPack.mockResolvedValue(
      packWith([taskItem], { projectId: "99999999-9999-4999-8999-999999999999" }),
    );
    await renderInspector();

    const error = await screen.findByTestId("detail-error");
    expect(error.textContent ?? "").toContain("pertence a outro projeto");
    expect(screen.queryByTestId("pack-detail")).not.toBeInTheDocument();
  });
});

describe("browser storage", () => {
  it("persists no part of a pack in localStorage, sessionStorage or IndexedDB", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");

    // jsdom ships no IndexedDB, so there is nothing to spy on unless one is provided. Installing a
    // stand-in is what makes the assertion meaningful: if the component ever opened a database,
    // this is the object it would reach.
    const open = vi.fn();
    Object.defineProperty(window, "indexedDB", { value: { open }, configurable: true });

    getContextPack.mockResolvedValue(packWith([redactedItem, hugeItem]));
    await renderInspector();
    fireEvent.click(screen.getByRole("button", { name: "Expandir" }));

    expect(setItem).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});
