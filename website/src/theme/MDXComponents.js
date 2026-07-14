import MDXComponents from '@theme-original/MDXComponents';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import EnrichedCode from '@site/src/components/EnrichedCode';
import MermaidDiagram from '@site/src/components/MermaidDiagram';
import OutlineTree from '@site/src/components/OutlineTree';
import SemanticJson from '@site/src/components/SemanticJson';
import SemanticSymbol from '@site/src/components/SemanticSymbol';
import SyntaxCode from '@site/src/components/SyntaxCode';
import StructureGraph from '@site/src/components/StructureGraph';
import WordDiffCode from '@site/src/components/WordDiffCode';

// Registered globally so the mdoc-generated Markdown can use <Tabs>/<TabItem>/<EnrichedCode>
// without per-file import statements (mdoc passes these tags through verbatim into website/docs).
export default {
  ...MDXComponents,
  Tabs,
  TabItem,
  EnrichedCode,
  MermaidDiagram,
  OutlineTree,
  SemanticJson,
  SemanticSymbol,
  SyntaxCode,
  StructureGraph,
  WordDiffCode,
};
