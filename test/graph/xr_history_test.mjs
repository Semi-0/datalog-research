import test from "node:test";
import assert from "node:assert/strict";
import { historyLayout } from "../../graph/xr_static/app/history.js";

const sample = (node, time) => ({ "sample/node": node, "sample/label": node,
  "sample/time-ms": time, "sample/duration-ms": 0.1, "sample/tick": 1 });

test("generic history has stable lanes for distinct occurrences", () => {
  const result = historyLayout([sample("A", 1), sample("B", 2), sample("A", 3)]);
  assert.deepEqual(result.rows.map((row) => row.id), ["A", "B"]);
  assert.equal(result.rows[0].points.length, 2);
  assert.ok(result.rows[0].points[0].x < result.rows[0].points[1].x);
  assert.equal(result.rows[0].points[0].width, 0.1 * (760 - 150 - 12) / 3);
});

test("viewport excludes old records without rewriting source history", () => {
  const samples = [sample("old", 1), sample("current", 40000)];
  const result = historyLayout(samples);
  assert.equal(result.start, 10000);
  assert.deepEqual(result.rows.map((row) => row.id), ["current"]);
  assert.equal(samples.length, 2);
  assert.deepEqual(historyLayout([]).rows, []);
});
