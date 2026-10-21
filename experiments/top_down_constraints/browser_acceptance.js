const fs = require("node:fs");
const { performance } = require("node:perf_hooks");
const { chromium } = require("playwright");

const baseUrl = process.env.LAIN_EDITOR_URL || "http://127.0.0.1:4783";
const sourcePath =
  process.env.LAIN_ACCEPTANCE_SOURCE ||
  "examples/lain/top_down/stack-browser-acceptance.lain";
const screenshotPath = process.env.LAIN_ACCEPTANCE_SCREENSHOT;
const source = fs.readFileSync(sourcePath, "utf8");

async function planeTop(page, index) {
  return page
    .locator(".stack-scene-plane")
    .nth(index)
    .evaluate((node) => parseFloat(node.style.top));
}

async function closeRuntime(page) {
  const pane = await page.locator(".pane").first().getAttribute("data-pane-id");
  if (!pane) {
    return;
  }
  await page.evaluate(async (paneId) => {
    const response = await fetch("/api", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ op: "close", pane: paneId }),
    });
    const reply = await response.json();
    if (!response.ok || !reply.ok) {
      throw new Error(
        reply.error || `close failed with HTTP ${response.status}`,
      );
    }
  }, pane);
}

async function sample(browser, index, capture) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(`page: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") {
      errors.push(`console: ${message.text()}`);
    }
  });

  try {
    await page.goto(baseUrl, { waitUntil: "networkidle" });
    const editor = page.getByLabel("Lain code 1");
    await editor.fill(`${source}\n;; browser acceptance sample ${index}`);
    const started = performance.now();
    await editor.press("Meta+Enter");
    await page
      .locator(".stack-scene-summary")
      .waitFor({ state: "visible", timeout: 60000 });
    await page.evaluate(
      () =>
        new Promise((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(resolve));
        }),
    );
    const visibleElapsedMs = performance.now() - started;

    const observation = await page.evaluate(() => {
      const planes = [...document.querySelectorAll(".stack-scene-plane")];
      const root = planes.find((plane) =>
        plane
          .querySelector(".stack-scene-plane-title")
          ?.textContent.includes("browser-root"),
      );
      const expansion = document.querySelector(".stack-scene-edge.expansion");
      const world = document.querySelector(".stack-scene-world");
      const viewport = document.querySelector(".stack-scene-viewport");
      const call = root?.querySelector('.graph-node[data-kind="propagator"]');
      const matrix = new DOMMatrix(getComputedStyle(world).transform);
      const viewportBounds = viewport.getBoundingClientRect();
      const edgeLocal = new DOMPoint(
        parseFloat(expansion.style.left),
        parseFloat(expansion.style.top),
      );
      const edgeWorld = edgeLocal.matrixTransform(matrix);
      const edgeStart = {
        x: viewportBounds.left + edgeWorld.x,
        y: viewportBounds.top + edgeWorld.y,
      };
      const callBounds = call.getBoundingClientRect();
      const callCenter = {
        x: callBounds.left + callBounds.width / 2,
        y: callBounds.top + callBounds.height / 2,
      };
      return {
        summary: document
          .querySelector(".stack-scene-summary")
          .textContent.trim(),
        semantic: {
          planes: planes.length,
          expansionEdges: document.querySelectorAll(
            ".stack-scene-edge.expansion",
          ).length,
          referenceEdges: document.querySelectorAll(
            ".stack-scene-edge.reference",
          ).length,
          nodeKinds: [...document.querySelectorAll(".graph-node")]
            .map((node) => node.dataset.kind)
            .sort(),
          diagnostics: [
            ...document.querySelectorAll(".semantic-diagnostics"),
          ].map((node) => node.textContent.trim()),
          depthLabels: [
            ...document.querySelectorAll(".stack-scene-depth-label"),
          ].map((node) => node.textContent.trim()),
          planeAriaLabels: planes.map((node) =>
            node.getAttribute("aria-label"),
          ),
          edgeAriaLabels: [
            ...document.querySelectorAll(".stack-scene-edge"),
          ].map((node) => node.getAttribute("aria-label")),
        },
        edgeStart,
        callCenter,
        anchorDistancePx: Math.hypot(
          edgeStart.x - callCenter.x,
          edgeStart.y - callCenter.y,
        ),
      };
    });

    const expected = {
      planes: 2,
      expansionEdges: 1,
      referenceEdges: 0,
      nodeKinds: ["cell", "cell", "propagator", "propagator"],
      diagnostics: ["Partial capture.", "Partial capture."],
      depthLabels: ["D0", "D1"],
      planeAriaLabels: [
        "Stack plane depth 0, :browser-root",
        "Stack plane depth 1, :browser-child",
      ],
      edgeAriaLabels: [
        "expansion from :browser-root node :call to :browser-child",
      ],
    };
    if (observation.summary !== "2 semantic planes · 1 expansion · depth 1") {
      throw new Error(`unexpected summary: ${observation.summary}`);
    }
    if (JSON.stringify(observation.semantic) !== JSON.stringify(expected)) {
      throw new Error(
        `unexpected scene: ${JSON.stringify(observation.semantic)}`,
      );
    }
    if (observation.anchorDistancePx > 4) {
      throw new Error(
        `expansion is not source-anchored: ${observation.anchorDistancePx}px`,
      );
    }
    if (errors.length !== 0) {
      throw new Error(`browser errors: ${JSON.stringify(errors)}`);
    }

    if (capture) {
      const before = JSON.stringify(observation.semantic);
      const spacing = page.getByLabel("Stack layer spacing");
      const initialTop = await planeTop(page, 1);
      await spacing.evaluate((input) => {
        input.value = "300";
        input.dispatchEvent(new Event("input", { bubbles: true }));
      });
      await page.evaluate(
        () => new Promise((resolve) => requestAnimationFrame(resolve)),
      );
      const changedTop = await planeTop(page, 1);
      const after = await page.evaluate(() =>
        JSON.stringify({
          planes: document.querySelectorAll(".stack-scene-plane").length,
          expansionEdges: document.querySelectorAll(
            ".stack-scene-edge.expansion",
          ).length,
          referenceEdges: document.querySelectorAll(
            ".stack-scene-edge.reference",
          ).length,
          nodeKinds: [...document.querySelectorAll(".graph-node")]
            .map((node) => node.dataset.kind)
            .sort(),
          diagnostics: [
            ...document.querySelectorAll(".semantic-diagnostics"),
          ].map((node) => node.textContent.trim()),
          depthLabels: [
            ...document.querySelectorAll(".stack-scene-depth-label"),
          ].map((node) => node.textContent.trim()),
          planeAriaLabels: [
            ...document.querySelectorAll(".stack-scene-plane"),
          ].map((node) => node.getAttribute("aria-label")),
          edgeAriaLabels: [
            ...document.querySelectorAll(".stack-scene-edge"),
          ].map((node) => node.getAttribute("aria-label")),
        }),
      );
      if (initialTop === changedTop || before !== after) {
        throw new Error(
          "view-state interaction did not remain separate from semantics",
        );
      }
      if (screenshotPath) {
        await page.screenshot({ path: screenshotPath, fullPage: true });
      }
      observation.viewState = {
        spacingBeforePx: initialTop,
        spacingAfterPx: changedTop,
        semanticStable: true,
      };
    }

    return { visibleElapsedMs, observation };
  } finally {
    try {
      await closeRuntime(page);
    } finally {
      await context.close();
    }
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const warmups = [];
    const measurements = [];
    for (let index = 0; index < 3; index += 1) {
      warmups.push(await sample(browser, index, false));
    }
    for (let index = 0; index < 5; index += 1) {
      measurements.push(await sample(browser, index + 3, index === 4));
    }
    process.stdout.write(
      `${JSON.stringify({ warmups, measurements }, null, 2)}\n`,
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  process.stderr.write(`${error.stack}\n`);
  process.exitCode = 1;
});
