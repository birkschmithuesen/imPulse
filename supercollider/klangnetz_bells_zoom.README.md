# supercollider/klangnetz_bells_zoom.scd

Live-Snapshot des SC-Patches, der aktuell auf dem KlangNetz-Windows-Laptop
läuft (`C:\Users\birk\klangnetz_bells_zoom.scd`), inkl. `-10dB`-Amplituden-Fix
(Birk, 2026-07-30): `~minAmp` 0.22→0.06957, `~maxAmp` 0.55→0.17393.

Einfache 4-Kanal-Verteilung (`outCh = nodeId % 4`), D-Kurd-Skala — dies ist
der **Working State für den heutigen Show-Betrieb**, NICHT die Ambisonics-
Weiterentwicklung.

Master-LED-Helligkeit (`/master/level`, separat vom Sound) läuft mit
Java-Boot-Default `0.1` (Sicherheitsventil, `ArtNetOutput.java:43`) und wird
für den Live-Betrieb per OSC auf `1.0` gesetzt — siehe
`scenes/hang_drum_slow/`.
