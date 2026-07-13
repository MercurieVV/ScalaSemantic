import MDXComponents from '@theme-original/MDXComponents';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import EnrichedCode from '@site/src/components/EnrichedCode';

// Registered globally so the mdoc-generated Markdown can use <Tabs>/<TabItem>/<EnrichedCode>
// without per-file import statements (mdoc passes these tags through verbatim into website/docs).
export default {
  ...MDXComponents,
  Tabs,
  TabItem,
  EnrichedCode,
};
