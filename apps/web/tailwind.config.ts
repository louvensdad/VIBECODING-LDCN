import type { Config } from "tailwindcss";

/**
 * The workspace theme: graphite-blue ground, indigo accents, quiet surfaces.
 *
 * Colours live here as named tokens rather than as literals scattered through components, so the
 * look can be adjusted in one place.
 */
export default {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ground: "#090d18",
        surface: {
          DEFAULT: "#111728",
          raised: "#161d31",
          sunken: "#0c1120",
        },
        edge: {
          DEFAULT: "#26304a",
          strong: "#33405f",
        },
        ink: {
          DEFAULT: "#dce3fa",
          muted: "#8f9ab5",
          faint: "#616d8a",
        },
        accent: {
          DEFAULT: "#6366f1",
          soft: "#818cf8",
          dim: "#4338ca",
        },
        signal: {
          ok: "#34d399",
          warn: "#fbbf24",
          bad: "#f87171",
          idle: "#64748b",
        },
      },
      fontFamily: {
        sans: ["Manrope", "system-ui", "sans-serif"],
        mono: ["DM Mono", "ui-monospace", "monospace"],
      },
      borderRadius: {
        card: "14px",
      },
    },
  },
  plugins: [],
} satisfies Config;
