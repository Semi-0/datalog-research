import { deref, entries, items, field, layer, premises, describe, inspectSource, sourceReference } from "./inspection.js";

const text = (tag, value) => {
  const element = document.createElement(tag);
  element.textContent = value;
  return element;
};

// Expand lazily so an inspector never renders an entire Net eagerly.
const tree = (codec, value, label, ancestors, path, expandedPaths, inspectValue) => {
  const details = document.createElement("details");
  details.dataset.treePath = path;
  details.append(text("summary", `${label}: ${describe(codec, value)}`));
  let expanded = false;
  details.addEventListener("toggle", () => {
    if (!details.open || expanded) return;
    expanded = true;
    if (value?.ref && ancestors.has(value.ref)) {
      details.append(text("p", `Shared reference ${value.ref}`));
      return;
    }
    const next = new Set(ancestors);
    if (value?.ref) next.add(value.ref);
    const reference = sourceReference(codec, value);
    if (reference) {
      const button = text("button", "Inspect referenced node");
      button.type = "button";
      button.addEventListener("click", () => inspectValue(reference, value?.tag === "node-id"));
      details.append(button);
    }
    for (const [key, data] of entries(codec, value)) {
      const childPath = `${path}/${describe(codec, key)}`;
      details.append(tree(codec, data, describe(codec, key), next, childPath, expandedPaths, inspectValue));
    }
    items(codec, value).forEach((item, index) => details.append(tree(codec, item, String(index), next, `${path}/${index}`, expandedPaths, inspectValue)));
    if (!entries(codec, value).length && !items(codec, value).length) {
      details.append(text("pre", JSON.stringify(deref(codec, value), null, 2)));
    }
  });
  details.open = expandedPaths.has(path);
  return details;
};

export const renderInspector = (body, context, node, openSource) => {
  const scroll = body.scrollTop;
  const expandedPaths = new Set([...body.querySelectorAll("details[open]")].map((item) => item.dataset.treePath));
  const retainedTree = (value, label) => tree(context.datum, value, label, new Set(), label, expandedPaths,
    (source, implicitPath) => {
      let reference = source;
      if (implicitPath && inspectSource(context, { ...source, path: node.source?.path })) {
        reference = { ...source, path: node.source.path };
      }
      openSource({ id: reference.id, source: reference, label: reference.id });
    });
  body.replaceChildren(text("p", `Live context: ${context?.status || "unavailable"}`));
  body.append(text("p", `Identity: ${node.source?.id || node.id}`),
    text("p", `Path: ${JSON.stringify(node.source?.path || [])}`));
  if (node.value) body.append(text("p", `Published view value: ${JSON.stringify(node.value)}`));
  const inspected = inspectSource(context, node.source);
  if (!inspected) {
    body.append(text("p", "No current runtime entry. This may be a synthetic declaration node or an unavailable context."),
      text("pre", JSON.stringify(node, null, 2)));
    return;
  }
  const { codec, entry, topology, relationship, names } = inspected;
  body.append(text("p", `Entry: ${describe(codec, entry)}`));
  body.append(text("p", `Names: ${names.map((name) => describe(codec, name)).join(", ") || "unnamed"}`));
  for (const name of ["name", "strongest", "content", "activate"]) {
    const value = field(codec, entry, name);
    if (value !== undefined) body.append(retainedTree(value, name));
  }
  if (topology) body.append(retainedTree(topology, "Inputs / outputs"));
  if (relationship) body.append(retainedTree(relationship, "Parents / children"));
  else body.append(text("p", "No stored parent/child entry for this occurrence."));
  const sources = premises(codec, entry);
  body.append(text("h3", `Retained premises · ${sources.length}`));
  for (const premise of sources) {
    const button = text("button", `${premise.source.value} @ ${premise.timestamp.value} · ${premise.status.value}`);
    button.type = "button";
    button.addEventListener("click", () => {
      let path;
      if (inspectSource(context, { id: premise.source.value, path: ["outer"] })) path = ["outer"];
      else path = node.source.path;
      openSource({ id: premise.source.value, label: `Source ${premise.source.value}`,
        source: { id: premise.source.value, path } });
    });
    body.append(button);
  }
  body.append(text("h3", "Network observation premises"));
  const projected = field(codec, codec.root, "projected");
  // Read only the publication's support, not all nested domain premises.
  for (const premise of premises(codec, layer(codec, projected, "support"))) {
    const button = text("button", `${premise.source.value} @ ${premise.timestamp.value} · ${premise.status.value}`);
    button.type = "button";
    button.addEventListener("click", () => openSource({ id: premise.source.value,
      label: "Network observation source", source: { id: premise.source.value, path: ["outer"] } }));
    body.append(button);
  }
  const inlet = field(codec, codec.root, "inlet");
  body.append(retainedTree(inlet, "Publication inlet (ordinary TTMS cell)"));
  body.scrollTop = scroll;
};
