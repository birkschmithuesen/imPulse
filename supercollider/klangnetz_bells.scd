// =====================================================================
// KlangNetz / imPulse — Glockenspiel-Sound für Node-Treffer
// Zoom AMS-24 (4 Outputs) via ASIO
//
// KANONISCHE DATEI (2026-07-31): dies ist die EINZIGE SC-Sound-Datei fürs
// KlangNetz. Vorher gab es zwei parallele Versionen -- eine im Repo
// (`supercollider/klangnetz_bells.scd`, pentatonisch, Stereo, Ambisonics-
// Vorarbeit) und eine lose Kopie auf dem Windows-Laptop
// (`C:\Users\birk\klangnetz_bells_zoom.scd`, D-Kurd-Skala, 4-Kanal via
// Zoom AMS-24), die tatsächlich lief. Das führte zu einer echten
// Verwechslung: eine Analyse der Repo-Datei ergab falsche Tonwerte, weil
// der Live-Task eine andere Datei lud. Ab jetzt läuft der Windows-Task
// direkt aus diesem Repo-Pfad (kein `run_bells.bat`-Duplikat mehr), damit
// Repo-Stand und Live-Stand immer identisch sind.
//
// Update-Historie:
//  2026-07-30 (Birk): Skala D Kurd (Hang-Drum/Handpan, natürlich Moll ab D),
//    Grundton eine Oktave tiefer (D3->D2), mehr Obertöne/Sägezahn-Anteil
//    ("Reibung"/Kernigkeit), mehr Hall (FreeVerb).
//  2026-07-30 (Birk): Mindestlautstärke -10dB gegen zu leise Treffer.
//  2026-07-31 (Birk, vor Ort): +6dB Gesamtlautstärke (Faktor x2), Limiter
//    am Summenbus gegen Übersteuerung bei vielen gleichzeitigen Treffern,
//    Skala eine Quinte höher (+7 Halbtöne, D2->A2 als Grundton).
//  2026-07-31 (Birk, vor Ort): Lautstärke kurzzeitig -6dB, dann wieder auf
//    +6dB zurückgesetzt (dritte Korrektur).
//  2026-07-31 (Birk, vor Ort): Skala von natürlicher Moll (D Kurd) auf
//    Phrygisch geändert (kleine statt große Sekund als zweite Stufe),
//    Grundton A2 unverändert.
// =====================================================================
(
s.options.device = "ASIO : ZOOM AMS-24 ASIO Driver";
s.options.numOutputBusChannels = 4;
s.options.numInputBusChannels = 2;

~oscListenPort = 8002;

// Phrygisch (Halbtonabstaende von der Grundnote): kleine Sekund statt
// grosser Sekund als zweite Stufe -- der markanteste Unterschied zur
// vorherigen natuerlichen Moll (D Kurd). Birk vor Ort 2026-07-31.
~scaleSteps = [0, 1, 3, 5, 7, 8, 10];

// Grundton A2 (MIDI 45), unveraendert -- nur die Skala (Intervallstruktur)
// hat sich geaendert, nicht die Transposition.
~rootMidiNote = 45;
~numOctaves = 3;
~notesPerOctaveSet = ~scaleSteps.size * ~numOctaves;

// Mindestlautstärke deutlich angehoben (Birk 2026-07-30): manche Node-Treffer
// kommen mit so niedriger "energy" rein, dass sie unterhalb der Hörschwelle
// landeten (Verdacht: Randomizer/Jitter auf der Energy trotz augenscheinlich
// konstantem Wert). Fix: Range nach oben verschoben, damit auch die leisesten
// Treffer klar hörbar bleiben.
// -10dB gegenüber dem urspruenglichen Pegel (0.22/0.55), linear: *0.316228
// (Birk, 2026-07-30, "master -10db" für den Sound), DANACH +6dB (Faktor x2,
// Birk vor Ort 2026-07-31): 0.06957*2 / 0.17393*2. Kurzzeitig auf -6dB
// gegenueber dem -10dB-Stand reduziert, dann auf ausdruecklichen Wunsch
// wieder auf +6dB zurueckgesetzt (Birk vor Ort 2026-07-31, dritte
// Korrektur: "lass die Lautstaerke wie sie ist, mit den +6dB").
~minAmp = 0.13914;
~maxAmp = 0.34786;
~numOutChannels = 4;

// Limiter am Summenbus (Birk vor Ort 2026-07-31): bei vielen gleichzeitig
// klingenden Glocken summiert sich das Signal ueber alle 4 Kanaele und kann
// uebersteuern/verzerren, auch wenn jede einzelne Stimme innerhalb ~maxAmp
// bleibt. ~limiterCeiling ist die harte Ausgabegrenze (0.95 statt 1.0 als
// kleiner Sicherheitsabstand zur digitalen Vollausteuerung).
~limiterCeiling = 0.95;

// Polyphonie-Obergrenze (Birk 2026-07-31, nach Live-Ausfall): bei sehr hoher
// Trefferrate (viele Ambient-Spawns + hohe Speed gleichzeitig) kann die
// interne scsynth-Befehlswarteschlange ueberlaufen ("command FIFO full" im
// Log) -- der Server verwirft dann die meisten neuen Synth-Erzeugungen und
// es kommt kein Sound mehr raus, OHNE dass der Prozess abstuerzt (schwer zu
// erkennen: sclang/scsynth laufen weiter, "Wird ausgefuehrt" im Task-
// Manager). ~maxPolyphony deckelt die Anzahl gleichzeitig aktiver Glocken
// hart: kommt eine neue dazu und das Limit ist erreicht, wird die AELTESTE
// per Voice-Stealing sofort abgewuergt (n_free), bevor die neue startet.
// Das haelt die Anzahl aktiver Synths -- und damit die DSP-/Command-Last --
// dauerhaft unter der Grenze, an der der Server ueberlastet.
~maxPolyphony = 24;
~activeBells = List.new;

s.waitForBoot({
    // ---- Gruppen fuer garantierte Ausfuehrungsreihenfolge ---------------
    // Synth.new haengt standardmaessig an den KOPF (addToHead) der Ziel-
    // gruppe -- ohne explizite Gruppen wuerde ein spaeter gestarteter
    // Glocken-Synth VOR dem einmalig gestarteten Limiter landen und der
    // Limiter wuerde ein leeres/veraltetes Signal lesen. Zwei Gruppen lösen
    // das robust, unabhaengig vom Spawn-Zeitpunkt einzelner Glocken:
    // ~sourceGroup (Glocken) immer VOR ~fxGroup (Limiter) in der Node-Order.
    ~sourceGroup.free;
    ~fxGroup.free;
    ~sourceGroup = Group.new(s);
    ~fxGroup = Group.after(~sourceGroup);

    // ---- Privater 4-Kanal-Summenbus: alle Glocken schreiben hierher,
    // nicht mehr direkt auf den Hardware-Ausgang -- der Limiter liest von
    // hier und schreibt erst danach auf die vier ASIO-Kanaele. 4 Kanaele
    // (nicht 2/Stereo), weil jeder Node-Treffer auf einen von 4 physischen
    // Zoom-AMS-24-Ausgaengen verteilt wird (outCh = nodeId % 4).
    ~mixBus.free;
    ~mixBus = Bus.audio(s, ~numOutChannels);

    // Liste aktiver Glocken-Synths zuruecksetzen (Boot/Reboot: alte Synth-
    // Referenzen sind ungueltig, sonst wuerde spaeter versucht, laengst
    // verschwundene Nodes freizugeben).
    ~activeBells = List.new;

    SynthDef(\glockenBell, { |freq = 440, amp = 0.3, out = 0|
        var sig, bellSig, grit, partials, partialAmps, partialDecays, env0;

        // ---- Glocken-Grundklang: unharmonische Sinus-Partialtöne ----
        partials      = #[1.0, 2.001, 2.998, 4.204, 5.515];
        partialAmps   = #[1.0, 0.55, 0.30, 0.18, 0.10];
        partialDecays = #[3.2, 2.1, 1.35, 0.85, 0.55];

        bellSig = Mix.fill(partials.size, { |i|
            var pFreq, pAmp, pDecay, env;
            pFreq  = freq * partials[i];
            pAmp   = partialAmps[i];
            pDecay = partialDecays[i];
            env = EnvGen.kr(
                Env([0, 1, 0], [0.003, pDecay], curve: [\lin, -4]),
                doneAction: if(i == 0) { 2 } { 0 }
            );
            SinOsc.ar(pFreq) * pAmp * env;
        });

        // ---- "Reibung"/Kernigkeit: dezenter Sägezahn-Layer -----------
        // Kurzer, schneller abklingender Saw-Anteil, bandpass-gefiltert um den
        // Grundton herum -> gibt Attack/Obertongehalt (Hang-Drum-typisch "metallisch-
        // kernig"), ohne die Bell-Hülle zu dominieren oder hart/verzerrt zu klingen.
        env0 = EnvGen.kr(Env([0, 1, 0], [0.002, 0.35], curve: [\lin, -6]));
        grit = Saw.ar(freq) * env0;
        grit = BPF.ar(grit, freq * 2, 0.7) * 0.22;

        sig = (bellSig + grit) * amp;

        // ---- Hall: mehr räumliche Tiefe -------------------------------
        sig = FreeVerb.ar(sig, mix: 0.42, room: 0.75, damp: 0.35);

        Out.ar(out, sig);
    }).add;

    // ---- SynthDef: Limiter am Summenbus --------------------------------
    // Liest ~mixBus (wohin alle Glocken-Synths schreiben), begrenzt hart auf
    // ~limiterCeiling und schreibt erst danach auf die 4 Hardware-Kanaele.
    // Limiter.ar braucht KEINEN Attack/Release-Tuningaufwand wie ein
    // Compressor -- er ist ein reiner Peak-Limiter, der Samples notfalls
    // hart kappt, ohne vorher hoerbar zu komprimieren/pumpen. Laeuft
    // dauerhaft (nicht pro Treffer neu gestartet), deshalb eigene Gruppe
    // ~fxGroup NACH ~sourceGroup: der Bus muss beim Lesen bereits die Summe
    // aller in diesem Frame geschriebenen Glocken enthalten.
    SynthDef(\klangLimiter, { |in = 0, out = 0, ceiling = 0.95|
        var sig = In.ar(in, 4);
        sig = Limiter.ar(sig, ceiling, 0.01);
        Out.ar(out, sig);
    }).add;

    s.sync;

    // Limiter einmalig starten, liest ~mixBus, schreibt auf Hardware-out 0..3.
    ~limiterSynth.free;
    ~limiterSynth = Synth(\klangLimiter, [\in, ~mixBus, \out, 0,
        \ceiling, ~limiterCeiling], ~fxGroup);

    ~netHitNodeOscFunc.free;
    ~netHitNodeOscFunc = OSCFunc({ |msg, time, addr|
        var nodeId, energy, noteIndex, octave, degree, semitoneOffset, midiNote, freq, amp, outCh, newBell;

        nodeId = msg[1].asInteger;
        energy = msg[2].asFloat;

        noteIndex = nodeId % ~notesPerOctaveSet;
        octave = noteIndex div: ~scaleSteps.size;
        degree = noteIndex mod: ~scaleSteps.size;
        semitoneOffset = ~scaleSteps[degree] + (octave * 12);
        midiNote = ~rootMidiNote + semitoneOffset;
        freq = midiNote.midicps;

        amp = energy.clip(0.0, 2.0).linlin(0.0, 2.0, ~minAmp, ~maxAmp);

        outCh = nodeId % ~numOutChannels;

        // Polyphonie-Deckel: ist das Limit erreicht, die AELTESTE aktive
        // Glocke sofort abwuergen (voice stealing), bevor die neue startet.
        // ~activeBells haelt Synths in Erzeugungsreihenfolge -> removeAt(0)
        // ist immer die aelteste. Free ist sofort wirksam (kein Fade-out),
        // das ist ein bewusster Trade-off: ein kurzer harter Cut bei
        // Ueberlast-Grenze ist unauffaelliger als gar kein Sound mehr, weil
        // der Server komplett dicht ist.
        if (~activeBells.size >= ~maxPolyphony) {
            ~activeBells.removeAt(0).free;
        };

        // Glocken landen in ~sourceGroup (VOR dem Limiter in der Node-Order)
        // und schreiben auf den passenden Kanal des ~mixBus, nicht mehr
        // direkt auf Hardware-out -- der Limiter setzt hinter allen vier
        // Kanaelen an, siehe \klangLimiter oben. ~mixBus.index (nicht
        // ~mixBus direkt!) liefert den numerischen Bus-Index, den der
        // \out-Slot des Synths erwartet -- Bus-Objekte selbst haben keinen
        // '+'-Operator.
        newBell = Synth(\glockenBell, [\freq, freq, \amp, amp, \out, ~mixBus.index + outCh],
            ~sourceGroup);
        ~activeBells.add(newBell);
        // Natuerliches Verklingen (Envelope-doneAction 2 im SynthDef)
        // muss ~activeBells auch aufraeumen, sonst waechst die Liste
        // dauerhaft weiter und removeAt(0) wuergt irgendwann laengst
        // verklungene/nicht mehr existierende Synths ab (harmlos, aber
        // sinnlos -- das echte Voice-Stealing traefe dann die falschen).
        newBell.onFree({ ~activeBells.remove(newBell); });

        ("node % -> % Hz (amp %) on output ch %".format(nodeId, freq.round(0.1), amp.round(0.01), outCh)).postln;
    }, '/net/hitNode', recvPort: ~oscListenPort);

    "KlangNetz Hang-Drum-Bells (Phrygisch, Zoom AMS-24 4ch, Limiter): Server gebootet, hoere auf /net/hitNode Port % - bereit.".format(~oscListenPort).postln;
});
)
