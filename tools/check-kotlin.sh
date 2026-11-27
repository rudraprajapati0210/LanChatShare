#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cache="${XDG_CACHE_HOME:-$HOME/.cache}/lanchatshare/quality"
mkdir -p "$cache"

fetch() {
  local name="$1" digest="$2" url="$3"
  if [[ ! -f "$cache/$name" ]]; then
    local tmp
    tmp=$(mktemp "$cache/download.XXXXXX")
    curl --fail --location --retry 3 --output "$tmp" "$url"
    printf '%s  %s\n' "$digest" "$tmp" | sha256sum --check --status
    mv "$tmp" "$cache/$name"
  fi
  printf '%s  %s\n' "$digest" "$cache/$name" | sha256sum --check --status
}

fetch ktlint-1.8.0.jar a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75 https://github.com/pinterest/ktlint/releases/download/1.8.0/ktlint
fetch detekt-1.23.8.jar 2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1 https://github.com/detekt/detekt/releases/download/v1.23.8/detekt-cli-1.23.8-all.jar

java -jar "$cache/ktlint-1.8.0.jar" --relative --baseline=config/quality/ktlint.xml \
  'app/src/**/*.kt' 'core/**/src/**/*.kt' '*.gradle.kts' 'app/*.gradle.kts' 'core/*/*.gradle.kts'
java -jar "$cache/detekt-1.23.8.jar" --baseline config/quality/detekt.xml \
  --input app/src,core/connectivity/src,core/data/src,core/design/src
