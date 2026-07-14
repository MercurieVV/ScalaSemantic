import React from 'react';

const tokenRe = /([/#.$()]+)/g;

export default function SemanticSymbol({ value, inline = false }) {
  const text = value ?? '';
  const parts = text.split(tokenRe).filter((p) => p.length > 0);
  const Tag = inline ? 'span' : 'code';
  return (
    <Tag className={`ss-semantic-symbol${inline ? ' ss-semantic-symbol-inline' : ''}`}>
      {parts.map((part, i) => {
        const punct = tokenRe.test(part);
        tokenRe.lastIndex = 0;
        return (
          <span className={punct ? 'ss-symbol-punct' : 'ss-symbol-name'} key={i}>
            {part}
          </span>
        );
      })}
    </Tag>
  );
}
