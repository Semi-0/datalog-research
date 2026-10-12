// Read the typed transport, never execute opaque host values or recompute TTMS.
export const deref = (codec, value) => {
  if (value?.ref !== undefined) return codec.objects[value.ref];
  else return value;
};
export const entries = (codec, value) => deref(codec, value)?.entries || [];
export const items = (codec, value) => deref(codec, value)?.items || [];
export const field = (codec, value, name) => entries(codec, value)
  .find(([key]) => key?.tag === "keyword" && key.value === name)?.[1];
export const lookup = (codec, value, id) => entries(codec, value)
  .find(([key]) => key?.tag === "node-id" && key.value === id)?.[1];

export const sourceReference = (codec, value) => {
  if (value?.tag === "node-id") return { path: ["outer"], id: value.value };
  const pair = items(codec, value);
  if (pair.length !== 2 || pair[1]?.tag !== "node-id") return null;
  const path = [];
  for (const segment of items(codec, pair[0])) {
    if (segment?.tag === "keyword" && segment.value === "outer") path.push("outer");
    else {
      const part = items(codec, segment);
      if (part[0]?.value !== "cell" || part[1]?.tag !== "node-id") return null;
      path.push({ cell: part[1].value });
    }
  }
  return { path, id: pair[1].value };
};

const relationshipEntry = (codec, network, source) => entries(codec, field(codec, network, "relationship"))
  .find(([key]) => {
    const reference = sourceReference(codec, key);
    return reference?.id === source.id && JSON.stringify(reference.path) === JSON.stringify(source.path || ["outer"]);
  })?.[1];

export const layer = (codec, value, name) => {
  const direct = field(codec, value, name);
  if (direct !== undefined) return direct;
  const slot = field(codec, field(codec, value, "dict"), name);
  if (!slot) return undefined;
  return field(codec, lookup(codec, field(codec, value, "env"), slot.value), "strongest");
};

export const base = (codec, value) => {
  let current = value;
  const seen = new Set();
  while (current && !seen.has(current)) {
    seen.add(current);
    const next = layer(codec, current, "base");
    if (next === undefined) return current;
    current = next;
  }
  return current;
};

export const networkAt = (context, path = ["outer"]) => {
  if (!context?.datum || context.status !== "ready") return null;
  const codec = context.datum;
  let network = base(codec, field(codec, codec.root, "projected"));
  for (const segment of path) {
    if (segment === "outer") continue;
    if (!segment?.cell) return null;
    const cell = lookup(codec, field(codec, network, "env"), segment.cell);
    network = base(codec, field(codec, cell, "strongest"));
  }
  if (field(codec, network, "env")) return network;
  else return null;
};

export const inspectSource = (context, source) => {
  if (!context?.datum || !source) return null;
  const codec = context.datum;
  let network = networkAt(context, source.path);
  const inletSource = field(codec, codec.root, "source");
  if (source.id === inletSource?.value) network = field(codec, codec.root, "inlet");
  const entry = lookup(codec, field(codec, network, "env"), source.id);
  if (!entry) return null;
  return { codec, entry, topology: lookup(codec, field(codec, network, "graph"), source.id),
    relationship: relationshipEntry(codec, networkAt(context), source)
      || relationshipEntry(codec, network, source),
    names: entries(codec, field(codec, network, "dict"))
      .filter(([, value]) => value?.tag === "node-id" && value.value === source.id)
      .map(([key]) => key) };
};

export const premises = (codec, value) => {
  const found = new Map();
  const seen = new Set();
  const visit = (item) => {
    if (item?.ref !== undefined) {
      if (seen.has(item.ref)) return;
      seen.add(item.ref);
    }
    const source = field(codec, item, "source");
    const timestamp = field(codec, item, "timestamp");
    const status = field(codec, item, "premises-status");
    if (source && timestamp && status) {
      const premise = { source, timestamp, status };
      found.set(JSON.stringify(premise), premise);
    }
    for (const [key, data] of entries(codec, item)) { visit(key); visit(data); }
    for (const data of items(codec, item)) visit(data);
  };
  visit(value);
  return [...found.values()];
};

export const describe = (codec, value) => {
  const data = deref(codec, value);
  if (data === null || data === undefined) return String(data ?? "unavailable");
  if (typeof data !== "object") return String(data);
  if (data.tag === "opaque") return `opaque ${data.class}`;
  if (data.value !== undefined) return `${data.tag}: ${data.value}`;
  if (data.entries) return `${data.tag} · ${data.entries.length} entries`;
  if (data.items) return `${data.tag} · ${data.items.length} items`;
  return "unsupported transport value";
};
