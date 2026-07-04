# Releasing

CI builds and tests every push/PR, and **publishes to Sonatype Central on a `vX.Y.Z` tag** via `sbt ci-release`. Artifacts publish under `io.github.mercurievv`. Published modules: `core`, `analysis`, `mcp` (the `root` aggregate skips publish).

Scripts read defaults from [`scripts/config.sh`](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/config.sh); override any value with an env var of the same name.

## One-time repo setup

```sh
# Set GitHub Actions secrets (credentials from env vars or 1Password):
PGP_SECRET="$(gpg --armor --export-secret-keys <KEYID>)" PGP_PASSPHRASE=... \
SONATYPE_USERNAME=... SONATYPE_PASSWORD=... \
  scripts/setup-gh-repo.sh

# 1Password variant:
scripts/setup-gh-repo.sh --op-item op://Vault/ScalaSemantic-release

# Optional: install pre-push hook to run ./mill prePush locally:
scripts/install-git-hooks.sh
```

`PGP_SECRET` is the raw armored key; `config.sh` has `PGP_SECRET_BASE64_ENCODE=true` so the script base64-encodes it before storing. If you supply an already-base64 value, set `PGP_SECRET_BASE64_ENCODE=false`.

Publish the matching PGP **public key** to a keyserver (e.g. `keys.openpgp.org`) so Central can verify signatures.

## Required GitHub Actions secrets

| Secret | Value |
|--------|-------|
| `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` | Central Portal user token (central.sonatype.com → Account → Generate User Token) |
| `PGP_SECRET` | `gpg --armor --export-secret-keys <KEYID> \| base64` |
| `PGP_PASSPHRASE` | passphrase for that key |

## Cut a release

Development is PR-based (branch protection on `master`, squash-merge). A release is a tag on the latest `origin/master` commit:

```sh
scripts/bump-version.sh minor       # tags vX.Y.Z on origin/master and pushes unconditionally
scripts/check-push-workflow.sh      # wait for CI; reports the latest published version
scripts/retry-last-tag.sh --push    # move the tag to HEAD to retry a failed release
```

## Version in docs

- `docs/getting-started/integration.md` uses `@VERSION@`, filled at site-build time from the highest `v*` git tag.
- `README.md` shows an `x.y.z` placeholder plus the Maven Central badge for the real number.
- `scripts/update-doc-versions.sh` is a retired no-op kept to avoid breaking the post-release CI step.

## Release notes

Notes are **generated from PR titles**, not hand-written. [`scripts/changelog.sh`](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/changelog.sh) keeps only user-facing Conventional-Commit types (`feat`, `fix`, `perf`, breaking `type!:`) and omits `docs`/`refactor`/`test`/`chore`/`ci`/`build`/`style`. An all-maintenance release reads "no user-facing changes".

The same renderer feeds:
- **GitHub Release body** — CI runs `changelog.sh <prevTag>..<tag>` as the release body.
- **Docs site** — [`scripts/gen-release-notes.sh`](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/gen-release-notes.sh) rebuilds `docs/project/release-notes.md` (gitignored; regenerated before each Docusaurus build).

## Dry run

Actions → CI → Run workflow (`workflow_dispatch`) builds and publishes a `-SNAPSHOT` to exercise secrets, signing, and upload without cutting a release.
