// Browser-local geometry. None of these operations mutate network data.
export const initialWindow = (index, width, height) => {
  let columns;
  if (width >= 900) columns = 2;
  else columns = 1;
  const windowWidth = Math.max(260, Math.min(420, (width - 24) / columns - 12));
  return { x: 12 + (index % columns) * (windowWidth + 12),
    y: 12 + Math.floor(index / columns) * 290,
    width: windowWidth, height: Math.max(180, Math.min(280, height - 24)),
    zoom: 1, panX: 0, panY: 0, closed: false };
};

export const moveWindow = (state, dx, dy, width, height) => ({ ...state,
  x: Math.max(0, Math.min(width - 60, state.x + dx)),
  y: Math.max(0, Math.min(height - 36, state.y + dy)),
});

export const resizeWindow = (state, dx, dy) => ({ ...state,
  width: Math.max(260, state.width + dx), height: Math.max(180, state.height + dy),
});

export const zoomWindow = (state, factor, x = 384, y = 252) => {
  const zoom = Math.max(0.2, Math.min(8, state.zoom * factor));
  const ratio = zoom / state.zoom;
  return { ...state, zoom,
    panX: x - (x - state.panX) * ratio,
    panY: y - (y - state.panY) * ratio };
};

export const fitWindow = (state) => ({ ...state, zoom: 1, panX: 0, panY: 0 });
// Keep the original midpoint's graph coordinate under the moving fingers.
export const pinchWindow = (state, first, second, nextFirst, nextSecond) => {
  const distance = Math.hypot(second.x - first.x, second.y - first.y);
  if (distance === 0) return state;
  const x = (first.x + second.x) / 2;
  const y = (first.y + second.y) / 2;
  const nextDistance = Math.hypot(nextSecond.x - nextFirst.x, nextSecond.y - nextFirst.y);
  const zoomed = zoomWindow(state, nextDistance / distance, x, y);
  return { ...zoomed,
    panX: zoomed.panX + (nextFirst.x + nextSecond.x) / 2 - x,
    panY: zoomed.panY + (nextFirst.y + nextSecond.y) / 2 - y };
};
export const sourceKey = (source) => JSON.stringify([source?.path || ["outer"], source?.id]);

export const reconcilePositions = (previous, fresh) => Object.fromEntries(
  Object.entries(fresh).map(([id, position]) => [id, previous[id] || position])
);
