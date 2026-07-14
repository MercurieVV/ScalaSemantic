import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';
import SemanticSymbol from './SemanticSymbol';

const symbolKeys = new Set(['symbol', 'chosen', 'owner', 'definition', 'reference']);
const scalaKeys = new Set(['type', 'appliedType', 'signature']);

function decodeBase64(value) {
  if (!value) return '';
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function isSemanticSymbol(value) {
  return /[/#]/.test(value) && /[#.]$|\(\)\.$/.test(value);
}

function InlineSyntax({ code, language = 'scala' }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  return (
    <Highlight theme={theme} code={code ?? ''} language={language}>
      {({ tokens, getTokenProps }) => (
        <span className="ss-inline-syntax">
          {tokens.flatMap((line, lineIndex) =>
            line.map((token, tokenIndex) => (
              <span {...getTokenProps({ token })} key={`${lineIndex}-${tokenIndex}`} />
            )),
          )}
        </span>
      )}
    </Highlight>
  );
}

function renderString(key, value) {
  const symbolLike = symbolKeys.has(key) || isSemanticSymbol(value);
  const scalaLike = scalaKeys.has(key);
  return (
    <>
      <span className="ss-json-string">"</span>
      {symbolLike ? (
        <SemanticSymbol value={value} inline />
      ) : scalaLike ? (
        <InlineSyntax code={value} />
      ) : (
        <span className="ss-json-string">{value}</span>
      )}
      <span className="ss-json-string">"</span>
    </>
  );
}

function renderValue(value, indent, key) {
  const pad = '  '.repeat(indent);
  const childPad = '  '.repeat(indent + 1);

  if (value === null) return <span className="ss-json-null">null</span>;
  if (typeof value === 'string') return renderString(key, value);
  if (typeof value === 'number') return <span className="ss-json-number">{String(value)}</span>;
  if (typeof value === 'boolean') return <span className="ss-json-boolean">{String(value)}</span>;

  if (Array.isArray(value)) {
    if (value.length === 0) return <span>[]</span>;
    return (
      <>
        <span>[</span>
        {value.map((item, index) => (
          <React.Fragment key={index}>
            {'\n'}
            <span>{childPad}</span>
            {renderValue(item, indent + 1, key)}
            {index < value.length - 1 ? <span>,</span> : null}
          </React.Fragment>
        ))}
        {'\n'}
        <span>{pad}]</span>
      </>
    );
  }

  if (typeof value === 'object') {
    const entries = Object.entries(value);
    if (entries.length === 0) return <span>{'{}'}</span>;
    return (
      <>
        <span>{'{'}</span>
        {entries.map(([entryKey, entryValue], index) => (
          <React.Fragment key={entryKey}>
            {'\n'}
            <span>{childPad}</span>
            <span className="ss-json-key">"{entryKey}"</span>
            <span>: </span>
            {renderValue(entryValue, indent + 1, entryKey)}
            {index < entries.length - 1 ? <span>,</span> : null}
          </React.Fragment>
        ))}
        {'\n'}
        <span>{pad}</span>
        <span>{'}'}</span>
      </>
    );
  }

  return <span>{String(value)}</span>;
}

export default function SemanticJson({ code, base64 }) {
  const source = base64 ? decodeBase64(base64) : code;
  let parsed;
  try {
    parsed = JSON.parse(source ?? '');
  } catch {
    return (
      <pre className="ss-semantic-json">
        <code>{source}</code>
      </pre>
    );
  }

  return (
    <pre className="ss-semantic-json">
      <code>{renderValue(parsed, 0, undefined)}</code>
    </pre>
  );
}
