# Szene: Standby

Abgeleitet von `Hang Drum Slow` (siehe dort), 2026-07-30. Diff zur Basis-Szene:

| Parameter | Hang Drum Slow | Standby |
|---|---|---|
| `/net/randomSpawn/enabled` | 1 | **0** — keine Ambient-Impulse mehr, Netz feuert nur noch auf echten Tube-Trigger |
| `/nodes/colors/central/fired/Hue` | 1.0 (rot) | **0.45** (grün→zyan) |
| `/nodes/colors/central/waiting/Hue` | 0.0 (rot) | **0.45** |
| `/nodes/colors/outer/fired/Hue` | 1.0 (rot) | **0.45** |
| `/nodes/colors/outer/waiting/Hue` | 0.0 (rot) | **0.45** |
| `/master/level` | 1.0 | **0.1** — Show-Fader heruntergefahren |

Restliche Parameter (Speed, fadeOut, Node-Radien etc.) unverändert
aus der Basis-Szene übernommen. `klangnetz_bells.scd` unverändert (Sound-Patch
identisch zu Hang Drum Slow).

**Live gesendet** am 2026-07-30 via `osc_send.py`, verifiziert:
`VERDICT=RENDERING_AND_OSC_LIVE`.

**Reload-Hinweis:** wie bei Hang Drum Slow — derselbe Wertesatz liegt seit dem
Preset-System (2026-07-30) als ladbares Preset unter
`data/presets/standby.txt`. Reaktivieren also per `/preset/load standby`
statt per erneutem Senden aller Einzelwerte.
