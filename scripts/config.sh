#!/usr/bin/env bash
# Project-specific configuration for the release/CI helper scripts.
# These are the only values that change between projects — override any of them by exporting an
# environment variable of the same name before running a script.

# GitHub "owner/repo".
REPO="${REPO:-MercurieVV/ScalaSemantic}"

# First release version, used when no v* tag exists yet.
FIRST_VERSION="${FIRST_VERSION:-0.1.0}"

# Sonatype Central host (the new portal; legacy OSSRH would be s01.oss.sonatype.org).
SONATYPE_CREDENTIAL_HOST="${SONATYPE_CREDENTIAL_HOST:-central.sonatype.com}"

# CI workflow name (as shown in the Actions tab), used by check-push-workflow.sh.
CI_WORKFLOW_NAME="${CI_WORKFLOW_NAME:-CI}"

# Maven Central metadata of a representative published artifact, for the "latest published" report.
MAVEN_METADATA_URL="${MAVEN_METADATA_URL:-https://repo1.maven.org/maven2/io/github/mercurievv/scalasemantic-core_3/maven-metadata.xml}"

# Repository secrets that setup-gh-repo.sh sets for the release workflow.
# (SONATYPE_CREDENTIAL_HOST is passed as a workflow env in ci.yml, so it isn't required as a secret.)
RELEASE_SECRETS=(PGP_PASSPHRASE PGP_SECRET SONATYPE_USERNAME SONATYPE_PASSWORD)

# sbt-ci-release imports the key via `base64 --decode | gpg --import`, so PGP_SECRET must be base64.
# If true, setup-gh-repo.sh base64-encodes the PGP_SECRET value before posting — i.e. you supply the
# raw armored key (`gpg --armor --export-secret-keys ...`) and the script encodes it. Set false if
# the value you supply is already base64.
PGP_SECRET_BASE64_ENCODE="${PGP_SECRET_BASE64_ENCODE:-true}"

# Resolve the directory of the calling script so sourcing this file is location-independent.
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SCRIPTS_DIR
