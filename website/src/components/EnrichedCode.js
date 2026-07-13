import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';

// The delimiter the analysis layer uses for every inserted note in the `compilable` format.
// Everything from this marker to end-of-line is a compiler insertion (not in the source as written),
// so we tint only that span — the "intra-line diff" that avoids re-printing whole `+`/`-` lines.
const NOTE_MARKER = '// ⟹';

/**
 * Renders enriched Scala from `annotated_source` (compilable format) with Prism syntax colours,
 * background-tinting ONLY the inserted `// ⟹ …` notes green — so the reader sees exactly what the
 * compiler added, in place, without a whole-line diff. `code` arrives as a plain string prop.
 */
export default function EnrichedCode({ code, language = 'scala' }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  const src = (code ?? '').replace(/\n+$/, '');
  return (
    <Highlight theme={theme} code={src} language={language}>
      {({ className, style, tokens, getLineProps, getTokenProps }) => (
        <pre className={`${className} ss-enriched`} style={style}>
          <code>
            {tokens.map((line, i) => {
              const lineStr = line.map((t) => t.content).join('');
              const marker = lineStr.indexOf(NOTE_MARKER);
              let pos = 0;
              return (
                <span {...getLineProps({ line })} key={i} style={{ display: 'block' }}>
                  {line.map((token, k) => {
                    const start = pos;
                    pos += token.content.length;
                    const props = getTokenProps({ token });
                    if (marker >= 0 && start >= marker) {
                      props.className = `${props.className || ''} ss-ins`;
                    }
                    return <span {...props} key={k} />;
                  })}
                </span>
              );
            })}
          </code>
        </pre>
      )}
    </Highlight>
  );
}
