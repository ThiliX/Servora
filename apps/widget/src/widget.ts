import { ChatWidget } from "./chat";
import { asLoaderMessage, type FromWidget } from "./protocol";
import "./style.css";

// Entry point of the iframe page. It trusts only messages from the window that embeds it, and
// sends its own messages to that window only.
const root = document.getElementById("servora-root")!;

function postToParent(message: FromWidget): void {
  // The parent's origin is not known in advance; the messages carry nothing secret.
  window.parent.postMessage(message, "*");
}

const widget = new ChatWidget({
  root,
  fetch: window.fetch.bind(window),
  postToParent,
  newKey: () => crypto.randomUUID(),
});

window.addEventListener("message", (event) => {
  if (event.source !== window.parent) {
    return;
  }
  const message = asLoaderMessage(event.data);
  if (message) {
    widget.handleLoaderMessage(message);
  }
});

postToParent({ source: "servora-widget", type: "ready" });
