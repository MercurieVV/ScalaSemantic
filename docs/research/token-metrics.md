# Token Metrics

Approximate token cost uses `ceil(character_count / 4)` for both paths. The ScalaSemantic path is the exact JSON text returned by the MCP tool renderer. The baseline path is deterministic raw source or grep-style context from checked-in fixture sources that an agent would otherwise need to inspect.

| Query | MCP tool | Tool tokens | Baseline tokens | Delta | Savings |
| --- | --- | ---: | ---: | ---: | ---: |
| `find-usages-animal` | `find_usages` | 100 | 2808 | 2708 | 96.4% |
| `class-hierarchy-animal` | `class_hierarchy` | 111 | 380 | 269 | 70.8% |
| `method-signature-render` | `method_signature` | 83 | 379 | 296 | 78.1% |
| `trace-implicit-show` | `trace_implicit_chain` | 56 | 379 | 323 | 85.2% |
| `call-path-a-to-c` | `call_path` | 65 | 378 | 313 | 82.8% |
| **Overall** |  | **415** | **4324** | **3909** | **90.4%** |

Regenerate with:

```bash
UPDATE_TOKEN_METRICS=1 sbt "mcp/testOnly com.github.mercurievv.scalasemantic.mcp.TokenMetricsSuite"
```
