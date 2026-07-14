import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';

function segments(line) {
  const out = [];
  const re = /(\[-[\s\S]*?-\]|\{\+[\s\S]*?\+\})/g;
  let last = 0;
  for (const match of line.matchAll(re)) {
    if (match.index > last) out.push({ text: line.slice(last, match.index), kind: 'plain' });
    const marker = match[0];
    out.push({
      text: marker.slice(2, -2),
      kind: marker.startsWith('[-') ? 'del' : 'ins',
    });
    last = match.index + marker.length;
  }
  if (last < line.length) out.push({ text: line.slice(last), kind: 'plain' });
  return out;
}

function parsedLine(line) {
  const diffPrefix = line.match(/^(\s*[+-])/);
  const contextPrefix = diffPrefix ? null : line.match(/^( )/);
  const prefix = diffPrefix?.[1] ?? contextPrefix?.[1] ?? '';
  const body = prefix ? line.slice(prefix.length) : line;
  const parts = segments(body);
  let offset = 0;
  const ranges = [];
  const text = parts
    .map((part) => {
      const start = offset;
      offset += part.text.length;
      if (part.kind !== 'plain' && part.text.length > 0) {
        ranges.push({ start, end: offset, kind: part.kind });
      }
      return part.text;
    })
    .join('');
  return { prefix, text, ranges };
}

function rangesFor(start, end, ranges) {
  const splits = [];
  let cursor = start;
  for (const range of ranges) {
    if (range.end <= cursor || range.start >= end) continue;
    if (range.start > cursor) {
      splits.push({ start: cursor, end: range.start, kind: 'plain' });
    }
    const segmentStart = Math.max(cursor, range.start);
    const segmentEnd = Math.min(end, range.end);
    splits.push({ start: segmentStart, end: segmentEnd, kind: range.kind });
    cursor = segmentEnd;
  }
  if (cursor < end) splits.push({ start: cursor, end, kind: 'plain' });
  return splits;
}

function lineKind(line) {
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) return 'meta';
  if (/^\s*\+/.test(line)) return 'ins-line';
  if (/^\s*-/.test(line)) return 'del-line';
  return 'plain';
}

function decodeBase64(value) {
  if (!value) return '';
  const bytes = atob(value);
  const escaped = Array.from(bytes, (c) => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`).join('');
  return decodeURIComponent(escaped);
}

function HighlightedLine({ line, theme }) {
  const kind = lineKind(line);
  if (kind === 'meta') {
    return (
      <span className="ss-diff-line ss-meta">
        {line}
        {'\n'}
      </span>
    );
  }

  const parsed = parsedLine(line);
  return (
    <Highlight theme={theme} code={parsed.text} language="scala">
      {({ tokens, getTokenProps }) => {
        let offset = 0;
        return (
          <span className={`ss-diff-line ss-${kind}`}>
            {parsed.prefix ? <span className="ss-diff-prefix">{parsed.prefix}</span> : null}
            {tokens[0].map((token, tokenIndex) => {
              const tokenStart = offset;
              const tokenEnd = offset + token.content.length;
              offset = tokenEnd;
              return rangesFor(tokenStart, tokenEnd, parsed.ranges).map((slice, sliceIndex) => {
                const content = token.content.slice(
                  slice.start - tokenStart,
                  slice.end - tokenStart,
                );
                const tokenProps = getTokenProps({ token: { ...token, content } });
                const tokenNode = <span {...tokenProps} />;
                return slice.kind === 'plain' ? (
                  <React.Fragment key={`${tokenIndex}-${sliceIndex}`}>{tokenNode}</React.Fragment>
                ) : (
                  <span className={`ss-${slice.kind}`} key={`${tokenIndex}-${sliceIndex}`}>
                    {tokenNode}
                  </span>
                );
              });
            })}
            {'\n'}
          </span>
        );
      }}
    </Highlight>
  );
}

export default function WordDiffCode({ code, base64 }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  const src = (code ?? decodeBase64(base64)).replace(/\n+$/, '');
  return (
    <pre className="ss-word-diff">
      <code>
        {src.split('\n').map((line, i) => (
          <HighlightedLine line={line} theme={theme} key={i} />
        ))}
      </code>
    </pre>
  );
}
