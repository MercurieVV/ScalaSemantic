import React from 'react';
import { Highlight, themes } from 'prism-react-renderer';
import { useColorMode } from '@docusaurus/theme-common';

// The glyph the analysis layer uses for every inserted note — as a trailing `// ⟹` comment
// (annotated_source's `compilable` format) or a bare trailing `⟹` (source_ranges' notes with no
// known splice point). Everything from the glyph to end-of-line is a compiler insertion (not in the
// source as written), so we tint only that span — the "intra-line diff" that avoids re-printing
// whole `+`/`-` lines. Spliced insertions (source_ranges' real `: T`/`[T]`) carry no marker at all
// and are not tinted — they ARE the code, not a note about it.
const NOTE_MARKER = '⟹';

function decodeBase64(value) {
  if (!value) return '';
  const bytes = atob(value);
  const escaped = Array.from(bytes, (c) => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`).join('');
  return decodeURIComponent(escaped);
}

/**
 * Renders enriched Scala from `annotated_source` (compilable format) with Prism syntax colours,
 * background-tinting ONLY the inserted `// ⟹ …` notes green — so the reader sees exactly what the
 * compiler added, in place, without a whole-line diff. Pass `code` (a plain string prop) or
 * `base64` (matching `WordDiffCode`'s prop) — base64 is the safer choice for text mdoc/MDX's own
 * markdown parser could otherwise misparse (e.g. `(n)` read as a link reference).
 */
export default function EnrichedCode({ code, base64, language = 'scala' }) {
  const { colorMode } = useColorMode();
  const theme = colorMode === 'dark' ? themes.dracula : themes.github;
  const src = (code ?? decodeBase64(base64)).replace(/\n+$/, '');
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
