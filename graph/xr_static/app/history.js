// Generic history rendering. No audio, clock, or propagator policy lives here.
export const historyLayout = (samples, width = 760) => {
  const timed = samples.filter((sample) => Number.isFinite(sample["sample/time-ms"]));
  if (timed.length === 0) return { rows: [], start: 0, end: 0, width };
  const end = Math.max(...timed.map((sample) => sample["sample/time-ms"]));
  const start = Math.max(0, end - 30000);
  const span = Math.max(1, end - start);
  const left = 150;
  const lanes = new Map();
  for (const sample of timed) {
    const time = sample["sample/time-ms"];
    if (time < start) continue;
    const id = sample["sample/node"];
    if (!lanes.has(id)) lanes.set(id, { id, label: sample["sample/label"], points: [] });
    lanes.get(id).points.push({ sample, x: left + (time - start) * (width - left - 12) / span,
      width: Math.max(2, sample["sample/duration-ms"] * (width - left - 12) / span) });
  }
  return { rows: [...lanes.values()], start, end, width };
};

export const renderHistory = (body, view, inspect) => {
  const { samples = [], dropped = 0 } = view;
  const layout = historyLayout(samples, Math.max(360, body.clientWidth));
  if (layout.rows.length === 0) {
    const text = document.createElement("pre");
    text.textContent = "No timed activations yet.";
    body.append(text);
    return;
  }
  const make = (tag, attributes, text) => {
    const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const svg = make("svg", { viewBox: `0 0 ${layout.width} ${layout.rows.length * 28 + 40}`,
    role: "img", "aria-label": "Activation history" });
  svg.style.width = "100%";
  for (const [index, row] of layout.rows.entries()) {
    const y = index * 28 + 20;
    svg.append(make("text", { x: 4, y, "font-size": 12, fill: "#eee" }, String(row.label).slice(0, 21)));
    svg.append(make("line", { x1: 150, x2: layout.width - 12, y1: y - 4, y2: y - 4, stroke: "#555" }));
    for (const point of row.points) {
      const mark = make("rect", { x: point.x, y: y - 10, width: point.width, height: 12, fill: "#eee", tabindex: 0 });
      mark.append(make("title", {}, `${row.label} · tick ${point.sample["sample/tick"]} · ${point.sample["sample/duration-ms"].toFixed(3)} ms`));
      const select = () => inspect({ id: row.id, label: row.label, source: point.sample.source });
      mark.addEventListener("click", select);
      mark.addEventListener("keydown", (event) => { if (event.key === "Enter") select(); });
      svg.append(mark);
    }
  }
  svg.append(make("text", { x: 4, y: layout.rows.length * 28 + 24, "font-size": 11, fill: "#eee" },
    `${(layout.start / 1000).toFixed(1)}–${(layout.end / 1000).toFixed(1)} s · ${dropped} older samples omitted`));
  body.append(svg);
};
