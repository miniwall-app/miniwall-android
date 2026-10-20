#!/usr/bin/env bash
# Zips build/central-staging (made by `./gradlew publishAllPublicationsToStagingRepository`) into the bundle the Maven Central
# Portal accepts, and checks it first: every artifact needs a signature and the sources and javadoc jars.
#   scripts/central-bundle.sh            -> build/central-bundle.zip
# Upload (done by the release workflow, not by hand): POST the zip to https://central.sonatype.com/api/v1/publisher/upload
set -euo pipefail
cd "$(dirname "$0")/.."
stage=build/central-staging
[ -d "$stage" ] || { echo "no $stage: run ./gradlew publishAllPublicationsToStagingRepository first" >&2; exit 1; }
bad=0
while IFS= read -r pom; do
  base=${pom%.pom}
  main=$base.jar
  [ -f "$base.aar" ] && main=$base.aar
  [ "$(ls "$(dirname "$pom")"/*.pom | wc -l | tr -d " ")" = 1 ] || { echo "more than one version in $(dirname "$pom"): clean build/central-staging first" >&2; bad=1; }
  for f in "$pom" "$main" "$base-sources.jar" "$base-javadoc.jar" "$base.module"; do
    [ -f "$f" ] || { echo "missing: $f" >&2; bad=1; continue; }
    [ -f "$f.asc" ] || { echo "not signed: $f" >&2; bad=1; }
    [ -f "$f.md5" ] && [ -f "$f.sha1" ] || { echo "missing checksums: $f" >&2; bad=1; }
  done
done < <(find "$stage" -name '*.pom')
[ "$bad" = 0 ] || exit 1
rm -f build/central-bundle.zip
(cd "$stage" && zip -qr ../central-bundle.zip .)
echo "ok: build/central-bundle.zip ($(find "$stage" -name "*.pom" | wc -l | tr -d " ") artifacts)"
