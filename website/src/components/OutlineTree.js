import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';

function decodeBase64(value) {
  if (!value) return '';
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function InlineScala({ code }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  return (
    <Highlight theme={theme} code={code ?? ''} language="scala">
      {({ tokens, getTokenProps }) => (
        <code className="ss-outline-signature">
          {tokens.flatMap((line, lineIndex) =>
            line.map((token, tokenIndex) => (
              <span {...getTokenProps({ token })} key={`${lineIndex}-${tokenIndex}`} />
            )),
          )}
        </code>
      )}
    </Highlight>
  );
}

function OutlineNode({ node }) {
  const children = node.children ?? [];
  return (
    <li className="ss-outline-node">
      <div className="ss-outline-row">
        <span className="ss-outline-name">{node.name ?? '?'}</span>
        {node.kind ? <span className="ss-outline-kind">{node.kind}</span> : null}
        {node.signature ? <InlineScala code={node.signature} /> : null}
      </div>
      {children.length > 0 ? (
        <ul className="ss-outline-children">
          {children.map((child, index) => (
            <OutlineNode node={child} key={`${child.symbol ?? child.name}-${index}`} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

export default function OutlineTree({ base64 }) {
  let outline = [];
  try {
    outline = JSON.parse(decodeBase64(base64)).outline ?? [];
  } catch {
    outline = [];
  }

  return (
    <div className="ss-outline-tree">
      <ul className="ss-outline-roots">
        {outline.map((node, index) => (
          <OutlineNode node={node} key={`${node.symbol ?? node.name}-${index}`} />
        ))}
      </ul>
    </div>
  );
}
