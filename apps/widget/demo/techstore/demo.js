// Embeds the widget the way a customer's website would, taking the key from the address bar.
// In development the loader is served by the widget app's dev server; a production build serves
// it as /servora-widget.js.
const params = new URLSearchParams(location.search);
const key = params.get("key");
if (!key) {
  document.getElementById("missing").hidden = false;
} else {
  const script = document.createElement("script");
  script.type = "module";
  script.src = (params.get("widget") ?? "http://localhost:5174") + (params.get("loader") ?? "/src/loader.ts");
  script.dataset.widgetKey = key;
  script.dataset.apiBase = params.get("api") ?? "http://localhost:8080";
  document.body.append(script);
}
