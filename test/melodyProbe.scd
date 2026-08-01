// Funktionsprobe der topologiebasierten Notenzuordnung und des
// Ursprungs-Baum-Klangbias auf der SuperCollider-Seite.
//
// Aufruf (aus der Repo-Wurzel):
//   QT_QPA_PLATFORM=offscreen sclang -D test/melodyProbe.scd
//
// KEINE zweite Sound-Datei. Es gibt genau eine, und das ist
// supercollider/klangnetz_bells.scd (CLAUDE.md). Dieses Skript enthaelt
// nichts vom Klang, es LIEST die eine Datei und wertet ihren Kopfteil aus --
// alles bis zum s.waitForBoot, also genau die Teile, die keinen laufenden
// Server brauchen: ~scaleSteps, ~melodyModes, ~melodyLoad, ~treeBias, die
// Parameter-Registry. Damit kann es nicht zu einer zweiten Wahrheit werden;
// eine Aenderung an der Zuordnungslogik in der .scd wird hier zwangslaeufig
// mitgeprueft, weil dieselben Zeilen laufen.
//
// Was es NICHT prueft: Klang. Kein Server, kein Audio, keine SynthDefs. Die
// Klangseite hat in diesem Repo kein Testgeruest, dort gilt weiter manuelle
// Pruefung am Geraet. Was hier faellt, ist trotzdem sicher kaputt.
//
// Es spricht die Installation nicht an: es wird nichts gesendet, kein Port
// geoeffnet, keine Datei geschrieben.
(
var src, cut, head, failures = 0, checks = 0;
var check = { |what, condition|
    checks = checks + 1;
    if (condition.not) {
        failures = failures + 1;
        ("  FEHLER " ++ what).postln;
    };
};

src = File("supercollider/klangnetz_bells.scd", "r").readAllString;
if (src.isNil or: { src.size == 0 }) {
    "FEHLER: supercollider/klangnetz_bells.scd nicht lesbar (aus der Repo-Wurzel starten)".postln;
    1.exit;
};
cut = src.find("s.waitForBoot");
if (cut.isNil) {
    "FEHLER: kein s.waitForBoot in der Datei gefunden - Aufbau geaendert?".postln;
    1.exit;
};
// Der Kopfteil endet mitten im offenen "(" des einen Top-Level-Blocks, das
// wird hier geschlossen. Mehrere Top-Level-Bloecke haengen sclang -D auf,
// deshalb ist die .scd EIN Block - genau darum dieser Schnitt.
head = src.copyRange(0, cut - 1) ++ "\n);\n";
head.interpret;

// ~melodyDir haengt an thisProcess.nowExecutingPath und zeigt beim Laden aus
// diesem Skript auf test/ statt auf supercollider/. Fuer die Probe explizit
// setzen; im Betrieb rechnet die .scd selbst richtig.
~melodyDir = "data";

"---- Grundzustand ----".postln;
check.("acht Modi in ~melodyModes", ~melodyModes.size == 8);
check.("Reihenfolge beginnt mit dorisch", ~melodyModes[0][\key] == "dorisch");
check.("Index 4 ist phrygisch", ~melodyModes[4][\key] == "phrygisch");
check.("Index 7 ist saba", ~melodyModes[7][\key] == "saba");
// Spiegelung von MelodyModes.java: gleiche Reihenfolge, gleiche Skalen.
check.("phrygisch traegt den heute live laufenden Tonvorrat",
    ~melodyModes[4][\steps] == [0, 1, 3, 5, 7, 8, 10]);
check.("pentatonik hat fuenf Stufen", ~melodyModes[1][\steps].size == 5);

// Der umbenannte Bias-Parameter muss registriert sein, der alte weg.
check.("treeBiasAmount ist registriert", ~params[\treeBiasAmount].notNil);
check.("regionBiasAmount gibt es nicht mehr", ~params[\regionBiasAmount].isNil);
check.("melodyMode ist registriert", ~params[\melodyMode].notNil);
check.("melodyRootMidiNote ist registriert", ~params[\melodyRootMidiNote].notNil);
check.("melodyNumOctaves ist registriert", ~params[\melodyNumOctaves].notNil);
// Bewusst NICHT registriert: SC rechnet nichts neu, der Regler waere hier
// ohne Wirkung und wanderte trotzdem in jedes Sound-Preset.
check.("melodyStartNode ist bewusst NICHT registriert", ~params[\melodyStartNode].isNil);

"---- Baum-Bias ----".postln;
~treeBiasAmount = 1.0;
check.("Baum -1 ist neutral", ~treeBias.(-1)[\noteOffset] == 0);
check.("Baum -1 laesst brightness in Ruhe", ~treeBias.(-1)[\brightness] == 1.0);
check.("Baum ausserhalb der Tabelle ist neutral", ~treeBias.(99)[\noteOffset] == 0);
check.("nil ist neutral", ~treeBias.(nil)[\noteOffset] == 0);
check.("Baum 0 verschiebt um -2 Stufen", ~treeBias.(0)[\noteOffset] == -2);
check.("Baum 1 ist der neutrale Baum", ~treeBias.(1)[\noteOffset] == 0);
check.("Baum 3 verschiebt um +4 Stufen", ~treeBias.(3)[\noteOffset] == 4);
~treeBiasAmount = 0.0;
// amount = 0 IST aus: bitgleich dem Klang ohne dieses Feature.
4.do({ |t|
    check.("bei amount 0 ist Baum " ++ t ++ " notenneutral",
        ~treeBias.(t)[\noteOffset] == 0);
    check.("bei amount 0 ist Baum " ++ t ++ " klangneutral",
        (~treeBias.(t)[\brightness] == 1.0) and: { ~treeBias.(t)[\detune] == 1.0 });
});
~treeBiasAmount = 0.6;

"---- Rueckfall ohne Datei ----".postln;
~melodyLoad.("gibtsnichtgarantiert");
check.("ohne Datei ist die Zuordnung leer", ~melodyScaleIndex.size == 0);
check.("und kein Modus als geladen vermerkt", ~melodyLoadedKey.isNil);
// Der Rueckfall ist die ALTE nodeId-Formel, nicht die Tonika: eine fehlende
// Datei soll klingen wie frueher, nicht wie 92 gleiche Toene.
check.("Rueckfall auf die nodeId", (~melodyScaleIndex[17] ? 17) == 17);

"---- Laden der echten Zuordnung ----".postln;
if (File.exists("data/nodeMelody_phrygisch.txt").not) {
    "  Hinweis: data/nodeMelody_phrygisch.txt fehlt, Ladeprobe uebersprungen".postln;
} {
    var loaded = ~melodyLoad.("phrygisch");
    check.("es werden Knoten geladen", loaded > 0);
    check.("die Zuordnung ist gefuellt", ~melodyScaleIndex.size == loaded);
    check.("der Modus ist vermerkt", ~melodyLoadedKey == "phrygisch");
    // Der Dateikopf gewinnt - die Faltung ist mit genau diesen Werten
    // gerechnet worden.
    check.("Grundton aus dem Kopf", ~rootMidiNote == 45);
    check.("Oktavzahl aus dem Kopf", ~numOctaves == 3);
    check.("scaleSteps aus dem Kopf", ~scaleSteps == [0, 1, 3, 5, 7, 8, 10]);
    check.("notesPerOctaveSet neu gerechnet",
        ~notesPerOctaveSet == (~scaleSteps.size * ~numOctaves));

    // Jede geladene Stufe liegt im gefalteten Bereich, jede daraus
    // abgeleitete Note in der garantierten Spanne.
    ~melodyScaleIndex.keysValuesDo({ |nodeId, idx|
        var octave = idx div: ~scaleSteps.size;
        var midi = ~rootMidiNote + ~scaleSteps[idx mod: ~scaleSteps.size] + (octave * 12);
        check.("Stufe von Knoten " ++ nodeId ++ " im Bereich",
            (idx >= 0) and: { idx < ~notesPerOctaveSet });
        check.("MIDI von Knoten " ++ nodeId ++ " in der Spanne",
            (midi >= ~rootMidiNote) and: { midi <= (~rootMidiNote + 10 + 24) });
    });

    // Ein Testknoten durch dieselbe Kette wie im /net/hitNode-Handler.
    "  Beispielknoten (dieselbe Rechnung wie ~netHitNodeOscFunc):".postln;
    [0, 1, 45, 91, 99999].do({ |nodeId|
        [-1, 0, 3].do({ |tree|
            var bias = ~treeBias.(tree);
            var baseIndex = ~melodyScaleIndex[nodeId] ? nodeId;
            var noteIndex = ((baseIndex + bias[\noteOffset]) % ~notesPerOctaveSet).abs;
            var octave = noteIndex div: ~scaleSteps.size;
            var degree = noteIndex mod: ~scaleSteps.size;
            var midiNote = ~rootMidiNote + ~scaleSteps[degree] + (octave * 12);
            check.("Note von Knoten " ++ nodeId ++ " ist plausibel",
                (midiNote >= 24) and: { midiNote <= 108 });
            ("    node % baum % -> stufe % -> MIDI % = % Hz".format(
                nodeId, tree, noteIndex, midiNote, midiNote.midicps.round(0.1))).postln;
        });
    });

    // Ein unbekannter Knoten faellt weiterhin auf die nodeId zurueck, auch
    // wenn eine Datei geladen ist - der Fall nach einer Kalibriersitzung,
    // die eine Kreuzung hinzugefuegt hat.
    check.("unbekannter Knoten faellt auf die nodeId zurueck",
        (~melodyScaleIndex[99999] ? 99999) == 99999);
};

if (failures == 0) {
    ("melodyProbe: % Pruefungen, alle bestanden".format(checks)).postln;
    0.exit;
} {
    ("melodyProbe: % Pruefungen, % Fehler".format(checks, failures)).postln;
    1.exit;
};
)
