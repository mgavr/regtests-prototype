"use strict";

// The live picture of who is calling whom, and the code behind the latest step.
// Both only read the state the page already has: the mode, the processes and the latest step.

const NS = "http://www.w3.org/2000/svg";

const NODES = {
  caller: { x: 20, y: 105, w: 170, h: 90, title: "Caller", sub: " " },
  filter: { x: 285, y: 105, w: 120, h: 90, title: "Servlet", sub: "filter" },
  code: { x: 435, y: 105, w: 120, h: 90, title: "Service", sub: "code" },
  http: { x: 590, y: 52, w: 125, h: 68, title: "HTTP hooks" },
  jdbc: { x: 590, y: 180, w: 125, h: 68, title: "JDBC hook" },
  partner: { x: 805, y: 42, w: 170, h: 88, title: "Fake partner", sub: " " },
  database: { x: 805, y: 170, w: 170, h: 88, title: "Database", sub: " " },
  recordings: { x: 1040, y: 105, w: 130, h: 90, title: "Recordings" },
};

const ARROWS = {
  callerIn: [[190, 135], [283, 135]],
  callerOut: [[285, 165], [192, 165]],
  filterCode: [[405, 150], [433, 150]],
  codeHttp: [[555, 130], [588, 96]],
  codeJdbc: [[555, 170], [588, 204]],
  httpPartner: [[715, 86], [803, 86]],
  jdbcDatabase: [[715, 214], [803, 214]],
  httpRecordings: [[652, 50], [652, 12], [1105, 12], [1105, 103]],
  jdbcRecordings: [[652, 250], [652, 288], [1105, 288], [1105, 197]],
};

// Latest step title -> what lights up. Checked in order; the first match wins.
const MOMENTS = [
  [/^Request arrived|^Request id stamped/, { arrows: ["callerIn", "filterCode"], nodes: ["caller", "filter"] }],
  [/^Case loaded/, { arrows: [], nodes: ["filter", "recordings"] }],
  [/^Database call captured/, { arrows: ["codeJdbc", "jdbcDatabase", "jdbcRecordings"], nodes: ["code", "jdbc", "database", "recordings"] }],
  [/^Database call answered/, { arrows: ["codeJdbc", "jdbcRecordings"], nodes: ["code", "jdbc", "recordings"] }],
  [/^Outbound call captured/, { arrows: ["codeHttp", "httpPartner", "httpRecordings"], nodes: ["code", "http", "partner", "recordings"] }],
  [/^Outbound call answered/, { arrows: ["codeHttp", "httpRecordings"], nodes: ["code", "http", "recordings"] }],
  [/^Request id carried/, { arrows: [], nodes: ["code"] }],
  [/^Personal data masked|^Case saved/, { arrows: [], nodes: ["recordings"] }],
  [/^Response returned/, { arrows: ["callerOut"], nodes: ["filter", "caller"] }],
  [/compared|^PASS$|^FAIL$/, { arrows: [], nodes: ["filter", "recordings"] }],
  [/^DataSource wrapped|^Real DataSource swapped/, { arrows: [], nodes: ["jdbc"] }],
  [/^Database is up|^Database stopped/, { arrows: [], nodes: ["database"] }],
  [/^Fake partner service/, { arrows: [], nodes: ["partner"] }],
  [/^Service|^Capture library/, { arrows: [], nodes: ["filter", "code", "http", "jdbc"] }],
];

function svg(tag, attributes, text) {
  const node = document.createElementNS(NS, tag);
  for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, value);
  if (text !== undefined) node.textContent = text;
  return node;
}

function marker(id) {
  const shape = svg("marker", { id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto-start-reverse" });
  shape.append(svg("path", { d: "M0 0 L10 5 L0 10 z", class: id }));
  return shape;
}

function buildDiagram(host) {
  const root = svg("svg", { viewBox: "0 0 1190 300", role: "img", "aria-label": "Who is calling whom right now" });
  const defs = svg("defs", {});
  defs.append(marker("tip"), marker("tip-on"), marker("tip-cut"));
  root.append(defs);
  root.append(svg("rect", { class: "box", x: 265, y: 26, width: 470, height: 248, rx: 12 }));
  const service = svg("text", { class: "group", x: 280, y: 50 }, "Service under test");
  root.append(service);

  const arrows = {};
  for (const name of Object.keys(ARROWS)) {
    const line = svg("polyline", { class: "arrow", "marker-end": "url(#tip)" });
    root.append(line);
    arrows[name] = line;
  }
  const nodes = {};
  for (const [name, node] of Object.entries(NODES)) {
    const group = svg("g", { class: "node" });
    group.append(svg("rect", { x: node.x, y: node.y, width: node.w, height: node.h, rx: 8 }));
    const middle = node.y + node.h / 2;
    const centre = node.x + node.w / 2;
    group.append(svg("text", { x: centre, y: node.sub ? middle - 5 : middle + 7, "text-anchor": "middle" }, node.title));
    const sub = svg("text", { class: "sub", x: centre, y: middle + 20, "text-anchor": "middle" }, node.sub || "");
    group.append(sub);
    root.append(group);
    nodes[name] = { group, sub };
  }
  host.append(root);
  return { arrows, nodes, service };
}

const picture = buildDiagram(document.getElementById("diagram-art"));

function pointsFor(name, reversed) {
  const points = reversed ? [...ARROWS[name]].reverse() : ARROWS[name];
  return points.map((point) => point.join(",")).join(" ");
}

function renderDiagram(state) {
  const process = (name) => state.processes.find((item) => item.name === name);
  const mode = process("service")?.mode || "";
  const replaying = mode === "replay";
  const latest = state.steps[state.steps.length - 1];
  const title = latest?.title || "";
  const moment = MOMENTS.find(([pattern]) => pattern.test(title))?.[1] || { arrows: [], nodes: [] };

  picture.service.textContent = mode ? `Service under test: mode ${mode}` : "Service under test";
  picture.nodes.caller.sub.textContent = replaying ? "Python test" : "Sample request";

  for (const name of ["partner", "database"]) {
    const up = process(name)?.running;
    picture.nodes[name].group.classList.toggle("down", !up);
    const state_ = up ? "running" : "STOPPED";
    picture.nodes[name].sub.textContent = name === "partner" ? `service, ${state_}` : state_;
  }

  for (const [name, line] of Object.entries(picture.arrows)) {
    const reversed = replaying && (name === "httpRecordings" || name === "jdbcRecordings");
    const cut = (name === "httpPartner" && !process("partner")?.running) || (name === "jdbcDatabase" && !process("database")?.running);
    const on = moment.arrows.includes(name);
    line.setAttribute("points", pointsFor(name, reversed));
    line.setAttribute("class", `arrow${on ? " on" : ""}${cut ? " cut" : ""}`);
    line.setAttribute("marker-end", `url(#${cut ? "tip-cut" : on ? "tip-on" : "tip"})`);
  }
  for (const [name, node] of Object.entries(picture.nodes)) node.group.classList.toggle("on", moment.nodes.includes(name));

  const stage = state.stages.find((item) => item.id === state.stage);
  document.getElementById("diagram-now").textContent = latest ? `${stage ? `${stage.title}: ` : ""}${title}` : "Nothing has happened yet.";
  renderSnippet(latest);
}

// The code panel. snippets.json is made from the REG pointers in the source when the image is built.
let snippets = {};
fetch("snippets.json").then((response) => (response.ok ? response.json() : {})).then((loaded) => {
  snippets = loaded;
  if (current) renderDiagram(current);
});

function renderSnippet(step) {
  const snippet = step && snippets[step.reg];
  document.getElementById("code-file").textContent = snippet ? `${step.reg} · ${snippet.file}:${snippet.line}` : "";
  document.getElementById("code-text").textContent = snippet ? snippet.code : "The code behind each step shows here.";
}
