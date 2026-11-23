# Programmatic use and code mode

ScalaSemantic is built to be driven by code as well as by chat. Clients that generate and run
programs against MCP tools ("code mode"), scripts, and other agents get the same compact answers
an interactive agent does, and can rely on their shape.

## What a program can rely on

- **Typed inputs.** `tools/list` returns an `inputSchema` per tool. Required arguments are the
  non-optional fields; optional ones may be omitted. Bad arguments fail with
  ``tool: invalid arguments: `field`: message``, naming the offending field. Unknown arguments are
  ignored, so a newer client can talk to an older server.
- **Typed outputs.** `tools/list` also returns an `outputSchema` per tool: compact JSON Schema, with
  recursive types as `$defs` references. The schemas are derived from the same Scala types that
  produce the results (circe codecs plus tapir schemas), so schema and output cannot drift; a test
  checks every tool's real output against its schema.
- **Absent means absent.** An optional field with no value is left out of the result, never `null`.
  Test for the key, not for `null`.
- **Symbols are stable handles.** Results carry SemanticDB symbols and `uri:line:col` locations that
  you can feed straight into the next call (`find_symbol` → `find_usages` → `rename_plan`).
- **Compact by design.** Answers are projections, not dumps, so a program can hold many of them in
  context. See [ScalaSemantic vs grep](scala-semantic-vs-grep.md) for measured token savings.

## Chaining calls

`batch` runs up to 20 calls in order in one request and returns one `{tool, ok, result | error}`
entry per call. A failing call does not abort the rest, and `batch` cannot call itself. Use it when
the calls are independent or when a program wants one round trip instead of many.

```json
{"calls": [
  {"tool": "find_symbol", "args": {"query": "SemanticIndex"}},
  {"tool": "structure", "args": {}}
]}
```

## Typical program shape

1. Generate bindings (or a validator) from the `inputSchema`/`outputSchema` of the tools you use.
2. Resolve names with `find_symbol`; keep the returned symbol strings.
3. Ask the semantic question (`find_usages`, `class_hierarchy`, `call_path`, `resolve_implicits`, …).
4. For changes, request a plan (`rename_plan`, `move_plan`, `extract_method_plan`) and apply its
   edits yourself; the server never writes files except through `annotated_source` write mode.

## Caveat

Answers are only as fresh as the compiled SemanticDB. Compile first, or call `refresh_workspace`;
an empty index is reported in the result (`indexEmptyHint`) rather than as an error.
