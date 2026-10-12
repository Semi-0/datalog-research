import test from "node:test";
import assert from "node:assert/strict";
import { initialWindow, moveWindow, resizeWindow, zoomWindow, pinchWindow, fitWindow, reconcilePositions, sourceKey } from "../../graph/xr_static/app/desktop-state.js";
import { field, base, networkAt, inspectSource, premises, describe, sourceReference } from "../../graph/xr_static/app/inspection.js";
import { initialModel } from "../../graph/xr_static/app/model.js";
import { update } from "../../graph/xr_static/app/update.js";
import { connectSocket } from "../../graph/xr_static/app/effects.js";
import { graphWindow } from "../../graph/xr_static/app/desktop-graph.js";

test("LAN HTTP graph initialization needs no secure-context crypto API", () => {
  const previousDocument = globalThis.document;
  const cryptoDescriptor = Object.getOwnPropertyDescriptor(globalThis, "crypto");
  const markers = [];
  const node = () => ({
    classList: { add() {} }, append() {}, addEventListener() {},
    setAttribute(key, value) { if (key === "id") markers.push(value); },
    getContext() { return { font: "", measureText() { return { width: 10 }; } }; },
  });
  globalThis.document = { createElementNS: node, createElement: node };
  Object.defineProperty(globalThis, "crypto", { value: undefined, configurable: true });
  try {
    const body = node();
    const state = initialWindow(0, 390, 844);
    assert.equal(typeof graphWindow(body, () => state, () => {}, () => {}).draw, "function");
    graphWindow(body, () => state, () => {}, () => {});
    assert.equal(markers.length, 2);
    assert.notEqual(markers[0], markers[1]);
  } finally {
    globalThis.document = previousDocument;
    if (cryptoDescriptor) Object.defineProperty(globalThis, "crypto", cryptoDescriptor);
    else delete globalThis.crypto;
  }
});

const keyword = (value) => ({ tag: "keyword", value });
const id = (value) => ({ tag: "node-id", value });
const number = (value) => ({ tag: "number", value: String(value), class: "java.lang.Long" });
const map = (data) => ({ tag: "map", entries: Object.entries(data).map(([key, value]) => [keyword(key), value]) });
const env = (data) => ({ tag: "map", entries: Object.entries(data).map(([key, value]) => [id(key), value]) });
const cell = (strongest) => map({ strongest, content: strongest });
const support = { tag: "set", items: [map({ source: id("A"), timestamp: number(2), "premises-status": keyword("retracted") })] };
const inner = map({ env: env({ B: cell(number(20)) }), dict: map({ b: id("B") }) });
const net = map({ env: env({ A: cell(map({ base: number(10), support })), owner: cell(inner) }), dict: map({ a: id("A") }) });
const wrapper = map({ env: env({ payload: cell(net), support: cell(support) }), dict: map({ base: id("payload"), support: id("support") }) });
const context = { status: "ready", generation: "one", datum: { root: map({ projected: wrapper, source: id("inlet"), inlet: map({ env: env({ inlet: cell(wrapper) }) }) }), objects: {} } };

test("window movement, resize, pointer-centred zoom and Fit are isolated", () => {
  const first = initialWindow(0, 1000, 800);
  const second = initialWindow(1, 1000, 800);
  const moved = moveWindow(first, -999, 999, 1000, 800);
  assert.equal(moved.x, 0);
  assert.equal(moved.y, 764);
  assert.equal(resizeWindow(first, -999, -999).width, 260);
  const zoomed = zoomWindow(first, 2, 100, 50);
  assert.equal((100 - zoomed.panX) / zoomed.zoom, 100);
  assert.equal((50 - zoomed.panY) / zoomed.zoom, 50);
  assert.equal(second.zoom, 1);
  assert.deepEqual(fitWindow(zoomed), first);
});

test("phone pinch zoom follows both distance and moving midpoint", () => {
  const state = initialWindow(0, 390, 844);
  const zoomed = pinchWindow(state, { x: 50, y: 100 }, { x: 150, y: 100 },
    { x: 20, y: 120 }, { x: 220, y: 120 });
  assert.equal(zoomed.zoom, 2);
  assert.equal((120 - zoomed.panX) / zoomed.zoom, 100);
  assert.equal((120 - zoomed.panY) / zoomed.zoom, 100);
  assert.equal(pinchWindow(state, { x: 0, y: 0 }, { x: 0, y: 0 },
    { x: 1, y: 1 }, { x: 2, y: 2 }), state);
  assert.ok(initialWindow(1, 390, 844).y > state.y);
  assert.ok(state.width < 390);
});

test("layout updates preserve existing positions and remove missing nodes", () => {
  assert.deepEqual(reconcilePositions({ A: { x: 8 }, gone: { x: 3 } }, { A: { x: 1 }, B: { x: 2 } }), { A: { x: 8 }, B: { x: 2 } });
  assert.notEqual(sourceKey({ path: ["outer"], id: "A" }), sourceKey({ path: ["outer", { cell: "owner" }], id: "A" }));
});

test("inspection uses slot-backed TTMS base and path-qualified nested entries", () => {
  assert.equal(base(context.datum, wrapper), net);
  assert.equal(networkAt(context), net);
  assert.equal(networkAt(context, ["outer", { cell: "owner" }]), inner);
  const inspected = inspectSource(context, { path: ["outer"], id: "A" });
  assert.equal(inspected.names[0].value, "a");
  assert.equal(premises(inspected.codec, inspected.entry).length, 1);
  assert.equal(inspectSource(context, { path: ["outer"], id: "synthetic" }), null);
  assert.equal(inspectSource({ ...context, status: "withdrawn" }, { path: ["outer"], id: "A" }), null);
  assert.ok(inspectSource(context, { path: ["outer"], id: "inlet" }));
});

test("shared and cyclic references preserve premise identity without recursion", () => {
  const codec = { root: { ref: "0" }, objects: { "0": map({ self: { ref: "0" }, support, again: support }) } };
  assert.equal(premises(codec, codec.root).length, 1);
  assert.match(describe(codec, { tag: "opaque", class: "Closure" }), /opaque Closure/);
  assert.equal(describe(codec, { tag: "number", value: "9007199254740993" }), "number: 9007199254740993");
});

test("relationship lookup uses path and ID, not a bare node ID", () => {
  const key = { tag: "vector", items: [{ tag: "vector", items: [keyword("outer")] }, id("A")] };
  const node = map({ inputs: { tag: "set", items: [] }, outputs: { tag: "set", items: [id("B")] } });
  const relations = { tag: "map", entries: [[key, node]] };
  const withRelations = map({ env: field(context.datum, net, "env"), relationship: relations });
  const updated = { ...context, datum: { root: map({ projected: map({ base: withRelations, support }) }), objects: {} } };
  assert.deepEqual(sourceReference(updated.datum, key), { path: ["outer"], id: "A" });
  assert.equal(inspectSource(updated, { path: ["outer"], id: "A" }).relationship, node);
  assert.equal(inspectSource(updated, { id: "A", path: ["outer"] }).relationship, node);
  assert.equal(inspectSource(updated, { path: ["outer"], id: "B" }), null);
});

test("coherent publication context reaches the browser model", () => {
  const payload = { graph: { nodes: [], edges: [] }, views: [], context };
  const [model] = update(initialModel(), { type: "socket/message", payload });
  assert.equal(model.context, context);
  const [updated] = update(model, { type: "socket/message", payload: { ...payload, context: { ...context, generation: "two" } } });
  assert.equal(updated.context.generation, "two");
  assert.equal(field(updated.context.datum, updated.context.datum.root, "projected"), wrapper);
  const [disconnected] = update(updated, { type: "socket/closed" });
  assert.equal(disconnected.context, null);
  const [withoutContext] = update(updated, { type: "socket/message", payload: { graph: payload.graph } });
  assert.equal(withoutContext.context, updated.context);
  const [reloaded] = update(updated, { type: "socket/message", payload: { graph: payload.graph, generation: "three" } });
  assert.equal(reloaded.context, null);
  const [juxtaposed] = update(updated, { type: "socket/message", payload: { ...payload,
    views: [{ type: "juxtapose", children: [{ type: "graph" }, { type: "collection" }] }] } });
  assert.equal(juxtaposed.status, "2 views · network ready");
});

test("separate context updates preserve views and graph; errors clear inspection", () => {
  const graph = { nodes: [], edges: [] };
  const views = [{ type: "graph" }];
  const model = { ...initialModel(), graph, views };
  const [observed] = update(model, { type: "socket/message", payload: {
    type: "xr/context/update", ok: true, result: { context },
  } });
  assert.equal(observed.context, context);
  assert.equal(observed.graph, graph);
  assert.equal(observed.views, views);
  const [juxtaposed] = update({ ...model, views: [{ type: "juxtapose", children: views.concat(views) }] },
    { type: "socket/message", payload: { type: "xr/context/update", ok: true, result: { context } } });
  assert.equal(juxtaposed.status, "2 views · network ready");
  const [failed] = update(observed, { type: "socket/message", payload: {
    type: "xr/context/update", ok: false, error: "encoding failed",
  } });
  assert.equal(failed.context, null);
  assert.equal(failed.views, views);
  assert.match(failed.status, /encoding failed/);
  const [, commands] = update(model, { type: "socket/open" });
  assert.equal(commands.length, 1);
});

test("retired socket callbacks cannot overwrite or reconnect over a newer connection", () => {
  const sockets = [];
  const timers = [];
  const messages = [];
  const oldWindow = globalThis.window;
  const oldSocket = globalThis.WebSocket;
  class Socket {
    constructor() { this.listeners = {}; sockets.push(this); }
    addEventListener(name, callback) { this.listeners[name] = callback; }
  }
  globalThis.window = { location: { protocol: "http:", host: "localhost" },
    setTimeout: (callback) => timers.push(callback) };
  globalThis.WebSocket = Socket;
  try {
    const env = {};
    const dispatch = (message) => messages.push(message);
    connectSocket().run(dispatch, env);
    const first = sockets[0];
    first.listeners.close();
    assert.equal(timers.length, 1);
    connectSocket().run(dispatch, env);
    const second = sockets[1];
    const count = messages.length;
    first.listeners.message({ data: '{"old":true}' });
    first.listeners.close();
    timers[0]();
    assert.equal(messages.length, count);
    assert.equal(sockets.length, 2);
    assert.equal(env.socket, second);
    second.listeners.message({ data: '{"current":true}' });
    assert.equal(messages.at(-1).payload.current, true);
    second.listeners.close();
    timers[1]();
    assert.equal(sockets.length, 3);
    second.listeners.message({ data: '{"stale":true}' });
    assert.equal(messages.at(-1).type, "socket/closed");
  } finally {
    globalThis.window = oldWindow;
    globalThis.WebSocket = oldSocket;
  }
});
