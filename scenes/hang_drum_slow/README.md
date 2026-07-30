# Szene: Hang Drum Slow

Gespeichert: 2026-07-30, Snapshot der laufenden Installation vor dem Umschalten
auf die Standby-Szene.

- `remoteSettings.txt` — 1:1-Kopie des live gezogenen Standes von
  `C:\Users\birk\Documents\imPulse\data\remoteSettings.txt` (kompletter
  imPulse-Parametersatz zum Zeitpunkt der Sicherung, inkl. Ambient-Spawns aktiv).
- `klangnetz_bells.scd` — SuperCollider-Patch (Bells, pentatonisch), unverändert
  gegenüber `supercollider/klangnetz_bells.scd` im Repo-Root zum Zeitpunkt der
  Sicherung.

**Hinweis:** Dies ist ein reiner Werte-Snapshot (Textdatei-Kopie), kein aktives
Preset-Loading-Feature — imPulse liest `data/remoteSettings.txt` nur beim Start
neu ein und schreibt sie bei jedem Start aus dem Code-Stand neu (siehe
`CLAUDE.md`). Um diese Szene wieder live zu fahren: Werte per
`scripts/osc_send.py` erneut senden (Restart würde stattdessen die
Code-Defaults laden, nicht diese Szene).
