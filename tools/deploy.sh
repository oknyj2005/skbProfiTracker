#!/usr/bin/env bash
# Copies the freshly built jar into the client's mods folder.
#
# The Fabric client keeps an open handle on the jar it booted from, so replacing the file
# while it runs makes the next lazily loaded class fail with "invalid LOC header" and kicks
# the player off the server. This script therefore waits for the client to exit first, then
# swaps the jar and verifies the archive.

set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="/d/pcl/.minecraft/versions/skb-main/mods"
WAIT_SECONDS="${WAIT_SECONDS:-7200}"   # how long to wait for the client to close
POLL_SECONDS=5

client_running() {
    tasklist 2>/dev/null | grep -qiE '^(java|javaw)\.exe'
}

version=$(sed -n 's/^mod\.version=//p' "$REPO/gradle.properties" | tr -d '\r')
jar="$REPO/build/versions/skbProfiTracker-$version+26.2-fabric.jar"
[ -f "$jar" ] || jar=$(ls -t "$REPO"/build/versions/skbProfiTracker-*.jar 2>/dev/null | head -n 1)
if [ -z "$jar" ]; then
    echo "no build output in $REPO/build/versions" >&2
    exit 1
fi

name=$(basename "$jar")

# Refuse to install a build whose decision table fails: a successful compile only proves the
# Kotlin is valid, not that the scanner still classifies sack moves correctly.
bash "$REPO/tools/sim/run.sh" >/dev/null || {
    echo "sim checks failed, keeping the previous jar in place" >&2
    exit 4
}
waited=0
while client_running; do
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
        echo "client still running after ${WAIT_SECONDS}s, giving up" >&2
        exit 2
    fi
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
done

cp "$jar" "$DEST/$name" || exit 3

# Drop older builds so the loader cannot pick two copies of the same mod.
for old in "$DEST"/skbProfiTracker-*.jar; do
    [ "$old" = "$DEST/$name" ] || rm -f "$old"
done

python - "$DEST/$name" <<'PY'
import hashlib, sys, zipfile

path = sys.argv[1]
archive = zipfile.ZipFile(path)
digest = hashlib.sha256(open(path, "rb").read()).hexdigest()[:16]
print(f"deployed {path}")
print(f"entries {len(archive.namelist())} testzip {archive.testzip()} sha256 {digest}")
PY
