#!/usr/bin/env bash
# Uebersetzt die netz- und processingunabhaengigen Klassen samt Tests und startet sie.
set -euo pipefail
cd "$(dirname "$0")/.."

# core.jar von Processing. Ueber die Umgebungsvariable IMPULSE_CORE_JAR
# ueberschreibbar, damit die Suite auch ohne Processing-Installation laeuft -
# etwa auf einem Linux-Server ohne root. Nur LedColor und LedStripeNetworks
# brauchen ueberhaupt processing.core; die Datei genuegt, ein vollstaendiges
# Processing ist dafuer nicht noetig:
#   curl -o ~/lib/core.jar \
#     https://repo1.maven.org/maven2/org/processing/core/3.3.7/core-3.3.7.jar
#   export IMPULSE_CORE_JAR=~/lib/core.jar
CORE="${IMPULSE_CORE_JAR:-/Applications/Processing.app/Contents/Java/core.jar}"
if [ ! -f "$CORE" ]; then
  echo "core.jar nicht gefunden unter $CORE" >&2
  echo "Auf einem Rechner ohne Processing: core.jar besorgen und" >&2
  echo "IMPULSE_CORE_JAR darauf zeigen lassen, siehe Kommentar oben." >&2
  exit 1
fi

rm -rf build
mkdir -p build

SOURCES="LedColor.java ArtNetOutput.java"
[ -f NodeCrossingStore.java ] && SOURCES="$SOURCES NodeCrossingStore.java"
[ -f NodeSelection.java ] && SOURCES="$SOURCES NodeSelection.java"
[ -f LedStripeNetworks.java ] && SOURCES="$SOURCES LedStripeNetworks.java"
[ -f TestPatterns.java ] && SOURCES="$SOURCES TestPatterns.java"
[ -f LedAnchorStore.java ] && SOURCES="$SOURCES LedAnchorStore.java"
[ -f LedPositionMap.java ] && SOURCES="$SOURCES LedPositionMap.java"
[ -f LedPositionCalibration.java ] && SOURCES="$SOURCES LedPositionCalibration.java"
[ -f ImpulseOscThrottle.java ] && SOURCES="$SOURCES ImpulseOscThrottle.java"
[ -f PresetStore.java ] && SOURCES="$SOURCES PresetStore.java"
[ -f PresetScheduler.java ] && SOURCES="$SOURCES PresetScheduler.java"

javac -nowarn -cp "$CORE" -d build $SOURCES test/*.java

# Ohne Argumente alle Suiten starten - ein leerer Aufruf soll nicht
# stillschweigend als "alles bestanden" durchgehen, ohne ueberhaupt etwas
# geprueft zu haben.
if [ "$#" -eq 0 ]; then
  # Die vier Kernsuiten sind immer da. Alle weiteren werden nur aufgenommen,
  # wenn ihre Quelldatei vorhanden ist, und ein Fehlen wird gemeldet statt
  # stillschweigend uebergangen: NodeSelectionTest etwa liegt zeitweise nur
  # als unversionierte Arbeitskopie vor und fehlt auf einem frischen Klon.
  set -- ArtNetOutputTest ArtNetDecoderTest NodeCrossingStoreTest ApplyCrossingsTest
  for optional in NodeSelectionTest LedAnchorStoreTest LedPositionMapTest \
                  LedPositionCalibrationTest ImpulseOscThrottleTest \
                  PresetStoreTest PresetSchedulerTest; do
    if [ -f "test/$optional.java" ]; then
      set -- "$@" "$optional"
    else
      echo "Hinweis: test/$optional.java fehlt, Suite uebersprungen" >&2
    fi
  done
fi

status=0
for t in "$@"; do
  echo "== $t"
  java -cp "build:$CORE" "$t" || status=1
done
exit $status
