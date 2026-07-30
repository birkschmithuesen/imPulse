# Szene: Hang Drum Slow

Gespeichert: 2026-07-30, Snapshot der laufenden Installation vor dem Umschalten
auf die Standby-Szene.

- `remoteSettings.txt` — 1:1-Kopie des live gezogenen Standes von
  `C:\Users\birk\Documents\imPulse\data\remoteSettings.txt` (kompletter
  imPulse-Parametersatz zum Zeitpunkt der Sicherung, inkl. Ambient-Spawns aktiv).
- `klangnetz_bells.scd` — SuperCollider-Patch (Bells, pentatonisch), unverändert
  gegenüber `supercollider/klangnetz_bells.scd` im Repo-Root zum Zeitpunkt der
  Sicherung.

**Hinweis:** Dies war ursprünglich ein reiner Werte-Snapshot ohne Ladefunktion.
Seit dem Preset-System (2026-07-30) liegt derselbe Wertesatz als ladbares
Preset unter `data/presets/hang_drum_slow.txt` — abzurufen per
`/preset/load hang_drum_slow`, per `IMPULSE_PRESET=hang_drum_slow` beim Start
oder über den Scheduler. Die Datei hier bleibt als Herkunftsbeleg liegen: sie
ist die 1:1-Kopie des live gezogenen Standes, das Preset ist die daraus
abgeleitete, um die zwei Kommandozeilen bereinigte und nach Adresse sortierte
Fassung.
