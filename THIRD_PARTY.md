# Third-Party Source Corpora

The build can fetch these source corpora into `target/vendor-corpus/` for compatibility testing.
They are downloaded on demand as checksum-verified build artifacts and are not redistributed in
this repository.

## Scalameta SemanticDB Integration Fixtures

- Upstream: https://github.com/scalameta/scalameta
- Pinned ref: `v4.13.9`
- Fetched archive: https://github.com/scalameta/scalameta/archive/refs/tags/v4.13.9.tar.gz
- Extracted subtree: `tests-semanticdb/src/test/resources/example`
- Local path: `target/vendor-corpus/scala-2.13/`
- License: BSD-3-Clause

## Scala 3 SemanticDB Expect Fixtures

- Upstream: https://github.com/scala/scala3
- Pinned ref: `3.8.4`
- Fetched archive: https://github.com/scala/scala3/archive/refs/tags/3.8.4.tar.gz
- Extracted subtree: `tests/semanticdb/expect`
- Local path: `target/vendor-corpus/scala-3/`
- License: Apache-2.0
