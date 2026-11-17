// Serves the two demo websites on separate origins, so each can be allowed on its own widget:
//   TechStore  http://localhost:5181
//   HotelPro   http://localhost:5182
// Open a site with its widget key, for example http://localhost:5181/?key=wk_...
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const sites = [
  { name: "techstore", port: 5181 },
  { name: "hotelpro", port: 5182 },
];
const types = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript" };

for (const site of sites) {
  const root = join(here, site.name);
  createServer(async (request, response) => {
    const path = new URL(request.url ?? "/", "http://localhost").pathname;
    const file = normalize(join(root, path === "/" ? "index.html" : path));
    if (!file.startsWith(root)) {
      response.writeHead(403).end();
      return;
    }
    try {
      const body = await readFile(file);
      const extension = file.slice(file.lastIndexOf("."));
      response.writeHead(200, { "Content-Type": types[extension] ?? "application/octet-stream" }).end(body);
    } catch {
      response.writeHead(404).end("Not found");
    }
  }).listen(site.port, "127.0.0.1", () => {
    console.log(`${site.name} demo: http://localhost:${site.port}/?key=<widget key>`);
  });
}
