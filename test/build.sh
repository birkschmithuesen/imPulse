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

# Wurzel der Processing-Installation. Ueber IMPULSE_PROCESSING_JAVA
# ueberschreibbar, damit die Pruefung auch auf einem Rechner ohne
# Processing-Installation im Standardpfad laeuft - etwa auf einem Linux-Server
# ohne root, wo das Linux-Tarball einfach ins Home entpackt wird:
#   tar xf processing-3.5.4-linux64.tgz -C ~/opt
#   export IMPULSE_PROCESSING_JAVA=~/opt/processing-3.5.4
# ACHTUNG: der Linux-Pfad ist nicht erprobt, nur der macOS-Standard darunter.
PJ="${IMPULSE_PROCESSING_JAVA:-/Applications/Processing.app/Contents/Java}"
if [ ! -d "$PJ" ]; then
  echo "Processing nicht gefunden unter $PJ" >&2
  echo "Mit IMPULSE_PROCESSING_JAVA auf eine Processing-3-Installation zeigen," >&2
  echo "siehe Kommentar in dieser Datei." >&2
  exit 1
fi
if [ ! -f "$PJ/modes/java/mode/JavaMode.jar" ]; then
  echo "JavaMode.jar nicht gefunden unter $PJ/modes/java/mode/" >&2
  echo "Ist das eine Processing-3-Installation?" >&2
  exit 1
fi

# Klassenpfad: das Wurzelverzeichnis selbst, dazu alle Jars daneben. Die erste
# Zeile ist das macOS-Bundle-Layout (Jars direkt in Contents/Java), die zweite
# das Layout der Linux- und Windows-Pakete (Jars in lib/). Beide Globs sind
# optional, es greift jeweils nur eines.
CP="$PJ"
for j in "$PJ"/*.jar "$PJ"/lib/*.jar "$PJ"/core/library/*.jar "$PJ"/modes/java/mode/*.jar; do
  [ -f "$j" ] && CP="$CP:$j"
done

# Der abschliessende Schraegstrich wird selbst gesetzt statt aus TMPDIR
# angenommen: auf macOS endet $TMPDIR mit /, unter Linux nicht. Ohne das
# Abschneiden ergab "${TMPDIR}impulse-build-check.$$" dort ein Verzeichnis
# NEBEN TMPDIR (/home/x/.hermes/tmpimpulse-build-check.123) statt darin.
TMPROOT="${TMPDIR:-/tmp}"
WORK="${TMPROOT%/}/impulse-build-check.$$"
# Commander bricht ab, wenn --output schon existiert ("The output folder
# already exists"), also nur WORK anlegen und OUT von Processing erzeugen
# lassen.
OUT="$WORK/out"
rm -rf "$WORK"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# Uebersetzt wird eine KOPIE des Sketches, nicht das Original - und zwar mit
# einem code/-Ordner, in den die Bibliotheks-Jars aus libraries/ gelegt werden.
#
# Warum dieser Umweg: Processing loest Bibliotheken normalerweise aus dem
# Sketchbook (sketchbook.path.three in den Einstellungen, hier
# ~/Documents/Processing). Dieser Pfad ist auf diesem Rechner unter der
# macOS-Datenschutzsperre fuer den Documents-Ordner nicht lesbar - jeder
# Aufruf endete mit "No library found for netP5 / oscP5 /
# codeanticode.syphon", auch mit abgeschalteter Sandbox. Ein code/-Unterordner
# im Sketch wird von Processing dagegen immer in den Klassenpfad genommen und
# braucht kein Sketchbook, kein root und keine Freigabe.
#
# Der Ordnername der Kopie MUSS wie die Haupt-.pde heissen, das ist
# Processing-Konvention.
#
# Der Name kommt aus der .pde-DATEI, nicht aus dem Quellordner: ein
# git-worktree heisst zwangslaeufig anders als der Hauptcheckout (etwa
# imPulse-split-feature neben imPulse), und mit dem Ordnernamen brach die
# Pruefung dort mit "Not a valid sketch folder" ab - ausgerechnet in dem
# Arbeitsverzeichnis, in dem entwickelt wird. Gibt es mehrere .pde, gewinnt
# die, die schon so heisst wie der Ordner.
SKETCH_NAME="$(basename "$SKETCH")"
if [ ! -f "$SKETCH/$SKETCH_NAME.pde" ]; then
  MAIN_PDE=""
  for p in "$SKETCH"/*.pde; do
    [ -f "$p" ] || continue
    if [ -n "$MAIN_PDE" ]; then
      echo "Mehrere .pde in $SKETCH, aber keine heisst $SKETCH_NAME.pde." >&2
      echo "Welche die Haupt-.pde ist, laesst sich so nicht entscheiden." >&2
      exit 1
    fi
    MAIN_PDE="$p"
  done
  if [ -n "$MAIN_PDE" ]; then
    SKETCH_NAME="$(basename "$MAIN_PDE" .pde)"
  fi
fi
COPY="$WORK/$SKETCH_NAME"
mkdir -p "$COPY/code"
cp "$SKETCH"/*.pde "$COPY/" 2>/dev/null || {
  echo "Keine .pde-Datei in $SKETCH gefunden" >&2
  exit 1
}
cp "$SKETCH"/*.java "$COPY/" 2>/dev/null || true

# oscP5.jar bringt netP5 mit. Syphon braucht beide Jars; die .jnilib daneben
# ist nur zur Laufzeit noetig, zum Uebersetzen genuegen die Jars.
JARS_FOUND=0
for j in "$REPO"/libraries/*/library/*.jar; do
  if [ -f "$j" ]; then
    cp "$j" "$COPY/code/"
    JARS_FOUND=$((JARS_FOUND + 1))
  fi
done
if [ "$JARS_FOUND" -eq 0 ]; then
  echo "Keine Bibliotheks-Jars unter $REPO/libraries/*/library/*.jar gefunden." >&2
  echo "Ohne sie kann imPulse.pde nicht uebersetzt werden (oscP5, netP5, Syphon)." >&2
  exit 1
fi

# -Djava.awt.headless=true verhindert, dass der Aufruf den Fokus klaut
# (processing/processing#3996). -Djna.nosys=true wie im Original.
# Der cd ist noetig, weil Commander relativ zu diesem Verzeichnis sucht.
cd "$PJ"
java -Djna.nosys=true -Djava.awt.headless=true -cp "$CP" \
  processing.mode.java.Commander \
  --sketch="$COPY" --output="$OUT" --build
