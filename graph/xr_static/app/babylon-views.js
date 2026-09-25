import { flattenViewPlanes, viewFingerprint, viewLines } from "./views.js";

const panelSize = { width: 3.2, height: 2.1 };
const textureSize = { width: 768, height: 504 };

const drawHierarchy = (ctx, view) => {
  const nodes = view.graph?.nodes || [];
  const edges = view.graph?.edges || [];
  const byId = Object.fromEntries(nodes.map((node) => [node.id, node]));
  const incoming = Object.fromEntries(nodes.map((node) => [node.id, 0]));
  for (const edge of edges) {
    if (byId[edge.to]) incoming[edge.to] = (incoming[edge.to] || 0) + 1;
  }
  const roots = nodes.filter((node) => incoming[node.id] === 0);
  const queue = roots.map((node) => [node.id, 0]);
  const depth = {};
  while (queue.length > 0) {
    const [id, level] = queue.shift();
    if (depth[id] !== undefined && depth[id] <= level) continue;
    depth[id] = level;
    for (const edge of edges.filter((candidate) => candidate.from === id)) {
      queue.push([edge.to, level + 1]);
    }
  }
  const levels = Object.groupBy
    ? Object.groupBy(nodes, (node) => depth[node.id] ?? 0)
    : nodes.reduce((result, node) => {
        const level = depth[node.id] ?? 0;
        result[level] ||= [];
        result[level].push(node);
        return result;
      }, {});
  const positions = {};
  for (const [levelText, levelNodes] of Object.entries(levels)) {
    const level = Number(levelText);
    levelNodes.forEach((node, index) => {
      positions[node.id] = {
        x: 70 + level * 170,
        y: 100 + ((index + 1) * 340) / (levelNodes.length + 1),
      };
    });
  }
  ctx.strokeStyle = "#6f7782";
  ctx.lineWidth = 3;
  for (const edge of edges) {
    const from = positions[edge.from];
    const to = positions[edge.to];
    if (!from || !to) continue;
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.lineTo(to.x, to.y);
    ctx.stroke();
  }
  for (const node of nodes) {
    const point = positions[node.id];
    if (!point) continue;
    ctx.fillStyle = "#ffffff";
    ctx.beginPath();
    ctx.arc(point.x, point.y, 10, 0, Math.PI * 2);
    ctx.fill();
    if (nodes.length <= 24) {
      ctx.font = "18px sans-serif";
      ctx.fillText(String(node.label || node.id).slice(0, 16), point.x + 16, point.y + 6);
    }
  }
};

const paint = (texture, view) => {
  const ctx = texture.getContext();
  ctx.fillStyle = "#07090c";
  ctx.fillRect(0, 0, textureSize.width, textureSize.height);
  ctx.strokeStyle = "#ffffff";
  ctx.lineWidth = 4;
  ctx.strokeRect(3, 3, textureSize.width - 6, textureSize.height - 6);
  ctx.fillStyle = "#ffffff";
  ctx.font = "bold 30px sans-serif";
  ctx.fillText(view.type || "view", 28, 48);
  ctx.font = "22px ui-monospace, monospace";
  viewLines(view).forEach((line, index) => {
    ctx.fillText(String(line).slice(0, 58), 28, 94 + index * 32);
  });
  if (view.type === "hierarchy") drawHierarchy(ctx, view);
  texture.update();
};

export const createBabylonViewLayer = ({ BABYLON, scene }) => {
  const panels = new Map();

  const createPanel = (view) => {
    const texture = new BABYLON.DynamicTexture(
      `view-${view.id}-texture`, textureSize, scene, false
    );
    const material = new BABYLON.StandardMaterial(`view-${view.id}-material`, scene);
    material.diffuseTexture = texture;
    material.emissiveTexture = texture;
    material.disableLighting = true;
    const plane = BABYLON.MeshBuilder.CreatePlane(
      `view-${view.id}`, panelSize, scene
    );
    plane.material = material;
    plane.billboardMode = BABYLON.Mesh.BILLBOARDMODE_ALL;
    plane.isPickable = false;
    const panel = { plane, material, texture, fingerprint: null };
    panels.set(view.id, panel);
    return panel;
  };

  const prune = (ids) => {
    for (const [id, panel] of panels) {
      if (ids.has(id)) continue;
      panel.plane.dispose();
      panel.material.dispose();
      panel.texture.dispose();
      panels.delete(id);
    }
  };

  const renderViews = (model) => {
    const views = flattenViewPlanes(model.views);
    prune(new Set(views.map((view) => view.id)));
    const center = (views.length - 1) / 2;
    views.forEach((view, index) => {
      const panel = panels.get(view.id) || createPanel(view);
      const fingerprint = viewFingerprint(view);
      if (panel.fingerprint !== fingerprint) {
        paint(panel.texture, view);
        panel.fingerprint = fingerprint;
      }
      panel.plane.position.set((index - center) * 3.55, 0, 0);
    });
  };

  return { renderViews };
};
