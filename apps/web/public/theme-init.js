// Applies the saved or system colour theme before the page paints, so it never flashes the wrong
// one. A separate file because the Content Security Policy allows no inline scripts.
// Kept in sync with src/lib/theme.ts.
(function () {
  var preference = "system";
  try {
    preference = window.localStorage.getItem("servora:theme") || "system";
  } catch (e) {
    // Storage blocked: follow the system.
  }
  var dark =
    preference === "dark" ||
    (preference !== "light" && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
})();
