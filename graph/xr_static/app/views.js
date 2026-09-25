const summaryText = (summary) => {
  if (!summary) return "unavailable";
  if (summary.kind === "nothing") return "nothing";
  if (summary.kind === "contradiction") return "contradiction";
  return String(summary.value ?? summary.current ?? summary.kind ?? "unavailable");
};

export const flattenViewPlanes = (views) =>
  (views || []).flatMap((view) =>
    view.type === "juxtapose"
      ? flattenViewPlanes(view.children || [])
      : [view]
  );

export const viewLines = (view) => {
  switch (view.type) {
    case "cell-window":
      return [
        `strongest: ${summaryText(view.strongest)}`,
        `content: ${summaryText(view.content)}`,
      ];
    case "cell-history":
      return (view.samples || []).slice(-10).map((sample) =>
        `${sample["sample/epoch"]}:${sample["sample/tick"]}  ${summaryText(sample["sample/strongest"])}`
      );
    case "hierarchy":
      return [`${view.graph?.nodes?.length || 0} nodes`, `${view.graph?.edges?.length || 0} relationships`];
    default:
      return [`unsupported view: ${view.type || "unknown"}`];
  }
};

export const viewFingerprint = (view) => JSON.stringify(view);
