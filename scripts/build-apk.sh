#!/usr/bin/env bash
# Build FinReader inside the Android container. No Android SDK needed on the host.
#
#   scripts/build-apk.sh                  # assembleDebug (default)
#   scripts/build-apk.sh test             # unit tests
#   scripts/build-apk.sh assembleRelease   # signed release, see below
#   scripts/build-apk.sh bash             # a shell in the container
#
# For a signed release, export these before calling:
#   FINREADER_KEYSTORE (path on the host), FINREADER_KEYSTORE_PASSWORD,
#   FINREADER_KEY_ALIAS, FINREADER_KEY_PASSWORD
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="finreader-android:latest"
VOLUME="finreader-gradle"

docker build \
    --build-arg "USER_UID=$(id -u)" \
    --build-arg "USER_GID=$(id -g)" \
    -t "$IMAGE" \
    "$ROOT/.devcontainer"

docker volume inspect "$VOLUME" >/dev/null 2>&1 || docker volume create "$VOLUME" >/dev/null

args=("$@")
[ ${#args[@]} -eq 0 ] && args=(assembleDebug)

run_args=(
    --rm -i
    -v "$ROOT:/workspace"
    -v "$VOLUME:/home/dev/.gradle"
    -w /workspace
)
[ -t 0 ] && run_args+=(-t)

# Pass the signing config through, mounting the keystore read-only if present.
if [ -n "${FINREADER_KEYSTORE:-}" ] && [ -f "${FINREADER_KEYSTORE}" ]; then
    run_args+=(-v "${FINREADER_KEYSTORE}:/keystore.jks:ro" -e "FINREADER_KEYSTORE=/keystore.jks")
    for var in FINREADER_KEYSTORE_PASSWORD FINREADER_KEY_ALIAS FINREADER_KEY_PASSWORD; do
        run_args+=(-e "$var=${!var:-}")
    done
fi

if [ "${args[0]}" = "bash" ]; then
    exec docker run "${run_args[@]}" "$IMAGE" bash
fi

docker run "${run_args[@]}" "$IMAGE" ./gradlew --no-daemon "${args[@]}"

echo
echo "APKs:"
find "$ROOT/app/build/outputs/apk" -name '*.apk' 2>/dev/null || echo "  (none)"
