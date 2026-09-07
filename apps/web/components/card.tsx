import type { ReactNode } from "react";

interface SectionCardProps {
  title: string;
  children: ReactNode;
  /** Marks the card the user should look at first. */
  emphasis?: boolean;
  action?: ReactNode;
}

export function SectionCard({ title, children, emphasis = false, action }: SectionCardProps) {
  return (
    <article
      className={`card p-5 ${emphasis ? "border-accent/50 bg-accent/[0.07]" : ""}`}
    >
      <header className="flex items-start justify-between gap-3">
        <h2 className="label">{title}</h2>
        {action}
      </header>
      <div className="mt-3 text-sm leading-6 text-ink">{children}</div>
    </article>
  );
}

const TONE_CLASSES = {
  ok: "border-signal-ok/30 bg-signal-ok/10 text-signal-ok",
  warn: "border-signal-warn/30 bg-signal-warn/10 text-signal-warn",
  bad: "border-signal-bad/30 bg-signal-bad/10 text-signal-bad",
  idle: "border-edge-strong bg-surface-raised text-ink-muted",
  accent: "border-accent/40 bg-accent/10 text-accent-soft",
} as const;

export type Tone = keyof typeof TONE_CLASSES;

export function Pill({ tone = "idle", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 font-mono text-[11px] ${TONE_CLASSES[tone]}`}
    >
      {children}
    </span>
  );
}

export function ProgressBar({ value, label }: { value: number; label?: string }) {
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-surface-sunken"
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label ?? "Progress"}
      >
        <div className="h-full rounded-full bg-accent" style={{ width: `${clamped}%` }} />
      </div>
    </div>
  );
}

/** Marks data that is not real yet, so a placeholder is never mistaken for a measurement. */
export function PlaceholderNote({ children }: { children: ReactNode }) {
  return <p className="mt-3 font-mono text-[11px] text-ink-faint">{children}</p>;
}
