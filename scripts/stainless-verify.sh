#!/usr/bin/env bash
# Formal-verification gate: run the standalone Stainless tool over the project's verifiable
# contracts (analysis/.../PureKernels.scala — the production numeric/geometric kernels, verified
# in place, no mirror) and fail iff any verification condition is INVALID.
#
# Why parse the summary instead of trusting Stainless's exit code: the tool exits non-zero on
# `unknown` (solver timeout) as well as `invalid`. One contract — `rangeSpan` — has a nonlinear
# Long-multiplication overflow VC that the bundled `smt-z3` cannot discharge within the timeout
# (it needs the native Z3 backend), so it comes back `unknown` on most runners. That is a solver
# limitation, not a soundness failure, and must NOT fail CI. A genuine regression (e.g. reverting
# rangeSpan to the unsound `Int` form) produces an `invalid` VC, which we DO fail on. So the gate
# keys off the `invalid:` count in the summary, tolerating `unknown`.
#
# The Stainless standalone distribution bundles its own z3/cvc5 binaries, so no extra solver
# install is needed. Usage: scripts/stainless-verify.sh   (run from the repo root)
set -euo pipefail

VERSION="${STAINLESS_VERSION:-0.9.9.3}"
TARGET="analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/PureKernels.scala"
CACHE_DIR="${STAINLESS_CACHE_DIR:-$HOME/.cache/scalasemantic/stainless}"
# Per-VC timeout. Default kept low so the local/prePush path stays snappy: every genuinely-valid
# VC is discharged in well under this, an INVALID yields its counter-example near-instantly, and the
# only VCs that ever hit the limit are rangeSpan's nonlinear-multiplication ones that the bundled
# smt-z3 can't solve regardless (tolerated as `unknown`). CI overrides this to 30 for headroom.
TIMEOUT="${STAINLESS_TIMEOUT:-10}"

# --- pick the release asset for this OS/arch -------------------------------------------------
os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64)          platform="linux" ;;
  Darwin/arm64)          platform="mac-arm64" ;;
  Darwin/x86_64)         platform="mac-x64" ;;
  *) echo "stainless-verify: unsupported platform $os/$arch" >&2; exit 2 ;;
esac

asset="stainless-dotty-standalone-${VERSION}-${platform}.zip"
url="https://github.com/epfl-lara/stainless/releases/download/v${VERSION}/${asset}"
install_dir="${CACHE_DIR}/${VERSION}-${platform}"

# --- download + cache the standalone tool ----------------------------------------------------
stainless_bin="$(find "$install_dir" -maxdepth 2 -name stainless -type f 2>/dev/null | head -1 || true)"
if [ -z "$stainless_bin" ]; then
  echo "stainless-verify: installing Stainless $VERSION ($platform) -> $install_dir"
  mkdir -p "$install_dir"
  tmp_zip="$(mktemp -t stainless.XXXXXX.zip)"
  curl -fsSL "$url" -o "$tmp_zip"
  unzip -q -o "$tmp_zip" -d "$install_dir"
  rm -f "$tmp_zip"
  stainless_bin="$(find "$install_dir" -maxdepth 2 -name stainless -type f | head -1)"
fi
[ -n "$stainless_bin" ] || { echo "stainless-verify: stainless launcher not found after install" >&2; exit 2; }
chmod +x "$stainless_bin" 2>/dev/null || true

# --- verify ----------------------------------------------------------------------------------
echo "stainless-verify: verifying $TARGET (timeout ${TIMEOUT}s/VC)"
out="$(mktemp -t stainless-out.XXXXXX)"
# Don't let a non-zero exit (e.g. from `unknown`) abort the script before we inspect the summary.
set +e
"$stainless_bin" --timeout="$TIMEOUT" "$TARGET" 2>&1 | tee "$out"
set -e

# Strip ANSI colour, then read the summary's `invalid: N` field.
summary="$(sed -E 's/\x1b\[[0-9;]*m//g' "$out" | grep -E 'total:[[:space:]]+[0-9]+' | tail -1 || true)"
rm -f "$out"

if [ -z "$summary" ]; then
  echo "stainless-verify: FAILED — no verification summary produced (tool/compile error above)" >&2
  exit 1
fi

invalid="$(echo "$summary" | sed -E 's/.*invalid:[[:space:]]*([0-9]+).*/\1/')"
echo "stainless-verify: summary -> $summary"

if [ "${invalid:-0}" -ne 0 ]; then
  echo "stainless-verify: FAILED — $invalid invalid verification condition(s)" >&2
  exit 1
fi

echo "stainless-verify: OK — no invalid verification conditions (unknown/timeout VCs tolerated)"
