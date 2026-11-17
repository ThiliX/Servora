import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "coverage"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.ts"],
    languageOptions: { globals: globals.browser },
    rules: {
      // Customer and model text must never be parsed as HTML.
      "no-restricted-properties": [
        "error",
        { property: "innerHTML", message: "Use textContent. Untrusted text must never be parsed as HTML." },
        { property: "outerHTML", message: "Use textContent. Untrusted text must never be parsed as HTML." },
        { property: "insertAdjacentHTML", message: "Use DOM methods. Untrusted text must never be parsed as HTML." },
      ],
    },
  },
  {
    files: ["demo/**/*.{mjs,js}"],
    languageOptions: { globals: { ...globals.node, ...globals.browser } },
  },
);
