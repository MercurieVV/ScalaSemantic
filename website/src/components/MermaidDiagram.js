import React from 'react';
import Mermaid from '@theme/Mermaid';

function decodeBase64(value) {
  if (!value) return '';
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export default function MermaidDiagram({ base64, kind = 'default' }) {
  return (
    <div className={`ss-mermaid-diagram ss-mermaid-${kind}`}>
      <Mermaid value={decodeBase64(base64)} />
    </div>
  );
}
