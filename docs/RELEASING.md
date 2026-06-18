# Releasing

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) builds and tests every push/PR, and
**publishes to Sonatype Central on a `vX.Y.Z` tag** via `sbt ci-release` (sbt-dynver derives the
version from the tag; sbt-ci-release signs and uploads). Artifacts publish under
`io.github.mercurievv`.

## Cut a release

```sh
scripts/bump-version.sh patch --push   # or minor / major — tags vX.Y.Z and pushes (triggers publish)
scripts/retry-last-tag.sh --push       # move the tag to HEAD to retry a release that failed early
```

## Required GitHub Actions secrets

Settings → Secrets and variables → Actions:

| Secret | What |
|--------|------|
| `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` | Central Portal user token (central.sonatype.com → Account → Generate User Token) |
| `PGP_SECRET` | base64 of your armored secret key: `gpg --armor --export-secret-keys <KEYID> \| base64` |
| `PGP_PASSPHRASE` | passphrase for that key |

Publish the matching **public** key to a keyserver (e.g. `keys.openpgp.org`) so Central can verify
signatures. The namespace `io.github.mercurievv` must be verified once under your account.

## Dry run

Trigger the workflow manually (Actions → CI → *Run workflow*, i.e. `workflow_dispatch`) to build and
publish a `-SNAPSHOT` — this exercises the secrets, signing, and upload without cutting a release.

## What publishes

`core`, `analysis`, `mcp`, and `sbt-plugin`. The `root` aggregate sets `publish / skip := true`.
