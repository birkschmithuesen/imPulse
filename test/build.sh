#!/usr/bin/env bash
# Uebersetzt den kompletten Sketch - imPulse.pde eingeschlossen - ohne
# Processing-Fenster und ohne die Installation anzusprechen.
#
# Warum es dieses Skript gibt: das Kommandozeilenwerkzeug processing-java ist
# auf diesem Rechner nicht installiert. Es ist aber ohnehin nur ein Wrapper um
# processing.mode.java.Commander aus pde.jar. Genau diesen Wrapper legt
# Processings eigenes Werkzeug "Install processing-java" an
# (processing/app/tools/InstallCommander.class); hier steht er nachgebaut,
# damit die Pruefung nicht an einer Installation ausserhalb des Repos haengt.
#
# Der Sketch wird dabei nur GELESEN. Alles Erzeugte landet in einem Ordner
# unter TMPDIR, nicht im Arbeitsbaum - das Repo bleibt unberuehrt und eine
# parallel laufende Processing-IDE wird nicht gestoert.
#
# Aufruf:
#   test/build.sh              # diesen Sketch uebersetzen
#   test/build.sh <pfad>       # einen anderen Sketch-Ordner uebersetzen
#
# Exit 0 heisst: uebersetzt. Exit 1 heisst: Fehler, mit Datei und Zeile auf
# stderr/stdout. Der Sketch wird NICHT gestartet, es gehen keine Daten ans
# Netz und die LEDs bleiben unangetastet.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SKETCH="${1:-$REPO}"

PJ=/Applications/Processing.app/Contents/Java
if [ ! -d "$PJ" ]; then
  echo "Processing nicht gefunden unter $PJ" >&2
  exit 1
fi
if [ ! -f "$PJ/modes/java/mode/JavaMode.jar" ]; then
  echo "JavaMode.jar nicht gefunden - ist das eine Processing-3-Installation?" >&2
  exit 1
fi

# Klassenpfad wie im Original: das Java-Verzeichnis selbst, dazu alle Jars
# daneben, aus core/library und aus modes/java/mode.
CP="$PJ"
for j in "$PJ"/*.jar "$PJ"/core/library/*.jar "$PJ"/modes/java/mode/*.jar; do
  [ -f "$j" ] && CP="$CP:$j"
done

OUT="${TMPDIR:-/tmp}impulse-build-check.$$"
rm -rf "$OUT"
trap 'rm -rf "$OUT"' EXIT

# -Djava.awt.headless=true verhindert, dass der Aufruf den Fokus klaut
# (processing/processing#3996). -Djna.nosys=true wie im Original.
# Der cd ist noetig, weil Commander relativ zu diesem Verzeichnis sucht.
cd "$PJ"
java -Djna.nosys=true -Djava.awt.headless=true -cp "$CP" \
  processing.mode.java.Commander \
  --sketch="$SKETCH" --output="$OUT" --build
