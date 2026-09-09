import { resolve } from "node:path";
import { defineConfig } from "vitest/config";

/**
 * The web app's test runner.
 *
 * No React plugin: oxc is told to use the automatic JSX runtime directly, which is all these
 * component tests need. The alternative — adding `@vitejs/plugin-react` — is a dependency for Fast
 * Refresh and Babel features that a test run does not use. The setting has to be given here because
 * `tsconfig.json` says `jsx: "preserve"`, which Next requires and which would otherwise leave JSX
 * untransformed in the test build.
 *
 * `.mts` rather than `.ts` so the config is loaded as ESM, which is what it is.
 *
 * The aliases mirror `tsconfig.json`. They are repeated rather than derived because a wrong path
 * here fails loudly on the first import, whereas a plugin that reads tsconfig would be one more
 * thing to keep installed.
 */
export default defineConfig({
  oxc: { jsx: { runtime: "automatic" } },
  resolve: {
    alias: {
      "@vibecode/contracts": resolve(import.meta.dirname, "../../packages/contracts/src/index.ts"),
      "@": resolve(import.meta.dirname, "."),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    include: ["**/*.test.ts", "**/*.test.tsx"],
    exclude: ["node_modules/**", ".next/**"],
    restoreMocks: true,
  },
});
