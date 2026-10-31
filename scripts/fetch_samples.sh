#!/usr/bin/env bash
# Downloads wallpapers from the upstream repository into samples/.
# Usage: scripts/fetch_samples.sh wallpapers/custom/Cipher.tendies [more repo-relative paths...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="https://raw.githubusercontent.com/SerStars/Nugget-Wallpapers/main"
[ $# -gt 0 ] || { echo "usage: $0 <repo-relative path>..."; exit 1; }
for p in "$@"; do
  name="$(basename "$p")"
  enc="$(python3 -c 'import sys,urllib.parse; print("/".join(urllib.parse.quote(s) for s in sys.argv[1].split("/")))' "$p")"
  echo "-> $name"
  curl -fL --retry 3 -o "$ROOT/samples/$name" "$BASE/$enc"
done
