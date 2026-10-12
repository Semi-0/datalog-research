import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { arrowGeometry, drawNode } from "../../graph/xr_static/app/card-symbols.js";

test("arrowheads terminate before the target and reverse with dataflow", () => {
  const a = { x: 0, y: 0 };
  const b = { x: 100, y: 0 };
  const forward = arrowGeometry(a, b);
  const backward = arrowGeometry(b, a);
  assert.equal(forward.start.x, 13);
  assert.equal(forward.tip.x, 87);
  assert.ok(forward.left.x < forward.tip.x);
  assert.equal(backward.tip.x, 13);
  assert.ok(backward.left.x > backward.tip.x);
  assert.equal(arrowGeometry(a, a), null);
  const vertical = arrowGeometry(a, { x: 0, y: 100 });
  assert.equal(vertical.tip.y, 87);
  assert.ok(vertical.left.y < vertical.tip.y);
});

test("node kind, not its label, chooses circle or triangle", () => {
  const commands = [];
  const ctx = new Proxy({}, { get: (_target, key) => (...args) => commands.push([key, ...args]) });
  drawNode(ctx, { kind: "propagator", label: "cell" }, { x: 50, y: 50 });
  assert.equal(commands.filter(([name]) => name === "lineTo").length, 2);
  assert.equal(commands.some(([name]) => name === "arc"), false);
  commands.length = 0;
  drawNode(ctx, { kind: "cell", label: "filter" }, { x: 50, y: 50 });
  assert.equal(commands.some(([name]) => name === "arc"), true);
});

test("visualizer color literals are monochrome across desktop and 3D views", () => {
  for (const path of ["styles.css", "app/desktop-graph.js", "app/card-symbols.js",
    "app/babylon-views.js", "app/babylon-graph-view.js"]) {
    const source = readFileSync(new URL(`../../graph/xr_static/${path}`, import.meta.url), "utf8");
    for (const match of source.matchAll(/#([0-9a-f]{3,8})\b/gi)) {
      const hex = match[1];
      let channels;
      if (hex.length === 3 || hex.length === 4) channels = hex.slice(0, 3).split("");
      else if (hex.length === 6 || hex.length === 8) channels = [hex.slice(0, 2), hex.slice(2, 4), hex.slice(4, 6)];
      else throw new Error(`Unsupported color literal in ${path}: ${hex}`);
      assert.equal(new Set(channels).size, 1, `${path}: ${match[0]}`);
    }
  }
});
