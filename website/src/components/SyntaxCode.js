import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';

export default function SyntaxCode({ code, language = 'scala' }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  const src = (code ?? '').replace(/\n+$/, '');
  return (
    <Highlight theme={theme} code={src} language={language}>
      {({ className, style, tokens, getLineProps, getTokenProps }) => (
        <pre className={`${className} ss-syntax-code`} style={style}>
          <code>
            {tokens.map((line, i) => (
              <span {...getLineProps({ line })} key={i} style={{ display: 'block' }}>
                {line.map((token, k) => (
                  <span {...getTokenProps({ token })} key={k} />
                ))}
              </span>
            ))}
          </code>
        </pre>
      )}
    </Highlight>
  );
}
