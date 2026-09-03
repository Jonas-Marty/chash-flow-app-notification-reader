#!/usr/bin/env bash
# Build FinReader inside the Android container. No Android SDK needed on the host.
#
#   scripts/build-apk.sh                  # assembleDebug (default)
#   scripts/build-apk.sh test             # unit tests
#   scripts/build-apk.sh assembleRelease   # signed release, see below
#   scripts/build-apk.sh bash             # a shell in the container
#   scripts/build-apk.sh bash -lc '...'    # one command in the container
#
# For a signed release, export these before calling:
#   FINREADER_KEYSTORE (path on the host), FINREADER_KEYSTORE_PASSWORD,
#   FINREADER_KEY_ALIAS, FINREADER_KEY_PASSWORD
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="finreader-android:latest"
VOLUME="finreader-gradle"
ANDROID_VOLUME="finreader-android-home"   # keeps the debug signing key stable

docker build \
    --build-arg "USER_UID=$(id -u)" \
    --build-arg "USER_GID=$(id -g)" \
    -t "$IMAGE" \
    "$ROOT/.devcontainer"

for vol in "$VOLUME" "$ANDROID_VOLUME"; do
    docker volume inspect "$vol" >/dev/null 2>&1 || docker volume create "$vol" >/dev/null
done

args=("$@")
[ ${#args[@]} -eq 0 ] && args=(assembleDebug)

run_args=(
    --rm -i
    -v "$ROOT:/workspace"
    -v "$VOLUME:/home/dev/.gradle"
    -v "$ANDROID_VOLUME:/home/dev/.android"
    -w /workspace
)
[ -t 0 ] && run_args+=(-t)

# A local keystore.properties wins: mount its keystore at the very same absolute
# path inside the container so the path in the properties file just works.
if [ -f "$ROOT/keystore.properties" ]; then
    store="$(sed -n 's/^storeFile=//p' "$ROOT/keystore.properties" | head -1)"
    if [ -n "$store" ] && [ -f "$store" ]; then
        run_args+=(-v "$(dirname "$store"):$(dirname "$store"):ro")
    else
        echo "warning: keystore.properties points at a missing storeFile: $store" >&2
    fi
fi

# Otherwise CI-style env vars, with the keystore mounted read-only.
if [ ! -f "$ROOT/keystore.properties" ] && [ -n "${FINREADER_KEYSTORE:-}" ] && [ -f "${FINREADER_KEYSTORE}" ]; then
    run_args+=(-v "${FINREADER_KEYSTORE}:/keystore.jks:ro" -e "FINREADER_KEYSTORE=/keystore.jks")
    for var in FINREADER_KEYSTORE_PASSWORD FINREADER_KEY_ALIAS FINREADER_KEY_PASSWORD; do
        run_args+=(-e "$var=${!var:-}")
    done
fi

if [ "${args[0]}" = "bash" ]; then
    exec docker run "${run_args[@]}" "$IMAGE" bash "${args[@]:1}"
fi

docker run "${run_args[@]}" "$IMAGE" ./gradlew --no-daemon "${args[@]}"

echo
echo "APKs:"
find "$ROOT/app/build/outputs/apk" -name '*.apk' 2>/dev/null || echo "  (none)"
