#!/usr/bin/env bash
# Publish the staged APKs as a GitHub release.
#
# The old release step was a GitHub Action, which is exactly what isn't available. This talks to
# the REST API instead, so it runs from any builder (or from a laptop) with a token that has
# Contents write. Usage: scripts/publish-github-release.sh v2.7.0
set -euo pipefail

TAG="${1:?Usage: publish-github-release.sh <tag>}"
REPO="${GH_REPO:-amirmahdavi2023/Panther-VPN}"
DIR="${RELEASE_DIR:-release}"
: "${GH_TOKEN:?GH_TOKEN is not set}"

api() { curl -fsS -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

[ -d "$DIR" ] || { echo "No $DIR directory to publish."; exit 1; }
count=$(ls -1 "$DIR" | wc -l)
[ "$count" -gt 0 ] || { echo "$DIR is empty."; exit 1; }

notes=$(cat <<'EOF'
## Which file do I download?

If you're not sure, grab **Universal**. It works on every phone, it's just a bit bigger.

| File | For |
|---|---|
| `Universal` | Any phone. Pick this one if unsure. |
| `ARM64` | Almost every phone from the last several years. Smallest. |
| `ARMv7` | Older 32-bit phones. |
| `x86_64` | Emulators and the rare x86 device. |

Android 8.0 or newer. Your phone will warn you about installing from outside the Play Store,
that's normal for a sideloaded APK.

`SHA256SUMS.txt` is in the assets if you want to check the download. Every APK here is
release-signed, and the bundled Aether core was pulled from its official release and SHA-256
verified during the build.
EOF
)

# Reuse the release if it already exists, so a re-run replaces assets instead of failing.
if existing=$(api "https://api.github.com/repos/$REPO/releases/tags/$TAG" 2>/dev/null); then
  release_id=$(printf '%s' "$existing" | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).id")
  echo "Reusing existing release $TAG (id $release_id)"
else
  payload=$(TAG="$TAG" NOTES="$notes" node -e '
    process.stdout.write(JSON.stringify({
      tag_name: process.env.TAG,
      name: "Panther " + process.env.TAG.replace(/^v/, ""),
      body: process.env.NOTES,
      draft: false,
      prerelease: false
    }));
  ')
  created=$(api -X POST -d "$payload" "https://api.github.com/repos/$REPO/releases")
  release_id=$(printf '%s' "$created" | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).id")
  echo "Created release $TAG (id $release_id)"
fi

# Drop any asset with the same name first; the upload endpoint rejects duplicates.
existing_assets=$(api "https://api.github.com/repos/$REPO/releases/$release_id/assets")
for file in "$DIR"/*; do
  name=$(basename "$file")
  asset_id=$(printf '%s' "$existing_assets" | NAME="$name" node -p "
    (JSON.parse(require('fs').readFileSync(0,'utf8')).find(a => a.name === process.env.NAME) || {}).id || ''
  ")
  if [ -n "$asset_id" ]; then
    api -X DELETE "https://api.github.com/repos/$REPO/releases/assets/$asset_id"
    echo "Replaced $name"
  fi
  curl -fsS -X POST \
    -H "Authorization: Bearer $GH_TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$file" \
    "https://uploads.github.com/repos/$REPO/releases/$release_id/assets?name=$name" > /dev/null
  echo "Uploaded $name"
done

echo "Done: https://github.com/$REPO/releases/tag/$TAG"
