import { forceCardPositions } from "./card-layout.js";
import { placeCardLabels } from "./card-labels.js";
import { reconcilePositions, zoomWindow, pinchWindow } from "./desktop-state.js";

// SVG marker IDs only need uniqueness within this page, not secure randomness.
let nextArrowId = 0;

const svgElement = (tag, attributes = {}) => {
  const element = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, value);
  return element;
};

export const graphWindow = (body, getState, setState, inspect) => {
  const svg = svgElement("svg", { viewBox: "0 0 768 504", "aria-label": "Interactive dataflow graph" });
  svg.classList.add("desktop-graph");
  body.append(svg);
  const world = svgElement("g");
  const arrowId = `desktop-arrow-${++nextArrowId}`;
  const defs = svgElement("defs");
  const marker = svgElement("marker", { id: arrowId, viewBox: "0 0 10 10",
    refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" });
  marker.append(svgElement("path", { d: "M 0 0 L 10 5 L 0 10 z", fill: "#ccc" }));
  defs.append(marker);
  svg.append(defs, world);
  let positions = {};
  let graph = { nodes: [], edges: [] };
  let bounds = { minX: 0, maxX: 768, minY: 0, maxY: 504 };
  let dragging = null;
  const pointers = new Map();
  let pinch = null;
  const canvas = document.createElement("canvas").getContext("2d");
  canvas.font = "14px monospace";
  const localPoint = (event) => {
    const point = new DOMPoint(event.clientX, event.clientY);
    return point.matrixTransform(svg.getScreenCTM().inverse());
  };
  const transform = () => {
    const state = getState();
    world.setAttribute("transform", `translate(${state.panX} ${state.panY}) scale(${state.zoom})`);
  };
  const fit = () => {
    const zoom = Math.min(8, 720 / Math.max(1, bounds.maxX - bounds.minX),
      460 / Math.max(1, bounds.maxY - bounds.minY));
    setState({ ...getState(), zoom,
      panX: 384 - (bounds.minX + bounds.maxX) / 2 * zoom,
      panY: 252 - (bounds.minY + bounds.maxY) / 2 * zoom });
    transform();
  };
  svg.addEventListener("wheel", (event) => {
    event.preventDefault();
    const point = localPoint(event);
    let factor;
    if (event.deltaY < 0) factor = 1.15;
    else factor = 1 / 1.15;
    setState(zoomWindow(getState(), factor, point.x, point.y));
    transform();
  }, { passive: false });
  svg.addEventListener("pointerdown", (event) => {
    pointers.set(event.pointerId, localPoint(event));
    if (pointers.size === 1) {
      dragging = { point: localPoint(event), state: getState(), moved: false };
    } else {
      dragging = null;
      pinch = { points: [...pointers.values()].slice(0, 2), state: getState() };
    }
    svg.setPointerCapture(event.pointerId);
  });
  svg.addEventListener("pointermove", (event) => {
    if (!pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, localPoint(event));
    if (pinch && pointers.size >= 2) {
      const [first, second] = [...pointers.values()];
      setState(pinchWindow(pinch.state, ...pinch.points, first, second));
      transform();
      return;
    }
    if (!dragging) return;
    const point = localPoint(event);
    const dx = point.x - dragging.point.x;
    const dy = point.y - dragging.point.y;
    if (Math.hypot(dx, dy) > 4) dragging.moved = true;
    setState({ ...dragging.state, panX: dragging.state.panX + dx, panY: dragging.state.panY + dy });
    transform();
  });
  svg.addEventListener("pointerup", (event) => {
    if (dragging && !dragging.moved) {
      const hit = document.elementFromPoint(event.clientX, event.clientY)?.closest("[data-node-id]");
      const node = graph.nodes.find((item) => item.id === hit?.dataset.nodeId);
      if (node) inspect(node);
    }
    dragging = null;
    pointers.delete(event.pointerId);
    pinch = null;
  });
  svg.addEventListener("pointercancel", (event) => {
    pointers.delete(event.pointerId);
    dragging = null;
    pinch = null;
  });

  const draw = (next) => {
    const initial = Object.keys(positions).length === 0;
    graph = next;
    if ((graph.nodes || []).some((node) => !positions[node.id])) {
      positions = reconcilePositions(positions, forceCardPositions(graph));
    } else {
      positions = Object.fromEntries((graph.nodes || []).map((node) => [node.id, positions[node.id]]));
    }
    world.replaceChildren();
    for (const edge of graph.edges || []) {
      const from = positions[edge.from || edge[0]];
      const to = positions[edge.to || edge[1]];
      if (!from || !to) continue;
      const length = Math.max(1, Math.hypot(to.x - from.x, to.y - from.y));
      const dx = (to.x - from.x) / length;
      const dy = (to.y - from.y) / length;
      world.append(svgElement("line", { x1: from.x + dx * 13, y1: from.y + dy * 13,
        x2: to.x - dx * 16, y2: to.y - dy * 16, stroke: "#ccc", "stroke-width": 2,
        "marker-end": `url(#${arrowId})` }));
    }
    for (const node of graph.nodes || []) {
      const point = positions[node.id];
      const group = svgElement("g", { tabindex: 0, role: "button", "aria-label": `Inspect ${node.label || node.id}` });
      group.dataset.nodeId = node.id;
      let shape;
      if (node.kind === "propagator") {
        shape = svgElement("polygon", { points: `${point.x},${point.y - 13} ${point.x - 12},${point.y + 10} ${point.x + 12},${point.y + 10}`, fill: "#fff" });
      } else {
        shape = svgElement("circle", { cx: point.x, cy: point.y, r: 10, fill: "#fff" });
      }
      const title = svgElement("title");
      title.textContent = `${node.label || node.id}\n${node.kind || "node"} · click to inspect`;
      group.append(svgElement("circle", { cx: point.x, cy: point.y, r: 22, fill: "transparent" }), shape, title);
      group.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") { event.preventDefault(); inspect(node); }
      });
      world.append(group);
    }
    const labels = placeCardLabels(graph.nodes || [], positions,
      (text) => canvas.measureText(text).width, 1, { x: -500, y: -500, width: 2000, height: 1500 });
    const boxes = [...Object.values(positions).map((point) => ({ x: point.x - 16, y: point.y - 16, width: 32, height: 32 })), ...labels];
    if (boxes.length) {
      bounds = { minX: Math.min(...boxes.map((box) => box.x)), maxX: Math.max(...boxes.map((box) => box.x + box.width)),
        minY: Math.min(...boxes.map((box) => box.y)), maxY: Math.max(...boxes.map((box) => box.y + box.height)) };
    } else {
      bounds = { minX: 0, maxX: 768, minY: 0, maxY: 504 };
    }
    for (const label of labels) {
      const text = svgElement("text", { x: label.x + label.padding, y: label.y + 16,
        fill: "#eee", "font-size": 14, "font-family": "monospace", "pointer-events": "none" });
      text.textContent = label.text;
      world.append(text);
    }
    if (initial) fit();
    else transform();
  };
  return { draw, transform, fit };
};
