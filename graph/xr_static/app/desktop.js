import { flattenViewPlanes, viewLines, summaryText } from "./views.js";
import { initialWindow, moveWindow, resizeWindow, zoomWindow, fitWindow, sourceKey } from "./desktop-state.js";
import { graphWindow } from "./desktop-graph.js";
import { renderInspector } from "./inspector.js";
import { renderHistory } from "./history.js";

const element = (tag, className, label) => {
  const node = document.createElement(tag);
  node.className = className;
  if (label !== undefined) node.textContent = label;
  return node;
};

export const createDesktop = (root, menu) => {
  const desktop = element("div", "desktop");
  root.append(desktop);
  const windows = new Map();
  let context = null;
  let generation;
  let front = 1;
  let previousViews;
  let previousContext;
  let previousGraph;

  const geometry = (handle) => {
    const { state, shell } = handle;
    shell.style.left = `${state.x}px`;
    shell.style.top = `${state.y}px`;
    shell.style.width = `${state.width}px`;
    shell.style.height = `${state.height}px`;
    shell.hidden = state.closed;
  };
  const gesture = (target, handle, change) => {
    let start = null;
    target.addEventListener("pointerdown", (event) => {
      if (event.target.closest("button")) return;
      event.preventDefault();
      start = { x: event.clientX, y: event.clientY, state: handle.state };
      target.setPointerCapture(event.pointerId);
      handle.shell.style.zIndex = ++front;
    });
    target.addEventListener("pointermove", (event) => {
      if (!start) return;
      handle.state = change(start.state, event.clientX - start.x, event.clientY - start.y);
      geometry(handle);
    });
    const stop = () => { start = null; };
    target.addEventListener("pointerup", stop);
    target.addEventListener("pointercancel", stop);
  };
  const refreshMenu = () => {
    menu.replaceChildren(element("h2", "", "Windows"));
    for (const handle of windows.values()) {
      const button = element("button", "", handle.title.textContent);
      button.type = "button";
      button.addEventListener("click", () => {
        handle.state = { ...handle.state, closed: false, y: Math.min(handle.state.y, root.clientHeight - 40) };
        geometry(handle);
        handle.shell.style.zIndex = ++front;
        handle.shell.scrollIntoView({ block: "nearest", inline: "nearest" });
      });
      menu.append(button);
    }
  };
  const makeWindow = (key, title, inspector = false) => {
    const shell = element("section", "desktop-window");
    shell.dataset.windowId = key;
    const header = element("header", "window-header");
    const titleNode = element("span", "window-title", title);
    const controls = element("div", "window-controls");
    const body = element("div", "window-body");
    const resize = element("div", "window-resize");
    resize.setAttribute("aria-label", "Resize window");
    const handle = { key, shell, body, title: titleNode, controls,
      state: initialWindow(windows.size, desktop.clientWidth || root.clientWidth,
        desktop.clientHeight || root.clientHeight), inspector };
    header.append(titleNode, controls);
    shell.append(header, body, resize);
    desktop.append(shell);
    windows.set(key, handle);
    const close = element("button", "", "×");
    close.type = "button";
    close.setAttribute("aria-label", `Close ${title}`);
    close.addEventListener("click", () => { handle.state = { ...handle.state, closed: true }; geometry(handle); });
    controls.append(close);
    gesture(header, handle, (state, dx, dy) => moveWindow(state, dx, dy,
      desktop.clientWidth + desktop.scrollLeft, desktop.clientHeight + desktop.scrollTop));
    gesture(resize, handle, resizeWindow);
    shell.addEventListener("pointerdown", () => { shell.style.zIndex = ++front; });
    geometry(handle);
    refreshMenu();
    return handle;
  };
  const openInspector = (node) => {
    const key = `inspect:${sourceKey(node.source || { id: node.id })}`;
    let handle = windows.get(key);
    if (!handle) {
      handle = makeWindow(key, `Inspect · ${node.label || node.id}`, true);
      handle.state = { ...handle.state, x: 24, y: 24, width: Math.min(500, root.clientWidth - 24), height: 430 };
    }
    handle.node = node;
    handle.state = { ...handle.state, closed: false };
    handle.shell.style.zIndex = ++front;
    geometry(handle);
    renderInspector(handle.body, context, node, openInspector);
    handle.shell.scrollIntoView({ block: "nearest", inline: "nearest" });
  };
  const renderView = (handle, view) => {
    const fingerprint = JSON.stringify(view);
    if (handle.fingerprint === fingerprint) return;
    handle.fingerprint = fingerprint;
    let graph = view.graph;
    if (view.status && view.status !== "ready") graph = null;
    if (!graph && handle.graph) {
      handle.graph = null;
      for (const button of handle.controls.querySelectorAll(".graph-control")) button.remove();
    }
    if (graph) {
      if (!handle.graph) {
        handle.body.replaceChildren();
        handle.graph = graphWindow(handle.body, () => handle.state,
          (state) => { handle.state = state; }, openInspector);
        for (const action of ["+", "−", "Fit"]) {
          const button = element("button", "graph-control", action);
          button.type = "button";
          button.addEventListener("click", () => {
            if (action === "Fit") {
              handle.state = fitWindow(handle.state);
              handle.graph.fit();
            }
            else if (action === "+") handle.state = zoomWindow(handle.state, 1.25);
            else handle.state = zoomWindow(handle.state, 1 / 1.25);
            handle.graph.transform();
          });
          handle.controls.prepend(button);
        }
      }
      handle.graph.draw(graph);
    } else {
      handle.body.replaceChildren();
      if (view.type === "collection") {
        handle.body.append(element("p", "", `${view.kind} · ${view.items?.length || 0} items · ${view.pending || 0} pending`));
        for (const item of view.items || []) {
          const button = element("button", "collection-row", summaryText(item.value));
          button.addEventListener("click", () => openInspector({ ...item, id: item.id || item.identity,
            label: item.label || summaryText(item.value) }));
          handle.body.append(button);
        }
      } else if (view.type === "cell-history" && (view.samples || []).some((sample) => Number.isFinite(sample["sample/time-ms"]))) {
        renderHistory(handle.body, view, openInspector);
      } else {
        handle.body.append(element("pre", "", viewLines(view).join("\n")));
      }
    }
  };
  const render = (model) => {
    root.dataset.mode = model.viewMode;
    desktop.hidden = model.viewMode !== "2d";
    if (previousViews === model.views && previousContext === model.context && previousGraph === model.graph) return;
    previousViews = model.views;
    previousGraph = model.graph;
    previousContext = model.context;
    context = model.context;
    if (generation !== context?.generation) {
      for (const [key, handle] of windows) {
        if (handle.inspector) { handle.shell.remove(); windows.delete(key); }
      }
      generation = context?.generation;
    }
    let views = flattenViewPlanes(model.views);
    if (!views.length && model.graph?.nodes?.length) views = [{ id: "root-graph", type: "graph", graph: model.graph }];
    const present = new Set(views.map((view, index) => `view:${view.id || index}`));
    for (const [key, handle] of windows) {
      if (!handle.inspector && !present.has(key)) { handle.shell.remove(); windows.delete(key); }
    }
    views.forEach((view, index) => {
      const key = `view:${view.id || index}`;
      const handle = windows.get(key) || makeWindow(key, `${index + 1} · ${view.type}`);
      handle.shell.dataset.contextStatus = context?.status || "unavailable";
      renderView(handle, view);
    });
    for (const handle of windows.values()) {
      if (handle.inspector) renderInspector(handle.body, context, handle.node, openInspector);
    }
    refreshMenu();
  };
  window.addEventListener("resize", () => {
    for (const handle of windows.values()) {
      handle.state = moveWindow(handle.state, 0, 0, root.clientWidth, root.clientHeight);
      geometry(handle);
    }
  });
  return { render };
};
