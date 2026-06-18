# Releasing

CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) builds and tests every push/PR, and
**publishes to Sonatype Central on a `vX.Y.Z` tag** via `sbt ci-release` (sbt-dynver derives the
version from the tag; sbt-ci-release signs and uploads). Artifacts publish under
`io.github.mercurievv`.

All helper scripts read project-specific values from [`scripts/config.sh`](../scripts/config.sh)
(repo, first version, Central host, artifact metadata URL, the secret list) — override any with an
env var of the same name, so the scripts are reusable across projects.

## One-time repo setup

```sh
# PGP_SECRET = the RAW armored key; the script base64-encodes it (PGP_SECRET_BASE64_ENCODE=true in
# config.sh) because ci-release imports via `base64 --decode | gpg --import`.
PGP_SECRET="$(gpg --armor --export-secret-keys <KEYID>)" PGP_PASSPHRASE=... \
SONATYPE_USERNAME=... SONATYPE_PASSWORD=... \
  scripts/setup-gh-repo.sh                 # sets the repo secrets + enables the dependency graph
# or pull the values from 1Password:
scripts/setup-gh-repo.sh --op-item op://Vault/ScalaSemantic-release
scripts/install-git-hooks.sh               # optional: run `sbt prePush` on every push
```

If you supply a value that is *already* base64, set `PGP_SECRET_BASE64_ENCODE=false`.

## Cut a release

```sh
scripts/bump-version.sh patch --push   # or bump-fix/bump-minor/bump-major — tags vX.Y.Z and pushes
scripts/check-push-workflow.sh         # wait for CI, report the latest published version
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
