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
import math
import os
import re
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

try:
    from flask import Flask, jsonify, render_template, request
except ImportError as _flask_error:  # Tests laufen ohne Flask, siehe test_webui.py
    Flask = None
    _FLASK_IMPORT_ERROR = _flask_error
else:
    _FLASK_IMPORT_ERROR = None

# Liegt neben dieser Datei. Wird beim direkten Start ueber das Skript-
# Verzeichnis gefunden, beim Import aus den Tests ueber deren sys.path-Eintrag.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import autocommit  # noqa: E402

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
# gebildet und die gekoppelten Parameter proportional (lifetime) bzw.
# invers (nodeDeadTime, randomSpawn/interval) mitskaliert.
#
# /net/impulse/lifetime hiess bis 2026-07-31 /net/impulse/energyDecayfactor;
# das damalige zweite /net/impulse/energyDecay war im Sketch wirkungslos und
# ist ersatzlos entfallen.
#
# Achtung: die Konstruktor-Defaults in LedNetworkTransportEffect.java stehen
# aktuell auf einem anderen Arbeitspunkt (speed 16, lifetime 0.02,
# nodeDeadTime 5.0, randomSpawn/interval 30.0). Die
# Kopplung bezieht sich bewusst auf den hier notierten Referenzpunkt -- wer
# stattdessen den Sketch-Arbeitspunkt koppeln will, aendert nur diesen Block.
# ---------------------------------------------------------------------------

SPEED_ADDRESS = "/net/impulse/speed"
SPEED_REFERENCE = 160.0

# address -> (Referenzwert bei SPEED_REFERENCE, "proportional" | "invers")
SPEED_COUPLED: Dict[str, Tuple[float, str]] = {
    "/net/impulse/lifetime": (0.2, "proportional"),
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
    "net/impulse/split",
    "net/impulse/randomize",
    "net/impulse/color",
    "net/randomSpawn",
    "net/sequencer",
    "net/sequencer/track0",
    "net/sequencer/track1",
    "net/sequencer/track2",
    "net/sequencer/track3",
    "net/sequencer/track4",
    "net/sequencer/track5",
    "net",
    "nodes",
    "nodes/radius",
    "nodes/times",
    "nodes/colors",
]

# Adress-Praefixe, die aus ihrer regulaeren group_key()-Gruppe herausgezogen
# und in eine eigene Gruppe verschoben werden -- unabhaengig von der
# generischen Praefix-Regel. Gebraucht fuer die Trennung Impuls/Impuls-Farbe
# (/net/impulse/color/* und /net/impulse/fadeOut/* landen sonst in derselben
# Gruppe wie speed/nodeDeadTime/lifetime) und fuer die Sinus-Randomizer: die
# generische Regel schneidet nach zwei Segmenten ab, /net/impulse/speed/
# randomize/enabled bekaeme also ebenfalls den Schluessel "net/impulse" und
# die acht Randomizer-Regler stuenden zwischen den vier Reglern, die sie
# steuern. Reihenfolge wichtig: laengster/spezifischster Praefix zuerst, damit
# z.B. "/net/impulse/color/gamma" nicht faelschlich unter einem kuerzeren
# "/net/impulse"-Eintrag landet, falls der je hinzukaeme.
SPLIT_GROUP_PREFIXES: List[Tuple[str, str]] = [
    ("/net/impulse/speed/randomize/", "net/impulse/randomize"),
    ("/net/impulse/lifetime/randomize/", "net/impulse/randomize"),
    ("/net/impulse/split/", "net/impulse/split"),
    ("/net/impulse/color/", "net/impulse/color"),
    ("/net/impulse/fadeOut/", "net/impulse/color"),
    # Je Sequencer-Track eine eigene Sektion. Ohne diese sechs Eintraege
    # schneidet die generische Praefix-Regel nach zwei Segmenten ab und alle
    # 36 Track-Regler landen unsortiert in einer einzigen Gruppe.
    #
    # Im Normalfall greift das nicht mehr: build_sequencer() baut ein eigenes
    # Panel und sequencer_addresses() nimmt diese Adressen aus dem
    # generischen Rendering. Die Eintraege sind der RUECKFALL fuer den Fall,
    # dass das Panel nicht gebaut werden kann (z.B. eine remoteSettings.txt
    # mit Track-Parametern, aber ohne /net/sequencer/bpm) - dann steht hier
    # wenigstens eine brauchbare Gruppierung statt einer Sammelgruppe.
    ("/net/sequencer/track0/", "net/sequencer/track0"),
    ("/net/sequencer/track1/", "net/sequencer/track1"),
    ("/net/sequencer/track2/", "net/sequencer/track2"),
    ("/net/sequencer/track3/", "net/sequencer/track3"),
    ("/net/sequencer/track4/", "net/sequencer/track4"),
    ("/net/sequencer/track5/", "net/sequencer/track5"),
]

# ---------------------------------------------------------------------------
# Spezial-Sektionen
#
# Sequencer, Speed-Klassen und die SC-Sound-Parameter bekommen ein eigenes,
# handgebautes Bedienfeld statt einer Reihe generischer Regler. Der Server
# liefert dafuer nur STRUKTUR (welche Adresse ist welches Feld welchen
# Tracks); wie es aussieht, entscheidet app.js.
#
# Die Adressen dieser Sektionen fallen anschliessend aus dem generischen
# Gruppen-Rendering heraus (siehe sequencer_addresses) -- zwei
# Bedienelemente fuer denselben Parameter waeren zwei Anzeigen, die
# auseinanderlaufen koennen.
# ---------------------------------------------------------------------------

# Notenwerte des Sequencers: Wert, Symbol, Name. Die Symbole sind
# U+1D15D..U+1D161 (MUSICAL SYMBOL WHOLE NOTE .. SIXTEENTH NOTE). Nicht jede
# Windows-Schrift hat sie, deshalb steht der Name immer daneben und nie nur
# das Symbol allein -- ein leeres Kaestchen waere sonst die ganze Beschriftung.
NOTE_VALUES: List[Tuple[int, str, str]] = [
    (1, "\U0001D15D", "Ganze"),
    (2, "\U0001D15E", "Halbe"),
    (4, "\U0001D15F", "Viertel"),
    (8, "\U0001D160", "Achtel"),
    (16, "\U0001D161", "Sechzehntel"),
]

SEQUENCER_PREFIX = "/net/sequencer/"
SEQUENCER_TRACK_COUNT = 6
# Reihenfolge im Track-Panel. noteValue steht bewusst vorn: er bestimmt, wie
# dicht der Track ueberhaupt laeuft.
SEQUENCER_TRACK_FIELDS = ["noteValue", "repeatCount", "energy",
                          "swingJitter", "originTreeFilter",
                          "originStripeOverride"]

# Klartext je Wert von originTreeFilter. Index 0 = kein Filter, 1..4 = die
# vier Baeume in derselben Reihenfolge wie StripeTreeStore.TREE_NAMES auf der
# Java-Seite. Der Parameter traegt eine Zahl, weil
# RemoteControlledIntParameter keine Aufzaehlung kann - im UI steht der Name.
TREE_LABELS = ["alle", "vorn", "hinten", "rechts", "links"]

# Erklaerung zum Baum-Filter, einmal je Sequencer-Sektion. Sie steht HIER und
# nicht sechsmal in app.js: derselbe Satz unter jedem der sechs Tracks waere
# Rauschen, und der Vorrang von originStripeOverride ist eine inhaltliche
# Aussage ueber die Java-Seite (OriginSequencer.advanceOrigin), die hier
# pruefbar bleibt.
TREE_HELP = ("Baum: schraenkt den Ursprungs-Stripe eines Tracks auf einen der "
             "vier Baeume ein. „alle“ = kein Filter, der Track "
             "wuerfelt aus allen Stripes. Der Filter wirkt nur, solange "
             "„Ursprung“ auf „zufall“ steht – ein "
             "fest gesetzter Stripe (originStripeOverride) hat Vorrang.")

# Speed-Klassen aus SpeedQuantizer.MULTIPLIERS, gleiche Reihenfolge.
# Adress-Suffix und Anzeigename; das Suffix spiegelt die Java-Seite (Punkt
# vermieden, siehe Kommentar dort).
SPEED_CLASSES: List[Tuple[str, str]] = [
    ("0x5", "0,5x"),
    ("1x", "1x"),
    ("2x", "2x"),
    ("4x", "4x"),
    ("8x", "8x"),
]
SPEED_WEIGHT_PREFIX = "/net/impulse/speedQuantize/weight/"

# Split-Verhalten: wieviele Zweige eine Aufspaltung nimmt und wie weit sie
# zeitlich auseinander starten. Adress-Suffix und Anzeigename; die Reihenfolge
# spiegelt SplitFanout (Index 0 = alle, 1 = einer weniger, 2 = genau einer).
SPLIT_PREFIX = "/net/impulse/split/"
SPLIT_WEIGHT_PREFIX = SPLIT_PREFIX + "weight/"
SPLIT_WEIGHTS: List[Tuple[str, str]] = [
    ("all", "alle Zweige"),
    ("oneLess", "einer weniger"),
    ("single", "nur einer"),
]

# Notenwert-Klassen des zeitlichen Versatzes: Adress-Suffix und Notenwert.
# Reihenfolge wie OriginSequencer.NOTE_VALUES auf der Java-Seite (Ganze ..
# Sechzehntel). Symbol und Name kommen aus NOTE_VALUES oben, damit "Achtel"
# im ganzen UI gleich aussieht.
#
# Der Versatz hatte bis 2026-08-01 einen einzigen festen Notenwert; jetzt
# wird er je Aufspaltung aus diesen Klassen gezogen. Deshalb steht hier eine
# Verteilung wie bei den Speed-Klassen und keine Notenwert-Leiste mehr.
#
# Die sechste Klasse ist KEIN Notenwert: "gleichzeitig" laesst alle Zweige
# ohne Versatz starten. Auf der Java-Seite ist sie der Sentinel-Notenwert 0
# (SplitStagger.SIMULTANEOUS_NOTE_VALUE), hier steht sie deshalb hinten und
# mit einem eigenen Label -- in NOTE_VALUES oben gibt es keinen Eintrag fuer
# 0, ein generisches Lookup liefe dort in einen KeyError.
SPLIT_STAGGER_WEIGHT_PREFIX = SPLIT_PREFIX + "stagger/weight/"
SPLIT_STAGGER_SIMULTANEOUS = 0
# Symbol und Name wie bei den fuenf Notenwerten, nur ohne Musik-Unicode: das
# Symbol allein ist nie die ganze Beschriftung (app.js setzt "symbol label"
# zusammen), und U+2261 (Identisch-Zeichen) liegt anders als die Notensymbole
# U+1D15D.. in jeder Windows-Schrift.
SPLIT_STAGGER_SIMULTANEOUS_LABEL: Tuple[str, str] = ("≡", "Gleichzeitig")
SPLIT_STAGGER_NOTES: List[Tuple[str, int]] = [
    ("whole", 1),
    ("half", 2),
    ("quarter", 4),
    ("eighth", 8),
    ("sixteenth", 16),
    ("simultaneous", SPLIT_STAGGER_SIMULTANEOUS),
]

# Ruhemomente (PauseGate.java): alle checkIntervalBars Takte faellt mit
# probability die Entscheidung, ob eine Pause beginnt; sie dauert dann
# lengthMinBars..lengthMaxBars Takte und schaltet die zwei Spawn-Ebenen stumm,
# die dafuer angehakt sind. Alle Zeitangaben in TAKTEN, nicht Beats oder
# Sekunden -- dieselbe Einheit, in der der Sequencer daneben gedacht wird
# (siehe den Kommentar in PauseGateConfig).
PAUSE_PREFIX = "/net/pause/"

# Die zwei Ebenen, die eine laufende Pause stummschalten kann. Adress-Suffix
# und Anzeigename, Reihenfolge wie im Panel.
PAUSE_TARGETS: List[Tuple[str, str]] = [
    ("affectsSequencer", "Sequencer"),
    ("affectsRandomSpawn", "Zufalls-Spawns"),
]

# Die drei Fragen, die live am Geraet offen geblieben sind (Birk, 2026-08-02):
# nach WIEVIEL TAKTEN kann eine Pause ueberhaupt beginnen, mit WELCHER
# WAHRSCHEINLICHKEIT tut sie das dann, und WIE LANGE dauert sie. Jede bekommt
# einen eigenen benannten Block statt sieben Schieber in einer Reihe.
#
# Die Ueberschriften sind ausgeschriebene Fragen und keine Substantive
# ("Ziehung", "Dauer"): genau die Kurzform war der Zustand, in dem
# „probability 0,25“ dastand und niemand sagen konnte, worauf sich die Zahl
# bezieht. Sie stehen hier und nicht in app.js, aus demselben Grund wie
# ADDRESS_LABELS und TREE_HELP -- nur hier sind sie ohne jsdom pruefbar.
PAUSE_BLOCK_TITLES: Dict[str, str] = {
    "when": "Wann kann eine Pause beginnen?",
    "length": "Wie lange dauert eine Pause?",
    "targets": "Wen schaltet eine Pause stumm?",
}

# Kurzbeschriftungen der vier Schieber im Panel. Die langen Titel aus
# ADDRESS_LABELS ("Pausendauer, kuerzeste") passen nicht in die
# Beschriftungsspalte einer .mini-row, muessen dort aber trotzdem ihre EINHEIT
# nennen: „2“ neben einem Schieber ist ohne „Takte“ wieder eine Zahl ohne
# Bezug. Die lange Fassung samt Erklaerung bleibt als Tooltip erreichbar.
PAUSE_FIELD_LABELS: Dict[str, str] = {
    "checkIntervalBars": "Pruefintervall (Takte)",
    "probability": "Wahrscheinlichkeit",
    "lengthMinBars": "kuerzeste Dauer (Takte)",
    "lengthMaxBars": "laengste Dauer (Takte)",
}

# Was eine Pause WIRKLICH tut -- einmal unter dem Schalter. Der Text steht
# hier und nicht in app.js, aus demselben Grund wie TREE_HELP: er ist eine
# Aussage ueber die Java-Seite (PauseGate.tick: kein harter Stop, sondern
# keine neuen Anfaenge; kein neuer Wurf waehrend einer laufenden Pause) und
# bleibt hier ohne jsdom pruefbar.
PAUSE_HELP = ("Eine Pause verbietet nur NEUE Impulse. Schon fliegende laufen "
              "weiter, spalten sich auf und klingen aus – es ist kein harter "
              "Stop, sondern eine Atempause. Gezaehlt wird in Takten auf "
              "derselben Uhr wie der Sequencer (/net/sequencer/bpm), auch "
              "wenn der Sequencer selbst aus ist. Waehrend einer laufenden "
              "Pause wird nicht neu gewuerfelt – eine Pause kann keine "
              "andere verlaengern.")

# Die zwei Haken darunter. Ihre Bedeutung stand bisher nur im Java-Kommentar
# in PauseGateConfig: aus „affectsSequencer“ allein ist nicht zu erraten, dass
# hier steht, WER waehrend der Pause verstummt.
PAUSE_TARGET_HELP = ("Welche Spawn-Ebene eine laufende Pause stummschaltet. "
                     "Beide unabhaengig: nur den Sequencer aussetzen lassen "
                     "(die Zufalls-Spawns laufen als Ambient weiter) oder "
                     "beide zusammen fuer echte Stille.")

# Die Titel und Kurzerklaerungen aller Regler stehen weiter unten in
# ADDRESS_LABELS -- sie brauchen die Levelnamen der Song-Struktur, die erst
# darunter definiert sind.


def build_sequencer(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Struktur des Sequencer-Panels, oder None.

    None heisst: dieser imPulse-Stand kennt den Sequencer nicht (aeltere
    remoteSettings.txt). Dann faellt das UI stillschweigend auf das generische
    Rendering zurueck, statt eine leere Sektion zu zeigen.
    """
    bpm = by_address.get(SEQUENCER_PREFIX + "bpm")
    enabled = by_address.get(SEQUENCER_PREFIX + "enabled")
    if bpm is None or enabled is None:
        return None
    tracks: List[Dict[str, Any]] = []
    for i in range(SEQUENCER_TRACK_COUNT):
        base = "%strack%d/" % (SEQUENCER_PREFIX, i)
        track_enabled = by_address.get(base + "enabled")
        if track_enabled is None:
            continue
        fields = {}
        for name in SEQUENCER_TRACK_FIELDS:
            param = by_address.get(base + name)
            if param is not None:
                fields[name] = param.as_dict()
        tracks.append({
            "index": i,
            "enabled": track_enabled.as_dict(),
            "fields": fields,
        })
    if not tracks:
        return None
    return {
        "bpm": bpm.as_dict(),
        "enabled": enabled.as_dict(),
        "tracks": tracks,
        "noteValues": [{"value": v, "symbol": s, "name": n}
                       for v, s, n in NOTE_VALUES],
        "treeLabels": list(TREE_LABELS),
        "treeHelp": TREE_HELP,
        "legend": track_field_legend(),
    }


def track_field_legend() -> List[Dict[str, str]]:
    """Was die Regler einer Track-Karte bedeuten -- EINMAL unter der Reihe.

    Die Karten sind bewusst kompakt beschriftet ("Wdh.", "Swing"): sechs
    Erklaerungssaetze in jeder der sechs Karten waeren 36 Absaetze und
    machten das Panel unbedienbar, das ja gerade deshalb existiert. Einmal
    darunter als Legende ist die Erklaerung trotzdem da, ohne die Karten zu
    sprengen.

    Text und Titel kommen aus ADDRESS_LABELS, nicht aus einer zweiten Liste --
    sonst stuende dieselbe Aussage an zwei Orten und nur einer wuerde
    nachgezogen. originTreeFilter fehlt hier bewusst: TREE_HELP steht direkt
    daneben und sagt mehr (den Vorrang des festen Ursprungs).
    """
    legend: List[Dict[str, str]] = []
    for name in SEQUENCER_TRACK_FIELDS:
        if name == "originTreeFilter":
            continue
        label, text = label_for("%strack0/%s" % (SEQUENCER_PREFIX, name))
        if label and text:
            legend.append({"label": label, "text": text})
    return legend


def build_speed_classes(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Struktur der Speed-Klassen-Sektion, oder None wenn unbekannt.

    ``base`` ist der GRUNDREGLER /net/impulse/speed selbst, nicht ein zweiter
    Parameter daneben. Bis 2026-08-02 stand hier ein eigenes
    /net/impulse/speedQuantize/baseSpeed -- zwei Regler, die beide "die
    Geschwindigkeit" hiessen, und der eine machte den anderen bei
    eingeschalteten Klassen wirkungslos (siehe spawnSpeed() auf der
    Java-Seite). Jetzt steht der Grundregler in der Sektion, die ihn
    vervielfacht, und nirgends sonst: die Klassen 0,5x..8x multiplizieren
    sichtbar auf den Wert direkt darueber.
    """
    enabled = by_address.get("/net/impulse/speedQuantize/enabled")
    if enabled is None:
        return None
    weights: List[Dict[str, Any]] = []
    for suffix, label in SPEED_CLASSES:
        param = by_address.get(SPEED_WEIGHT_PREFIX + suffix)
        if param is None:
            continue
        entry = param.as_dict()
        entry["label"] = label
        weights.append(entry)
    if not weights:
        return None
    jitter = by_address.get("/net/impulse/speedQuantize/jitter")
    base = by_address.get(SPEED_ADDRESS)
    return {
        "enabled": enabled.as_dict(),
        "jitter": jitter.as_dict() if jitter is not None else None,
        "base": base.as_dict() if base is not None else None,
        "weights": weights,
    }


def build_split(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Struktur der Split-Sektion, oder None wenn unbekannt.

    Eigene Sektion statt acht generischer Schieber aus einem Grund: die
    Gewichte gehoeren als Verteilung zusammen (wie bei den Speed-Klassen).
    Acht Schieber nebeneinander sagen nicht, dass drei davon eine Verteilung
    und fuenf davon eine zweite sind -- der Operator rechnete sich im Kopf
    aus, was 40/25/10 bedeutet.

    Zwei Verteilungen also: WIEVIELE Zweige eine Kreuzung nimmt, und mit
    WELCHEM Notenwert sie auseinander starten. Der Notenwert wird je
    Aufspaltung gezogen; einen festen Regler dafuer gibt es seit 2026-08-01
    nicht mehr.
    """
    stagger_enabled = by_address.get(SPLIT_PREFIX + "staggerEnabled")
    weights: List[Dict[str, Any]] = []
    for suffix, label in SPLIT_WEIGHTS:
        param = by_address.get(SPLIT_WEIGHT_PREFIX + suffix)
        if param is None:
            continue
        entry = param.as_dict()
        entry["label"] = label
        weights.append(entry)
    note_labels = {value: (symbol, name) for value, symbol, name in NOTE_VALUES}
    stagger_weights: List[Dict[str, Any]] = []
    for suffix, note_value in SPLIT_STAGGER_NOTES:
        param = by_address.get(SPLIT_STAGGER_WEIGHT_PREFIX + suffix)
        if param is None:
            continue
        # Der Sonderfall steht VOR dem generischen Lookup und nicht als
        # dict.get()-Rueckfall: ein unbekannter echter Notenwert soll weiter
        # auffallen (KeyError) statt still als "Gleichzeitig" durchzurutschen.
        if note_value == SPLIT_STAGGER_SIMULTANEOUS:
            symbol, name = SPLIT_STAGGER_SIMULTANEOUS_LABEL
        else:
            symbol, name = note_labels[note_value]
        entry = param.as_dict()
        entry["noteValue"] = note_value
        entry["symbol"] = symbol
        entry["label"] = name
        stagger_weights.append(entry)
    if not weights and not stagger_weights and stagger_enabled is None:
        return None
    return {
        "weights": weights,
        "staggerEnabled": stagger_enabled.as_dict() if stagger_enabled is not None else None,
        "staggerWeights": stagger_weights,
    }


def build_pause(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Struktur der Ruhemomente-Sektion, oder None wenn unbekannt.

    Eigene Sektion statt sieben generischer Schieber, aus demselben Grund wie
    bei den Speed-Klassen: die sieben Adressen sind kein Satz unabhaengiger
    Zahlen, sondern EIN Mechanismus. „Wahrscheinlichkeit 0,25“ heisst nichts,
    ohne dass daneben steht, worauf sie sich bezieht (alle acht Takte eine
    Ziehung), und die Dauer der Pause lag bis 2026-08-02 im
    Erweitert-Bereich zwischen den RandomSpawn-Reglern -- Birk hat sie live am
    Geraet nicht gefunden.

    None heisst wie bei den anderen build_*-Funktionen: dieser imPulse-Stand
    kennt die Ruhemomente nicht (aelterer Dump). Dann faellt das UI still auf
    das generische Rendering zurueck, statt eine leere Sektion zu zeigen.
    Fehlt nur ein einzelner Regler, bleibt die Sektion und laesst ihn weg --
    anders als bei der Song-Matrix ist hier jeder Wert fuer sich lesbar, ein
    fehlender ist keine stille Luecke in einem Gitter.
    """
    enabled = by_address.get(PAUSE_PREFIX + "enabled")
    if enabled is None:
        return None

    def entry(name: str) -> Optional[Dict[str, Any]]:
        param = by_address.get(PAUSE_PREFIX + name)
        if param is None:
            return None
        item = param.as_dict()
        # Kurzform fuer die Beschriftungsspalte; der lange Titel aus
        # ADDRESS_LABELS bleibt ueber "help" im Tooltip erreichbar.
        item["label"] = PAUSE_FIELD_LABELS[name]
        return item

    targets: List[Dict[str, Any]] = []
    for suffix, label in PAUSE_TARGETS:
        param = by_address.get(PAUSE_PREFIX + suffix)
        if param is None:
            continue
        item = param.as_dict()
        item["label"] = label
        targets.append(item)

    return {
        "enabled": enabled.as_dict(),
        "probability": entry("probability"),
        "checkIntervalBars": entry("checkIntervalBars"),
        # lengthMin/lengthMax gehen als PAAR heraus und werden im UI als
        # Spanne gezeigt: PauseGate.drawLength() zieht gleichverteilt
        # dazwischen, zwei einzelne Schieber lesen sich als zwei unabhaengige
        # Werte.
        "lengthMin": entry("lengthMinBars"),
        "lengthMax": entry("lengthMaxBars"),
        "targets": targets,
        "blockTitles": dict(PAUSE_BLOCK_TITLES),
        "help": PAUSE_HELP,
        "targetHelp": PAUSE_TARGET_HELP,
    }


def pause_addresses(pause: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die die Ruhemomente-Sektion selbst rendert.

    Genau die, die oben auch wirklich in die Struktur gewandert sind -- ein
    Regler, der hier steht und dort fehlt, waere aus dem UI verschwunden.
    """
    if not pause:
        return set()
    taken = {pause["enabled"]["address"]}
    for key in ("probability", "checkIntervalBars", "lengthMin", "lengthMax"):
        item = pause.get(key)
        if item:
            taken.add(item["address"])
    for target in pause.get("targets", []):
        taken.add(target["address"])
    return taken


# ---------------------------------------------------------------------------
# Farben
#
# Alle Farben des UI werden ueber einen nativen Farbwaehler eingestellt, nicht
# mehr ueber Kanalregler (Birk, 2026-08-01). Der Server liefert dafuer nur die
# STRUKTUR -- welche drei Adressen eine Karte bilden und in welchem Farbraum
# --, das Aussehen macht app.js. Dieselbe Aufteilung wie beim Sequencer.
#
# Zwei Farbraeume, weil die Java-Seite zwei kennt: die Impuls- und
# Stripe-Farben liegen als r/g/b (RemoteControlledFloatParameter, 0..1), die
# Knotenfarben als Hue/Sat/Bright (RemoteControlledColorParameter). Umgerechnet
# wird im Browser, gesendet werden weiter die Einzelkanaele -- an dem, was bei
# imPulse ankommt, aendert sich nichts.
# ---------------------------------------------------------------------------

STRIPE_COLOR_PREFIX = "/net/impulse/stripeColor/"
# Spiegelt StripeColorDefaults.COUNT auf der Java-Seite. Der Effekt bildet
# Stripe -> Slot per Modulo ab; bei 30 Stripes wiederholt sich das Muster also
# alle acht.
STRIPE_COLOR_COUNT = 8
RGB_COMPONENTS = ("r", "g", "b")

IMPULSE_COLOR_BASE = "/net/impulse/color"


def _rgb_card(by_address: Dict[str, "Parameter"], base: str
              ) -> Optional[Dict[str, Any]]:
    """Eine Farbkarte aus <base>/r, <base>/g und <base>/b, oder None.

    None heisst: dieser imPulse-Stand kennt die drei Adressen nicht. Dann
    faellt das UI auf das generische Rendering zurueck, statt eine Karte ohne
    Wirkung zu zeigen.
    """
    parts = {}
    for component in RGB_COMPONENTS:
        param = by_address.get("%s/%s" % (base, component))
        if param is None:
            return None
        parts[component] = param.as_dict()
    label, help_text = label_for(base)
    return {
        "kind": "rgb",
        "base": base,
        "label": label or base.rpartition("/")[2],
        "help": help_text,
        "components": parts,
    }


def build_colors(by_address: Dict[str, "Parameter"]) -> Dict[str, Any]:
    """Struktur der Farb-Sektionen: Impulsfarbe, Modus, acht Stripe-Slots.

    Liefert immer ein Objekt (nie None) -- die einzelnen Felder sind None,
    wenn ihre Adressen fehlen. Anders als beim Sequencer gibt es hier kein
    Alles-oder-nichts: eine aeltere remoteSettings.txt kennt die
    Stripe-Farben nicht, die Impulsfarbe aber schon, und dann soll die
    Impulsfarbe trotzdem als Farbwaehler dastehen.
    """
    stripes: List[Dict[str, Any]] = []
    for slot in range(STRIPE_COLOR_COUNT):
        card = _rgb_card(by_address, "%s%d" % (STRIPE_COLOR_PREFIX, slot))
        if card is None:
            # Teilweise vorhandene Slots gibt es nicht: sie werden in einer
            # Schleife registriert. Ein fehlender Slot heisst "dieser Stand
            # kennt das Feature nicht", also gar keine Slot-Reihe.
            stripes = []
            break
        card["slot"] = slot
        stripes.append(card)

    mode = by_address.get(IMPULSE_COLOR_BASE + "/useSpecificColor")
    return {
        "impulse": _rgb_card(by_address, IMPULSE_COLOR_BASE),
        "mode": mode.as_dict() if mode is not None else None,
        # Die Beschriftung der zwei Zustaende steht HIER und nicht in app.js:
        # sie ist die Aussage darueber, was der Schalter auf der Java-Seite
        # tut, und nur hier pruefbar. "an (1)/aus (0)" war fuer genau diesen
        # Parameter die schlechteste aller Beschriftungen -- beide Zustaende
        # sind ein Modus, keiner ist "aus".
        "modeLabels": {"1": "Spezifische Farbe", "0": "Stripe-Farben"},
        "stripes": stripes,
    }


def color_addresses(colors: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die die Farb-Sektionen selbst rendern.

    Ohne das stuenden Impulsfarbe und Stripe-Slots zweimal auf der Seite:
    einmal als Farbwaehler und einmal als Kanalregler -- zwei Bedienelemente
    fuer denselben Wert, die auseinanderlaufen koennen.
    """
    taken: Set[str] = set()
    if not colors:
        return taken
    cards = list(colors.get("stripes") or [])
    if colors.get("impulse"):
        cards.append(colors["impulse"])
    for card in cards:
        for entry in card["components"].values():
            taken.add(entry["address"])
    if colors.get("mode"):
        taken.add(colors["mode"]["address"])
    return taken


# ---------------------------------------------------------------------------
# Nachleuchten: Zielfarbe + Tempo statt drei Zerfallsraten
#
# /net/impulse/fadeOut/{r,g,b} sind KEINE Farbe. Der Effekt multipliziert den
# ganzen LED-Puffer in jedem Frame damit (LedColor.mult in drawMe()) -- es sind
# drei Zerfallsraten je Kanal. Ein Farbwaehler direkt darauf waere irrefuehrend:
# die Intuition "heller im Waehler = mehr davon" bedeutet hier "zerfaellt
# LANGSAMER", also das Gegenteil dessen, was man beim Aufziehen erwartet.
#
# Bedient wird deshalb, was ein Operator wirklich fragt: welche Farbe hat die
# Spur, kurz bevor sie verschwindet, und wie schnell verschwindet sie. Die drei
# Raten werden daraus gerechnet.
#
# Formel:
#     w_c       = MIN_WEIGHT + (1 - MIN_WEIGHT) * (ziel_c / max(ziel))
#     fadeOut_c = baseDecay ** (1 / w_c)
#
# Der staerkste Kanal der Zielfarbe hat w = 1 und bekommt damit genau
# baseDecay; jeder schwaechere bekommt einen groesseren Exponenten, zerfaellt
# also schneller und ist frueher weg. Uebrig bleibt am Ende der Spur die
# Zielfarbe -- genau das, was der Waehler zeigt.
#
# Drei Grenzfaelle, die die Kalibrierung bestimmen:
#
# * baseDecay = 1 -> alle Kanaele 1, unabhaengig von der Zielfarbe. Faellt
#   ohne Sonderfall heraus, weil 1**x == 1 fuer jedes x. Das ist der Grund fuer
#   die Potenz-Form: eine multiplikative Gewichtung (baseDecay * w_c) haette
#   hier je Kanal etwas anderes ergeben.
# * Schwarze Zielfarbe -> max(ziel) == 0, die Division waere undefiniert.
#   Dann gelten alle Gewichte als 1: die Spur zerfaellt in allen drei Kanaelen
#   gleich schnell, verfaerbt sich also nicht. Das ist die einzige Antwort, die
#   ohne willkuerliche Annahme auskommt -- "welche Farbe bleibt uebrig" hat bei
#   Schwarz keine Antwort.
# * MIN_WEIGHT > 0 haelt den Exponenten endlich. Bei w = 0 waere er unendlich.
#
# MIN_WEIGHT ist NICHT geraten, sondern aus dem Auslieferungswert
# 0.97/0.96/0.56 zurueckgerechnet: mit baseDecay = 0.97 (der langsamste Kanal)
# braucht Blau ein Gewicht von ln(0.97)/ln(0.56) = 0.0525, damit die Zahl
# wieder herauskommt. Ein groesseres MIN_WEIGHT koennte die gelieferte
# Einstellung gar nicht mehr darstellen -- der warme Schweif der Installation
# waere mit dem neuen Bedienelement unerreichbar.
# ---------------------------------------------------------------------------

FADE_PREFIX = "/net/impulse/fadeOut/"
FADE_ADDRESSES = tuple(FADE_PREFIX + c for c in RGB_COMPONENTS)

MIN_WEIGHT = 0.05

# Ab hier gilt baseDecay als "kein Zerfall": ln(1) ist 0, die Rueckrechnung
# teilte also durch null. Der Abstand ist grob genug, um auch die Rundung auf
# vier Stellen in einer Preset-Datei aufzufangen.
_NO_DECAY_EPSILON = 1e-4


def fade_from_target(red: float, green: float, blue: float,
                     decay: float) -> Tuple[float, float, float]:
    """(Zielfarbe 0..1, Tempo 0..1) -> die drei Zerfallsraten 0..1."""
    channels = [_clamp01(float(c)) for c in (red, green, blue)]
    base = _clamp01(float(decay))
    strongest = max(channels)
    result: List[float] = []
    for value in channels:
        if strongest <= 0.0:
            weight = 1.0
        else:
            weight = MIN_WEIGHT + (1.0 - MIN_WEIGHT) * (value / strongest)
        result.append(_clamp01(base ** (1.0 / weight)))
    return result[0], result[1], result[2]


def fade_to_target(red: float, green: float, blue: float
                   ) -> Tuple[float, float, float, float]:
    """Die Umkehrung: drei Zerfallsraten -> (Zielfarbe 0..1, Tempo 0..1).

    Gebraucht beim Seitenaufbau und nach jedem Preset-Laden -- das UI kennt
    dann nur die drei rohen Werte und muss daraus Waehler und Fader stellen.

    Zwei Faelle haben keine eindeutige Antwort und liefern deshalb Weiss:
    zerfaellt gar nichts (alle Raten 1), gibt es keine Reihenfolge, in der die
    Kanaele verschwinden; zerfaellt alles sofort (Rate 0), bleibt keine Farbe
    uebrig. Weiss ist dabei die harmloseste Anzeige: sie behauptet keine
    Faerbung, die es nicht gibt.
    """
    rates = [_clamp01(float(c)) for c in (red, green, blue)]
    base = max(rates)
    if base >= 1.0 - _NO_DECAY_EPSILON or base <= 0.0:
        return 1.0, 1.0, 1.0, base
    import math
    log_base = math.log(base)
    channels: List[float] = []
    for rate in rates:
        if rate <= 0.0:
            # Kanal verschwindet sofort: das ist das Gewicht MIN_WEIGHT, also
            # der schwaechstmoegliche Anteil an der Zielfarbe.
            channels.append(0.0)
            continue
        exponent = math.log(rate) / log_base
        if exponent <= 1.0:
            channels.append(1.0)
            continue
        weight = 1.0 / exponent
        channels.append(_clamp01((weight - MIN_WEIGHT) / (1.0 - MIN_WEIGHT)))
    return channels[0], channels[1], channels[2], base


def build_fade(by_address: Dict[str, "Parameter"],
               values: Dict[str, float]) -> Optional[Dict[str, Any]]:
    """Struktur der Sektion "Nachleuchten", oder None wenn unbekannt."""
    params = [by_address.get(address) for address in FADE_ADDRESSES]
    if any(p is None for p in params):
        return None
    current = [float(values.get(p.address, p.value)) for p in params]
    red, green, blue, decay = fade_to_target(*current)
    return {
        "addresses": list(FADE_ADDRESSES),
        "target": {"r": red, "g": green, "b": blue},
        "decay": decay,
        "raw": {p.address: value for p, value in zip(params, current)},
    }


def fade_addresses(fade: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die die Nachleucht-Sektion selbst bedient."""
    return set(fade["addresses"]) if fade else set()


# ---------------------------------------------------------------------------
# Song-Struktur-Ebene
#
# Die Dramaturgie ueber eine ganze Nacht: eine Markov-Kette ueber vier
# Energie-Level. Eigener Tab, weil eine 4x4-Matrix als sechzehn untereinander
# stehende Regler nicht zu lesen ist -- ein Gitter zeigt in einem Blick, wovon
# es eine Zeile und wovon eine Spalte gibt.
#
# SONG_LEVEL_NAMES spiegelt EnergyLevelStore.LEVEL_NAMES auf der Java-Seite;
# ein Test vergleicht beide, damit die Reihenfolge nicht abdriftet. Sie steckt
# in den Adressen selbst (/songStructure/matrix/<von>/<nach>), eine
# Verschiebung waere also nicht nur eine falsche Beschriftung.
# ---------------------------------------------------------------------------

SONG_PREFIX = "/songStructure/"
SONG_LEVEL_NAMES: List[str] = ["ruhig", "mittel", "dynamisch", "dramatisch"]

# Manueller Levelwechsel. Ein KOMMANDO und deshalb bewusst nicht in
# remoteSettings.txt (SongStructureParams.writeToStream() ist leer) -- ein
# Regler waere hier sinnlos, "Wert halten" ergibt fuer einen Sprung keinen
# Sinn. Argument 1..4, nicht 0..3: 0 waere von "kein Argument" nicht zu
# unterscheiden.
SONG_GOTO_ADDRESS = SONG_PREFIX + "goto"

# Zustandsdatei, die imPulse bei jedem Levelwechsel schreibt. Der einzige Weg
# zum Live-Zustand: es gibt keinen OSC-Rueckkanal hierher, imPulse sendet nur
# an Port 8002 und dort hoert SuperCollider. Dieser Server laeuft auf
# derselben Maschine, also wird die Datei direkt gelesen -- dasselbe Muster
# wie die Preset-Liste, die auch vom Dateisystem kommt.
DEFAULT_STATE_FILENAME = "songStructureState.txt"

SONG_LEVEL_HINTS: List[str] = [
    "wenig Bewegung, lange Lebensdauern, dunkel und kalt",
    "spuerbarer Puls, aber kein Draengen",
    "hohe Spawn-Rate, viel Bewegung im Netz",
    "Maximalausschlag - kurz und intensiv, kein Dauerzustand",
]


# ---------------------------------------------------------------------------
# Regler-Beschriftungen
#
# Adresse -> (sprechender Titel, Kurzerklaerung oder None).
#
# Warum das HIER steht und nicht in app.js: es ist eine inhaltliche Aussage
# darueber, was ein Parameter im Sketch bewirkt -- dasselbe Argument wie bei
# TAB_RULES und TREE_HELP. Hier ist sie ohne jsdom pruefbar; test_webui.py
# haelt fest, dass jede bekannte Adresse einen Titel hat.
#
# Der Titel ersetzt das letzte Adresssegment als HAUPTtext des Reglers. Die
# rohe OSC-Adresse bleibt sichtbar, aber als kleinste, gedimmte Zeile darunter
# -- ohne sie waere ein Regler nicht mehr einer Adresse zuzuordnen, und genau
# das braucht man beim Debuggen mit OSC von Hand.
#
# Die zweite Spalte ist bewusst dreiwertig gemeint:
#   Text  -- Erklaerung, wird unter dem Regler angezeigt
#   None  -- SELBSTERKLAEREND, es soll bewusst nichts dastehen (Farbkanaele,
#            Min/Max-Paare, deren Sektionsueberschrift schon alles sagt)
# Ein fehlender Eintrag heisst dagegen "vergessen" -- der Unterschied ist der
# Grund, warum hier auch triviale Adressen mit None aufgefuehrt sind.
#
# remoteSettings.txt fuehrt zwar selbst eine Beschreibungsspalte, die steht
# aber bei praktisch allen Parametern auf dem Platzhalter
# "space for descripiton" und ist deshalb keine Quelle.
# ---------------------------------------------------------------------------

# Nachgeschlagen wird in drei Stufen (siehe label_for): exakte Adresse, dann
# das Ziffern-Muster (track0 -> track#), dann das letzte Segment. Die
# Muster-Stufe spart sechs identische Zeilen je Sequencer-Feld.
ADDRESS_LABELS: Dict[str, Tuple[str, Optional[str]]] = {

    # --- Mixer ------------------------------------------------------------
    "/master/level": (
        "Gesamt-Helligkeit",
        "Show-Fader ueber alle 18 000 LEDs. Wirkt auf die Hardware; die "
        "Vorschau im Sketch-Fenster zeigt weiter volle Helligkeit."),
    "Master/trace": (
        "Nachleuchten (Gesamtbild)",
        "Wieviel vom vorigen Bild stehen bleibt, bevor das neue darauf "
        "gemischt wird. 0 = harte Punkte, hoch = lange Schweife durch das "
        "ganze Netz. Wirkt auf ALLE Ebenen; die Spur der Impulse allein "
        "regelt der Farben-Tab."),
    "Master/0/opacity/0.Impulse": (
        "Ebene: Impulse",
        "Deckkraft der wandernden Impulse in der Mischung."),
    "Master/1/opacity/1.Nodes": (
        "Ebene: Knoten",
        "Deckkraft der Kreuzungspunkte in der Mischung."),

    # --- Impuls-Physik ----------------------------------------------------
    "/net/impulse/speed": (
        "Grundgeschwindigkeit",
        "Wie schnell ein Impuls den Stripe entlangwandert, in LEDs pro "
        "Sekunde. Die EINZIGE Quelle des Grundtempos: die Tempo-Klassen "
        "darunter sind Vielfache davon. Bei eingeschalteter Kopplung ziehen "
        "Lebensdauer, Totzeit und Spawn-Intervall proportional mit; die "
        "Sinus-Schwingung auf demselben Wert steht im Tab Impuls-Verhalten."),
    "/net/impulse/lifetime": (
        "Energieverlust je Sekunde",
        "Wie schnell ein Impuls unterwegs dunkler wird und schliesslich "
        "stirbt. Klein = weite Reise durchs Netz, gross = kurzer Funke."),
    "/net/impulse/nodeDeadTime": (
        "Totzeit pro Knoten",
        "So lange bleibt eine Kreuzung nach dem Feuern stumm. Ohne sie wuerde "
        "derselbe Impuls denselben Knoten in jedem Frame neu ausloesen."),
    "/net/impulse/energyExponent": (
        "Anschlags-Kennlinie",
        "Exponent auf der Energie eines eingehenden Triggers. 1 = linear, "
        "hoeher = nur kraeftige Anschlaege kommen noch hell durch."),
    "/net/impulse/oscRate": (
        "Positionsmeldungen je Sekunde",
        "Takt, in dem die Positionen reisender Impulse an SuperCollider "
        "gehen. 0 schaltet den Strom ab - die Knotentoene laufen weiter. Der "
        "Not-Aus, wenn der Klangrechner nicht mitkommt."),
    "/net/impulse/oscMaxCount": (
        "Gemeldete Impulse je Takt",
        "Hoechstzahl gleichzeitig gemeldeter Impulse, ausgewaehlt nach "
        "Energie. Deckel gegen Klangbrei und Netzlast."),
    "/net/impulse/splitSpeedJitter": (
        "Streuung: Tempo der Zweige",
        "Streut die Geschwindigkeit der Kinder an einer Kreuzung. "
        "0 = jeder Zweig exakt so schnell wie der Elternimpuls."),
    "/net/impulse/splitLifetimeJitter": (
        "Streuung: Lebensdauer der Zweige",
        "Streut die Lebensdauer der Kinder an einer Kreuzung, ohne ihre "
        "Helligkeit zu aendern. 0 = Geschwister sterben synchron."),

    # --- Sinus-Randomizer -------------------------------------------------
    "/net/impulse/speed/randomize/enabled": (
        "Tempo automatisch schwanken lassen",
        "Faehrt die Grundgeschwindigkeit langsam zwischen Min und Max hin und "
        "her, statt sie fest stehen zu lassen. Der Regler oben folgt der "
        "Bewegung im UI nicht - es gibt keinen Rueckkanal."),
    "/net/impulse/speed/randomize/min": (
        "Tempo: untere Grenze",
        "Langsamster Wert, den die Schwingung erreicht."),
    "/net/impulse/speed/randomize/max": (
        "Tempo: obere Grenze",
        "Schnellster Wert, den die Schwingung erreicht."),
    "/net/impulse/speed/randomize/period": (
        "Tempo: Dauer eines Zyklus",
        "Sekunden fuer einen vollen Auf-und-Ab-Durchlauf. Beim Einschalten "
        "startet die Schwingung in der Mitte des Bereichs."),
    "/net/impulse/lifetime/randomize/enabled": (
        "Lebensdauer automatisch schwanken lassen",
        "Faehrt den Energieverlust langsam zwischen Min und Max hin und her. "
        "Unabhaengig von der Tempo-Schwingung, eigener Takt."),
    "/net/impulse/lifetime/randomize/min": (
        "Lebensdauer: untere Grenze",
        "Kleinster Energieverlust der Schwingung - die Impulse reisen hier am "
        "weitesten."),
    "/net/impulse/lifetime/randomize/max": (
        "Lebensdauer: obere Grenze",
        "Groesster Energieverlust der Schwingung - die Impulse sterben hier "
        "am schnellsten."),
    "/net/impulse/lifetime/randomize/period": (
        "Lebensdauer: Dauer eines Zyklus",
        "Sekunden fuer einen vollen Auf-und-Ab-Durchlauf."),

    # --- Tempo-Klassen ----------------------------------------------------
    "/net/impulse/speedQuantize/enabled": (
        "Rhythmische Tempo-Klassen",
        "Laesst neue Impulse mit einem rhythmischen Vielfachen der "
        "Grundgeschwindigkeit starten statt immer mit genau diesem Wert."),
    "/net/impulse/speedQuantize/jitter": (
        "Swing auf der Tempo-Klasse",
        "0 = exakt das Vielfache. Ueber 0,29 rutschen einzelne Impulse im "
        "Klang hoerbar in die Nachbarklasse."),
    "/net/impulse/speedQuantize/weight/0x5": (
        "Gewicht 0,5x",
        "Wie oft ein neuer Impuls halb so schnell startet wie die "
        "Grundgeschwindigkeit."),
    "/net/impulse/speedQuantize/weight/1x": (
        "Gewicht 1x",
        "Wie oft ein neuer Impuls genau mit der Grundgeschwindigkeit "
        "startet - der Normalfall."),
    "/net/impulse/speedQuantize/weight/2x": (
        "Gewicht 2x",
        "Wie oft ein neuer Impuls doppelt so schnell startet."),
    "/net/impulse/speedQuantize/weight/4x": (
        "Gewicht 4x",
        "Wie oft ein neuer Impuls vierfach so schnell startet."),
    "/net/impulse/speedQuantize/weight/8x": (
        "Gewicht 8x",
        "Wie oft ein neuer Impuls achtfach so schnell startet - der seltene "
        "Ausreisser, der quer durchs Netz schiesst."),

    # --- Split-Verhalten --------------------------------------------------
    "/net/impulse/split/weight/all": (
        "Gewicht: alle Zweige",
        "Gewicht dafuer, dass eine Kreuzung alle moeglichen Zweige nimmt - "
        "das Verhalten von frueher."),
    "/net/impulse/split/weight/oneLess": (
        "Gewicht: einer weniger",
        "Gewicht fuer einen Zweig weniger als moeglich. Welcher wegfaellt, "
        "wird je Aufspaltung gewuerfelt."),
    "/net/impulse/split/weight/single": (
        "Gewicht: nur einer",
        "Gewicht dafuer, dass der Impuls nur in EINE Richtung weiterlaeuft, "
        "statt sich zu teilen."),
    "/net/impulse/split/staggerEnabled": (
        "Zweige zeitversetzt starten",
        "Laesst die Zweige einer Aufspaltung nacheinander statt gleichzeitig "
        "losgehen. Der Abstand haengt am BPM-Raster, auch wenn der Sequencer "
        "aus ist."),
    # Der Versatz hatte bis 2026-08-01 EINEN festen Notenwert
    # (/net/impulse/split/staggerNoteValue); er wird jetzt je Aufspaltung aus
    # diesen fuenf Gewichten gezogen, deshalb steht hier eine Verteilung und
    # keine einzelne Adresse mehr.
    "/net/impulse/split/stagger/weight/whole": (
        "Gewicht: ganze Note",
        "Wie oft die Zweige einer Aufspaltung eine ganze Note auseinander "
        "starten - der weiteste Abstand."),
    "/net/impulse/split/stagger/weight/half": (
        "Gewicht: halbe Note",
        "Wie oft die Zweige einer Aufspaltung eine halbe Note auseinander "
        "starten."),
    "/net/impulse/split/stagger/weight/quarter": (
        "Gewicht: Viertel",
        "Wie oft die Zweige einer Aufspaltung ein Viertel auseinander "
        "starten."),
    "/net/impulse/split/stagger/weight/eighth": (
        "Gewicht: Achtel",
        "Wie oft die Zweige einer Aufspaltung ein Achtel auseinander "
        "starten."),
    "/net/impulse/split/stagger/weight/sixteenth": (
        "Gewicht: Sechzehntel",
        "Wie oft die Zweige einer Aufspaltung ein Sechzehntel auseinander "
        "starten. Gezogen wird je Aufspaltung, nicht je Zweig - die Kinder "
        "EINES Splits stehen also immer auf demselben Raster."),
    "/net/impulse/split/stagger/weight/simultaneous": (
        "Gewicht: gleichzeitig",
        "Wie oft eine Aufspaltung ohne jeden Versatz losgeht - ALLE Zweige "
        "im selben Moment, nicht nur der erste. Die einzige Klasse, die kein "
        "Notenwert ist; bei lauter Nullen gilt weiterhin Sechzehntel, nicht "
        "diese hier."),

    # --- Impuls-Farbe -----------------------------------------------------
    # Die drei Kanaele sind Birks Beispiel fuer "selbsterklaerend": ein
    # Regler namens "Rot" braucht keinen Satz darunter.
    "/net/impulse/color/r": ("Impulsfarbe Rot", None),
    "/net/impulse/color/g": ("Impulsfarbe Gruen", None),
    "/net/impulse/color/b": ("Impulsfarbe Blau", None),
    "/net/impulse/color/gamma": (
        "Farbkurve",
        "Wie steil die Helligkeit mit der Energie des Impulses steigt. Unter "
        "1 hebt schwache Impulse an, ueber 1 drueckt sie weg."),
    # Kein Regler mehr, sondern der Zwei-Zustands-Schalter der Sektion
    # "Impuls-Farbe" -- der Titel steht trotzdem hier, damit die
    # Vollstaendigkeitspruefung greift und der Rueckfall stimmt, falls die
    # Sektion einmal nicht gebaut werden kann.
    "/net/impulse/color/useSpecificColor": (
        "Farbquelle der Impulse",
        "„Spezifische Farbe“ = alle Impulse nehmen die eine Farbe darueber. "
        "„Stripe-Farben“ = jeder Impuls nimmt die Farbe seines Stripes aus "
        "den acht Slots."),
    "/net/impulse/color": (
        "Impulsfarbe",
        "Die Farbe, in der ein Impuls durchs Netz laeuft. Wirkt nur im Modus "
        "„Spezifische Farbe“."),
    # Die acht Slots des Modus "Stripe-Farben". Der Slot-Titel kommt aus einer
    # Schleife weiter unten -- acht fast gleiche Zeilen von Hand waeren acht
    # Gelegenheiten, eine Nummer zu verwechseln.
    # Die drei fadeOut-Kanaele sind KEINE Farbe, sondern Zerfallsraten je
    # Kanal. Sie stehen im UI nicht mehr als eigene Regler (die Sektion
    # "Nachleuchten" rechnet sie aus Zielfarbe und Tempo aus), behalten hier
    # aber ihren Eintrag: fuer den Rueckfall und die Vollstaendigkeitspruefung.
    "/net/impulse/fadeOut/r": (
        "Zerfallsrate Rot",
        "Anteil, den der Rotkanal der Spur je Frame behaelt. Nahe 1 = bleibt "
        "lange stehen, klein = verschwindet sofort."),
    "/net/impulse/fadeOut/g": (
        "Zerfallsrate Gruen",
        "Anteil, den der Gruenkanal der Spur je Frame behaelt. Nahe 1 = "
        "bleibt lange stehen, klein = verschwindet sofort."),
    "/net/impulse/fadeOut/b": (
        "Zerfallsrate Blau",
        "Anteil, den der Blaukanal der Spur je Frame behaelt. Nahe 1 = bleibt "
        "lange stehen, klein = verschwindet sofort."),

    # --- Zufalls-Spawns ---------------------------------------------------
    "/net/randomSpawn/enabled": (
        "Zufalls-Spawns",
        "Der Grundpuls der Installation: laesst von selbst Impulse an "
        "zufaelligen Stripes starten, ohne Trigger von aussen."),
    "/net/randomSpawn/count": (
        "Stripes je Runde",
        "Wieviele Stripes gleichzeitig einen Impuls bekommen. Hohe Werte "
        "wirken wie ein Flaechenblitz statt wie Ambient."),
    "/net/randomSpawn/interval": (
        "Abstand zwischen den Runden",
        "Sekunden von einer Spawn-Runde zur naechsten. Klein = dichtes Netz, "
        "gross = einzelne Ereignisse."),
    "/net/randomSpawn/energy": (
        "Energie je Impuls",
        "Starthelligkeit der so erzeugten Impulse - bestimmt zugleich, wie "
        "weit sie kommen, bevor sie sterben."),
    "/net/randomSpawn/directionBias": (
        "Laufrichtung",
        "Wahrscheinlichkeit fuer „vorwaerts“. 1 = alle laufen in dieselbe "
        "Richtung, 0,5 = gemischt."),
    "/net/randomSpawn/jitter": (
        "Unregelmaessigkeit des Abstands",
        "0 = exakt periodisch wie ein Metronom, 1 = der Abstand schwankt "
        "zwischen null und dem doppelten Intervall."),

    # --- Sequencer --------------------------------------------------------
    "/net/sequencer/enabled": (
        "Sequencer",
        "Not-Aus fuer alle sechs Spuren. Die Taktuhr laeuft weiter, das "
        "Wiedereinschalten haengt also nicht an der Dauer der Pause."),
    "/net/sequencer/bpm": (
        "Tempo",
        "Gemeinsames Tempo aller Spuren. Ein Wechsel aendert die Rate, nicht "
        "die Position - es gibt keinen Sprung."),
    # Muster-Eintraege: gelten fuer track0..track5 gleichermassen.
    "/net/sequencer/track#/enabled": (
        "Spur an",
        "Schaltet diese Spur ab, ohne ihre Einstellungen zu verlieren."),
    "/net/sequencer/track#/noteValue": (
        "Notenwert",
        "In welchem Abstand die Spur feuert, gemessen am gemeinsamen "
        "BPM-Raster."),
    "/net/sequencer/track#/repeatCount": (
        "Wiederholungen am selben Ursprung",
        "So viele Zyklen bleibt die Spur auf demselben Ursprungs-Stripe, "
        "bevor sie neu wuerfelt. Daraus entsteht die wiedererkennbare "
        "Melodie."),
    "/net/sequencer/track#/energy": (
        "Energie",
        "Starthelligkeit der Impulse dieser Spur."),
    "/net/sequencer/track#/swingJitter": (
        "Swing",
        "Verschiebt den Einsatz gegenueber dem exakten Raster. 0 = "
        "maschinell genau."),
    "/net/sequencer/track#/originTreeFilter": (
        "Baum-Filter",
        "Schraenkt den Ursprungs-Stripe dieser Spur auf einen der vier "
        "physischen Baeume ein. „alle“ = kein Filter."),
    "/net/sequencer/track#/originStripeOverride": (
        "Fester Ursprung",
        "-1 = die Spur wuerfelt ihren Ursprungs-Stripe. Ein Wert ab 0 nagelt "
        "sie auf diesen Stripe fest und schlaegt den Baum-Filter."),

    # --- Ruhemomente (Pause) ----------------------------------------------
    # Der Schalter hiess im UI bis 2026-08-02 schlicht „Enabled“ (letztes
    # Adresssegment, Rueckfall von label_for) -- ein Schalter ohne Aussage
    # darueber, was er einschaltet.
    "/net/pause/enabled": (
        "Ruhemomente aktiv",
        "Laesst die Installation von selbst gelegentlich verstummen. Aus "
        "heisst: es beginnt nie eine Pause."),
    "/net/pause/probability": (
        "Wahrscheinlichkeit einer Pause",
        "Chance je Pruefintervall, dass eine Pause beginnt - nicht je Takt "
        "und nicht je Minute. Bei 0,25 und einem Pruefintervall von 8 Takten "
        "beginnt im Mittel etwa alle 32 Takte eine."),
    "/net/pause/checkIntervalBars": (
        "Pruefintervall",
        "So viele Takte liegen zwischen zwei Ziehungen. Nur dann faellt "
        "ueberhaupt eine Entscheidung; laeuft gerade eine Pause, wird "
        "uebersprungen."),
    "/net/pause/lengthMinBars": (
        "Pausendauer, kuerzeste",
        "Untergrenze der Spanne, aus der die Dauer einer ausgeloesten Pause "
        "gleichverteilt gezogen wird, in Takten."),
    "/net/pause/lengthMaxBars": (
        "Pausendauer, laengste",
        "Obergrenze derselben Spanne, in Takten. Vertauschte Grenzen werden "
        "beim Ziehen getauscht, nicht als Fehler behandelt."),
    "/net/pause/affectsSequencer": (
        "Pause schaltet den Sequencer stumm",
        "Waehrend einer Pause startet der Sequencer keine neuen Impulse. "
        "Schon fliegende laufen unbeeindruckt weiter."),
    "/net/pause/affectsRandomSpawn": (
        "Pause schaltet die Zufalls-Spawns stumm",
        "Dasselbe fuer die Ambient-Ebene. Aus heisst: sie rauscht auch "
        "waehrend der Pause weiter, nur der Sequencer setzt aus."),

    # --- Knoten -----------------------------------------------------------
    "/nodes/fadeOutGamma": (
        "Ausblend-Kurve der Knoten",
        "Wie ein Knoten von „gefeuert“ nach „wartend“ verblasst. Ueber 1 = "
        "haelt lange und faellt spaet ab, unter 1 = faellt sofort."),
    "/nodes/pulseFrequency": (
        "Pulsfrequenz der Knoten",
        "Wie schnell wartende Knoten atmen, in Schlaegen pro Sekunde."),
    "/nodes/pulseFreqRandFrac": (
        "Streuung der Pulsfrequenz",
        "0 = alle Knoten atmen im Gleichtakt, 1 = jeder in seinem eigenen "
        "Tempo."),
    "/nodes/radius/fired": (
        "Radius: feuert",
        "Wieviele LEDs um die Kreuzung herum leuchten, waehrend der Knoten "
        "feuert."),
    "/nodes/radius/inactive": (
        "Radius: erloschen",
        "Groesse im stummen Zustand direkt nach dem Feuern, waehrend die "
        "Totzeit laeuft."),
    "/nodes/radius/waiting": (
        "Radius: wartend",
        "Groesse im Ruhezustand, wenn der Knoten wieder ausloesbar ist."),
    "/nodes/times/fire": (
        "Dauer des Feuerns",
        "Sekunden, die ein getroffener Knoten hell bleibt."),
    "/nodes/times/recover": (
        "Erholungsdauer",
        "Sekunden vom Erloeschen bis zurueck in den wartenden Zustand."),

    # Farbkarten der Knoten. Der Schluessel ist die BASIS der drei Adressen
    # <basis>/Hue|Sat|Bright -- eine Farbkarte traegt keine eigene Adresse.
    "/nodes/colors/central/fired": (
        "Knotenkern: feuert",
        "Farbe des Kreuzungspunkts selbst im Moment des Ausloesens."),
    "/nodes/colors/central/inactive": (
        "Knotenkern: erloschen",
        "Farbe direkt nach dem Feuern, solange die Totzeit laeuft."),
    "/nodes/colors/central/waiting": (
        "Knotenkern: wartend",
        "Farbe im Ruhezustand, wenn der Knoten wieder ausloesbar ist."),
    "/nodes/colors/outer/fired": (
        "Knotenhof: feuert",
        "Farbe der LEDs rings um den Kreuzungspunkt im Moment des "
        "Ausloesens."),
    "/nodes/colors/outer/inactive": (
        "Knotenhof: erloschen",
        "Farbe des Hofs direkt nach dem Feuern."),
    "/nodes/colors/outer/waiting": (
        "Knotenhof: wartend",
        "Farbe des Hofs im Ruhezustand."),

    # --- Trigger (Kommandos, keine Regler) --------------------------------
    "/net/activateNode": (
        "Knoten von Hand ausloesen",
        "Laesst die Kreuzung mit dieser Nummer sofort feuern - zum Pruefen "
        "einzelner Knoten."),
    "/net/activateStripe": (
        "Stripe von Hand anstossen",
        "Startet sofort einen Impuls am Anfang dieses Stripes."),

    # --- Preset-Wechsler --------------------------------------------------
    "/preset/scheduler/enabled": (
        "Preset-Wechsler",
        "Wechselt von selbst alphabetisch durch alle Presets. Ist die "
        "Song-Struktur eingeschaltet, hat die Vorrang."),
    "/preset/scheduler/interval": (
        "Wechsel-Intervall",
        "Sekunden zwischen zwei Preset-Wechseln."),

    # --- Song-Struktur ----------------------------------------------------
    "/songStructure/enabled": (
        "Dramaturgie",
        "Waehlt bei jedem faelligen Wechsel erst das naechste Energie-Level "
        "und dann ein Preset daraus. Hat Vorrang vor dem alphabetischen "
        "Preset-Wechsler."),
    # Die 16 Matrixzellen und die 8 Verweildauern kommen gleich darunter aus
    # einer Schleife -- sechzehnmal derselbe Satz von Hand waere sechzehn
    # Gelegenheiten, sich zu vertippen.
}

# Letztes Adresssegment -> Titel. Greift erst, wenn weder die exakte Adresse
# noch das Ziffern-Muster passt. Deckt die 18 Farbkomponenten der sechs
# Knoten-Farbkarten ab, ohne sie einzeln aufzufuehren.
SUFFIX_LABELS: Dict[str, Tuple[str, Optional[str]]] = {
    "Hue": ("Farbton", None),
    "Sat": ("Saettigung", None),
    "Bright": ("Helligkeit", None),
}

for _slot in range(STRIPE_COLOR_COUNT):
    ADDRESS_LABELS["%s%d" % (STRIPE_COLOR_PREFIX, _slot)] = (
        "Stripe-Slot %d" % _slot,
        None)
    for _channel, _channel_name in (("r", "Rot"), ("g", "Gruen"), ("b", "Blau")):
        ADDRESS_LABELS["%s%d/%s" % (STRIPE_COLOR_PREFIX, _slot, _channel)] = (
            "Slot %d %s" % (_slot, _channel_name), None)
del _slot, _channel, _channel_name

for _from_index, _from in enumerate(SONG_LEVEL_NAMES):
    for _to in SONG_LEVEL_NAMES:
        ADDRESS_LABELS["%smatrix/%s/%s" % (SONG_PREFIX, _from, _to)] = (
            "%s → %s" % (_from, _to),
            "Gewicht dafuer, dass auf „%s“ als naechstes „%s“ folgt. Die Zeile "
            "muss sich nicht zu 100 summieren; ein Gewicht von 0 kommt nie "
            "vor." % (_from, _to))
    # Die zwei Grenzen einer Spanne unter der Ueberschrift „Verweildauer je
    # Level“ erklaeren sich gegenseitig -- hier waere ein Satz Fuellmaterial.
    ADDRESS_LABELS["%sdwell/%s/min" % (SONG_PREFIX, _from)] = (
        "%s: kuerzeste Dauer" % _from, None)
    ADDRESS_LABELS["%sdwell/%s/max" % (SONG_PREFIX, _from)] = (
        "%s: laengste Dauer" % _from, None)
del _from_index, _from, _to

# Ziffernfolge hinter einem Buchstaben durch '#' ersetzen: aus
# /net/sequencer/track3/energy wird /net/sequencer/track#/energy. Die
# Einschraenkung "hinter einem Buchstaben" haelt Master/0/opacity/0.Impulse
# und die Kanalnummern dort unberuehrt -- die sind echte, eigene Adressen und
# haben ihren eigenen Eintrag.
_PATTERN_DIGITS = re.compile(r"(?<=[A-Za-z])\d+(?=/|$)")


def pattern_address(address: str) -> str:
    """Die Adresse mit '#' statt einer Nummer im Segment (track0 -> track#)."""
    return _PATTERN_DIGITS.sub("#", address)


def label_for(address: str) -> Tuple[Optional[str], Optional[str]]:
    """(Titel, Erklaerung) einer Adresse; (None, None) wenn unbekannt.

    Drei Stufen, spezifisch vor allgemein: exakte Adresse, Ziffern-Muster,
    letztes Segment. Ein unbekannter Parameter faellt damit auf die alte
    Darstellung zurueck (Adresssegment als Titel, keine Erklaerung) statt zu
    verschwinden -- ein neuer Regler im Sketch soll auch dann sichtbar sein,
    wenn hier niemand eine Zeile ergaenzt hat.
    """
    entry = ADDRESS_LABELS.get(address)
    if entry is None:
        entry = ADDRESS_LABELS.get(pattern_address(address))
    if entry is None:
        entry = SUFFIX_LABELS.get(address.rpartition("/")[2])
    if entry is None:
        return None, None
    return entry


def build_song_structure(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Struktur des Song-Struktur-Panels, oder None.

    None heisst: dieser imPulse-Stand kennt die Ebene nicht (aeltere
    remoteSettings.txt). Dann zeigt der Tab nichts, statt ein halbes Gitter
    anzubieten -- eine Matrix mit einer fehlenden Zelle waere schlimmer als
    keine: der Operator saehe vier Zeilen und wuesste nicht, dass eine Zelle
    fehlt.
    """
    enabled = by_address.get(SONG_PREFIX + "enabled")
    if enabled is None:
        return None
    matrix: List[List[Dict[str, Any]]] = []
    for frm in SONG_LEVEL_NAMES:
        row: List[Dict[str, Any]] = []
        for to in SONG_LEVEL_NAMES:
            param = by_address.get("%smatrix/%s/%s" % (SONG_PREFIX, frm, to))
            if param is None:
                return None
            entry = param.as_dict()
            entry["from"] = frm
            entry["to"] = to
            row.append(entry)
        matrix.append(row)
    dwell: List[Dict[str, Any]] = []
    for level in SONG_LEVEL_NAMES:
        low = by_address.get("%sdwell/%s/min" % (SONG_PREFIX, level))
        high = by_address.get("%sdwell/%s/max" % (SONG_PREFIX, level))
        if low is None or high is None:
            return None
        dwell.append({"level": level, "min": low.as_dict(), "max": high.as_dict()})
    return {
        "enabled": enabled.as_dict(),
        "levels": list(SONG_LEVEL_NAMES),
        "hints": list(SONG_LEVEL_HINTS),
        "matrix": matrix,
        "dwell": dwell,
        "gotoAddress": SONG_GOTO_ADDRESS,
    }


def song_structure_addresses(song: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die das Song-Struktur-Panel selbst rendert."""
    taken: Set[str] = set()
    if not song:
        return taken
    taken.add(song["enabled"]["address"])
    for row in song["matrix"]:
        for cell in row:
            taken.add(cell["address"])
    for entry in song["dwell"]:
        taken.add(entry["min"]["address"])
        taken.add(entry["max"]["address"])
    return taken


def read_song_state(path: str) -> Optional[Dict[str, Any]]:
    """Liest data/songStructureState.txt, oder None.

    None ist der NORMALFALL beim Start: imPulse schreibt die Datei erst beim
    ersten Levelwechsel. Eine kaputte Zeile wird uebersprungen statt zum
    Fehler gemacht -- die Anzeige ist Beiwerk, sie darf die Seite nicht
    zerlegen.
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError:
        return None
    state: Dict[str, Any] = {"level": None, "levelIndex": None, "preset": None,
                             "sinceMillis": None, "dwellSeconds": None}
    numeric = ("levelIndex", "sinceMillis", "dwellSeconds")
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or parts[0] not in state:
            continue
        if parts[0] in numeric:
            try:
                state[parts[0]] = int(parts[1].strip())
            except ValueError:
                continue
        else:
            state[parts[0]] = parts[1].strip()
    return state


# ---------------------------------------------------------------------------
# Melodie-Zuordnung
#
# Vier Werte plus ein Knopf, und der Knopf ist der Punkt: die Regler
# verstellen fuer sich GAR NICHTS. Erst /net/melody/recompute rechnet die
# Zuordnung neu, schreibt data/nodeMelody_<modus>.txt und sagt SuperCollider
# Bescheid -- alle vier Werte auf einmal, als EIN Vorgang. Getrennte Trigger
# je Regler rechneten beim Verstellen von dreien dreimal, davon zweimal mit
# einem halb gesetzten Zustand.
#
# Deshalb eine eigene Sektion und kein Platz zwischen den anderen Schiebern:
# ein Regler, dessen Bewegung nichts tut, sieht dort aus wie ein kaputter
# Regler. Und die Aktion selbst verwirft einen aufgebauten Zustand
# vollstaendig und laesst sich nicht zuruecknehmen -- dieselbe Lage wie bei
# der Taste L in den zwei Kalibriermodi, die deshalb eine Doppelbestaetigung
# verlangt.
# ---------------------------------------------------------------------------

MELODY_PREFIX = "/net/melody/"
MELODY_RECOMPUTE = "/net/melody/recompute"

# Reihenfolge und Namen spiegeln MelodyModes.ALL auf der Java-Seite und
# ~melodyModes in der .scd. Drei Kopien -- die zwei anderen sind Code, diese
# ist Beschriftung; ein Test haelt die Laenge zusammen.
MELODY_MODE_NAMES: List[str] = [
    "Dorisch", "Moll-Pentatonik", "Maqam Hijaz", "Harmonisch Moll",
    "Phrygisch", "Maqam Ajam", "Maqam Nikriz", "Maqam Saba",
]

# Reihenfolge im UI. mode zuerst, weil ein Moduswechsel der groesste Eingriff
# ist; startNode danach, weil er die Tonika setzt.
MELODY_FIELDS: List[Tuple[str, str, str]] = [
    ("mode", "Modus", "Tonvorrat und Kantenregel. Ein Wechsel ist ein "
                      "kompletter Zuordnungswechsel."),
    ("startNode", "Startknoten", "Die Tonika. Von hier aus waechst der "
                                 "BFS-Baum - ein anderer Startknoten ist "
                                 "eine andere Komposition."),
    ("rootMidiNote", "Grundton (MIDI)", "45 = A2. Verschiebt jede Note "
                                        "gleich weit."),
    ("numOctaves", "Oktaven", "Spanne, ueber die sich die Zuordnung "
                              "verteilt. Aendert die Faltungsbreite."),
]


def build_melody(by_address: Dict[str, "Parameter"]) -> Optional[Dict[str, Any]]:
    """Die vier Melodie-Parameter als eigene Sektion.

    None, wenn keiner davon im Dump steht -- dann laeuft ein aelterer
    imPulse-Stand ohne das Feature, und die Sektion bleibt weg statt leer
    dazustehen.
    """
    fields = []
    for key, label, hint in MELODY_FIELDS:
        param = by_address.get(MELODY_PREFIX + key)
        if param is None:
            continue
        entry = param.as_dict()
        entry["key"] = key
        entry["label"] = label
        entry["hint"] = hint
        fields.append(entry)
    if not fields:
        return None
    return {
        "fields": fields,
        "modeNames": list(MELODY_MODE_NAMES),
        "recomputeAddress": MELODY_RECOMPUTE,
    }


def melody_addresses(melody: Optional[Dict[str, Any]]) -> Set[str]:
    """Adressen, die die Melodie-Sektion selbst rendert.

    Ohne das stuende jeder Melodie-Regler zweimal auf der Seite: einmal in der
    Sektion mit Bestaetigungsknopf und einmal als generischer Schieber im
    Noten-Tab, der so aussieht, als taete er etwas.
    """
    if not melody:
        return set()
    return {entry["address"] for entry in melody["fields"]}


def sequencer_addresses(sequencer: Optional[Dict[str, Any]],
                        speed: Optional[Dict[str, Any]],
                        split: Optional[Dict[str, Any]] = None) -> Set[str]:
    """Adressen, die eine Spezial-Sektion selbst rendert."""
    taken: Set[str] = set()
    if sequencer:
        taken.add(sequencer["bpm"]["address"])
        taken.add(sequencer["enabled"]["address"])
        for track in sequencer["tracks"]:
            taken.add(track["enabled"]["address"])
            for field_entry in track["fields"].values():
                taken.add(field_entry["address"])
    if speed:
        taken.add(speed["enabled"]["address"])
        if speed.get("jitter"):
            taken.add(speed["jitter"]["address"])
        # Der Grundregler wird von der Sektion selbst gerendert -- ohne diese
        # Zeile stuende /net/impulse/speed zweimal auf der Seite: einmal ueber
        # den Klassen, die ihn vervielfachen, und einmal als generischer
        # Schieber im Impuls-Verhalten.
        if speed.get("base"):
            taken.add(speed["base"]["address"])
        for weight in speed["weights"]:
            taken.add(weight["address"])
    if split:
        entry = split.get("staggerEnabled")
        if entry:
            taken.add(entry["address"])
        for weight in split["weights"]:
            taken.add(weight["address"])
        for weight in split.get("staggerWeights", []):
            taken.add(weight["address"])
    return taken


# ---------------------------------------------------------------------------
# Tabs
#
# Acht Themen-Tabs statt einer langen Liste. Die Zuordnung steht HIER und
# nicht in app.js, weil sie eine inhaltliche Entscheidung ist und hier
# pruefbar bleibt: test_webui.py stellt sicher, dass jede Adresse aus
# remoteSettings.txt genau einem Tab gehoert. Im JS waere das nur mit einem
# jsdom-Test pruefbar, den dieses Projekt bewusst nicht hat.
#
# "primary" ist die kuratierte Auswahl, die oben im Tab steht; alles andere
# desselben Tabs landet im eingeklappten "Erweitert"-Bereich. Faustregel des
# Briefs: was Birk live tatsaechlich anfasst, gehoert nach oben.
# ---------------------------------------------------------------------------

TAB_MIXER = "mixer"
TAB_SOUND = "sound"
TAB_SPAWN = "spawn"
TAB_NOTES = "noten"
TAB_SCALE = "tonleiter"
TAB_PHYSICS = "physik"
TAB_COLORS = "farben"
TAB_SONG = "song"

TAB_TITLES: List[Tuple[str, str]] = [
    (TAB_MIXER, "Mixer"),
    (TAB_SOUND, "Sound Design"),
    (TAB_SPAWN, "Spawn-Verhalten"),
    (TAB_NOTES, "Noten-Verhalten"),
    # Direkt hinter dem Noten-Verhalten: die Melodie-Zuordnung entscheidet,
    # WELCHE Note ein Knoten bekommt, das Noten-Verhalten daneben, WANN und
    # WIE sie gespielt wird -- thematisch benachbart, deshalb auch im UI.
    # Ein eigener Tab und keine Sektion IM Noten-Tab, weil hier als einziger
    # Stelle im UI das Verstellen eines Feldes nichts sendet: zwischen
    # Reglern, die sofort wirken, waeren die vier Felder eine Falle.
    (TAB_SCALE, "Tonleiter"),
    (TAB_PHYSICS, "Impuls-Verhalten"),
    # Haengt hinten an: die Kern-Reihenfolge der fuenf Themen-Tabs bleibt,
    # wie sie ist. Farbe ist Gestaltung und wird am Stueck angefasst, nicht
    # zwischen Physik-Reglern verstreut.
    (TAB_COLORS, "Farben"),
    # Steht als letzter: die Song-Struktur ist die Ebene UEBER allen anderen
    # Tabs -- sie waehlt aus, welcher Wertesatz gerade gefahren wird. Ein
    # Platz vorn wuerde nahelegen, dass man hier anfaengt; man faengt aber mit
    # den Presets an, aus denen sie waehlt.
    (TAB_SONG, "Song-Struktur"),
]

# Reihenfolge zaehlt: die erste passende Regel gewinnt.
TAB_RULES: List[Tuple[str, str]] = [
    ("/songStructure/", TAB_SONG),
    ("/master/", TAB_MIXER),
    ("Master/", TAB_MIXER),
    # speedQuantize VOR /net/impulse/, sonst faengt die Physik-Regel es ab.
    ("/net/impulse/speedQuantize/", TAB_NOTES),
    # Dieselbe Falle bei den Farben: /net/impulse/color/* wuerde sonst von
    # der Physik-Regel eingesammelt, /nodes/colors/* von der Node-Regel.
    # fadeOut steht bewusst dabei -- es ist der Nachleucht-Farbfaktor des
    # Impulses und teilt sich mit color/* ohnehin schon eine Gruppe
    # (SPLIT_GROUP_PREFIXES); eine Gruppe geht als GANZES in einen Tab.
    ("/net/impulse/color/", TAB_COLORS),
    ("/net/impulse/fadeOut/", TAB_COLORS),
    # Rueckfall: normalerweise rendert die Sektion "Impuls-Farbe" die acht
    # Slots selbst und nimmt ihre Adressen aus dem generischen Rendering.
    # Kann sie nicht gebaut werden (unvollstaendiger Dump), landen die 24
    # Regler wenigstens im richtigen Tab statt bei der Physik.
    (STRIPE_COLOR_PREFIX, TAB_COLORS),
    ("/nodes/colors/", TAB_COLORS),
    # Das Split-Verhalten aus demselben Grund, und in denselben Tab: der
    # zeitliche Versatz der Zweige haengt am BPM-Raster, genau wie die
    # Speed-Klassen. Die zwei Haelften eines Features (wieviele Zweige, wie
    # weit auseinander) gehoeren ausserdem auf denselben Tab -- die
    # Zweig-Gewichte allein unter "Impuls-Verhalten" waeren ein Regler ohne
    # seinen Partner.
    #
    # Der Schraegstrich am Ende ist nicht Kosmetik: ohne ihn zoege die Regel
    # auch /net/impulse/splitSpeedJitter und /net/impulse/splitLifetimeJitter
    # hierher, die zur Physik gehoeren und dort schon kuratiert sind.
    ("/net/impulse/split/", TAB_NOTES),
    # Die Melodie-Zuordnung hat seit 2026-08-02 einen eigenen Tab, und ihre
    # vier Adressen rendert die Sektion dort selbst (melody_addresses()).
    # Diese Regel ist damit nur noch der Rueckfall fuer eine SPAETER
    # dazukommende /net/melody/-Adresse, die MELODY_FIELDS nicht kennt -- und
    # die gehoert neben die Sektion auf den Tonleiter-Tab, nicht ins
    # Noten-Verhalten. Bis 2026-08-02 zeigte die Regel noch auf TAB_NOTES, wo
    # die Sektion frueher stand.
    ("/net/melody/", TAB_SCALE),
    ("/net/sequencer/", TAB_SPAWN),
    ("/net/randomSpawn/", TAB_SPAWN),
    ("/net/pause/", TAB_SPAWN),
    ("/net/activate", TAB_SPAWN),
    ("/net/impulse/", TAB_PHYSICS),
    ("/nodes/", TAB_PHYSICS),
]

# Adressen, die EXAKT (nicht als Praefix) einem Tab gehoeren. Wird vor
# TAB_RULES nachgeschlagen.
#
# Gebraucht fuer genau einen Fall: /net/impulse/speed steht seit 2026-08-02 in
# der Speed-Klassen-Sektion des Noten-Tabs, weil die Klassen 0,5x..8x nichts
# anderes tun als ihn zu vervielfachen -- ein Tabwechsel zwischen dem Wert und
# seinen Vielfachen ist genau die Trennung, die den baseSpeed-Fehler ueberhaupt
# erst plausibel gemacht hat.
#
# Als PRAEFIX ginge das nicht: "/net/impulse/speed" faengt auch
# /net/impulse/speed/randomize/* (der Sinus-Randomizer, gehoert zur Physik --
# er teilt sich eine Gruppe mit dem Lifetime-Randomizer und kann nicht allein
# umziehen) und /net/impulse/speedQuantize/* (steht schon eine Zeile weiter
# oben). Deshalb eine eigene Tabelle mit exakter Gleichheit statt einer
# sechsten Praefix-Regel.
TAB_EXACT: Dict[str, str] = {
    SPEED_ADDRESS: TAB_NOTES,
}

# Tabs, deren Gruppen direkt sichtbar sind statt hinter "Erweitert".
# Gedacht fuer Tabs ohne kuratierte Auswahl: eine Farbkarte traegt keine
# eigene Adresse (kind == "color", drei Adressen darunter), TAB_PRIMARY kann
# sie also gar nicht nach oben holen -- der Farben-Tab bestuende sonst
# ausschliesslich aus einem zugeklappten <details>.
TAB_EXPANDED = {TAB_COLORS}

# Kuratierte SC-Parameter je Tab (Namen, nicht Adressen). Der Rest desselben
# Tabs landet im Erweitert-Bereich.
SC_PRIMARY: Dict[str, List[str]] = {
    TAB_MIXER: ["masterVolume", "bellVolume", "droneVolume", "tailVolume",
                "reverbMix"],
    TAB_SOUND: ["travelMix", "brightness", "detune", "treeBiasAmount",
                "tailShimmerAmp", "tailWhooshAmp", "tailFmglideAmp",
                "tailGranularAmp", "tailSubglowAmp",
                "tailOrbitRadius", "tailOrbitSpeed",
                "travelRq", "travelGrainRatio"],
    TAB_NOTES: ["melodyMode", "melodyRootMidiNote", "melodyNumOctaves"],
}

# Kuratierte Regler je Tab, in dieser Reihenfolge. Adressen, die es in
# diesem Dump nicht gibt, werden still uebergangen -- die Liste darf einem
# aelteren imPulse-Stand vorauseilen.
TAB_PRIMARY: Dict[str, List[str]] = {
    TAB_MIXER: [
        "/master/level",
        "Master/0/opacity/0.Impulse",
        "Master/1/opacity/1.Nodes",
    ],
    TAB_SOUND: [],
    # Die zwei /net/pause/-Adressen standen hier bis 2026-08-02. Seit die
    # Sektion "Ruhemomente" sie selbst rendert (siehe pause_addresses()),
    # stuenden sie zweimal auf der Seite -- einmal im Panel und einmal als
    # kuratierter Schieber darunter.
    TAB_SPAWN: [
        "/net/randomSpawn/enabled",
        "/net/randomSpawn/interval",
        "/net/randomSpawn/energy",
        "/net/randomSpawn/count",
    ],
    TAB_NOTES: [],
    # Leer wie TAB_SONG: der Tonleiter-Tab besteht ausschliesslich aus der
    # Melodie-Sektion, die ihre vier Adressen selbst rendert (siehe
    # melody_addresses()). Stuende hier eine davon, waere sie zweimal auf der
    # Seite -- einmal mit Bestaetigungsknopf und einmal als Schieber, der so
    # aussieht, als taete er etwas.
    TAB_SCALE: [],
    TAB_PHYSICS: [
        # /net/impulse/speed steht seit 2026-08-02 NICHT mehr hier, sondern in
        # der Speed-Klassen-Sektion des Noten-Tabs (build_speed_classes). Er
        # stuende sonst zweimal auf der Seite -- die Sektion rendert ihn
        # selbst, und ein Eintrag hier waere still wirkungslos, weil
        # sequencer_addresses() ihn aus dem generischen Rendering nimmt.
        "/net/impulse/lifetime",
        "/net/impulse/nodeDeadTime",
        "/net/impulse/splitSpeedJitter",
        "/net/impulse/splitLifetimeJitter",
    ],
    # Leer: der Song-Struktur-Tab besteht ausschliesslich aus seinem eigenen
    # Panel. Stuende hier eine Adresse, waere sie zweimal auf der Seite.
    TAB_SONG: [],
}


# ---------------------------------------------------------------------------
# SuperCollider-Sound-Parameter
#
# Sie laufen NICHT durch remoteSettings.txt -- das ist die Parameterliste von
# imPulse. SuperCollider hat seine eigene Registry (~registerParam in
# supercollider/klangnetz_bells.scd) und einen eigenen Port.
#
# Diese Tabelle ist eine HANDGEPFLEGTE Kopie davon. Sie kann veralten: wer in
# der .scd einen Parameter ergaenzt, ergaenzt ihn auch hier. Die Alternative
# waere, die .scd zu parsen -- dafuer muesste der Server sclang-Syntax lesen,
# und ein Parser, der bei der naechsten Umformatierung still das Falsche
# liefert, ist schlechter als eine Liste, deren Pflege sichtbar ist.
# test_webui.py prueft wenigstens, dass jeder Name hier in der .scd vorkommt.
#
# Es gibt KEINEN Rueckkanal von SuperCollider: die Werte hier sind die
# Defaults aus der .scd, nicht der Live-Zustand. Laeuft sclang nicht, geht die
# Nachricht ins Leere - fire-and-forget, wie /sc/preset/load.
# ---------------------------------------------------------------------------

SC_OSC_PORT = 8002
SC_PARAM_PREFIX = "/klangnetz/param/"
#
# "label" ist der sprechende Titel im UI, "description" die Zeile darunter --
# dieselbe Aufteilung wie ADDRESS_LABELS fuer die imPulse-Parameter. Der Name
# aus der Registry ("travelOctavesPerStep") bleibt als Adresszeile sichtbar:
# er ist die Kennung, unter der der Parameter per OSC ansprechbar ist.

# ---------------------------------------------------------------------------
# Vierkanal-Mitschnitt (SuperCollider schreibt die WAV-Datei, nicht dieses UI)
#
# Die drei Kommandos gehen an denselben Port wie die Sound-Parameter (8002),
# tragen aber KEIN Argument -- es sind Befehle, keine Werte.
#
# Als einziges Feature im ganzen UI gibt es hier einen RUECKKANAL: sclang
# schickt bei jedem Start/Stop und auf /klangnetz/record/query eine
# /klangnetz/record/status-Meldung an RECORD_STATUS_PORT. Ohne den waere der
# Knopf blind -- er zeigte, was zuletzt geklickt wurde, nicht was wirklich
# laeuft, und eine per Skript gestartete Aufnahme bliebe unsichtbar.
# ---------------------------------------------------------------------------

RECORD_PREFIX = "/klangnetz/record/"
RECORD_START = RECORD_PREFIX + "start"
RECORD_STOP = RECORD_PREFIX + "stop"
RECORD_TOGGLE = RECORD_PREFIX + "toggle"
RECORD_QUERY = RECORD_PREFIX + "query"
RECORD_STATUS = RECORD_PREFIX + "status"

# Eigener Port, nicht 8001 (imPulse) und nicht 8002 (dort hoert sclang
# selbst). Muss zu ~recordStatusPort in klangnetz_bells.scd passen.
DEFAULT_RECORD_STATUS_PORT = 8003
# Nur auf dem Loopback: die Meldung kommt von sclang auf derselben Maschine,
# und ein zusaetzlich nach aussen offener UDP-Port waere ein Angebot ohne
# Abnehmer (und unter Windows eine weitere Firewall-Nachfrage).
DEFAULT_RECORD_STATUS_HOST = "127.0.0.1"

# So lange wartet ein Record-Endpoint auf die Antwort von sclang, bevor er
# "unbekannt" meldet. Beides laeuft auf demselben Rechner ueber Loopback --
# kommt in 400 ms nichts, laeuft sclang nicht (oder auf einem anderen Port).
# Lieber ein ehrliches "kein Kontakt" als ein Knopf, der Erfolg behauptet.
RECORD_STATUS_TIMEOUT_S = 0.4
RECORD_STATUS_POLL_S = 0.01

SC_PARAMS: List[Dict[str, Any]] = [
    {"name": "masterVolume", "tab": TAB_MIXER, "default": 1.0, "min": 0.0, "max": 1.5,
     "group": "Master", "label": "Gesamtlautstaerke", "description":
     "Der Show-Fader des Klangs, hinter dem Panning und vor dem Limiter. "
     "Wirkt auf alles."},
    {"name": "bellVolume", "tab": TAB_MIXER, "default": 1.0, "min": 0.0, "max": 1.5,
     "group": "Master", "label": "Lautstaerke der Glocken", "description":
     "Nur die Toene, die an den Kreuzungen anschlagen. Wirkt erst auf den "
     "naechsten Ton - eine Glocke wird nicht mittendrin leiser."},
    # Default 0.0 wie in der .scd (~registerParam \droneVolume): die Tondrohne
    # ist ab Werk stumm, sie wird vor Ort hochgezogen. Die 1.0, die hier bis
    # zum Merge der Bell-Tails stand, war eine Abweichung von der Registry.
    {"name": "droneVolume", "tab": TAB_MIXER, "default": 0.0, "min": 0.0, "max": 1.5,
     "group": "Master", "label": "Lautstaerke der Impuls-Drohnen", "description":
     "Nur die liegenden Klaenge, die den reisenden Impulsen folgen. Wirkt "
     "sofort, auch auf schon klingende Stimmen."},
    {"name": "tailVolume", "tab": TAB_MIXER, "default": 0.5, "min": 0.0, "max": 1.5,
     "group": "Master", "label": "Lautstaerke der Schweife", "description":
     "Nur die fuenf Schweife, die bei jedem Knotentreffer zusaetzlich zur "
     "Glocke klingen. 0 spart auch Stimmen - der Synth entsteht dann gar "
     "nicht erst."},
    {"name": "reverbMix", "tab": TAB_MIXER, "default": 0.35, "min": 0.0, "max": 1.0,
     "group": "Master", "label": "Hallanteil", "description":
     "Trocken zu nass. Der Hall sitzt hinter der Ortung, verwischt sie also "
     "nicht."},
    {"name": "reverbRoom", "tab": TAB_MIXER, "default": 0.5, "min": 0.0, "max": 1.0,
     "group": "Master", "label": "Raumgroesse", "description":
     "Wie gross der Raum klingt, in dem die Toene stehen - laengerer Schweif "
     "bei hohen Werten."},
    {"name": "reverbDamp", "tab": TAB_MIXER, "default": 0.5, "min": 0.0, "max": 1.0,
     "group": "Master", "label": "Hoehendaempfung im Hall", "description":
     "Wie schnell die Hoehen im Hallschweif wegsterben. Hoch = dunkler, "
     "weicher Nachklang."},
    {"name": "panSharpness", "tab": TAB_SOUND, "default": 1.0, "min": 0.1, "max": 8.0,
     "group": "Master", "label": "Schaerfe der Ortung", "description":
     "Wie eng ein Klang auf eine Box gezogen wird. 1 = Referenz; hoch = "
     "punktgenau, niedrig = im Raum verteilt."},
    {"name": "brightness", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 2.0,
     "group": "Glocke", "label": "Glanz der Glocke", "description":
     "Pegel der oberen vier Teiltoene. Hoch = hell und glasig, niedrig = "
     "dumpf und holzig."},
    {"name": "detune", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Glocke", "label": "Metallischer Charakter", "description":
     "Verstimmung der Teiltoene gegeneinander. 1 = metallisch und schwebend, "
     "0 = rein harmonisch wie eine Orgel."},
    {"name": "treeBiasAmount", "tab": TAB_SOUND, "default": 0.6, "min": 0.0, "max": 1.0,
     "group": "Glocke", "label": "Baum-Klangbias", "description":
     "Wie stark sich Tonwahl und Klangfarbe zwischen den vier Ursprungs-"
     "Baeumen (vorn/hinten/rechts/links) unterscheiden. 0 = aus, ueberall "
     "gleich. Ersetzt seit 2026-08-01 den frueheren regionBiasAmount."},
    # Die drei Melodie-Parameter der SC-Seite. melodyStartNode steht bewusst
    # NICHT hier: SuperCollider rechnet nichts neu, der Regler waere dort
    # wirkungslos. Er liegt in imPulse (/net/melody/startNode) und wird von
    # der eigenen Melodie-Sektion bedient, nicht von dieser Tabelle.
    {"name": "melodyMode", "tab": TAB_NOTES, "default": 4.0, "min": 0.0, "max": 7.0,
     "group": "Melodie", "label": "Geladener Modus", "description":
     "Welche Zuordnungsdatei geladen ist: 0 Dorisch, 1 Pentatonik, 2 Hijaz, "
     "3 Harmonisch Moll, 4 Phrygisch, 5 Ajam, 6 Nikriz, 7 Saba."},
    {"name": "melodyRootMidiNote", "tab": TAB_NOTES, "default": 45.0, "min": 24.0, "max": 84.0,
     "group": "Melodie", "label": "Grundton (MIDI)", "description":
     "Grundton der Zuordnung. Beim Laden gewinnt der Kopf der Melodie-Datei; "
     "danach ist das eine saubere Live-Transposition."},
    {"name": "melodyNumOctaves", "tab": TAB_NOTES, "default": 3.0, "min": 1.0, "max": 6.0,
     "group": "Melodie", "label": "Oktavspanne", "description":
     "Aendert den Modulo-Teiler der Faltung - eine schon geladene Zuordnung "
     "laesst sich damit NICHT nachfalten, dafuer in imPulse neu berechnen."},
    {"name": "droneLpfMult", "tab": TAB_SOUND, "default": 6.0, "min": 1.0, "max": 12.0,
     "group": "Travel-Sound", "label": "Klangfarbe der Drohne", "description":
     "Filter auf der Tonschicht einer Impuls-Drohne. Niedrig = dumpf und "
     "zurueckhaltend, hoch = praesent."},
    {"name": "travelMix", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 1.0,
     "group": "Travel-Sound", "label": "Wind statt Ton", "description":
     "Blendet die Drohne vom liegenden Ton zur rieselnden Windschicht ueber. "
     "0 = kein Travel-Sound; nur ueber diese Schicht ist die Tempo-Klasse "
     "eines Impulses hoerbar."},
    {"name": "travelRq", "tab": TAB_SOUND, "default": 0.35, "min": 0.02, "max": 1.0,
     "group": "Travel-Sound", "label": "Koernerdauer im Wind", "description":
     "Wie lang ein einzelnes Rauschkorn klingt (Anteil von 20 ms). Klein = "
     "sandig und kleinteilig, gross = fliessend."},
    {"name": "travelGrainRatio", "tab": TAB_SOUND, "default": 0.125, "min": 0.01, "max": 2.0,
     "group": "Travel-Sound", "label": "Dichte des Windes", "description":
     "Koerner je Sekunde, gerechnet als Vielfaches der Windfrequenz - dadurch "
     "rieseln schnelle Impulse dichter als langsame."},
    {"name": "travelAmpScale", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 2.0,
     "group": "Travel-Sound", "label": "Pegel der Windschicht", "description":
     "Nur das Rauschen, ohne die Tonschicht. Zum Abgleichen, wenn der "
     "Uebergang beim Ueberblenden einen Sprung macht."},
    {"name": "travelFreqBase", "tab": TAB_SOUND, "default": 400.0, "min": 50.0, "max": 4000.0,
     "group": "Travel-Sound", "label": "Wind-Tonlage bei 1x", "description":
     "Grenzfrequenz des Windes fuer einen Impuls der Klasse 1x. Alle anderen "
     "Klassen liegen oktavweise darueber und darunter."},
    {"name": "travelSpeedRef", "tab": TAB_SOUND, "default": 16.0, "min": 1.0, "max": 1500.0,
     "group": "Travel-Sound", "label": "Bezugsgeschwindigkeit", "description":
     "Welche Geschwindigkeit in LEDs/s als 1x gilt. Sollte zur "
     "Grundgeschwindigkeit im Impuls-Tab passen, sonst liegt der ganze "
     "Wind zu hoch oder zu tief."},
    {"name": "travelOctavesPerStep", "tab": TAB_SOUND, "default": 1.0, "min": 0.25, "max": 3.0,
     "group": "Travel-Sound", "label": "Spreizung der Tempo-Klassen",
     "description":
     "Oktaven je Verdopplung der Geschwindigkeit. Groesser = die Klassen "
     "liegen klanglich weiter auseinander und sind leichter zu "
     "unterscheiden."},
    {"name": "travelSnap", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Travel-Sound", "label": "Auf Tempo-Klasse rasten",
     "description":
     "Rundet die Wind-Tonlage auf die Klasse, damit jeder Impuls einer Klasse "
     "auf derselben Hoehe zischt und der Swing sie nicht verschmiert."},
    {"name": "travelFreqMin", "tab": TAB_SOUND, "default": 80.0, "min": 20.0, "max": 2000.0,
     "group": "Travel-Sound", "label": "Wind: tiefste Tonlage", "description":
     "Harte Untergrenze - darunter faellt der Wind nie, egal wie langsam ein "
     "Impuls reist."},
    {"name": "travelFreqMax", "tab": TAB_SOUND, "default": 6000.0, "min": 200.0, "max": 16000.0,
     "group": "Travel-Sound", "label": "Wind: hoechste Tonlage", "description":
     "Harte Obergrenze - darueber steigt der Wind nie, egal wie schnell ein "
     "Impuls reist."},
    # ---- Bell-Tails: Orbit (fuenf globale Regler fuer alle fuenf Tails) ----
    {"name": "tailOrbitRadius", "tab": TAB_SOUND, "default": 0.25, "min": 0.0, "max": 2.0,
     "group": "Tail-Orbit", "label": "Radius der Kreisbahn", "description":
     "Wie weit ein Schweif um den Knoten kreist, der ihn ausgeloest hat. "
     "Normiert: 1.0 = Abstand zur Box, 0 = keine Rotation."},
    {"name": "tailOrbitSpeed", "tab": TAB_SOUND, "default": 0.5, "min": 0.0, "max": 4.0,
     "group": "Tail-Orbit", "label": "Tempo der Kreisbahn", "description":
     "Umdrehungen je Sekunde bei voller Lautstaerke. Die Bewegung klingt mit "
     "dem Schweif aus, eine ganze Umdrehung ueber 4 s braucht rund 1.08."},
    {"name": "tailOrbitEnvExp", "tab": TAB_SOUND, "default": 1.0, "min": 0.25, "max": 4.0,
     "group": "Tail-Orbit", "label": "Nachlauf der Kreisbahn", "description":
     "Wie eng die Rotation am Lautstaerkeverlauf haengt. Unter 1 dreht der "
     "Schweif bis in den Ausklang hinein weiter, ueber 1 steht er frueher."},
    {"name": "tailOrbitDirLock", "tab": TAB_SOUND, "default": 0.0, "min": -1.0, "max": 1.0,
     "group": "Tail-Orbit", "label": "Drehrichtung festhalten", "description":
     "0 = jeder Schweif wuerfelt links oder rechts herum, so laeuft der "
     "Betrieb. +1 oder -1 erzwingen eine Richtung, zum Pruefen der Bewegung."},
    {"name": "tailOrbitMinRadius", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 0.5,
     "group": "Tail-Orbit", "label": "Kleinster Radius", "description":
     "Untergrenze der Kreisbahn. Ueber 0 ist der Radius kein Aus-Schalter "
     "mehr - es kreist dann immer etwas."},
    # ---- Bell-Tails: Huellkurve und Pegel je Schicht ----------------------
    # Alle fuenf nach demselben Muster: Attack/Decay/Sustain/Release/Amp.
    # Sustain ist ein PLATEAU-PEGEL, kein gehaltenes Segment -- die Tails sind
    # One-Shots wie die Glocke (Begruendung in der .scd).
    #
    # Die Titel tragen den Namen ihrer Schicht ("Schimmer: Einschwingen"),
    # obwohl er auch in der Gruppenueberschrift steht: die Titel muessen ueber
    # die ganze Tabelle eindeutig sein, sonst stuenden fuenfmal "Einschwingen"
    # nebeneinander (test_labels_are_unique).
    {"name": "tailShimmerAttack", "tab": TAB_SOUND, "default": 0.02, "min": 0.001, "max": 2.0,
     "group": "Tail 1 Glass Shimmer", "label": "Schimmer: Einschwingen",
     "description": "Einschwingzeit in s."},
    {"name": "tailShimmerDecay", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 4.0,
     "group": "Tail 1 Glass Shimmer", "label": "Schimmer: Abfall",
     "description": "Abfall zum Halteplateau. 0 = kein Knick."},
    {"name": "tailShimmerSustain", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Tail 1 Glass Shimmer", "label": "Schimmer: Halteplateau",
     "description": "Pegel nach dem Abfall, gehalten bis zum Ausklang."},
    {"name": "tailShimmerRelease", "tab": TAB_SOUND, "default": 4.5, "min": 0.2, "max": 12.0,
     "group": "Tail 1 Glass Shimmer", "label": "Schimmer: Ausklingen",
     "description": "Ausklingzeit in s."},
    {"name": "tailShimmerAmp", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 4.0,
     "group": "Tail 1 Glass Shimmer", "label": "Schimmer: Pegel",
     "description": "Pegel dieser Schicht. 0 = der Synth entsteht gar nicht erst."},
    {"name": "tailWhooshAttack", "tab": TAB_SOUND, "default": 0.02, "min": 0.001, "max": 2.0,
     "group": "Tail 2 Digital Whoosh", "label": "Whoosh: Einschwingen",
     "description": "Einschwingzeit in s."},
    {"name": "tailWhooshDecay", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 4.0,
     "group": "Tail 2 Digital Whoosh", "label": "Whoosh: Abfall",
     "description": "Abfall zum Halteplateau. 0 = kein Knick."},
    {"name": "tailWhooshSustain", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Tail 2 Digital Whoosh", "label": "Whoosh: Halteplateau",
     "description": "Pegel nach dem Abfall, gehalten bis zum Ausklang."},
    {"name": "tailWhooshRelease", "tab": TAB_SOUND, "default": 3.2, "min": 0.2, "max": 12.0,
     "group": "Tail 2 Digital Whoosh", "label": "Whoosh: Ausklingen",
     "description": "Ausklingzeit UND Laufzeit des Bandpass-Sweeps in s."},
    {"name": "tailWhooshAmp", "tab": TAB_SOUND, "default": 3.0, "min": 0.0, "max": 4.0,
     "group": "Tail 2 Digital Whoosh", "label": "Whoosh: Pegel", "description":
     "Pegel dieser Schicht. Default 3.0, weil gefiltertes Rauschen gemessen "
     "rund 10 dB leiser ist als die anderen vier."},
    {"name": "tailFmglideAttack", "tab": TAB_SOUND, "default": 0.7, "min": 0.001, "max": 2.0,
     "group": "Tail 3 FM-Glide", "label": "FM-Glide: Einschwingen", "description":
     "Einschwingzeit in s. Bewusst lang -- Teil des abgenommenen Klangs."},
    {"name": "tailFmglideDecay", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 4.0,
     "group": "Tail 3 FM-Glide", "label": "FM-Glide: Abfall",
     "description": "Abfall zum Halteplateau. 0 = kein Knick."},
    {"name": "tailFmglideSustain", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Tail 3 FM-Glide", "label": "FM-Glide: Halteplateau",
     "description": "Pegel nach dem Abfall, gehalten bis zum Ausklang."},
    {"name": "tailFmglideRelease", "tab": TAB_SOUND, "default": 3.8, "min": 0.2, "max": 12.0,
     "group": "Tail 3 FM-Glide", "label": "FM-Glide: Ausklingen", "description":
     "Ausklingzeit UND Laufzeit von Tonhoehen-Glide und FM-Index in s."},
    {"name": "tailFmglideAmp", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 4.0,
     "group": "Tail 3 FM-Glide", "label": "FM-Glide: Pegel",
     "description": "Pegel dieser Schicht. 0 = der Synth entsteht gar nicht erst."},
    {"name": "tailGranularAttack", "tab": TAB_SOUND, "default": 0.7, "min": 0.001, "max": 2.0,
     "group": "Tail 4 Granularer Zerfall", "label": "Granular: Einschwingen",
     "description":
     "Einschwingzeit in s. Bewusst lang -- Teil des abgenommenen Klangs."},
    {"name": "tailGranularDecay", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 4.0,
     "group": "Tail 4 Granularer Zerfall", "label": "Granular: Abfall",
     "description": "Abfall zum Halteplateau. 0 = kein Knick."},
    {"name": "tailGranularSustain", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Tail 4 Granularer Zerfall", "label": "Granular: Halteplateau",
     "description": "Pegel nach dem Abfall, gehalten bis zum Ausklang."},
    {"name": "tailGranularRelease", "tab": TAB_SOUND, "default": 3.5, "min": 0.2, "max": 12.0,
     "group": "Tail 4 Granularer Zerfall", "label": "Granular: Ausklingen",
     "description":
     "Ausklingzeit UND Zeit, in der die Koernerdichte von 35 auf 5 je s faellt."},
    {"name": "tailGranularAmp", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 4.0,
     "group": "Tail 4 Granularer Zerfall", "label": "Granular: Pegel",
     "description": "Pegel dieser Schicht. 0 = der Synth entsteht gar nicht erst."},
    {"name": "tailSubglowAttack", "tab": TAB_SOUND, "default": 0.03, "min": 0.001, "max": 2.0,
     "group": "Tail 5 Sub-Glow", "label": "Sub-Glow: Einschwingen",
     "description": "Einschwingzeit in s."},
    {"name": "tailSubglowDecay", "tab": TAB_SOUND, "default": 0.0, "min": 0.0, "max": 4.0,
     "group": "Tail 5 Sub-Glow", "label": "Sub-Glow: Abfall",
     "description": "Abfall zum Halteplateau. 0 = kein Knick."},
    {"name": "tailSubglowSustain", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 1.0,
     "group": "Tail 5 Sub-Glow", "label": "Sub-Glow: Halteplateau",
     "description": "Pegel nach dem Abfall, gehalten bis zum Ausklang."},
    {"name": "tailSubglowRelease", "tab": TAB_SOUND, "default": 4.2, "min": 0.2, "max": 12.0,
     "group": "Tail 5 Sub-Glow", "label": "Sub-Glow: Ausklingen",
     "description": "Ausklingzeit in s."},
    {"name": "tailSubglowAmp", "tab": TAB_SOUND, "default": 1.0, "min": 0.0, "max": 4.0,
     "group": "Tail 5 Sub-Glow", "label": "Sub-Glow: Pegel",
     "description": "Pegel dieser Schicht. 0 = der Synth entsteht gar nicht erst."},
]


def tab_for_address(address: str) -> str:
    """Der Tab einer imPulse-Adresse. Unbekanntes landet in der Physik.

    Der Rueckfall ist bewusst ein echter Tab und nicht ein sechster
    "Sonstiges": ein neuer Parameter soll sichtbar sein, auch wenn hier
    niemand eine Regel dafuer ergaenzt hat.

    Exakte Treffer (TAB_EXACT) gehen vor den Praefix-Regeln -- eine Adresse,
    die zufaellig der Anfang einer anderen ist, laesst sich anders nicht
    einzeln zuordnen.
    """
    exact = TAB_EXACT.get(address)
    if exact is not None:
        return exact
    for prefix, tab in TAB_RULES:
        if address.startswith(prefix):
            return tab
    return TAB_PHYSICS


def build_tabs(groups: List[Dict[str, Any]],
               sequencer: Optional[Dict[str, Any]],
               speed: Optional[Dict[str, Any]],
               split: Optional[Dict[str, Any]] = None,
               song: Optional[Dict[str, Any]] = None,
               colors: Optional[Dict[str, Any]] = None,
               fade: Optional[Dict[str, Any]] = None,
               melody: Optional[Dict[str, Any]] = None,
               pause: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    """Verteilt Gruppen, Spezial-Sektionen und SC-Parameter auf die Tabs.

    Eine Gruppe geht als GANZES in einen Tab (bestimmt von ihrem ersten
    Regler) -- die Gruppenschluessel sind selbst Adress-Praefixe, eine Gruppe
    kann also nicht ueber zwei Tabs zerfallen. Die kuratierten Regler werden
    aus ihren Gruppen HERAUSGELOEST, damit kein Parameter zweimal auf der
    Seite steht.
    """
    primary_all = {addr for addrs in TAB_PRIMARY.values() for addr in addrs}
    by_tab: Dict[str, Dict[str, Any]] = {}
    for tab_id, title in TAB_TITLES:
        by_tab[tab_id] = {
            "id": tab_id,
            "title": title,
            "sections": [],
            "primary": [],
            "groups": [],
            "scParams": [],
            "expanded": tab_id in TAB_EXPANDED,
        }

    # Spezial-Sektionen
    #
    # Die Presets stehen GANZ OBEN im Mixer-Tab und brauchen wie Palette und
    # Mitschnitt keine Adresse aus remoteSettings.txt -- sie sind deshalb
    # bedingungslos da. Oben, weil ein Preset der erste Griff ist: man waehlt
    # eine Szene und stellt danach daran. Bis 2026-08-02 stand die Sektion
    # fest ueber der Tab-Leiste; dort zwang sie jeden Nutzer, an ihr vorbei zu
    # scrollen, bevor ueberhaupt ein Regler sichtbar wurde.
    by_tab[TAB_MIXER]["sections"].insert(0, "presets")
    # Die Melodie-Zuordnung ist der ganze Inhalt ihres Tabs. Ohne
    # melody-Daten (aelterer imPulse-Stand ohne /net/melody/*) faellt der Tab
    # unten komplett weg, statt leer dazustehen -- dieselbe Regel wie beim
    # frueheren hidden-Attribut der Sektion, nur eine Ebene hoeher.
    if melody:
        by_tab[TAB_SCALE]["sections"].append("melody")
    if sequencer:
        by_tab[TAB_SPAWN]["sections"].append("sequencer")
    # Direkt UNTER dem Sequencer und im selben Tab -- kein eigener Reiter
    # (Birk, 2026-08-02, ausdruecklich verneint). Die Pause schaltet Sequencer
    # UND Zufalls-Spawns stumm, gehoert also zwischen die beiden, und die
    # Reihenfolge dieser Liste ist die Reihenfolge im Panel (buildTabs() in
    # app.js baut sie der Reihe nach).
    if pause:
        by_tab[TAB_SPAWN]["sections"].append("pause")
    if speed:
        by_tab[TAB_NOTES]["sections"].append("speedClasses")
    # Reihenfolge im Farben-Tab: erst die Farbe der Impulse selbst mit ihrem
    # Moduswahlschalter, dann die acht Slots des Gegenmodus (direkt darunter,
    # weil der Schalter zwischen genau diesen zwei Sektionen umschaltet), dann
    # das Nachleuchten, dann die wiederverwendbare Palette.
    if colors and (colors.get("impulse") or colors.get("stripes")):
        by_tab[TAB_COLORS]["sections"].append("impulseColor")
    if fade:
        by_tab[TAB_COLORS]["sections"].append("fade")
    # Die Palette-Leiste braucht keine Adresse aus remoteSettings.txt und ist
    # deshalb bedingungslos da -- anders als Sequencer und Speed-Klassen, die
    # ohne ihre Parameter nicht gebaut werden koennen.
    by_tab[TAB_COLORS]["sections"].append("palette")
    # Der Aufnahmeknopf steht GANZ OBEN im Sound-Tab, aus demselben Grund
    # bedingungslos: er gehoert zu sclang wie die Regler darunter, hat aber
    # keine Adresse in remoteSettings.txt. Oben, weil ein Mitschnitt der
    # erste und der letzte Griff eines Drehtages ist -- nicht etwas, das man
    # unter "Erweitert" sucht, waehrend die Kamera laeuft.
    by_tab[TAB_SOUND]["sections"].insert(0, "record")
    if split:
        by_tab[TAB_NOTES]["sections"].append("split")
    if song:
        by_tab[TAB_SONG]["sections"].append("songStructure")

    # SC-Parameter je Eintrag, nicht je Gruppe: masterVolume gehoert in den
    # Mixer, brightness ins Sound Design, obwohl beide "Master"/"Glocke"
    # heissen.
    for entry in SC_PARAMS:
        item = dict(entry)
        item["address"] = SC_PARAM_PREFIX + entry["name"]
        tab_id = entry.get("tab", TAB_SOUND)
        item["primary"] = entry["name"] in SC_PRIMARY.get(tab_id, [])
        by_tab[tab_id]["scParams"].append(item)
    # Kuratierte SC-Regler in die Reihenfolge der Liste bringen, der Rest
    # behaelt seine Reihenfolge aus SC_PARAMS.
    for tab_id, names in SC_PRIMARY.items():
        entries = by_tab[tab_id]["scParams"]
        order = {name: i for i, name in enumerate(names)}
        entries.sort(key=lambda e: (not e["primary"],
                                    order.get(e["name"], len(order))))

    # Generische Gruppen
    for group in groups:
        controls = group.get("controls") or []
        addresses = []
        for control in controls:
            if control.get("address"):
                addresses.append(control["address"])
            elif control.get("kind") == "color" and control.get("base"):
                # Eine Farbkarte traegt KEINE eigene Adresse, sondern drei
                # darunter (<basis>/Hue|Sat|Bright). Ohne diesen Zweig hat
                # eine Gruppe aus lauter Farbkarten gar keine Adresse, faellt
                # durch das "if not addresses: continue" und landet in KEINEM
                # Tab -- genau das ist mit /nodes/colors passiert: 18 Werte,
                # sechs Karten, seit dem Tab-Umbau im UI unerreichbar, ohne
                # Fehlermeldung. Der Test haelt es fest.
                addresses.append("%s/%s" % (control["base"],
                                            COLOR_COMPONENTS[0]))
        if not addresses:
            continue
        tab_id = tab_for_address(addresses[0])
        kept = [c for c in controls
                if c.get("address") not in primary_all]
        if kept:
            trimmed = dict(group)
            trimmed["controls"] = kept
            by_tab[tab_id]["groups"].append(trimmed)

    # Kuratierte Regler in der Reihenfolge der Liste
    lookup = {}
    for group in groups:
        for control in group.get("controls") or []:
            if control.get("address"):
                lookup[control["address"]] = control
    for tab_id, addresses in TAB_PRIMARY.items():
        for address in addresses:
            control = lookup.get(address)
            if control is not None:
                by_tab[tab_id]["primary"].append(control)

    # Ein Tab ohne jeden Inhalt taucht nicht auf. Das betrifft genau den
    # Tonleiter-Tab: er traegt keine Regel in TAB_RULES und keine Adresse in
    # TAB_PRIMARY, sein einziger Inhalt ist die Melodie-Sektion. Ein leerer
    # Reiter waere ein Versprechen, hinter dem nichts steht.
    return [by_tab[tab_id] for tab_id, _t in TAB_TITLES
            if tab_id != TAB_SCALE or by_tab[tab_id]["sections"]]


def sc_param_groups() -> List[Dict[str, Any]]:
    """Die SC-Parameter nach ihrer group gebuendelt, Reihenfolge wie in SC_PARAMS."""
    order: List[str] = []
    by_group: Dict[str, List[Dict[str, Any]]] = {}
    for entry in SC_PARAMS:
        group = entry["group"]
        if group not in by_group:
            by_group[group] = []
            order.append(group)
        item = dict(entry)
        item["address"] = SC_PARAM_PREFIX + entry["name"]
        by_group[group].append(item)
    return [{"title": g, "params": by_group[g]} for g in order]

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
    # 2026-07-30, Birk: volle Java-Range (0.0001..1.0) macht den Regler am
    # unteren, tatsaechlich genutzten Ende zu grobstufig -- 0.1 deckt den
    # Live-Tuning-Bereich ab (per Speed-Kopplung ohnehin an /net/impulse/speed
    # gebunden, siehe SPEED_COUPLED oben).
    "/net/impulse/lifetime": (0.0001, 0.1),
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
    """Baut ein OSC-Paket mit hoechstens einem Argument.

    int -> 'i', float -> 'f', str -> 's'. Der String-Zweig wird fuer die
    Preset-Kommandos gebraucht (``/preset/load <name>``), die als einziges
    Argument einen Namen tragen; ``None`` erzeugt eine Nachricht ganz OHNE
    Argument (Typtag nur ",") fuer die Record-Kommandos, die reine Befehle
    ohne Wert sind.
    """
    if value is None:
        return _osc_string(address) + _osc_string(",")
    if isinstance(value, bool):
        raise TypeError("bool ist kein gueltiges OSC-Argument")
    if isinstance(value, int):
        return _osc_string(address) + _osc_string(",i") + struct.pack(">i", value)
    if isinstance(value, float):
        return _osc_string(address) + _osc_string(",f") + struct.pack(">f", value)
    if isinstance(value, str):
        return _osc_string(address) + _osc_string(",s") + _osc_string(value)
    raise TypeError("nicht unterstuetzter OSC-Argumenttyp: %r" % type(value))


def _read_osc_string(data: bytes, offset: int) -> Tuple[str, int]:
    """Liest einen OSC-String ab ``offset`` und liefert (Text, neuer Offset)."""
    end = data.index(b"\x00", offset)          # ValueError, wenn kein Nullbyte
    text = data[offset:end].decode("utf-8", "replace")
    # Auf das naechste Vielfache von 4 aufgefuellt, mindestens ein Nullbyte --
    # dieselbe Regel wie in _osc_string(), nur rueckwaerts.
    return text, offset + (((end - offset) // 4) + 1) * 4


def parse_osc_message(data: bytes) -> Tuple[str, List[Any]]:
    """Zerlegt ein OSC-Datagramm in Adresse und Argumente.

    Gegenstueck zu ``build_osc_message`` und aus demselben Grund selbst
    geschrieben: die Tests sollen ohne python-osc laufen (siehe
    test_webui.py). Unterstuetzt genau die drei Typen, die hier vorkommen
    ('i', 'f', 's') -- alles andere ist ein ValueError, statt still ein
    falsches Argument zu liefern.
    """
    address, offset = _read_osc_string(data, 0)
    if not address.startswith("/"):
        raise ValueError("keine OSC-Adresse: %r" % address)
    if offset >= len(data):
        return address, []          # Nachricht ohne Typtag
    tags, offset = _read_osc_string(data, offset)
    if not tags.startswith(","):
        raise ValueError("kein OSC-Typtag: %r" % tags)
    args: List[Any] = []
    for tag in tags[1:]:
        if tag == "i":
            args.append(struct.unpack_from(">i", data, offset)[0])
            offset += 4
        elif tag == "f":
            args.append(struct.unpack_from(">f", data, offset)[0])
            offset += 4
        elif tag == "s":
            text, offset = _read_osc_string(data, offset)
            args.append(text)
        else:
            raise ValueError("nicht unterstuetzter OSC-Typ %r" % tag)
    return address, args


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
        """``value=None`` schickt eine Nachricht ohne Argument.

        Beide Wege koennen das: python-osc baut aus ``None`` eine leere
        Argumentliste, der eingebaute Encoder schreibt den Typtag ",". Das
        brauchen die Record-Kommandos, die reine Befehle ohne Wert sind.
        """
        with self._lock:
            if self._client is not None and address.startswith("/"):
                self._client.send_message(address, value)
            else:
                self._socket.sendto(build_osc_message(address, value),
                                    (self.host, self.port))

    def close(self) -> None:
        with self._lock:
            self._socket.close()


class RecordStatusListener:
    """Hoert auf ``/klangnetz/record/status`` von sclang.

    Der EINZIGE Rueckkanal im ganzen Web-UI. Alles andere hier ist
    fire-and-forget (siehe /api/sc), und das ist fuer einen Regler richtig:
    ein Wert, den man geschickt hat, steht danach. Ein Aufnahmeknopf ist
    etwas anderes -- er hat einen Zustand, den auch jemand anderes aendern
    kann (Skript, IDE, zweiter Browser), und ein Knopf, der nur zeigt, was
    zuletzt geklickt wurde, waere im Zweifel eine Aufnahme, die gar nicht
    laeuft. Genau der Fehler, der beim Videodreh erst in der Nachbearbeitung
    auffiele.

    Ein fehlgeschlagenes Bind ist deshalb KEIN Grund, das UI nicht zu
    starten: dann bleibt der Zustand "unbekannt", der Knopf schickt trotzdem
    und sagt es dazu. Der haeufige Fall dafuer ist ein zweites, vergessenes
    server.py auf derselben Maschine.
    """

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self.error: Optional[str] = None
        self._lock = threading.Lock()
        self._recording: Optional[bool] = None
        self._path = ""
        # Zaehlt jede empfangene Meldung. Ein Endpoint merkt sich den Stand
        # VOR dem Senden und wartet auf eine Erhoehung -- damit kann er eine
        # frische Antwort nicht mit einer alten verwechseln, was ein
        # Vergleich der Werte allein nicht leistet (zweimal "laeuft nicht"
        # hintereinander sieht sonst aus wie keine Antwort).
        self._seq = 0
        self._socket: Optional[socket.socket] = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.bind((host, port))
            self._socket = sock
            # Port 0 heisst "irgendeinen freien nehmen" -- dann steht die
            # echte Nummer erst nach dem Bind fest. Im Betrieb ist der Port
            # fest (8003), die Tests nutzen 0, um nicht mit einem laufenden
            # Web-UI auf derselben Maschine zu kollidieren.
            self.port = sock.getsockname()[1]
        except OSError as exc:
            self.error = "Status-Port %s:%d nicht nutzbar (%s)" % (host, port, exc)
            print("[webui] %s -- der Aufnahmeknopf zeigt keinen Zustand"
                  % self.error, file=sys.stderr)
            return
        self._thread = threading.Thread(target=self._loop, daemon=True,
                                        name="record-status")
        self._thread.start()

    @property
    def active(self) -> bool:
        return self._socket is not None

    def _loop(self) -> None:  # pragma: no cover - Thread, per Endpoint geprueft
        sock = self._socket
        assert sock is not None
        while True:
            try:
                data, _sender = sock.recvfrom(4096)
            except OSError:
                return                      # close() -- geregeltes Ende
            try:
                address, args = parse_osc_message(data)
            except (ValueError, struct.error):
                continue                    # kein OSC/fremdes Format: ignorieren
            if address != RECORD_STATUS:
                continue
            with self._lock:
                if args:
                    self._recording = bool(args[0])
                if len(args) > 1:
                    self._path = str(args[1])
                self._seq += 1

    def sequence(self) -> int:
        with self._lock:
            return self._seq

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return {
                # None heisst "noch nie gehoert" und ist etwas anderes als
                # False ("laeuft gerade nicht") -- das Frontend zeigt beides
                # verschieden an.
                "known": self._recording is not None,
                "recording": bool(self._recording),
                "path": self._path,
                "listening": self._socket is not None,
                "statusPort": self.port,
                "error": self.error,
            }

    def wait_for_update(self, since: int,
                        timeout: float = RECORD_STATUS_TIMEOUT_S) -> bool:
        """Wartet, bis eine Meldung NACH ``since`` eingetroffen ist.

        Pollen statt Condition-Variable: die Wartezeit ist kurz und der
        Empfangsthread soll nichts ueber seine Leser wissen muessen.
        """
        if self._socket is None:
            return False
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.sequence() != since:
                return True
            time.sleep(RECORD_STATUS_POLL_S)
        return self.sequence() != since

    def close(self) -> None:
        if self._socket is not None:
            self._socket.close()
            self._socket = None


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
        label, help_text = label_for(self.address)
        return {
            "kind": "param",
            "type": self.type,
            "address": self.address,
            "description": self.description,
            # Sprechender Titel statt des letzten Adresssegments. None heisst
            # "keine Zeile in ADDRESS_LABELS" -- das UI faellt dann auf das
            # Segment zurueck, damit ein neuer Parameter nicht unbeschriftet
            # ist.
            "label": label,
            "min": ui_min,
            "max": ui_max,
            "step": self.step(),
            # 0/1-Ints bekommen einen Schalter statt eines Zweipunkt-Reglers
            "widget": "toggle" if (self.is_int and self.minimum == 0
                                   and self.maximum == 1) else "slider",
            # Kurzerklaerung. None heisst entweder "selbsterklaerend" (Rot,
            # Farbton, min/max unter einer eindeutigen Ueberschrift) oder
            # "unbekannte Adresse" -- in beiden Faellen zeigt das UI nichts
            # statt einer leeren Zeile.
            "help": help_text,
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


# ---------------------------------------------------------------------------
# Farbpalette
#
# Eine kleine Sammlung wiederverwendbarer Farben, die an JEDER
# Farbwaehler-Karte per Klick anwendbar ist. Sie liegt server-seitig in
# data/colorPalettes.txt und nicht im localStorage des Browsers: sie soll
# einen Neustart ueberleben und auf jedem Geraet dieselbe sein -- genau das
# meint "eine Palette, die von allen gewaehlt werden kann".
#
# Format wie data/stripeTrees.txt: Tab-getrennte Spalten, '#' leitet einen
# Kommentar ein, von Hand editierbar. Vier Spalten:
#
#     name<TAB>hue<TAB>sat<TAB>bright     (die drei Werte in 0..1, wie LedColor)
#
# Bei doppeltem Namen gewinnt die LETZTE Zeile -- dieselbe Regel und derselbe
# Grund wie bei StripeTreeStore: die natuerliche Handkorrektur ist eine
# angehaengte Zeile am Ende, "erste gewinnt" wuerde sie still verschlucken.
#
# Ein Preset ist das hier ausdruecklich NICHT: die Palette haelt nur
# Farbwerte, nicht welche Karte welche Farbe traegt. Was wo steht, ist
# Aufgabe der Presets, die es schon gibt.
# ---------------------------------------------------------------------------

PALETTE_FILENAME = "colorPalettes.txt"
PALETTE_COMPONENTS = ("hue", "sat", "bright")
# Deckel gegen eine Palette, die sich unbemerkt aufblaeht: die Swatch-Reihe
# steht unter JEDER Farbkarte, ab ein paar Dutzend Farben ist sie hoeher als
# die Karte selbst.
PALETTE_MAX_ENTRIES = 24
PALETTE_NAME_MAX_LENGTH = 32

PALETTE_HEADER = (
    "# Farbpalette fuer das Web-UI (Sektion \"Farben\").\n"
    "#\n"
    "# Format: name<TAB>hue<TAB>sat<TAB>bright -- die drei Werte in 0..1.\n"
    "# '#' leitet einen Kommentar ein. Bei doppeltem Namen gewinnt die\n"
    "# LETZTE Zeile (eine angehaengte Handkorrektur schlaegt den alten\n"
    "# Eintrag), genau wie in data/stripeTrees.txt.\n"
    "#\n"
    "# Wird vom Web-UI geschrieben und ist von Hand editierbar.\n")


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def parse_palette(text: str) -> Tuple[List[Dict[str, Any]], List[str]]:
    """Parst den Inhalt von colorPalettes.txt.

    Kaputte Zeilen werden gemeldet und uebersprungen, nicht als Abbruch
    weitergereicht -- dasselbe Verhalten wie parse_settings() und wie die
    Store-Klassen auf der Java-Seite: eine einzelne unerwartete Zeile soll
    das UI nicht lahmlegen.
    """
    entries: List[Dict[str, Any]] = []
    warnings: List[str] = []
    by_name: Dict[str, int] = {}
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = raw.rstrip("\r\n").split("\t")
        if len(fields) < 4:
            warnings.append("Zeile %d uebersprungen (%d Felder statt 4)"
                            % (lineno, len(fields)))
            continue
        name = fields[0].strip()
        if not name:
            warnings.append("Zeile %d uebersprungen (leerer Name)" % lineno)
            continue
        try:
            values = [float(fields[i + 1]) for i in range(3)]
        except ValueError:
            warnings.append("Zeile %d uebersprungen (unlesbare Zahl)" % lineno)
            continue
        if any(v != v for v in values):
            warnings.append("Zeile %d uebersprungen (keine Zahl)" % lineno)
            continue
        entry: Dict[str, Any] = {"name": name[:PALETTE_NAME_MAX_LENGTH]}
        for key, value in zip(PALETTE_COMPONENTS, values):
            entry[key] = _clamp01(value)
        if entry["name"] in by_name:
            warnings.append("Name %r in Zeile %d ersetzt den frueheren Eintrag"
                            % (entry["name"], lineno))
            entries[by_name[entry["name"]]] = entry
        else:
            by_name[entry["name"]] = len(entries)
            entries.append(entry)
    return entries, warnings


def format_palette(entries: List[Dict[str, Any]]) -> str:
    """Schreibt die Palette im Dateiformat, mit Kopfkommentar."""
    parts = [PALETTE_HEADER]
    for entry in entries:
        # "%.4f" schreibt in Python immer mit Punkt, unabhaengig von der
        # Systemsprache -- dieselbe Anforderung wie Locale.US auf der
        # Java-Seite, hier ohne eigenes Zutun erfuellt. Ein Test haelt es
        # fest, damit es niemand versehentlich aufgibt.
        parts.append("%s\t%.4f\t%.4f\t%.4f\n"
                     % (entry["name"], entry["hue"], entry["sat"],
                        entry["bright"]))
    return "".join(parts)


def load_palette(path: str) -> Tuple[List[Dict[str, Any]], List[str]]:
    """Liest die Palette. Fehlende Datei = leere Palette, kein Fehler.

    Die Datei entsteht erst beim ersten Speichern -- ein UI, das ohne sie
    einen Fehler zeigt, waere im Auslieferungszustand kaputt.
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except FileNotFoundError:
        return [], []
    except OSError as exc:
        return [], ["Palette nicht lesbar: %s" % exc]
    return parse_palette(text)


def save_palette(path: str, entries: List[Dict[str, Any]]) -> None:
    """Schreibt die Palette atomar (Temp-Datei + Rename).

    Dasselbe Muster wie NodeCrossingStore/LedAnchorStore auf der Java-Seite:
    ein abgebrochener Schreibvorgang darf keine halbe Datei hinterlassen.
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(format_palette(entries))
    os.replace(temp, path)


def validate_palette(raw: Any) -> Tuple[Optional[List[Dict[str, Any]]],
                                        Optional[str]]:
    """Prueft und normalisiert eine vom Browser geschickte Palette.

    Rueckgabe: (normalisierte Liste, None) oder (None, Fehlermeldung). Eine
    leere Liste ist gueltig -- die letzte Farbe zu entfernen muss moeglich
    sein.

    Anders als beim Parsen wird hier NICHT stillschweigend uebersprungen: was
    aus dem Browser kommt, ist keine handgepflegte Datei, sondern das
    Ergebnis eines Klicks. Ein still verschluckter Eintrag saehe im UI aus
    wie ein Speichern, das funktioniert hat.
    """
    if not isinstance(raw, list):
        return None, "Palette ist keine Liste"
    if len(raw) > PALETTE_MAX_ENTRIES:
        return None, ("Hoechstens %d Farben in der Palette"
                      % PALETTE_MAX_ENTRIES)
    entries: List[Dict[str, Any]] = []
    seen: Set[str] = set()
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            return None, "Eintrag %d ist kein Objekt" % index
        name = item.get("name")
        name = name.strip() if isinstance(name, str) else ""
        if not name:
            return None, "Eintrag %d hat keinen Namen" % index
        if len(name) > PALETTE_NAME_MAX_LENGTH:
            return None, ("Name in Eintrag %d ist laenger als %d Zeichen"
                          % (index, PALETTE_NAME_MAX_LENGTH))
        # Ein Tabulator im Namen zerlegte die Zeile beim naechsten Lesen in
        # fuenf Felder, ein Zeilenumbruch machte zwei Zeilen daraus, ein
        # fuehrendes '#' einen Kommentar: die Datei waere still kaputt.
        if ("\t" in name or "\n" in name or "\r" in name
                or name.startswith("#")):
            return None, ("Name in Eintrag %d enthaelt ein Zeichen, das die "
                          "Datei zerlegen wuerde (Tabulator, Zeilenumbruch "
                          "oder fuehrendes #)" % index)
        if name in seen:
            return None, "Name %r kommt zweimal vor" % name
        seen.add(name)
        entry: Dict[str, Any] = {"name": name}
        for key in PALETTE_COMPONENTS:
            try:
                value = float(item.get(key))
            except (TypeError, ValueError):
                return None, "%s in Eintrag %d ist keine Zahl" % (key, index)
            if value != value or value in (float("inf"), float("-inf")):
                return None, "%s in Eintrag %d ist keine Zahl" % (key, index)
            entry[key] = _clamp01(value)
        entries.append(entry)
    return entries, None


def default_palette_path(settings_path: str) -> str:
    """Palette neben der Parameterdatei: <dir von settings>/colorPalettes.txt."""
    return os.path.join(os.path.dirname(os.path.abspath(settings_path)),
                        PALETTE_FILENAME)


# ---------------------------------------------------------------------------
# Gesicherter UI-Zustand
#
# Adresse -> Wert, als flaches JSON-Objekt. Enthaelt AUSSCHLIESSLICH, was
# ueber dieses Web-UI gesendet wurde (siehe ParameterStore.store()) -- nicht
# den Live-Zustand von imPulse. Was jemand per osc_send.py oder aus der IDE an
# den Sketch schickt, steht hier nicht und soll hier auch nicht stehen: es
# gibt keinen Rueckkanal von imPulse hierher, und einen zu bauen war
# ausdruecklich nicht gewuenscht (Birk, 2026-08-02).
#
# Warum eine eigene Datei und nicht data/remoteSettings.txt: die schreibt
# imPulse bei JEDEM Start komplett neu (Boot-Snapshot, gitignored). Ein
# Fremdschreiber darin waere beim naechsten Sketch-Start weg, und schlimmer:
# er wuerde die Datei veraendern, an deren mtime der Server erkennt, dass
# imPulse neu gestartet wurde.
#
# Warum JSON und nicht das Tab-Format der Presets: das Tab-Format traegt
# Typ, Beschreibung und Bereich mit -- alles Angaben, die hier aus
# remoteSettings.txt kommen und in einer zweiten Datei nur veralten koennten.
# Gebraucht wird ein Paar aus Adresse und Zahl, und genau das ist JSON.
# ---------------------------------------------------------------------------

UI_STATE_FILENAME = "webuiState.json"


def default_ui_state_path(settings_path: str) -> str:
    """Zustandsdatei neben der Parameterdatei: <dir von settings>/webuiState.json.

    Neben remoteSettings.txt und nicht fest in webui/: der Ort gehoert zur
    Installation, nicht zum Programm. Ein Testaufbau, der --settings in ein
    Temp-Verzeichnis legt, schreibt seinen Zustand damit auch dorthin, statt
    in den Checkout.
    """
    return os.path.join(os.path.dirname(os.path.abspath(settings_path)),
                        UI_STATE_FILENAME)


def read_ui_state(path: str) -> Tuple[Dict[str, float], Optional[str]]:
    """Liest die Zustandsdatei. Rueckgabe: (Werte, Fehlermeldung oder None).

    Eine fehlende Datei ist der Normalfall (erster Start, frischer Rechner)
    und liefert ein leeres Ergebnis ohne Fehler. Eine kaputte Datei ist
    dagegen eine Meldung wert -- sie wird uebergangen, aber nicht
    verschwiegen: sonst saehe ein Operator nur, dass "die Werte weg sind".

    Einzelne unbrauchbare Eintraege werden still uebersprungen, wie beim
    Parsen von remoteSettings.txt: eine von Hand editierte Zeile darf nicht
    den ganzen Rest kosten.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            raw = json.load(handle)
    except FileNotFoundError:
        return {}, None
    except (OSError, ValueError) as exc:
        return {}, "UI-Zustand nicht lesbar (%s): %s" % (path, exc)
    if not isinstance(raw, dict):
        return {}, "UI-Zustand ist kein JSON-Objekt: %s" % path
    values: Dict[str, float] = {}
    for address, value in raw.items():
        if not isinstance(address, str) or isinstance(value, bool):
            continue
        if not isinstance(value, (int, float)):
            continue
        number = float(value)
        if number != number or number in (float("inf"), float("-inf")):
            continue
        values[address] = number
    return values, None


def write_ui_state(path: str, values: Dict[str, float]) -> None:
    """Schreibt die Zustandsdatei atomar (Temp-Datei + Rename).

    Dasselbe Muster wie save_palette und wie NodeCrossingStore/LedAnchorStore
    auf der Java-Seite: ein abgebrochener Schreibvorgang darf keine halbe
    Datei hinterlassen. Sortiert, damit zwei Staende vergleichbar bleiben.
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(values, handle, indent=1, sort_keys=True)
        handle.write("\n")
    os.replace(temp, path)


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


# Menschenlesbare Titel fuer die Gruppen-Ueberschriften. Ohne Eintrag steht
# dort der rohe Adress-Praefix ("/net/randomSpawn") -- derselbe Einwand wie
# bei den Reglern selbst: das ist ein Hinweis darauf, wo man nachschlagen
# muesste, keine Ueberschrift. Der Praefix ist an der Adresszeile jedes
# Reglers darunter weiterhin abzulesen.
GROUP_TITLE_OVERRIDES = {
    "Master": "Ebenen und Nachleuchten",
    "master": "Gesamt-Helligkeit",
    "net/impulse": "Impuls-Physik",
    # Nach dem Farbwaehler-Umbau steht hier nur noch die Farbkurve: die drei
    # Kanaele und der Moduswahlschalter gehoeren der Sektion "Impuls-Farbe".
    # Derselbe Titel fuer beides waeren zwei Ueberschriften, die dasselbe
    # versprechen und Verschiedenes halten.
    "net/impulse/color": "Impuls-Farbe: Feinheiten",
    "net/impulse/randomize": "Impuls-Randomizer (Sinus)",
    "net/impulse/split": "Split-Verhalten",
    "net/randomSpawn": "Zufalls-Spawns",
    # Rueckfall: normalerweise rendert die Sektion "Ruhemomente" alle sieben
    # Adressen selbst (build_pause). Fehlt /net/pause/enabled im Dump, kann
    # sie nicht gebaut werden -- dann steht die Gruppe wenigstens unter einem
    # Klartext-Titel statt unter "/net/pause".
    "net/pause": "Ruhemomente (Pause)",
    "net/sequencer": "Sequencer",
    "net": "Direkt-Trigger",
    "nodes": "Knoten",
    "nodes/colors": "Knoten-Farben",
    "nodes/radius": "Knoten-Groessen",
    "nodes/times": "Knoten-Zeiten",
    "preset/scheduler": "Preset-Wechsler",
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
            # Der Titel einer Farbkarte haengt an ihrer BASIS, nicht an einer
            # Adresse -- die Karte hat keine, sie hat drei darunter. Ohne
            # Eintrag bleibt es beim letzten Segment ("fired"), das allein
            # nicht verraet, wessen Zustand gemeint ist.
            color_label, color_help = label_for(base)
            controls.append({
                "kind": "color",
                "base": base,
                "label": color_label or base.split("/")[-1] or base,
                "help": color_help,
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
                trigger_label, trigger_help = label_for(param.address)
                controls.append({
                    "kind": "trigger",
                    "type": param.type,
                    "address": param.address,
                    "description": param.description,
                    "label": trigger_label,
                    "help": trigger_help,
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

    Ueber ``ui_state_path`` kommt seit 2026-08-02 eine ZWEITE, eigene Quelle
    dazu: alles, was ueber dieses UI gesendet wurde, steht als JSON in einer
    kleinen Zustandsdatei und wird beim naechsten START des Servers ueber die
    frisch gelesenen Boot-Werte gelegt. Ohne sie stand nach jedem Neustart des
    UI-Prozesses wieder der Code-Default in den Reglern, obwohl imPulse
    laengst etwas anderes fuhr -- der Operator musste jeden Wert von Hand
    nachziehen, um ueberhaupt zu sehen, wo er steht.

    Ohne ``ui_state_path`` (Vorgabe) verhaelt sich der Store exakt wie vorher
    und schreibt nichts -- so bleiben die Tests, die einen Store direkt auf
    eine Preset-Datei richten, frei von Nebenwirkungen.
    """

    path: str
    parameters: List[Parameter] = field(default_factory=list)
    values: Dict[str, float] = field(default_factory=dict)
    by_address: Dict[str, Parameter] = field(default_factory=dict)
    mtime: Optional[float] = None
    error: Optional[str] = None
    ui_state_path: Optional[str] = None
    # Zuletzt geschriebener Stand der Zustandsdatei, im Speicher gespiegelt:
    # geschrieben wird die ganze Datei, gelesen nur einmal beim Start.
    ui_state: Dict[str, float] = field(default_factory=dict)
    ui_state_error: Optional[str] = None
    # Der Ueberlagerung ist ein EINMALIGER Vorgang beim Start, kein Zustand,
    # der bei jedem Neulesen wieder greift -- siehe _read().
    ui_state_applied: bool = False
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
        # NUR beim ersten Lesen, also beim Start des Servers. Ein spaeteres
        # Neulesen heisst "imPulse wurde neu gestartet" -- dann faehrt der
        # Sketch wirklich seine Boot-Werte, und die alte UI-Anzeige darueber
        # zu legen behauptete einen Zustand, den es gerade nicht mehr gibt.
        if not self.ui_state_applied:
            self.ui_state_applied = True
            self._load_ui_state()

    def _load_ui_state(self) -> None:
        """Die zuletzt ueber das UI gesetzten Werte ueber die Boot-Werte legen.

        Bewusst OHNE OSC-Versand: der Live-Zustand von imPulse kommt aus
        dessen eigenem Boot-Zustand, hier wird nur die ANZEIGE nachgezogen.
        Ein automatischer Versand beim Start machte aus dem Neustart eines
        Anzeigeprozesses einen Eingriff in die laufende Show.

        Werte fuer Adressen, die es in remoteSettings.txt nicht (mehr) gibt,
        werden verworfen -- kein Fehler, sondern der Normalfall nach einem
        umbenannten oder entfernten Parameter.
        """
        if not self.ui_state_path:
            return
        raw, error = read_ui_state(self.ui_state_path)
        self.ui_state_error = error
        if error:
            print("[webui] %s" % error, file=sys.stderr)
        kept: Dict[str, float] = {}
        dropped = 0
        for address, value in raw.items():
            param = self.by_address.get(address)
            if param is None:
                dropped += 1
                continue
            kept[address] = param.coerce(value)
        self.ui_state = kept
        self.values.update(kept)
        if kept or dropped:
            print("[webui] UI-Zustand: %d Werte uebernommen, %d verworfen "
                  "(Adresse gibt es nicht mehr) -- %s"
                  % (len(kept), dropped, self.ui_state_path), file=sys.stderr)

    def _write_ui_state(self) -> None:
        """Die Zustandsdatei neu schreiben. Aufrufer haelt ``_lock``.

        Voll-Datei statt Anhaengen, wie beim POST auf die Farbpalette: zwei
        Formate (Vollstand und Nachtrag) waeren zwei Wege, die auseinander
        laufen koennen. Die Datei hat die Groessenordnung der Parameterliste,
        also wenige Kilobyte -- ein Regler, der 150 ms lang gezogen wird,
        schreibt sie ein paar Dutzend Mal, und das ist billiger als jede
        Sonderbehandlung.

        Ein Schreibfehler ist KEIN Fehler des Aufrufers: die Aenderung ist
        laengst per OSC raus, die Sicherung ist nur ihr Gedaechtnis. Er wird
        gemerkt (``ui_state_error``, sichtbar unter /api/parameters) statt
        geworfen.
        """
        if not self.ui_state_path:
            return
        try:
            write_ui_state(self.ui_state_path, self.ui_state)
        except OSError as exc:
            message = "UI-Zustand nicht schreibbar: %s" % exc
            if message != self.ui_state_error:
                print("[webui] %s" % message, file=sys.stderr)
            self.ui_state_error = message
        else:
            self.ui_state_error = None

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
            # Erst die Spezial-Sektionen bauen, dann deren Adressen aus dem
            # generischen Rendering nehmen: sonst stuende jeder
            # Sequencer-Regler zweimal auf der Seite, einmal im Panel und
            # einmal als generischer Schieber.
            sequencer = build_sequencer(self.by_address)
            speed = build_speed_classes(self.by_address)
            split = build_split(self.by_address)
            pause = build_pause(self.by_address)
            song = build_song_structure(self.by_address)
            colors = build_colors(self.by_address)
            fade = build_fade(self.by_address, self.values)
            melody = build_melody(self.by_address)
            taken = sequencer_addresses(sequencer, speed, split)
            taken |= pause_addresses(pause)
            taken |= song_structure_addresses(song)
            taken |= color_addresses(colors)
            taken |= fade_addresses(fade)
            taken |= melody_addresses(melody)
            generic = [p for p in self.parameters if p.address not in taken]
            groups = build_groups(generic)
            return {
                "groups": groups,
                "values": dict(self.values),
                "sequencer": sequencer,
                "speedClasses": speed,
                "split": split,
                "pause": pause,
                "songStructure": song,
                "colors": colors,
                "fade": fade,
                "melody": melody,
                "tabs": build_tabs(groups, sequencer, speed, split, song,
                                   colors, fade, melody, pause),
                "scParams": {
                    "port": SC_OSC_PORT,
                    "groups": sc_param_groups(),
                },
                "settings": {
                    "path": self.path,
                    "mtime": self.mtime,
                    "count": len(self.parameters),
                    "error": self.error,
                    # Der gesicherte UI-Zustand, rein informativ: das UI zeigt
                    # ihn nicht als eigenes Bedienelement, aber ein
                    # Schreibfehler soll nachschlagbar sein, ohne im Log des
                    # Servers zu suchen.
                    "uiState": {
                        "path": self.ui_state_path,
                        "count": len(self.ui_state),
                        "error": self.ui_state_error,
                    },
                },
            }

    def get(self, address: str) -> Optional[Parameter]:
        with self._lock:
            return self.by_address.get(address)

    def store(self, address: str, value: float) -> None:
        """Einen ueber das UI gesetzten Wert uebernehmen und sichern.

        Der einzige Ort, an dem ein Wert im Store landet, nachdem
        remoteSettings.txt gelesen wurde: ``apply_value()`` (/api/set,
        /api/fadeout, /api/melody/recompute) und ``apply_preset_entries()``
        gehen beide hier durch. Deshalb haengt die Sicherung hier und nicht an
        den vier Endpoints -- ein fuenfter Endpoint erbt sie damit, statt sie
        vergessen zu koennen.

        Geschrieben wird nur, wenn sich wirklich etwas geaendert hat: waehrend
        eines Reglerzugs kommen dieselben Werte mehrfach an.
        """
        with self._lock:
            self.values[address] = value
            if self.ui_state_path and self.ui_state.get(address) != value:
                self.ui_state[address] = value
                self._write_ui_state()


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
               presets_path: str, palette_path: Optional[str] = None,
               state_path: Optional[str] = None, autocommit_scheduler=None,
               sc_port: int = SC_OSC_PORT,
               record_status_host: str = DEFAULT_RECORD_STATUS_HOST,
               record_status_port: int = DEFAULT_RECORD_STATUS_PORT,
               ui_state_path: Optional[str] = None):
    if Flask is None:
        raise SystemExit("Flask fehlt (%s) -- bitte 'pip install -r requirements.txt'"
                         % _FLASK_IMPORT_ERROR)
    app = Flask(__name__)
    if state_path is None:
        state_path = os.path.join(os.path.dirname(settings_path),
                                  DEFAULT_STATE_FILENAME)
    if ui_state_path is None:
        ui_state_path = default_ui_state_path(settings_path)
    # Erst danach refresh(): der Store legt den gesicherten UI-Zustand direkt
    # beim ersten Lesen ueber die Boot-Werte, ohne ein zweites Kommando.
    store = ParameterStore(path=settings_path, ui_state_path=ui_state_path)
    store.refresh(force=True)
    sender = OscSender(osc_host, osc_port)
    # Zweiter Sender fuer die Sound-Parameter: die /klangnetz/param/*-Adressen
    # gehoeren zur SC-Registry und hoeren auf 8002, nicht auf 8001. Derselbe
    # Host -- SuperCollider laeuft auf derselben Maschine wie imPulse und
    # dieses UI. ``sc_port`` ist nur fuer die Tests da (dort haengt ein
    # gefaktes sclang an einem freien Port); im Betrieb bleibt es 8002.
    sc_sender = OscSender(osc_host, sc_port)
    record_status = RecordStatusListener(record_status_host, record_status_port)

    app.config["IMPULSE_STORE"] = store
    app.config["IMPULSE_SENDER"] = sender
    app.config["IMPULSE_SC_SENDER"] = sc_sender
    app.config["IMPULSE_RECORD_STATUS"] = record_status

    # Vorgabe erst hier aufloesen, nicht in der Signatur: sie haengt am
    # settings_path, ein Default-Argument wuerde einmal beim Import
    # ausgewertet.
    if palette_path is None:
        palette_path = default_palette_path(settings_path)
    app.config["IMPULSE_PALETTE_PATH"] = palette_path

    def palette_payload() -> Dict[str, Any]:
        entries, warnings = load_palette(palette_path)
        return {"ok": True, "entries": entries, "path": palette_path,
                "warnings": warnings}

    def apply_value(param: Parameter, value: float) -> Applied:
        coerced = param.coerce(value)
        payload = param.normalize(coerced)
        sender.send(param.address, payload)
        store.store(param.address, coerced)
        return Applied(param.address, coerced, payload)

    def record_command(address: str) -> Dict[str, Any]:
        """Ein Record-Kommando schicken und die Antwort von sclang abwarten.

        Der zurueckgemeldete Zustand kommt IMMER von sclang, nie aus dem
        Kommando: ein zweites /start bei laufender Aufnahme wird dort
        ignoriert und meldet trotzdem "laeuft" -- der Knopf zeigt danach also
        die Wahrheit und nicht das, was der Klick gemeint hat.
        """
        before = record_status.sequence()
        sc_sender.send(address, None)
        answered = record_status.wait_for_update(before)
        payload = record_status.snapshot()
        payload.update({"ok": True, "address": address, "answered": answered,
                        "port": sc_port})
        return payload

    def preset_file(name: str) -> str:
        return os.path.join(presets_path, name + ".txt")

    def preset_list_payload() -> Dict[str, Any]:
        names, error = list_presets(presets_path)
        return {"ok": True, "presets": names, "dir": presets_path,
                "error": error}

    def autocommit_payload() -> Dict[str, Any]:
        if autocommit_scheduler is None:
            return dict(autocommit.DISABLED_STATUS)
        return autocommit_scheduler.status()

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
                "palette": palette_payload(),
                "songState": read_song_state(state_path),
                "autocommit": autocommit_payload(),
                # Nur die Verdrahtung, KEIN Zustand: den holt app.js sich
                # sofort per /api/record/status. Ein Zustand im Bootstrap
                # kostete jeden Seitenaufruf die Wartezeit auf sclang und
                # waere beim ersten Blick auf die Seite trotzdem schon alt.
                "record": {
                    "port": sc_port,
                    # Der GEBUNDENE Port, nicht der gewuenschte -- bei 0
                    # sucht sich der Listener einen freien (Tests).
                    "statusPort": record_status.port,
                    "listening": record_status.active,
                    "error": record_status.error,
                },
            }),
        )

    @app.route("/api/autocommit")
    def api_autocommit():
        """Zustand der automatischen lokalen Sicherung.

        Nur lesend: ausgeloest wird ausschliesslich vom Takt im Server. Ein
        Knopf, der aus dem Browser heraus Git-Zustand aendert, waere eine
        zweite Ausloeseart mit eigenen Fehlerfaellen und ohne Anlass.
        """
        return jsonify(autocommit_payload())

    @app.route("/api/parameters")
    def api_parameters():
        store.refresh(force=request.args.get("force") == "1")
        snapshot = store.snapshot()
        snapshot["osc"] = {"host": osc_host, "port": osc_port}
        return jsonify(snapshot)

    @app.route("/api/sc", methods=["POST"])
    def api_sc():
        """Einen Sound-Parameter an SuperCollider schicken (Port 8002).

        Eigener Sender, eigener Port, eigene Adresstabelle: das hier gehoert
        nicht zu imPulses remoteSettings.txt. Fire-and-forget -- es gibt
        keinen Rueckkanal, laeuft sclang nicht, merkt das UI es nicht.
        """
        body = request.get_json(silent=True) or {}
        name = str(body.get("name", ""))
        entry = next((p for p in SC_PARAMS if p["name"] == name), None)
        if entry is None:
            return jsonify({"ok": False,
                            "error": "unbekannter SC-Parameter: %r" % name}), 400
        try:
            value = float(body.get("value"))
        except (TypeError, ValueError):
            return jsonify({"ok": False, "error": "Wert ist keine Zahl"}), 400
        if value != value:  # NaN
            return jsonify({"ok": False, "error": "Wert ist keine Zahl"}), 400
        value = max(entry["min"], min(entry["max"], value))
        address = SC_PARAM_PREFIX + name
        sc_sender.send(address, value)
        return jsonify({"ok": True, "address": address, "value": value})

    # ---- Vierkanal-Mitschnitt -------------------------------------------
    # Vier Endpoints statt eines mit Aktion im Body: eine Adresse pro
    # Kommando ist im Log und in der Konsole des Browsers direkt lesbar, und
    # ein Tippfehler wird zu einem 404 statt zu einem stillen Nichtstun.
    #
    # start/stop sind der Normalfall (das UI kennt den Zustand aus dem
    # Rueckkanal); /toggle ist fuer den Fall, dass es ihn NICHT kennt --
    # dieselbe Aufteilung wie auf der SC-Seite. Geschrieben wird die Datei
    # ausschliesslich von sclang; dieses UI schickt drei Datagramme.

    @app.route("/api/record/status")
    def api_record_status():
        """Aktueller Zustand, per /klangnetz/record/query frisch erfragt.

        Fragt bei JEDEM Aufruf nach, statt nur den letzten Stand
        auszuliefern: nur so faellt auf, wenn sclang zwischendurch beendet
        wurde. Die Abfrage aendert nichts an einer laufenden Aufnahme.
        """
        return jsonify(record_command(RECORD_QUERY))

    @app.route("/api/record/start", methods=["POST"])
    def api_record_start():
        return jsonify(record_command(RECORD_START))

    @app.route("/api/record/stop", methods=["POST"])
    def api_record_stop():
        return jsonify(record_command(RECORD_STOP))

    @app.route("/api/record/toggle", methods=["POST"])
    def api_record_toggle():
        return jsonify(record_command(RECORD_TOGGLE))

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

    @app.route("/api/fadeout", methods=["POST"])
    def api_fadeout():
        """Zielfarbe + Tempo -> die drei Zerfallsraten, gesendet und quittiert.

        Eigener Endpoint statt einer Rechnung in app.js: die Umrechnung ist
        eine inhaltliche Aussage darueber, wie der Effekt den Puffer
        multipliziert, und nur hier ohne jsdom pruefbar. Das UI kennt nur
        Zielfarbe und Tempo -- also genau die zwei Dinge, die es anzeigt.
        """
        body = request.get_json(silent=True) or {}
        try:
            red = float(body.get("r"))
            green = float(body.get("g"))
            blue = float(body.get("b"))
            decay = float(body.get("decay"))
        except (TypeError, ValueError):
            return jsonify({"ok": False,
                            "error": "r, g, b und decay muessen Zahlen sein"}), 400
        for value in (red, green, blue, decay):
            if value != value or value in (float("inf"), float("-inf")):
                return jsonify({"ok": False,
                                "error": "ungueltige Zahl"}), 400
        rates = fade_from_target(red, green, blue, decay)
        applied: List[Applied] = []
        for address, value in zip(FADE_ADDRESSES, rates):
            param = store.get(address)
            if param is None:
                return jsonify({"ok": False,
                                "error": "unbekannte Adresse: %s" % address}), 400
            applied.append(apply_value(param, value))
        return jsonify({
            "ok": True,
            "applied": [{"address": a.address, "value": a.value, "sent": a.sent}
                        for a in applied],
        })

    @app.route("/api/songstructure")
    def api_song_structure():
        """Der Live-Zustand der Song-Struktur, gelesen vom Dateisystem.

        Kein OSC: imPulse sendet nur an Port 8002, und dort hoert
        SuperCollider. Dieser Server laeuft auf derselben Maschine wie imPulse
        und liest die Datei direkt -- genau wie die Preset-Liste.

        ``state: null`` ist der Normalfall vor dem ersten Levelwechsel und
        kein Fehler; das UI sagt dann "noch kein Wechsel".
        """
        return jsonify({"ok": True, "state": read_song_state(state_path),
                        "path": state_path})

    @app.route("/api/goto", methods=["POST"])
    def api_goto():
        """Manueller Levelwechsel.

        Eigene Route, weil /songStructure/goto ein KOMMANDO ist und bewusst
        nicht in remoteSettings.txt steht -- /api/set kennt nur Adressen aus
        dem Dump und wuerde es mit 400 ablehnen.
        """
        body = request.get_json(silent=True) or {}
        try:
            level = int(body.get("level"))
        except (TypeError, ValueError):
            return jsonify({"ok": False, "error": "Level ist keine Zahl"}), 400
        if not 1 <= level <= len(SONG_LEVEL_NAMES):
            return jsonify({"ok": False,
                            "error": "Level ausserhalb 1..%d"
                                     % len(SONG_LEVEL_NAMES)}), 400
        sender.send(SONG_GOTO_ADDRESS, level)
        return jsonify({"ok": True, "level": level,
                        "name": SONG_LEVEL_NAMES[level - 1]})

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
        # Die Nachleucht-Sektion zeigt Zielfarbe und Tempo, nicht die drei
        # rohen Raten -- sie kann sich also nicht aus "values" nachziehen.
        # Ohne diesen Block bliebe ihr Farbwaehler nach einem Preset-Wechsel
        # auf der vorigen Farbe stehen, ohne Fehlermeldung.
        result["fade"] = build_fade(store.by_address, store.values)
        result.update({"ok": True, "name": name})
        return jsonify(result)

    @app.route("/api/melody/recompute", methods=["POST"])
    def api_melody_recompute():
        """Die Melodie-Zuordnung neu berechnen lassen.

        Reihenfolge ist der ganze Punkt: erst werden alle vier Werte gesetzt,
        DANN geht /net/melody/recompute raus. imPulse liest die vier Werte im
        Moment des Kommandos und behandelt sie als EINEN Vorgang -- ein
        Kommando vor den Werten rechnete mit dem alten Stand, und drei
        Kommandos zwischendurch rechneten dreimal, davon zweimal mit einem
        halb gesetzten Zustand.

        Die Werte gehen ueber denselben apply_value()-Weg wie jeder andere
        Regler, damit der Server-Store nicht auseinanderlaeuft.

        Fire-and-forget wie /preset/load: es gibt keinen Rueckkanal von
        imPulse. Laeuft der Sketch nicht, merkt das UI es hier nicht -- anders
        als beim Preset-Speichern, wo eine Datei auf der Platte erscheint, auf
        die sich warten laesst. Der Erfolg zeigt sich in der Konsole von
        imPulse und daran, dass data/nodeMelody_<modus>.txt frischer wird.
        """
        body = request.get_json(silent=True) or {}
        values = body.get("values")
        if not isinstance(values, dict):
            return jsonify({"ok": False,
                            "error": "keine Werte im Request"}), 400

        applied: List[Applied] = []
        for key, _label, _hint in MELODY_FIELDS:
            if key not in values:
                continue
            param = store.get(MELODY_PREFIX + key)
            if param is None:
                # Ein aelterer imPulse-Stand kennt den Parameter nicht. Das
                # ist kein Fehler des Aufrufers -- die Sektion baut sich
                # ohnehin nur aus dem, was im Dump steht.
                continue
            try:
                number = float(values[key])
            except (TypeError, ValueError):
                return jsonify({"ok": False,
                                "error": "%s ist keine Zahl" % key}), 400
            if number != number:  # NaN
                return jsonify({"ok": False,
                                "error": "%s ist keine Zahl" % key}), 400
            applied.append(apply_value(param, number))

        if not applied:
            return jsonify({"ok": False,
                            "error": "kein bekannter Melodie-Parameter dabei "
                                     "-- laeuft ein aelterer imPulse-Stand?"}), 400

        # Ein Kommando ohne Argument gibt es im OSC-Encoder nicht, und
        # RemoteControlled*-Parameter sind das hier ohnehin nicht: der
        # Empfaenger prueft nur die Adresse (MelodyManager.digestMessage),
        # der Wert ist bedeutungslos. 1 statt 0, damit eine Fernsteuerung, die
        # auf "Wert ungleich 0" filtert, nicht ins Leere laeuft.
        sender.send(MELODY_RECOMPUTE, 1)
        return jsonify({
            "ok": True,
            "applied": [{"address": a.address, "value": a.value} for a in applied],
            "command": MELODY_RECOMPUTE,
        })

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

    @app.route("/api/palette")
    def api_palette():
        return jsonify(palette_payload())

    @app.route("/api/palette", methods=["POST"])
    def api_palette_save():
        """Die komplette Palette ersetzen.

        Voll-Liste statt Hinzufuegen/Entfernen einzelner Eintraege: die
        Reihenfolge haelt ohnehin der Browser, und zwei Endpoints auf
        derselben Datei waeren zwei Wege, die auseinanderlaufen koennen.
        Preis: zwei gleichzeitig offene Browser ueberschreiben sich
        gegenseitig. Bei einer Installation mit einem Operator ist das der
        richtige Tausch -- im README steht es trotzdem.

        Anders als /api/preset/save geht hier kein OSC raus: die Palette ist
        reine UI-Sache, imPulse kennt sie nicht.
        """
        body = request.get_json(silent=True) or {}
        entries, problem = validate_palette(body.get("entries"))
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        try:
            save_palette(palette_path, entries)
        except OSError as exc:
            return jsonify({"ok": False,
                            "error": "Palette nicht schreibbar: %s" % exc}), 500
        return jsonify(palette_payload())

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
    parser.add_argument("--palette",
                        default=os.environ.get("IMPULSE_PALETTE"),
                        help="Datei mit der Farbpalette "
                             "(Vorgabe: colorPalettes.txt neben --settings)")
    parser.add_argument("--state",
                        default=os.environ.get("IMPULSE_SONG_STATE"),
                        help="Zustandsdatei der Song-Struktur "
                             "(Vorgabe: %s neben --settings)"
                             % DEFAULT_STATE_FILENAME)
    parser.add_argument("--ui-state",
                        default=os.environ.get("IMPULSE_UI_STATE"),
                        help="Datei, in der die ueber dieses UI gesetzten "
                             "Werte gesichert werden, damit sie einen "
                             "Neustart des Servers ueberleben "
                             "(Vorgabe: %s neben --settings)"
                             % UI_STATE_FILENAME)
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
    parser.add_argument("--record-status-port", type=int,
                        default=int(os.environ.get("IMPULSE_RECORD_STATUS_PORT",
                                                   DEFAULT_RECORD_STATUS_PORT)),
                        help="UDP-Port, auf dem /klangnetz/record/status von "
                             "sclang erwartet wird; muss zu ~recordStatusPort "
                             "in klangnetz_bells.scd passen (Vorgabe: %(default)s)")
    parser.add_argument("--debug", action="store_true",
                        help="Flask-Debugmodus (Autoreload)")
    # Voreinstellung ist AN: der Ausloeser des Features ist ein Rechner, an dem
    # niemand daran denkt zu committen. Ein Entwickler-Checkout, der das nicht
    # will, schaltet es ab -- das ist der seltenere Fall und der, in dem
    # jemand hinschaut.
    parser.add_argument("--no-autocommit", action="store_true",
                        default=os.environ.get("IMPULSE_AUTOCOMMIT", "1") == "0",
                        help="die automatische lokale Sicherung der "
                             "Daten-Dateien abschalten")
    parser.add_argument("--autocommit-interval", type=float,
                        default=float(os.environ.get(
                            "IMPULSE_AUTOCOMMIT_INTERVAL",
                            autocommit.DEFAULT_INTERVAL_S)),
                        help="Sekunden zwischen zwei Sicherungslaeufen "
                             "(Vorgabe: %(default)s)")
    args = parser.parse_args(argv)

    settings_path = os.path.abspath(args.settings)
    presets_path = (os.path.abspath(args.presets) if args.presets
                    else default_presets_path(settings_path))
    palette_path = (os.path.abspath(args.palette) if args.palette
                    else default_palette_path(settings_path))
    state_path = (os.path.abspath(args.state) if args.state
                  else os.path.join(os.path.dirname(settings_path),
                                    DEFAULT_STATE_FILENAME))
    ui_state_path = (os.path.abspath(args.ui_state) if args.ui_state
                     else default_ui_state_path(settings_path))

    # Der Auto-Commit arbeitet auf dem Checkout, in dem dieser Server liegt --
    # nicht auf dem Ordner von --settings. Zeigt --settings woanders hin, ist
    # das ein Testaufbau und die Sicherung gehoert trotzdem hierher.
    scheduler = None
    if not args.no_autocommit:
        scheduler = autocommit.AutoCommitScheduler(
            autocommit.AutoCommitter(REPO_ROOT),
            interval_s=args.autocommit_interval)
        scheduler.start()

    app = create_app(settings_path, args.osc_host, args.osc_port, presets_path,
                     palette_path, state_path, autocommit_scheduler=scheduler,
                     record_status_port=args.record_status_port,
                     ui_state_path=ui_state_path)

    print("[webui] remoteSettings: %s" % settings_path)
    print("[webui] Presets:        %s" % presets_path)
    print("[webui] Palette:        %s" % palette_path)
    print("[webui] Song-Zustand:   %s" % state_path)
    print("[webui] UI-Zustand:     %s (nur was ueber dieses UI gesendet wurde)"
          % ui_state_path)
    print("[webui] OSC-Ziel:       %s:%d" % (args.osc_host, args.osc_port))
    print("[webui] Sound (sclang): %s:%d" % (args.osc_host, SC_OSC_PORT))
    print("[webui] Record-Status:  %s:%d (Rueckkanal von sclang)"
          % (DEFAULT_RECORD_STATUS_HOST, args.record_status_port))
    print("[webui] HTTP:           http://%s:%d" % (args.host, args.port))
    if scheduler is None:
        print("[webui] Auto-Commit:    aus")
    else:
        print("[webui] Auto-Commit:    alle %.0f s in %s, nur lokal (kein Push)"
              % (scheduler.interval_s, REPO_ROOT))
    app.run(host=args.host, port=args.port, debug=args.debug, threaded=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
