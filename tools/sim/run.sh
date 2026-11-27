#!/usr/bin/env bash
# Compiles the pure scanner rules together with the fake-data scenarios and runs them.
# Needs nothing but the Kotlin compiler jar that Gradle already downloaded.
set -euo pipefail
cd "$(dirname "$0")/../.."

COMPILER=$(find "$HOME/.gradle/caches" -name "kotlin-compiler-embeddable-*.jar" -print -quit)
STDLIB=$(find "$HOME/.gradle/caches" -name "kotlin-stdlib-2*.jar" ! -name "*sources*" -print -quit)
REFLECT=$(find "$HOME/.gradle/caches" -name "kotlin-reflect-*.jar" ! -name "*sources*" -print -quit)
COROUTINES=$(find "$HOME/.gradle/caches" -name "kotlinx-coroutines-core-jvm-1.10*.jar" -print -quit)
SCRIPT=$(find "$HOME/.gradle/caches" -name "kotlin-script-runtime-2.3.0.jar" -print -quit)
DAEMON=$(find "$HOME/.gradle/caches" -name "kotlin-daemon-embeddable-2.3.0.jar" -print -quit)
ANNOTATIONS=$(find "$HOME/.gradle/caches" -name "annotations-2*.jar" ! -name "*sources*" -print -quit)
# Git Bash converts colon-separated POSIX classpaths to Windows classpaths for java.exe.
TOOLCP="$COMPILER:$STDLIB:$REFLECT:$COROUTINES:$SCRIPT:$DAEMON:$ANNOTATIONS"
OUT="build/sim"

rm -rf "$OUT"
mkdir -p "$OUT"

java -cp "$TOOLCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-reflect -no-stdlib -nowarn -classpath "$STDLIB" -d "$OUT" \
    src/main/kotlin/com/skbprofitracker/capture/ScanRules.kt \
    src/main/kotlin/com/skbprofitracker/capture/CaptureBuffer.kt \
    src/main/kotlin/com/skbprofitracker/capture/MiningConsumableUses.kt \
    src/main/kotlin/com/skbprofitracker/capture/EssenceDeduper.kt \
    src/main/kotlin/com/skbprofitracker/capture/ExcavationRewardBlock.kt \
    src/main/kotlin/com/skbprofitracker/capture/NonLootDeliveries.kt \
    src/main/kotlin/com/skbprofitracker/capture/SackDeposits.kt \
    src/main/kotlin/com/skbprofitracker/capture/SlayerCoinFlow.kt \
    src/main/kotlin/com/skbprofitracker/events/publishers/ChatLootParser.kt \
    src/main/kotlin/com/skbprofitracker/tracker/ActivitySignals.kt \
    src/main/kotlin/com/skbprofitracker/tracker/CrystalHollowsGate.kt \
    src/main/kotlin/com/skbprofitracker/tracker/GlaciteTravel.kt \
    src/main/kotlin/com/skbprofitracker/tracker/ResetConfirmation.kt \
    src/main/kotlin/com/skbprofitracker/utils/enums/Alignment.kt \
    src/main/kotlin/com/skbprofitracker/utils/gui/OverlayHitbox.kt \
    src/main/kotlin/com/skbprofitracker/utils/gui/RowViewport.kt \
    tools/sim/Sim.kt

java -cp "$(pwd)/$OUT:$STDLIB" SimKt
