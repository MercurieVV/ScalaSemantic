import React from 'react';

function decodeBase64(value) {
  if (!value) return '';
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function shortDim(dimensions = []) {
  const map = { extends: 'ext', memberType: 'type', typeRef: 'ref', call: 'call', implicit: 'impl' };
  return dimensions.map((d) => map[d] ?? d).join(', ');
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function mixChannel(a, b, t) {
  return Math.round(a + (b - a) * t);
}

function rgb([r, g, b]) {
  return `rgb(${r}, ${g}, ${b})`;
}

function mixColor(a, b, t) {
  return [
    mixChannel(a[0], b[0], t),
    mixChannel(a[1], b[1], t),
    mixChannel(a[2], b[2], t),
  ];
}

function instabilityColor(instability) {
  const base = [238, 244, 247];
  const unstable = [255, 188, 188];
  const strokeBase = [79, 103, 116];
  const strokeUnstable = [190, 18, 60];
  const textBase = [48, 65, 74];
  const textUnstable = [136, 19, 55];
  const value = Math.pow(clamp(instability ?? 0, 0, 1), 0.72);
  return {
    fill: rgb(mixColor(base, unstable, value)),
    stroke: rgb(mixColor(strokeBase, strokeUnstable, value)),
    text: rgb(mixColor(textBase, textUnstable, value)),
  };
}

function textWidth(text) {
  return clamp(String(text ?? '').length * 6 + 24, 86, 132);
}

function resolveModuleOverlaps(module, positions, nodeHeight) {
  const gap = 12;
  const nodes = module.items.map((symbol) => ({
    symbol,
    width: textWidth(symbol.name),
    height: nodeHeight,
    pos: positions.get(symbol.symbol),
  })).filter((node) => node.pos);

  for (let iteration = 0; iteration < 90; iteration += 1) {
    let moved = false;
    for (let i = 0; i < nodes.length; i += 1) {
      for (let j = i + 1; j < nodes.length; j += 1) {
        const a = nodes[i];
        const b = nodes[j];
        const minX = (a.width + b.width) / 2 + gap;
        const minY = (a.height + b.height) / 2 + gap;
        const dx = b.pos.x - a.pos.x;
        const dy = b.pos.y - a.pos.y;
        const overlapX = minX - Math.abs(dx);
        const overlapY = minY - Math.abs(dy);
        if (overlapX > 0 && overlapY > 0) {
          const pushX = dx === 0 ? (i % 2 === 0 ? 1 : -1) : Math.sign(dx);
          const pushY = dy === 0 ? (j % 2 === 0 ? 1 : -1) : Math.sign(dy);
          if (overlapX < overlapY) {
            a.pos.x -= (overlapX / 2) * pushX;
            b.pos.x += (overlapX / 2) * pushX;
          } else {
            a.pos.y -= (overlapY / 2) * pushY;
            b.pos.y += (overlapY / 2) * pushY;
          }
          moved = true;
        }
      }
    }

    nodes.forEach((node) => {
      node.pos.x = clamp(node.pos.x, module.x - module.rx * 0.82, module.x + module.rx * 0.82);
      node.pos.y = clamp(node.pos.y, module.y - module.ry * 0.78, module.y + module.ry * 0.78);
    });

    if (!moved) break;
  }
}

export default function StructureGraph({ base64 }) {
  let result = { symbols: [], dependencies: [] };
  try {
    result = JSON.parse(decodeBase64(base64));
  } catch {
    result = { symbols: [], dependencies: [] };
  }

  const symbols = result.symbols ?? [];
  const visibleSymbols = symbols.filter((s) => s.module && s.module !== 'out');
  const bySymbol = new Map(visibleSymbols.map((symbol) => [symbol.symbol, symbol]));
  const edges = (result.dependencies ?? result.edges ?? []).filter(
    (edge) => bySymbol.has(edge.from) && bySymbol.has(edge.to),
  );
  const maxEdgeWeight = Math.max(...edges.map((edge) => edge.weight ?? 1), 1);
  const maxSymbolCentrality = Math.max(...visibleSymbols.map((s) => s.centrality ?? 0), 0.001);
  const byModule = new Map();
  visibleSymbols.forEach((symbol) => {
    const list = byModule.get(symbol.module) ?? [];
    list.push(symbol);
    byModule.set(symbol.module, list);
  });

  const modules = [...byModule.entries()]
    .map(([name, items]) => ({
      name,
      items: items.sort((a, b) => (b.centrality ?? 0) - (a.centrality ?? 0)),
      centrality: items.reduce((sum, item) => sum + (item.centrality ?? 0), 0),
      instability: items.reduce((sum, item) => sum + (item.instability ?? 0), 0) / Math.max(items.length, 1),
      maxCentrality: Math.max(...items.map((item) => item.centrality ?? 0), 0),
    }))
    .sort((a, b) => b.centrality - a.centrality);

  const width = 1180;
  const height = 640;
  const center = { x: width / 2, y: height / 2 + 8 };
  const nodeHeight = 46;
  const positions = new Map();

  const slots = [
    { x: center.x, y: center.y, rx: 270, ry: 245 },
    { x: 150, y: center.y, rx: 125, ry: 190 },
    { x: width - 150, y: center.y, rx: 125, ry: 190 },
    { x: center.x, y: 112, rx: 210, ry: 72 },
    { x: center.x, y: 560, rx: 210, ry: 58 },
  ];

  const laidOutModules = modules.map((mod, index) => {
    const slot = slots[index] ?? {
      x: center.x + Math.cos(index) * 360,
      y: center.y + Math.sin(index) * 220,
      rx: 150,
      ry: 120,
    };
    const color = instabilityColor(mod.instability);
    const maxInModule = Math.max(mod.maxCentrality, 0.001);
    mod.items.forEach((symbol, itemIndex) => {
      const normalized = clamp((symbol.centrality ?? 0) / maxInModule, 0, 1);
      const angle = -Math.PI / 2 + (Math.PI * 2 * itemIndex) / Math.max(mod.items.length, 1);
      const ringOffset = (itemIndex % 4) * 0.035;
      const radius = 0.34 + (1 - normalized) * 0.54;
      positions.set(symbol.symbol, {
        x: slot.x + Math.cos(angle) * slot.rx * clamp(radius + ringOffset, 0.32, 0.92),
        y: slot.y + Math.sin(angle) * slot.ry * clamp(radius, 0.32, 0.9),
      });
    });
    return { ...mod, ...slot, color };
  });

  laidOutModules.forEach((mod) => resolveModuleOverlaps(mod, positions, nodeHeight));

  return (
    <div className="ss-structure-graph">
      <svg className="ss-structure-svg" viewBox={`0 0 ${width} ${height}`} role="img">
        <defs>
          <marker
            id="ss-structure-arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="10"
            markerHeight="10"
            markerUnits="userSpaceOnUse"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" />
          </marker>
        </defs>

        {laidOutModules.map((mod) => (
          <g className="ss-structure-module" key={mod.name}>
            <ellipse
              className="ss-structure-module-area"
              cx={mod.x}
              cy={mod.y}
              rx={mod.rx}
              ry={mod.ry}
              style={{ fill: mod.color.fill, stroke: mod.color.stroke }}
            />
            <text className="ss-structure-module-title" x={mod.x} y={mod.y - mod.ry + 28} style={{ fill: mod.color.text }}>
              {mod.name}
            </text>
            <text className="ss-structure-module-note" x={mod.x} y={mod.y - mod.ry + 48} style={{ fill: mod.color.text }}>
              {mod.items.length} shown | I {mod.instability.toFixed(2)}
            </text>
          </g>
        ))}

        {edges.map((edge, index) => {
          const from = positions.get(edge.from);
          const to = positions.get(edge.to);
          if (!from || !to) return null;
          const dx = to.x - from.x;
          const dy = to.y - from.y;
          const length = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
          const start = { x: from.x + (dx / length) * 32, y: from.y + (dy / length) * 24 };
          const end = { x: to.x - (dx / length) * 44, y: to.y - (dy / length) * 28 };
          const curve = clamp(Math.abs(dx) * 0.15, 20, 90);
          const path = `M ${start.x} ${start.y} C ${start.x} ${start.y - curve}, ${end.x} ${end.y + curve}, ${end.x} ${end.y}`;
          const labelX = (start.x + end.x) / 2;
          const labelY = (start.y + end.y) / 2;
          const normalizedWeight = Math.pow(clamp((edge.weight ?? 1) / maxEdgeWeight, 0, 1), 0.65);
          const strokeWidth = 3.2 + normalizedWeight * 5.2;
          const strokeOpacity = 0.76 + normalizedWeight * 0.22;
          const stroke = `rgba(15, 23, 42, ${strokeOpacity})`;
          return (
            <g key={`${edge.from}-${edge.to}-${index}`}>
              <path
                className="ss-structure-edge"
                d={path}
                markerEnd="url(#ss-structure-arrow)"
                style={{ stroke, strokeWidth }}
              />
              {index % 3 === 0 ? (
                <text className="ss-structure-edge-label" x={labelX} y={labelY - 4}>
                  {shortDim(edge.dimensions)}
                </text>
              ) : null}
            </g>
          );
        })}

        {visibleSymbols.map((symbol) => {
          const pos = positions.get(symbol.symbol);
          if (!pos) return null;
          const rank = symbol.centrality ?? 0;
          const normalized = clamp(rank / maxSymbolCentrality, 0, 1);
          const nodeWidth = textWidth(symbol.name);
          const prominent = normalized >= 0.72 || (symbol.ca ?? 0) >= 6;
          const color = instabilityColor(symbol.instability ?? 0);
          return (
            <g className="ss-structure-node" key={symbol.symbol} transform={`translate(${pos.x - nodeWidth / 2} ${pos.y - nodeHeight / 2})`}>
              <rect
                className={prominent ? 'ss-structure-node-box prominent' : 'ss-structure-node-box'}
                width={nodeWidth}
                height={nodeHeight}
                rx="6"
                style={{ fill: color.fill, stroke: prominent ? undefined : color.stroke }}
              />
              <text className="ss-structure-node-name" x={nodeWidth / 2} y="18" style={{ fill: color.text }}>{symbol.name}</text>
              <text className="ss-structure-node-layer" x={nodeWidth / 2} y="34" style={{ fill: color.text }}>
                Ca {symbol.ca ?? 0} | Ce {symbol.ce ?? 0} | {rank.toFixed(3)}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="ss-structure-legend">
        <span>Colored areas are modules; modules with higher aggregate centrality are placed closer to the center.</span>
        <span>Redder backgrounds mean higher instability for both modules and classes.</span>
        <span>Classes with higher centrality sit closer to the center of their module area.</span>
        <span>Arrows point from a dependent type to the type it depends on.</span>
      </div>
    </div>
  );
}
