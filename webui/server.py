#!/usr/bin/env python3
"""Web-Oberflaeche fuer die OSC-Parameter des imPulse-Sketches.

Laeuft auf derselben Maschine wie imPulse (Windows-Laptop), bindet auf
0.0.0.0:8080 und schickt Parameteraenderungen per OSC an localhost:8001.

Die Parameterliste wird NICHT hart verdrahtet, sondern bei jedem Seitenaufruf
aus ``data/remoteSettings.txt`` gelesen -- diese Datei schreibt imPulse bei
jedem Start aus den registrierten ``RemoteControlled*Parameter`` neu
(``OscMessageDistributor.dumpParameterInfo``). Ein neuer Parameter im Sketch
taucht damit ohne Codeaenderung hier im UI auf.

Start:  python server.py            (Optionen siehe --help / README.md)
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

try:
    from flask import Flask, jsonify, render_template, request
except ImportError as _flask_error:  # Tests laufen ohne Flask, siehe test_webui.py
    Flask = None
    _FLASK_IMPORT_ERROR = _flask_error
else:
    _FLASK_IMPORT_ERROR = None

# ---------------------------------------------------------------------------
# Voreinstellungen (alle per Umgebungsvariable und Kommandozeile ueberschreibbar)
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SETTINGS_PATH = os.path.join(REPO_ROOT, "data", "remoteSettings.txt")
DEFAULT_OSC_HOST = "127.0.0.1"
DEFAULT_OSC_PORT = 8001
DEFAULT_HTTP_HOST = "0.0.0.0"
DEFAULT_HTTP_PORT = 8080

# data/presets liegt neben data/remoteSettings.txt; ohne --presets wird der
# Ordner daraus abgeleitet.
DEFAULT_PRESETS_DIRNAME = "presets"

# /preset/save ist asynchron -- imPulse schreibt die Datei erst im naechsten
# draw()-Durchlauf (40 Hz, also ueblicherweise nach ~25 ms). So lange wartet
# der Speichern-Endpoint darauf, dass die Datei erscheint.
PRESET_SAVE_TIMEOUT_S = 1.0
PRESET_SAVE_POLL_S = 0.05

# ---------------------------------------------------------------------------
# Speed-Kopplung
#
# Referenzwerte laut Brief bzw. tune_speed.py (Hermes-Skill-Repo): bei
# impulseSpeed = 160 gehoeren die untenstehenden Werte zusammen. Aendert Birk
# die Geschwindigkeit, wird
#     faktor = neuer_speed / SPEED_REFERENCE
# gebildet und die gekoppelten Parameter proportional (energyDecay*) bzw.
# invers (nodeDeadTime, randomSpawn/interval) mitskaliert.
#
# Achtung: die Konstruktor-Defaults in LedNetworkTransportEffect.java stehen
# aktuell auf einem anderen Arbeitspunkt (speed 16, energyDecay 0.001,
# energyDecayfactor 0.02, nodeDeadTime 5.0, randomSpawn/interval 30.0). Die
# Kopplung bezieht sich bewusst auf den hier notierten Referenzpunkt -- wer
# stattdessen den Sketch-Arbeitspunkt koppeln will, aendert nur diesen Block.
# ---------------------------------------------------------------------------

SPEED_ADDRESS = "/net/impulse/speed"
SPEED_REFERENCE = 160.0

# address -> (Referenzwert bei SPEED_REFERENCE, "proportional" | "invers")
SPEED_COUPLED: Dict[str, Tuple[float, str]] = {
    "/net/impulse/energyDecay": (0.01, "proportional"),
    "/net/impulse/energyDecayfactor": (0.2, "proportional"),
    "/net/impulse/nodeDeadTime": (1.0, "invers"),
    "/net/randomSpawn/interval": (3.0, "invers"),
}

# Reihenfolge der Gruppen im UI; alles Unbekannte haengt alphabetisch hinten an.
# Sections-Neuordnung nach docs/webui-parameter-review-2026-07-30.md
# Abschnitt 2 (Birk-Freigabe 2026-07-30): Master, Impuls (Bewegung/Zeit),
# Impuls-Farbe (RGB + FadeOut als eigener Block -- siehe SPLIT_GROUP_PREFIXES
# unten, warum das ein group_key()-Sonderfall ist), Ambient-Spawns, Nodes,
# Trigger, Advanced.
GROUP_ORDER = [
    "master",
    "Master",
    "Master/opacity",
    "net/impulse",
    "net/impulse/color",
    "net/randomSpawn",
    "net",
    "nodes",
    "nodes/radius",
    "nodes/times",
    "nodes/colors",
]

# Adress-Praefixe, die aus ihrer regulaeren group_key()-Gruppe herausgezogen
# und in eine eigene Gruppe verschoben werden -- unabhaengig von der
# generischen Praefix-Regel. Bisher nur fuer die Trennung Impuls/Impuls-Farbe
# gebraucht: /net/impulse/color/* und /net/impulse/fadeOut/* landen sonst in
# derselben Gruppe wie speed/nodeDeadTime/energyDecay (reine Adress-Praefix-
# Gruppierung wuerde alles unter /net/impulse zusammenwerfen). Reihenfolge
# wichtig: laengster/spezifischster Praefix zuerst, damit z.B.
# "/net/impulse/color/gamma" nicht faelschlich unter einem kuerzeren
# "/net/impulse"-Eintrag landet, falls der je hinzukaeme.
SPLIT_GROUP_PREFIXES: List[Tuple[str, str]] = [
    ("/net/impulse/color/", "net/impulse/color"),
    ("/net/impulse/fadeOut/", "net/impulse/color"),
]

# Schrittweiten-Leiter fuer Float-Regler (grober Wert zuerst).
STEP_LADDER = [1.0, 0.5, 0.1, 0.05, 0.01, 0.005, 0.001, 0.0005, 0.0001]

COLOR_COMPONENTS = ("Hue", "Sat", "Bright")

# Trigger-Adressen: keine Regler, sondern Einmal-Aktionen (Direkt-Trigger
# eines Nodes/Stripes). writeToStream() in LedNetworkTransportEffect.java
# schreibt sie wie normale Int-Parameter in remoteSettings.txt, das UI
# zeigte sie bisher deshalb als Slider -- das verwirrt, weil ein "Wert
# halten" hier keinen Sinn ergibt (siehe docs/webui-parameter-review-2026-07-30.md
# Abschnitt 1). Bekommen ein eigenes Button-Widget statt eines Reglers.
TRIGGER_ADDRESSES = {"/net/activateNode", "/net/activateStripe"}

# Setup-/Sicherheits-Parameter: gestalterisch selten angefasst (Rechnerlast-
# Schutz, Trigger-Empfindlichkeits-Exponent), sollen nicht zwischen Farbe und
# Speed im Haupt-UI stehen. Werden unabhaengig von ihrem Adress-Praefix in
# eine eigene, eingeklappte "Advanced"-Gruppe am Ende sortiert. Siehe
# docs/webui-parameter-review-2026-07-30.md Abschnitt 1.
ADVANCED_ADDRESSES = {
    "/net/impulse/oscMaxCount",
    "/net/impulse/energyExponent",
}
ADVANCED_GROUP_KEY = "zzz_advanced"

# UI-Range-Verengung (docs/webui-parameter-review-2026-07-30.md Abschnitt 1,
# Birk-Freigabe 2026-07-30). Betrifft NUR die angezeigte/bedienbare Range im
# Web-UI (Slider + Zahlenfeld + Schrittweite) -- die tatsaechliche Klemmung
# beim Senden (Parameter.coerce()/normalize(), siehe apply_value()) bleibt
# auf der vollen, aus remoteSettings.txt gelesenen Range: die Java-Range ist
# weiterhin das Sicherheitsnetz, hier wird nur das Alltags-UI enger gefasst,
# damit niemand aus Versehen einen Wert waehlt, der bei 40Hz Framerate /
# Ambient-Charakter keinen sinnvollen Effekt mehr hat.
UI_RANGE_OVERRIDES: Dict[str, Tuple[float, float]] = {
    # 2026-07-30, Birk (Nachjustierung nach Live-Test): 400 war noch zu
    # schnell im Alltag -- 100 deckt den tatsaechlich genutzten Bereich ab.
    "/net/impulse/speed": (1, 100),
    # ab ~6 kollabiert jede Energie <1 durch wiederholtes Quadrieren
    # (energy *= energy, exponent-mal) praktisch auf 0
    "/net/impulse/energyExponent": (1, 5),
    # 2026-07-30, Birk: volle Java-Range (0.0001..0.5) macht den Regler am
    # unteren, tatsaechlich genutzten Ende zu grobstufig -- 0.01 deckt den
    # Live-Tuning-Bereich ab (per Speed-Kopplung ohnehin an /net/impulse/speed
    # gebunden, siehe SPEED_COUPLED oben). Nachjustiert von 0.05 auf 0.01.
    "/net/impulse/energyDecay": (0.0001, 0.01),
    "/net/impulse/energyDecayfactor": (0.0001, 0.1),
    # bei 30 (alle Stripes gleichzeitig) wird "Ambient" zum Flaechenblitz,
    # kein Ambient-Charakter mehr
    "/net/randomSpawn/count": (1, 8),
}


# ---------------------------------------------------------------------------
# OSC-Versand
# ---------------------------------------------------------------------------


def _osc_string(text: str) -> bytes:
    """OSC-String: UTF-8, mit mindestens einem Nullbyte auf 4 Byte aufgefuellt."""
    raw = text.encode("utf-8") + b"\x00"
    return raw + b"\x00" * ((-len(raw)) % 4)


def build_osc_message(address: str, value: Any) -> bytes:
    """Baut ein OSC-Paket mit genau einem Argument.

    int -> 'i', float -> 'f', str -> 's'. Der String-Zweig wird fuer die
    Preset-Kommandos gebraucht (``/preset/load <name>``), die als einziges
    Argument einen Namen tragen.
    """
    if isinstance(value, bool):
        raise TypeError("bool ist kein gueltiges OSC-Argument")
    if isinstance(value, int):
        return _osc_string(address) + _osc_string(",i") + struct.pack(">i", value)
    if isinstance(value, float):
        return _osc_string(address) + _osc_string(",f") + struct.pack(">f", value)
    if isinstance(value, str):
        return _osc_string(address) + _osc_string(",s") + _osc_string(value)
    raise TypeError("nicht unterstuetzter OSC-Argumenttyp: %r" % type(value))


class OscSender:
    """Schickt einzelne OSC-Nachrichten per UDP.

    Bevorzugt ``python-osc``; fuer Adressen ohne fuehrenden Schraegstrich
    (``Master/trace``, ``Master/0/opacity/0.Impulse`` -- so registriert in
    mixer.java, so erwartet von ``checkAddrPattern``) wird immer der eigene
    Encoder genutzt, weil python-osc solche Adressen ablehnt. Die Bytes sind
    fuer regulaere Adressen identisch, siehe test_webui.py.
    """

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self._lock = threading.Lock()
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._client = None
        try:
            from pythonosc.udp_client import SimpleUDPClient  # type: ignore

            self._client = SimpleUDPClient(host, port)
        except Exception as exc:  # pragma: no cover - haengt an der Installation
            print("[webui] python-osc nicht nutzbar (%s), nutze eingebauten "
                  "OSC-Encoder" % exc, file=sys.stderr)

    def send(self, address: str, value: Any) -> None:
        with self._lock:
            if self._client is not None and address.startswith("/"):
                self._client.send_message(address, value)
            else:
                self._socket.sendto(build_osc_message(address, value),
                                    (self.host, self.port))

    def close(self) -> None:
        with self._lock:
            self._socket.close()


# ---------------------------------------------------------------------------
# remoteSettings.txt lesen
# ---------------------------------------------------------------------------


@dataclass
class Parameter:
    """Eine Zeile aus remoteSettings.txt.

    Format (Tabs), siehe ``writeToStream`` in AbstractParameter.java:
        typ \t adresse \t beschreibung \t wert \t min \t max
    """

    type: str          # "float" oder "int"
    address: str
    description: str
    value: float
    minimum: float
    maximum: float

    @property
    def is_int(self) -> bool:
        return self.type == "int"

    def clamp(self, value: float) -> float:
        low, high = self.minimum, self.maximum
        if low > high:
            low, high = high, low
        return max(low, min(high, value))

    def coerce(self, value: float) -> float:
        """Klemmt auf den erlaubten Bereich und rundet Int-Parameter."""
        clamped = self.clamp(float(value))
        return int(round(clamped)) if self.is_int else clamped

    def normalize(self, value: float) -> Any:
        """Wandelt einen Wert in das, was imPulse per OSC erwartet.

        Float-Parameter: ``digestMessage`` mappt eingehende Floats selbst per
        ``PApplet.map(value, 0, 1, min, max)`` -- gesendet wird also der auf
        0..1 normalisierte Anteil, nicht der Rohwert.

        Int-Parameter: unveraendert als Ganzzahl. Die Float-Variante von
        ``RemoteControlledIntParameter.digestMessage`` ruft ``intValue()`` auf
        dem Float auf und verstuemmelt den Wert dadurch -- ein Float darf an
        einen Int-Parameter also nie geschickt werden.
        """
        clamped = self.clamp(float(value))
        if self.is_int:
            return int(round(clamped))
        span = self.maximum - self.minimum
        if span == 0:
            return 0.0
        return max(0.0, min(1.0, (clamped - self.minimum) / span))

    def step(self) -> float:
        if self.is_int:
            return 1.0
        span = abs(self.maximum - self.minimum)
        if span <= 0:
            return 0.001
        target = span / 100.0
        for candidate in STEP_LADDER:
            if candidate <= target:
                return candidate
        return STEP_LADDER[-1]

    def ui_range(self) -> Tuple[float, float]:
        """Angezeigte/bedienbare Range fuer den Regler.

        Normalerweise identisch mit minimum/maximum. Fuer Adressen in
        UI_RANGE_OVERRIDES wird eine engere Anzeige-Range zurueckgegeben --
        die tatsaechliche Klemmung beim Senden (clamp()/coerce()/normalize())
        bleibt unveraendert auf minimum/maximum, siehe Kommentar dort oben.
        """
        override = UI_RANGE_OVERRIDES.get(self.address)
        if override is None:
            return self.minimum, self.maximum
        low, high = override
        # Sicherheitsnetz: eine UI-Override-Range darf die eigentliche
        # Parameter-Range nie verlassen (falscher Eintrag in
        # UI_RANGE_OVERRIDES koennte sonst einen ungueltigen Wert anzeigen).
        low = max(low, min(self.minimum, self.maximum))
        high = min(high, max(self.minimum, self.maximum))
        if low >= high:
            return self.minimum, self.maximum
        return low, high

    def as_dict(self) -> Dict[str, Any]:
        ui_min, ui_max = self.ui_range()
        return {
            "kind": "param",
            "type": self.type,
            "address": self.address,
            "description": self.description,
            "min": ui_min,
            "max": ui_max,
            "step": self.step(),
            # 0/1-Ints bekommen einen Schalter statt eines Zweipunkt-Reglers
            "widget": "toggle" if (self.is_int and self.minimum == 0
                                   and self.maximum == 1) else "slider",
        }


def parse_settings(text: str) -> List[Parameter]:
    """Parst den Inhalt von remoteSettings.txt.

    Kaputte Zeilen werden uebersprungen (mit Hinweis auf stderr), damit eine
    einzelne unerwartete Zeile nicht das ganze UI lahmlegt. Doppelte Adressen
    gewinnen beim ersten Auftreten.
    """
    parameters: List[Parameter] = []
    seen: Dict[str, Parameter] = {}
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) < 6:
            print("[webui] remoteSettings.txt Zeile %d uebersprungen "
                  "(%d Felder statt 6): %r" % (lineno, len(fields), line),
                  file=sys.stderr)
            continue
        kind, address, description = fields[0].strip(), fields[1].strip(), fields[2]
        if kind not in ("float", "int"):
            print("[webui] remoteSettings.txt Zeile %d uebersprungen "
                  "(unbekannter Typ %r)" % (lineno, kind), file=sys.stderr)
            continue
        try:
            value, minimum, maximum = (float(fields[3]), float(fields[4]),
                                       float(fields[5]))
        except ValueError:
            print("[webui] remoteSettings.txt Zeile %d uebersprungen "
                  "(unlesbare Zahl): %r" % (lineno, line), file=sys.stderr)
            continue
        if not address:
            continue
        if address in seen:
            print("[webui] doppelte Adresse %s in Zeile %d ignoriert"
                  % (address, lineno), file=sys.stderr)
            continue
        param = Parameter(kind, address, description, value, minimum, maximum)
        seen[address] = param
        parameters.append(param)
    return parameters


# ---------------------------------------------------------------------------
# Presets
#
# Der Server laeuft auf derselben Maschine wie imPulse und liest data/presets/
# deshalb direkt vom Dateisystem -- es braucht keinen OSC-Rueckkanal, um die
# Liste zu erfahren. Geschrieben wird der Ordner ausschliesslich von imPulse:
# nur dort ist bekannt, welche Werte gerade tatsaechlich laufen.
# ---------------------------------------------------------------------------

PRESET_NAME_MAX_LENGTH = 64
PRESET_NAME_ALLOWED = set("abcdefghijklmnopqrstuvwxyz0123456789_-")

# Adressen, die beim Anwenden eines Presets uebergangen werden -- Spiegel von
# PresetStore.SILENTLY_IGNORED (Kommandos, kein Zustand) und
# PresetStore.EXCLUDED (Scheduler ist Transport, nicht Inhalt). Java ignoriert
# sie beim Laden; das UI darf danach also keine Regler bewegen, die der Sketch
# gar nicht gesetzt hat.
PRESET_IGNORED_ADDRESSES = set(TRIGGER_ADDRESSES) | {
    "/preset/scheduler/enabled",
    "/preset/scheduler/interval",
}


def valid_preset_name(name: Any) -> Optional[str]:
    """Wortgleicher Spiegel von PresetStore.isValidName() in Java.

    Rueckgabe: Fehlermeldung oder None. Java bleibt die Autoritaet -- dort geht
    es um Pfad-Traversal ("/preset/load ../../../etc/passwd"), hier nur darum,
    ungueltige Eingaben gar nicht erst rauszuschicken. Grossbuchstaben sind
    ausgeschlossen, weil zwei Presets, die sich nur in der Schreibweise
    unterscheiden, auf Windows dieselbe Datei waeren.
    """
    if not isinstance(name, str) or not name:
        return "Preset-Name ist leer"
    if len(name) > PRESET_NAME_MAX_LENGTH:
        return "Preset-Name laenger als %d Zeichen" % PRESET_NAME_MAX_LENGTH
    for char in name:
        if char not in PRESET_NAME_ALLOWED:
            return ("Preset-Name enthaelt unzulaessiges Zeichen %r -- erlaubt "
                    "sind a-z, 0-9, Unterstrich und Bindestrich" % char)
    return None


def list_presets(directory: str) -> Tuple[List[str], Optional[str]]:
    """Namen aller Preset-Dateien, alphabetisch, ohne die Endung .txt.

    Spiegel von PresetStore.list(): Dateien mit unzulaessigem Namen werden
    uebergangen -- sie liessen sich ohnehin nicht laden. Ein unlesbarer Ordner
    ist kein Abbruch, sondern eine leere Liste plus Meldung: das uebrige UI
    soll bedienbar bleiben.
    """
    try:
        entries = os.listdir(directory)
    except OSError as exc:
        return [], "Preset-Ordner nicht lesbar: %s" % exc
    names: List[str] = []
    for entry in entries:
        if not entry.endswith(".txt"):
            continue
        if not os.path.isfile(os.path.join(directory, entry)):
            continue
        bare = entry[:-len(".txt")]
        if valid_preset_name(bare) is None:
            names.append(bare)
    names.sort()
    return names, None


def default_presets_path(settings_path: str) -> str:
    """Preset-Ordner neben der Parameterdatei: <dir von settings>/presets."""
    return os.path.join(os.path.dirname(os.path.abspath(settings_path)),
                        DEFAULT_PRESETS_DIRNAME)


def wait_for_preset_file(path: str, previous_mtime: Optional[float],
                         timeout: float = PRESET_SAVE_TIMEOUT_S,
                         step: float = PRESET_SAVE_POLL_S) -> bool:
    """Wartet darauf, dass imPulse die Preset-Datei geschrieben hat.

    Verglichen wird die mtime, nicht nur die Existenz -- sonst waere das
    Ueberschreiben eines vorhandenen Presets nicht von "nichts passiert" zu
    unterscheiden. Laeuft der Sketch nicht, laeuft die Frist ab und der
    Aufrufer kann das ehrlich melden, statt Erfolg zu behaupten.
    """
    deadline = time.monotonic() + timeout
    while True:
        try:
            current = os.path.getmtime(path)
            if previous_mtime is None or current != previous_mtime:
                return True
        except OSError:
            pass
        if time.monotonic() >= deadline:
            return False
        time.sleep(step)


def group_key(address: str) -> str:
    """Gruppenschluessel aus dem Adress-Praefix.

    Alles unterhalb von ``/net/impulse`` landet in einer Gruppe, alles
    unterhalb von ``/net/randomSpawn`` in einer anderen. Rein numerische
    Segmente (``Master/0/opacity/...``) fallen raus, sonst bekaeme jeder
    Mixer-Kanal seine eigene Gruppe.

    Setup-/Sicherheits-Parameter (ADVANCED_ADDRESSES) werden unabhaengig von
    ihrem eigentlichen Praefix in eine eigene, ans Ende sortierte Gruppe
    verschoben -- siehe docs/webui-parameter-review-2026-07-30.md Abschnitt 1.

    SPLIT_GROUP_PREFIXES zieht bestimmte Unter-Praefixe (aktuell nur
    /net/impulse/color/* und /net/impulse/fadeOut/*) aus ihrer generischen
    Praefix-Gruppe in eine eigene Section -- Abschnitt 2 desselben Docs:
    Impuls-Bewegung/-Zeit soll optisch von Impuls-Farbe getrennt sein.
    """
    if address in ADVANCED_ADDRESSES:
        return ADVANCED_GROUP_KEY
    for prefix, key in SPLIT_GROUP_PREFIXES:
        if address.startswith(prefix):
            return key
    segments = [s for s in address.split("/") if s]
    if len(segments) <= 1:
        return segments[0] if segments else "sonstige"
    parent = [s for s in segments[:-1] if not s.isdigit()] or [segments[0]]
    return "/".join(parent[:2])


def group_sort_key(key: str) -> Tuple[int, str]:
    try:
        return (GROUP_ORDER.index(key), "")
    except ValueError:
        return (len(GROUP_ORDER), key.lower())


# Menschenlesbare Titel fuer Gruppen, die keinen sprechenden Adress-Praefix
# als Titel hergeben (z.B. "net/impulse/color" statt "/net/impulse/color").
GROUP_TITLE_OVERRIDES = {
    "net/impulse/color": "Impuls-Farbe",
}


def group_title(key: str) -> str:
    if key == ADVANCED_GROUP_KEY:
        return "Advanced"
    if key in GROUP_TITLE_OVERRIDES:
        return GROUP_TITLE_OVERRIDES[key]
    return key if key.startswith("Master") else "/" + key


def build_groups(parameters: List[Parameter]) -> List[Dict[str, Any]]:
    """Sortiert die Parameter in Gruppen und fasst HSB-Tripel zu Farbwaehlern.

    Farbparameter (``RemoteControlledColorParameter``) tauchen in
    remoteSettings.txt nicht als eigener Typ auf, sondern als drei
    Float-Zeilen ``<basis>/Hue``, ``<basis>/Sat`` und ``<basis>/Bright``.
    Liegen alle drei vor, wird daraus ein Farbwaehler.
    """
    by_group: Dict[str, List[Parameter]] = {}
    for param in parameters:
        by_group.setdefault(group_key(param.address), []).append(param)

    groups: List[Dict[str, Any]] = []
    for key in sorted(by_group, key=group_sort_key):
        members = sorted(by_group[key], key=lambda p: p.address)
        by_address = {p.address: p for p in members}

        color_bases: List[str] = []
        for param in members:
            base, _, leaf = param.address.rpartition("/")
            if leaf != COLOR_COMPONENTS[0] or not base:
                continue
            if all(("%s/%s" % (base, c)) in by_address for c in COLOR_COMPONENTS):
                color_bases.append(base)

        consumed = {"%s/%s" % (base, c)
                    for base in color_bases for c in COLOR_COMPONENTS}

        controls: List[Dict[str, Any]] = []
        for base in color_bases:
            controls.append({
                "kind": "color",
                "base": base,
                "label": base.split("/")[-1] or base,
                "components": {
                    "hue": by_address["%s/Hue" % base].as_dict(),
                    "sat": by_address["%s/Sat" % base].as_dict(),
                    "bright": by_address["%s/Bright" % base].as_dict(),
                },
            })
        for param in members:
            if param.address in consumed:
                continue
            if param.address in TRIGGER_ADDRESSES:
                controls.append({
                    "kind": "trigger",
                    "type": param.type,
                    "address": param.address,
                    "description": param.description,
                    "min": param.minimum,
                    "max": param.maximum,
                })
            else:
                controls.append(param.as_dict())

        groups.append({
            "key": key,
            "title": group_title(key),
            "controls": controls,
        })
    return groups


# ---------------------------------------------------------------------------
# Zustand
# ---------------------------------------------------------------------------


@dataclass
class Applied:
    address: str
    value: float
    sent: Any


@dataclass
class ParameterStore:
    """Haelt die geparste Parameterliste und die zuletzt gesetzten Werte.

    Die Werte stammen beim ersten Laden aus remoteSettings.txt (die Datei
    enthaelt nach jedem imPulse-Start die aktiven Registrierungswerte).
    Danach fuehrt der Server sie im Speicher weiter -- die Datei wird von
    imPulse ja nur beim Start geschrieben, waere also sofort veraltet.
    Aendert sich die Datei (imPulse wurde neu gestartet), wird komplett neu
    eingelesen.
    """

    path: str
    parameters: List[Parameter] = field(default_factory=list)
    values: Dict[str, float] = field(default_factory=dict)
    by_address: Dict[str, Parameter] = field(default_factory=dict)
    mtime: Optional[float] = None
    error: Optional[str] = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def _read(self) -> None:
        try:
            with open(self.path, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            self.mtime = os.path.getmtime(self.path)
            self.error = None
        except OSError as exc:
            self.parameters = []
            self.by_address = {}
            self.values = {}
            self.mtime = None
            self.error = "remoteSettings.txt nicht lesbar: %s" % exc
            print("[webui] %s" % self.error, file=sys.stderr)
            return
        self.parameters = parse_settings(text)
        self.by_address = {p.address: p for p in self.parameters}
        self.values = {p.address: p.coerce(p.value) for p in self.parameters}

    def refresh(self, force: bool = False) -> None:
        with self._lock:
            if force or not self.parameters:
                self._read()
                return
            try:
                current = os.path.getmtime(self.path)
            except OSError:
                self._read()
                return
            if current != self.mtime:
                print("[webui] remoteSettings.txt hat sich geaendert, lese neu",
                      file=sys.stderr)
                self._read()

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            groups = build_groups(self.parameters)
            return {
                "groups": groups,
                "values": dict(self.values),
                "settings": {
                    "path": self.path,
                    "mtime": self.mtime,
                    "count": len(self.parameters),
                    "error": self.error,
                },
            }

    def get(self, address: str) -> Optional[Parameter]:
        with self._lock:
            return self.by_address.get(address)

    def store(self, address: str, value: float) -> None:
        with self._lock:
            self.values[address] = value


def apply_preset_entries(store: ParameterStore,
                         entries: List[Parameter]) -> Dict[str, Any]:
    """Uebernimmt die Werte einer geparsten Preset-Datei in den Store.

    Preset-Dateien haben dasselbe Format wie remoteSettings.txt, ``entries``
    kommt also aus ``parse_settings()``.

    Geklemmt wird auf die Range aus remoteSettings.txt, nicht auf die aus der
    Preset-Datei -- dieselbe Regel wie in ``PresetStore.applyPreset()`` auf der
    Java-Seite, damit aeltere Presets nach einer Bereichsaenderung korrekt
    bleiben.

    Die Werte gehen bewusst NICHT als OSC raus: das Anwenden macht imPulse
    selbst nach ``/preset/load``, hier wird nur die Anzeige nachgezogen.

    Zwei Sonderfaelle werden gemeldet statt verschluckt:
    ``unknown`` sind Adressen, die es in remoteSettings.txt nicht gibt (Preset
    und Dump aus verschiedenen Codestaenden), ``outOfRange`` sind Werte
    ausserhalb der verengten UI-Range (UI_RANGE_OVERRIDES) -- dort klemmt der
    Regler sichtbar und darf nicht stillschweigend etwas anderes behaupten als
    der Sketch faehrt.
    """
    values: Dict[str, Any] = {}
    unknown: List[str] = []
    out_of_range: List[Dict[str, Any]] = []
    for entry in entries:
        if entry.address in PRESET_IGNORED_ADDRESSES:
            continue
        param = store.get(entry.address)
        if param is None:
            unknown.append(entry.address)
            continue
        value = param.coerce(entry.value)
        ui_min, ui_max = param.ui_range()
        low, high = min(ui_min, ui_max), max(ui_min, ui_max)
        shown = max(low, min(high, value))
        if shown != value:
            out_of_range.append({"address": entry.address, "value": value,
                                 "shown": shown})
        values[entry.address] = value
        store.store(entry.address, value)
    return {"values": values, "unknown": unknown, "outOfRange": out_of_range}


def coupled_values(store: ParameterStore, speed: float) -> Tuple[List[Tuple[Parameter, float]],
                                                                 List[Dict[str, str]]]:
    """Berechnet die an die Geschwindigkeit gekoppelten Werte.

    Rueckgabe: Liste (Parameter, Wert) plus Liste uebersprungener Adressen --
    uebersprungen wird, was in remoteSettings.txt gar nicht vorkommt (z.B.
    ``/net/randomSpawn/*`` in einem Dump vor Einfuehrung der Random-Spawns).
    """
    factor = speed / SPEED_REFERENCE
    result: List[Tuple[Parameter, float]] = []
    skipped: List[Dict[str, str]] = []
    if factor <= 0:
        return result, [{"address": a, "reason": "Faktor 0"} for a in SPEED_COUPLED]
    for address, (reference, mode) in SPEED_COUPLED.items():
        param = store.get(address)
        if param is None:
            skipped.append({"address": address,
                            "reason": "nicht in remoteSettings.txt"})
            continue
        raw = reference * factor if mode == "proportional" else reference / factor
        result.append((param, param.coerce(raw)))
    return result, skipped


# ---------------------------------------------------------------------------
# Flask-App
# ---------------------------------------------------------------------------


def create_app(settings_path: str, osc_host: str, osc_port: int,
               presets_path: str):
    if Flask is None:
        raise SystemExit("Flask fehlt (%s) -- bitte 'pip install -r requirements.txt'"
                         % _FLASK_IMPORT_ERROR)
    app = Flask(__name__)
    store = ParameterStore(path=settings_path)
    store.refresh(force=True)
    sender = OscSender(osc_host, osc_port)

    app.config["IMPULSE_STORE"] = store
    app.config["IMPULSE_SENDER"] = sender

    def apply_value(param: Parameter, value: float) -> Applied:
        coerced = param.coerce(value)
        payload = param.normalize(coerced)
        sender.send(param.address, payload)
        store.store(param.address, coerced)
        return Applied(param.address, coerced, payload)

    def preset_file(name: str) -> str:
        return os.path.join(presets_path, name + ".txt")

    def preset_list_payload() -> Dict[str, Any]:
        names, error = list_presets(presets_path)
        return {"ok": True, "presets": names, "dir": presets_path,
                "error": error}

    @app.route("/")
    def index() -> str:
        store.refresh()
        snapshot = store.snapshot()
        return render_template(
            "index.html",
            bootstrap=json.dumps({
                **snapshot,
                "osc": {"host": osc_host, "port": osc_port},
                "coupling": {
                    "speedAddress": SPEED_ADDRESS,
                    "reference": SPEED_REFERENCE,
                    "targets": [
                        {"address": a, "value": r, "mode": m}
                        for a, (r, m) in SPEED_COUPLED.items()
                    ],
                },
                "presets": preset_list_payload(),
            }),
        )

    @app.route("/api/parameters")
    def api_parameters():
        store.refresh(force=request.args.get("force") == "1")
        snapshot = store.snapshot()
        snapshot["osc"] = {"host": osc_host, "port": osc_port}
        return jsonify(snapshot)

    @app.route("/api/set", methods=["POST"])
    def api_set():
        body = request.get_json(silent=True) or {}
        updates = body.get("updates")
        if updates is None:
            updates = [{"address": body.get("address"), "value": body.get("value")}]
        if not isinstance(updates, list) or not updates:
            return jsonify({"ok": False, "error": "keine Aenderungen im Request"}), 400

        applied: List[Applied] = []
        skipped: List[Dict[str, str]] = []
        couple = bool(body.get("coupleSpeed"))

        for update in updates:
            if not isinstance(update, dict):
                return jsonify({"ok": False, "error": "ungueltiger Eintrag"}), 400
            address = update.get("address")
            param = store.get(address) if isinstance(address, str) else None
            if param is None:
                return jsonify({"ok": False,
                                "error": "unbekannte Adresse: %r" % (address,)}), 400
            try:
                value = float(update.get("value"))
            except (TypeError, ValueError):
                return jsonify({"ok": False,
                                "error": "ungueltiger Wert fuer %s" % address}), 400
            if value != value or value in (float("inf"), float("-inf")):
                return jsonify({"ok": False,
                                "error": "ungueltiger Wert fuer %s" % address}), 400
            applied.append(apply_value(param, value))

            if couple and address == SPEED_ADDRESS:
                targets, missing = coupled_values(store, applied[-1].value)
                skipped.extend(missing)
                for target, target_value in targets:
                    applied.append(apply_value(target, target_value))

        return jsonify({
            "ok": True,
            "applied": [{"address": a.address, "value": a.value, "sent": a.sent}
                        for a in applied],
            "skipped": skipped,
        })

    @app.route("/api/presets")
    def api_presets():
        return jsonify(preset_list_payload())

    @app.route("/api/preset/load", methods=["POST"])
    def api_preset_load():
        body = request.get_json(silent=True) or {}
        name = body.get("name")
        problem = valid_preset_name(name)
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        path = preset_file(name)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError as exc:
            return jsonify({"ok": False,
                            "error": "Preset nicht lesbar: %s" % exc}), 404
        entries = parse_settings(text)
        if not entries:
            return jsonify({"ok": False,
                            "error": "Preset %s enthaelt keine gueltige Zeile"
                                     % name}), 400
        # Erst senden, dann die Anzeige nachziehen: das Anwenden macht imPulse
        # selbst, hier werden nur die Regler nachgefuehrt.
        sender.send("/preset/load", name)
        result = apply_preset_entries(store, entries)
        result.update({"ok": True, "name": name})
        return jsonify(result)

    @app.route("/api/preset/save", methods=["POST"])
    def api_preset_save():
        body = request.get_json(silent=True) or {}
        name = body.get("name")
        problem = valid_preset_name(name)
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        path = preset_file(name)
        try:
            previous = os.path.getmtime(path)
            existed = True
        except OSError:
            previous = None
            existed = False
        sender.send("/preset/save", name)
        if not wait_for_preset_file(path, previous):
            return jsonify({
                "ok": False,
                "error": "imPulse hat %s.txt nicht geschrieben -- laeuft der "
                         "Sketch?" % name,
            }), 504
        payload = preset_list_payload()
        payload.update({"name": name, "overwritten": existed})
        return jsonify(payload)

    return app


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--settings",
                        default=os.environ.get("IMPULSE_SETTINGS", DEFAULT_SETTINGS_PATH),
                        help="Pfad zu remoteSettings.txt (Vorgabe: %(default)s)")
    parser.add_argument("--presets",
                        default=os.environ.get("IMPULSE_PRESETS"),
                        help="Ordner mit den Preset-Dateien "
                             "(Vorgabe: presets/ neben --settings)")
    parser.add_argument("--osc-host",
                        default=os.environ.get("IMPULSE_OSC_HOST", DEFAULT_OSC_HOST),
                        help="Ziel-Host fuer OSC (Vorgabe: %(default)s)")
    parser.add_argument("--osc-port", type=int,
                        default=int(os.environ.get("IMPULSE_OSC_PORT", DEFAULT_OSC_PORT)),
                        help="Ziel-Port fuer OSC (Vorgabe: %(default)s)")
    parser.add_argument("--host",
                        default=os.environ.get("IMPULSE_WEBUI_HOST", DEFAULT_HTTP_HOST),
                        help="Bind-Adresse des Webservers (Vorgabe: %(default)s)")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("IMPULSE_WEBUI_PORT", DEFAULT_HTTP_PORT)),
                        help="Port des Webservers (Vorgabe: %(default)s)")
    parser.add_argument("--debug", action="store_true",
                        help="Flask-Debugmodus (Autoreload)")
    args = parser.parse_args(argv)

    settings_path = os.path.abspath(args.settings)
    presets_path = (os.path.abspath(args.presets) if args.presets
                    else default_presets_path(settings_path))
    app = create_app(settings_path, args.osc_host, args.osc_port, presets_path)

    print("[webui] remoteSettings: %s" % settings_path)
    print("[webui] Presets:        %s" % presets_path)
    print("[webui] OSC-Ziel:       %s:%d" % (args.osc_host, args.osc_port))
    print("[webui] HTTP:           http://%s:%d" % (args.host, args.port))
    app.run(host=args.host, port=args.port, debug=args.debug, threaded=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
