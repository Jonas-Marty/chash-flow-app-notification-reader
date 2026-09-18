#!/usr/bin/env bash
# Builds a signed release APK and publishes it where the phone can install it.
#
#   scripts/publish-apk.sh             # build, publish, notify
#   scripts/publish-apk.sh --quiet     # build and publish, send no notification
#
# The version is read back out of the finished APK rather than out of
# build.gradle.kts. Only the APK knows what actually got packaged, and a stale
# one left over from an earlier run is exactly the mistake worth catching here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"

PUBLISH_DIR="${FINREADER_PUBLISH_DIR:-$HOME/finreader-apk}"
BASE_URL="${FINREADER_APK_URL:-https://apk.wi-wo.ch}"
GOTIFY_URL="${FINREADER_GOTIFY_URL:-https://gotify.wi-wo.ch}"
GOTIFY_TOKEN_FILE="${FINREADER_GOTIFY_TOKEN_FILE:-$HOME/.config/finreader/gotify-token}"
KEEP="${FINREADER_KEEP_BUILDS:-10}"

notify=true
[ "${1:-}" = "--quiet" ] && notify=false

# A release APK signed with the debug key installs, but can never upgrade one
# signed with the real key — so refuse to publish what the phone would later
# have to uninstall to get past.
if [ ! -f "$ROOT/keystore.properties" ] && [ -z "${FINREADER_KEYSTORE:-}" ]; then
    echo "error: no keystore.properties and no FINREADER_KEYSTORE." >&2
    echo "       That build would be debug-signed and could not upgrade the phone's install." >&2
    exit 1
fi

echo "==> building release APK"
"$ROOT/scripts/build-apk.sh" assembleRelease >&2
[ -f "$APK" ] || { echo "error: assembleRelease produced no APK at $APK" >&2; exit 1; }

# apkanalyzer is the only APK reader in the build image (no aapt), and it runs
# in the container, so the path has to be the one mounted there. The markers
# keep the two values apart from Docker's own build chatter.
echo "==> reading version out of the APK"
IN_CONTAINER="/workspace/app/build/outputs/apk/release/app-release.apk"
read_out="$("$ROOT/scripts/build-apk.sh" bash -lc "
    printf 'VN=%s\n' \"\$(apkanalyzer manifest version-name $IN_CONTAINER)\"
    printf 'VC=%s\n' \"\$(apkanalyzer manifest version-code $IN_CONTAINER)\"
" 2>/dev/null)"

VERSION_NAME="$(printf '%s\n' "$read_out" | sed -n 's/^VN=//p' | tail -1)"
VERSION_CODE="$(printf '%s\n' "$read_out" | sed -n 's/^VC=//p' | tail -1)"
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "error: could not read version out of the APK" >&2
    exit 1
fi

SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
# The app itself marks an uncommitted build with "+", but that character ends up
# in a URL the phone downloads, where proxies and download managers mangle it
# often enough not to risk it. Spell it out instead.
if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then
    SHA="$SHA-dirty"
fi

NAME="finreader-${VERSION_NAME}-${VERSION_CODE}-${SHA}.apk"
mkdir -p "$PUBLISH_DIR"
cp "$APK" "$PUBLISH_DIR/$NAME"
chmod 644 "$PUBLISH_DIR/$NAME"
ln -sfn "$NAME" "$PUBLISH_DIR/finreader-latest.apk"

DIGEST="$(sha256sum "$PUBLISH_DIR/$NAME" | cut -d' ' -f1)"
SIZE="$(du -h "$PUBLISH_DIR/$NAME" | cut -f1)"

# A build the phone already has is not an upgrade. versionCode comes from the
# commit count, so two builds off one commit collide — say so rather than
# letting the phone quietly refuse the install later.
duplicates="$(find "$PUBLISH_DIR" -maxdepth 1 -name "finreader-*-${VERSION_CODE}-*.apk" | wc -l)"
if [ "$duplicates" -gt 1 ]; then
    echo "note: versionCode $VERSION_CODE now has $duplicates builds — commit before rebuilding" >&2
fi

cat > "$PUBLISH_DIR/latest.json" <<EOF
{
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "commit": "$SHA",
  "file": "$NAME",
  "url": "$BASE_URL/$NAME",
  "sha256": "$DIGEST",
  "builtAt": "$(date -Is)"
}
EOF

# A hand-written index, so nginx never needs autoindex turned on.
{
    echo '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
    echo '<title>FinReader builds</title>'
    echo '<style>body{font:16px/1.5 system-ui;margin:2rem auto;max-width:34rem;padding:0 1rem}'
    echo 'a{display:block;padding:.6rem 0;border-bottom:1px solid #ddd}code{color:#666;font-size:.8em}</style>'
    echo "<h1>FinReader builds</h1>"
    echo "<p>Newest first. Tap to install.</p>"
    for f in $(ls -t "$PUBLISH_DIR"/finreader-*.apk 2>/dev/null); do
        b="$(basename "$f")"
        [ "$b" = "finreader-latest.apk" ] && continue
        echo "<a href=\"$b\">$b<br><code>$(date -r "$f" '+%Y-%m-%d %H:%M')</code></a>"
    done
} > "$PUBLISH_DIR/index.html"

# Keep the directory from growing without bound; the old ones are only useful
# as a way back to a build that worked.
ls -t "$PUBLISH_DIR"/finreader-*-*.apk 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r old; do
    [ "$(basename "$old")" = "finreader-latest.apk" ] && continue
    rm -f "$old"
done

echo "==> published $NAME ($SIZE)"
echo "    $BASE_URL/$NAME"
echo "    sha256 $DIGEST"

if [ "$notify" = true ]; then
    if [ -r "$GOTIFY_TOKEN_FILE" ]; then
        python3 - "$GOTIFY_URL" "$(cat "$GOTIFY_TOKEN_FILE")" "$VERSION_NAME" "$VERSION_CODE" "$BASE_URL/$NAME" "$SIZE" <<'PY' || echo "warning: notification failed" >&2
import json, sys, urllib.request
url, token, name, code, link, size = sys.argv[1:7]
body = json.dumps({
    "title": f"FinReader {name} ({code})",
    "message": f"{size} — tap to download, then tap the file to install.",
    "priority": 5,
    "extras": {"client::notification": {"click": {"url": link}}},
}).encode()
req = urllib.request.Request(
    f"{url}/message", data=body,
    headers={"Content-Type": "application/json", "X-Gotify-Key": token},
)
urllib.request.urlopen(req, timeout=15).read()
print("==> notified via Gotify")
PY
    else
        echo "warning: no Gotify token at $GOTIFY_TOKEN_FILE — skipping notification" >&2
    fi
fi
