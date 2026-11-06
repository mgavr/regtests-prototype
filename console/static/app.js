"use strict";

const byId = (id) => document.getElementById(id);
let shownSteps = 0;
let current = null;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function post(path) {
  fetch(path, { method: "POST" });
}

function clock(ts) {
  return new Date(ts).toLocaleTimeString([], { hour12: false }) + "." + String(ts % 1000).padStart(3, "0");
}

const SOURCES = { library: "from the library", test: "from the Python test", console: "from the console" };

function stepNode(step) {
  const item = element("li", "step");
  if (step.tone) item.classList.add(step.tone);
  const head = element("div", "step-head");
  head.append(element("span", "reg", step.reg), element("span", "title", step.title));
  if (step.label) head.append(element("span", "label", step.label));
  item.append(head, element("p", "detail", step.detail));
  const meta = element("p", "meta");
  const file = element("code", "", step.file);
  meta.append(file, ` · ${SOURCES[step.source] || step.source} · ${clock(step.ts)}`);
  item.append(meta);
  if (step.via) {
    const via = element("p", "meta");
    via.append("called from ", element("code", "", step.via));
    item.append(via);
  }
  if (step.report) item.append(reportNode(step.report));
  return item;
}

function reportNode(report) {
  const table = element("table", "differences");
  const head = element("tr");
  head.append(element("th", "", "Where"), element("th", "", "Expected"), element("th", "", "Got"));
  table.append(head);
  for (const found of report.differences) {
    const row = element("tr");
    row.append(element("td", "", `${found.where} ${found.path}`), element("td", "expected", found.expected), element("td", "actual", found.actual));
    table.append(row);
  }
  const node = element("div");
  node.append(table);
  for (const line of [...report.unexpectedCalls, ...report.missingCalls.map((call) => `Recorded but never made: ${call}`)]) {
    node.append(element("p", "detail", line));
  }
  if (report.serviceError) node.append(element("p", "detail", `The service failed with: ${report.serviceError}`));
  return node;
}

function renderSteps(state) {
  const list = byId("steps");
  if (state.steps.length < shownSteps) {
    list.replaceChildren();
    shownSteps = 0;
  }
  for (const step of state.steps.slice(shownSteps)) {
    list.querySelector(".current")?.classList.remove("current");
    const node = stepNode(step);
    if (!step.tone) node.classList.add("current");
    list.append(node);
  }
  const grew = state.steps.length !== shownSteps;
  shownSteps = state.steps.length;
  return grew;
}

function verdictFor(state, stageId) {
  return state.steps.find((step) => step.stage === stageId && step.verdict)?.verdict;
}

function renderStages(state) {
  const list = byId("stages");
  list.replaceChildren();
  const currentIndex = state.stages.findIndex((stage) => stage.id === state.stage);
  state.stages.forEach((stage, index) => {
    const item = element("li");
    item.append(element("strong", "", `${index + 1}. ${stage.title}`), element("span", "", stage.summary));
    const verdict = verdictFor(state, stage.id);
    if (verdict) item.classList.add(verdict.toLowerCase());
    else if (index < currentIndex) item.classList.add("pass");
    else if (index === currentIndex) item.classList.add("active");
    list.append(item);
  });
}

function renderProcesses(state) {
  const rows = state.processes.map((process) => {
    const row = element("tr");
    const name = element("td", "", process.label);
    const facts = process.running
      ? `pid ${process.pid}, port ${process.port}${process.mode ? `, mode ${process.mode}` : ""}`
      : `port ${process.port} closed`;
    name.append(element("small", "", facts));
    const status = element("td");
    status.append(element("span", `chip ${process.running ? "up" : "down"}`, process.running ? "RUNNING" : "STOPPED"));
    row.append(name, status);
    return row;
  });
  byId("processes").replaceChildren(...rows);
}

function renderResult(state) {
  const last = [...state.steps].reverse().find((step) => step.verdict);
  byId("result").hidden = !last;
  if (!last) return;
  const verdict = byId("verdict");
  verdict.textContent = last.verdict;
  verdict.className = `verdict ${last.verdict.toLowerCase()}`;
}

function renderStatus(state) {
  const status = byId("status");
  status.classList.toggle("error", Boolean(state.error));
  if (state.error) status.textContent = state.error;
  else if (state.busy) status.textContent = `${state.busy}...`;
  else if (state.finished) status.textContent = "Finished. Press Restart to go again.";
  else if (state.playing) status.textContent = "Playing, about one step a second.";
  else if (state.steps.length === 0) status.textContent = "Ready. Press Run, or Next for one step at a time.";
  else status.textContent = "Paused.";
}

function renderControls(state) {
  const blocked = state.finished || Boolean(state.error);
  byId("run").textContent = state.playing ? "Pause" : "Run";
  byId("run").disabled = blocked;
  byId("next").disabled = blocked;
}

function render(state) {
  current = state;
  const grew = renderSteps(state);
  renderStages(state);
  renderProcesses(state);
  renderResult(state);
  renderStatus(state);
  renderControls(state);
  if (grew) byId("steps").lastElementChild?.scrollIntoView({ block: "end" });
}

byId("run").addEventListener("click", () => post(current?.playing ? "/api/pause" : "/api/run"));
byId("next").addEventListener("click", () => post("/api/next"));
byId("restart").addEventListener("click", () => post("/api/restart"));

new EventSource("/api/events").onmessage = (message) => render(JSON.parse(message.data));
