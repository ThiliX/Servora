import { defineConfig } from "vitest/config";

// Two outputs: the iframe page (widget.html) and the loader that websites embed. The loader keeps a
// stable name, servora-widget.js, because customers paste its URL into their pages.
export default defineConfig({
  server: {
    port: 5174,
    strictPort: true,
  },
  build: {
    rollupOptions: {
      input: {
        widget: "widget.html",
        loader: "src/loader.ts",
      },
      output: {
        entryFileNames: (chunk) => (chunk.name === "loader" ? "servora-widget.js" : "assets/[name]-[hash].js"),
      },
    },
  },
  test: {
    environment: "jsdom",
    restoreMocks: true,
  },
});
