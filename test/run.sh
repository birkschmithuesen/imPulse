#!/usr/bin/env bash
# Uebersetzt die netz- und processingunabhaengigen Klassen samt Tests und startet sie.
set -euo pipefail
cd "$(dirname "$0")/.."

CORE=/Applications/Processing.app/Contents/Java/core.jar
if [ ! -f "$CORE" ]; then
  echo "core.jar nicht gefunden unter $CORE" >&2
  exit 1
fi

rm -rf build
mkdir -p build

SOURCES="LedColor.java ArtNetOutput.java"
[ -f NodeCrossingStore.java ] && SOURCES="$SOURCES NodeCrossingStore.java"
[ -f LedStripeNetworks.java ] && SOURCES="$SOURCES LedStripeNetworks.java"
[ -f TestPatterns.java ] && SOURCES="$SOURCES TestPatterns.java"
[ -f LedAnchorStore.java ] && SOURCES="$SOURCES LedAnchorStore.java"
[ -f LedPositionMap.java ] && SOURCES="$SOURCES LedPositionMap.java"
[ -f LedPositionCalibration.java ] && SOURCES="$SOURCES LedPositionCalibration.java"
[ -f ImpulseOscThrottle.java ] && SOURCES="$SOURCES ImpulseOscThrottle.java"

javac -nowarn -cp "$CORE" -d build $SOURCES test/*.java

# Ohne Argumente alle vier Suiten starten - ein leerer Aufruf soll nicht
# stillschweigend als "alles bestanden" durchgehen, ohne ueberhaupt etwas
# geprueft zu haben.
if [ "$#" -eq 0 ]; then
  set -- ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest LedAnchorStoreTest
fi

status=0
for t in "$@"; do
  echo "== $t"
  java -cp "build:$CORE" "$t" || status=1
done
exit $status
