#!/usr/bin/env python3
"""Tests fuer die Logik hinter dem Web-UI.

Laeuft mit der Standardbibliothek allein -- weder Flask noch python-osc noch
eine laufende Installation noetig. Geprueft wird das, was zwischen
remoteSettings.txt und dem OSC-Paket schiefgehen kann:

    python3 webui/test_webui.py
"""

import os
import re
import shutil
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server  # noqa: E402
from server import (ADVANCED_ADDRESSES, ADVANCED_GROUP_KEY, Parameter, ParameterStore,  # noqa: E402
                    TRIGGER_ADDRESSES, build_groups, build_osc_message,
                    coupled_values, group_key, list_presets, parse_settings,
                    valid_preset_name)


# Ausschnitt aus einem echten Dump (Tabs!), inklusive der Eigenheiten:
# wissenschaftliche Notation, Farbtripel, Adressen ohne fuehrenden Slash.
SAMPLE = "\n".join([
    "int\t/net/impulse/speed\tspace for descripiton\t160\t1\t1500",
    "float\t/net/impulse/lifetime\tspace for descripiton\t0.2\t1.0E-4\t1.0",
    "float\t/net/impulse/nodeDeadTime\tspace for descripiton\t1.0\t0.0\t10.0",
    "float\t/net/randomSpawn/interval\tspace for descripiton\t3.0\t0.05\t40.0",
    "int\t/net/randomSpawn/enabled\tspace for descripiton\t1\t0\t1",
    "float\t/nodes/times/recover\tspace for descripiton\t4.0\t0.0\t10.0",
    "float\t/nodes/colors/outer/fired/Hue\tspace for descripiton\t1.0\t0\t1",
    "float\t/nodes/colors/outer/fired/Sat\tspace for descripiton\t1.0\t0\t1",
    "float\t/nodes/colors/outer/fired/Bright\tspace for descripiton\t1.0\t0\t1",
    "float\tMaster/trace\tspace for descripiton\t0.0\t0.0\t1.0",
    "float\tMaster/0/opacity/0.Impulse\tspace for descripiton\t1.0\t0.0\t1.0",
]) + "\n"


def by_address(parameters):
    return {p.address: p for p in parameters}


class ParseTest(unittest.TestCase):
    def test_reads_all_lines(self):
        params = parse_settings(SAMPLE)
        self.assertEqual(len(params), 11)

    def test_fields(self):
        param = by_address(parse_settings(SAMPLE))["/net/impulse/lifetime"]
        self.assertEqual(param.type, "float")
        self.assertAlmostEqual(param.value, 0.2)
        self.assertAlmostEqual(param.minimum, 1.0e-4)  # wissenschaftliche Notation
        self.assertAlmostEqual(param.maximum, 1.0)

    def test_int_type(self):
        param = by_address(parse_settings(SAMPLE))["/net/impulse/speed"]
        self.assertTrue(param.is_int)
        self.assertEqual(param.step(), 1.0)

    def test_crlf_and_blank_lines(self):
        text = SAMPLE.replace("\n", "\r\n") + "\r\n   \r\n"
        self.assertEqual(len(parse_settings(text)), 11)

    def test_broken_lines_are_skipped_not_fatal(self):
        text = ("kaputt\n"
                "float\t/a/b\td\tnichtszahl\t0\t1\n"
                "quatsch\t/c/d\td\t1\t0\t1\n"
                + SAMPLE)
        self.assertEqual(len(parse_settings(text)), 11)

    def test_duplicate_address_wins_first(self):
        text = SAMPLE + "float\tMaster/trace\tzweitfassung\t0.5\t0.0\t1.0\n"
        params = by_address(parse_settings(text))
        self.assertEqual(params["Master/trace"].description, "space for descripiton")


class NormalizeTest(unittest.TestCase):
    """imPulse mappt eingehende Floats per map(v, 0, 1, min, max) selbst."""

    def test_float_is_normalized_to_unit_range(self):
        param = Parameter("float", "/nodes/times/recover", "", 4.0, 0.0, 10.0)
        self.assertAlmostEqual(param.normalize(4.0), 0.4)
        self.assertAlmostEqual(param.normalize(0.0), 0.0)
        self.assertAlmostEqual(param.normalize(10.0), 1.0)

    def test_float_with_offset_minimum(self):
        param = Parameter("float", "/net/impulse/color/gamma", "", 1.0, 0.1, 5.0)
        self.assertAlmostEqual(param.normalize(0.1), 0.0)
        self.assertAlmostEqual(param.normalize(5.0), 1.0)
        self.assertAlmostEqual(param.normalize(2.55), 0.5)

    def test_float_is_clamped_before_normalizing(self):
        param = Parameter("float", "/nodes/times/recover", "", 4.0, 0.0, 10.0)
        self.assertAlmostEqual(param.normalize(-5.0), 0.0)
        self.assertAlmostEqual(param.normalize(99.0), 1.0)

    def test_hsb_component_passes_through(self):
        # Farbkomponenten haben min 0 / max 1 und werden von
        # RemoteControlledColorParameter roh (nur constrain) verarbeitet --
        # die Normalisierung ist hier also die Identitaet.
        param = Parameter("float", "/nodes/colors/outer/fired/Hue", "", 1.0, 0.0, 1.0)
        self.assertAlmostEqual(param.normalize(0.37), 0.37)

    def test_int_stays_integer(self):
        param = Parameter("int", "/net/impulse/speed", "", 160, 1, 1500)
        sent = param.normalize(423.4)
        self.assertIsInstance(sent, int)
        self.assertEqual(sent, 423)

    def test_int_is_clamped(self):
        param = Parameter("int", "/net/impulse/speed", "", 160, 1, 1500)
        self.assertEqual(param.normalize(0), 1)
        self.assertEqual(param.normalize(9999), 1500)

    def test_degenerate_range_does_not_divide_by_zero(self):
        param = Parameter("float", "/x/y", "", 1.0, 1.0, 1.0)
        self.assertEqual(param.normalize(1.0), 0.0)


class GroupingTest(unittest.TestCase):
    def test_group_keys(self):
        self.assertEqual(group_key("/net/impulse/speed"), "net/impulse")
        self.assertEqual(group_key("/net/impulse/nodeDeadTime"), "net/impulse")
        # Farbe + FadeOut werden bewusst aus /net/impulse herausgezogen in
        # eine eigene Gruppe (Impuls-Bewegung/-Zeit vs. Impuls-Farbe, siehe
        # docs/webui-parameter-review-2026-07-30.md Abschnitt 2)
        self.assertEqual(group_key("/net/impulse/color/gamma"), "net/impulse/color")
        self.assertEqual(group_key("/net/impulse/fadeOut/r"), "net/impulse/color")
        # dasselbe fuer die Sinus-Randomizer: ohne den Sonderfall schneidet
        # group_key() nach zwei Segmenten ab und sie landeten unter
        # "net/impulse", zwischen den Reglern, die sie steuern
        self.assertEqual(group_key("/net/impulse/speed/randomize/enabled"),
                         "net/impulse/randomize")
        self.assertEqual(group_key("/net/impulse/lifetime/randomize/period"),
                         "net/impulse/randomize")
        self.assertEqual(group_key("/net/randomSpawn/interval"), "net/randomSpawn")
        self.assertEqual(group_key("/net/activateNode"), "net")
        self.assertEqual(group_key("/nodes/colors/outer/fired/Hue"), "nodes/colors")
        self.assertEqual(group_key("/nodes/fadeOutGamma"), "nodes")
        self.assertEqual(group_key("Master/trace"), "Master")
        # rein numerische Segmente fallen raus, sonst haette jeder Mixer-Kanal
        # eine eigene Gruppe
        self.assertEqual(group_key("Master/0/opacity/0.Impulse"), "Master/opacity")

    def test_impulse_color_group_has_its_own_human_readable_title(self):
        text = "\n".join([
            "float\t/net/impulse/color/r\td\t1.0\t0\t1",
            "float\t/net/impulse/fadeOut/r\td\t0.97\t0\t1",
            "int\t/net/impulse/speed\td\t160\t1\t1500",
        ])
        groups = {g["key"]: g for g in build_groups(parse_settings(text))}
        self.assertIn("net/impulse/color", groups)
        self.assertEqual(groups["net/impulse/color"]["title"], "Impuls-Farbe")
        color_addrs = {c["address"] for c in groups["net/impulse/color"]["controls"]}
        self.assertEqual(color_addrs, {"/net/impulse/color/r", "/net/impulse/fadeOut/r"})
        # speed bleibt in der Bewegungs-Gruppe, nicht in Impuls-Farbe
        self.assertNotIn("/net/impulse/speed", color_addrs)

    def test_randomizer_group_is_separate_and_keeps_its_toggles(self):
        text = "\n".join([
            "int\t/net/impulse/speed\td\t16\t1\t1500",
            "float\t/net/impulse/lifetime\td\t0.02\t0.0001\t1",
            "int\t/net/impulse/speed/randomize/enabled\td\t0\t0\t1",
            "int\t/net/impulse/speed/randomize/min\td\t16\t1\t1500",
            "int\t/net/impulse/speed/randomize/max\td\t160\t1\t1500",
            "float\t/net/impulse/speed/randomize/period\td\t30\t1\t300",
            "int\t/net/impulse/lifetime/randomize/enabled\td\t0\t0\t1",
            "float\t/net/impulse/lifetime/randomize/min\td\t0.005\t0.0001\t1",
            "float\t/net/impulse/lifetime/randomize/max\td\t0.05\t0.0001\t1",
            "float\t/net/impulse/lifetime/randomize/period\td\t20\t1\t300",
        ])
        groups = {g["key"]: g for g in build_groups(parse_settings(text))}
        self.assertIn("net/impulse/randomize", groups)
        self.assertEqual(groups["net/impulse/randomize"]["title"],
                         "Impuls-Randomizer (Sinus)")
        widgets = {c["address"]: c["widget"]
                   for c in groups["net/impulse/randomize"]["controls"]}
        self.assertEqual(len(widgets), 8)
        self.assertEqual(widgets["/net/impulse/speed/randomize/enabled"], "toggle")
        self.assertEqual(widgets["/net/impulse/lifetime/randomize/enabled"], "toggle")
        self.assertEqual(widgets["/net/impulse/speed/randomize/max"], "slider")
        # die gesteuerten Parameter selbst bleiben in der Bewegungs-Gruppe
        moved = {c["address"] for c in groups["net/impulse"]["controls"]}
        self.assertEqual(moved, {"/net/impulse/speed", "/net/impulse/lifetime"})

    def test_randomizer_group_sorts_between_impuls_and_impuls_farbe(self):
        text = "\n".join([
            "int\t/net/impulse/speed\td\t16\t1\t1500",
            "float\t/net/impulse/color/r\td\t1.0\t0\t1",
            "int\t/net/impulse/speed/randomize/enabled\td\t0\t0\t1",
        ])
        keys = [g["key"] for g in build_groups(parse_settings(text))]
        self.assertEqual(keys, ["net/impulse", "net/impulse/randomize",
                                "net/impulse/color"])

    def test_color_triple_becomes_one_control(self):
        groups = {g["key"]: g for g in build_groups(parse_settings(SAMPLE))}
        controls = groups["nodes/colors"]["controls"]
        self.assertEqual(len(controls), 1)
        self.assertEqual(controls[0]["kind"], "color")
        self.assertEqual(controls[0]["base"], "/nodes/colors/outer/fired")
        self.assertEqual(
            controls[0]["components"]["hue"]["address"], "/nodes/colors/outer/fired/Hue")

    def test_incomplete_triple_stays_plain_sliders(self):
        text = "\n".join([
            "float\t/nodes/colors/outer/fired/Hue\td\t1.0\t0\t1",
            "float\t/nodes/colors/outer/fired/Sat\td\t1.0\t0\t1",
        ])
        controls = build_groups(parse_settings(text))[0]["controls"]
        self.assertEqual([c["kind"] for c in controls], ["param", "param"])

    def test_zero_one_int_gets_a_toggle(self):
        groups = {g["key"]: g for g in build_groups(parse_settings(SAMPLE))}
        widgets = {c["address"]: c["widget"]
                   for c in groups["net/randomSpawn"]["controls"]}
        self.assertEqual(widgets["/net/randomSpawn/enabled"], "toggle")
        self.assertEqual(widgets["/net/randomSpawn/interval"], "slider")

    def test_every_parameter_shows_up_exactly_once(self):
        params = parse_settings(SAMPLE)
        seen = set()
        for group in build_groups(params):
            for control in group["controls"]:
                if control["kind"] == "color":
                    for component in control["components"].values():
                        seen.add(component["address"])
                else:
                    seen.add(control["address"])
        self.assertEqual(seen, {p.address for p in params})

    def test_trigger_addresses_get_trigger_widget_not_slider(self):
        text = "\n".join([
            "int\t/net/activateNode\tsactivateNode\t0\t0\t84",
            "int\t/net/activateStripe\tactivateStripe\t0\t0\t29",
        ])
        groups = {g["key"]: g for g in build_groups(parse_settings(text))}
        controls = groups["net"]["controls"]
        self.assertEqual({c["address"] for c in controls}, TRIGGER_ADDRESSES)
        for control in controls:
            self.assertEqual(control["kind"], "trigger")

    def test_advanced_addresses_land_in_their_own_group(self):
        text = "\n".join([
            "int\t/net/impulse/energyExponent\td\t2\t1\t10",
            "int\t/net/impulse/oscMaxCount\td\t32\t0\t256",
            "int\t/net/impulse/speed\td\t160\t1\t1500",
        ])
        groups = {g["key"]: g for g in build_groups(parse_settings(text))}
        self.assertIn(ADVANCED_GROUP_KEY, groups)
        advanced_addrs = {c["address"] for c in groups[ADVANCED_GROUP_KEY]["controls"]}
        self.assertEqual(advanced_addrs, ADVANCED_ADDRESSES & {
            "/net/impulse/energyExponent", "/net/impulse/oscMaxCount",
        })
        self.assertEqual(groups[ADVANCED_GROUP_KEY]["title"], "Advanced")
        # speed bleibt in seiner regulaeren Gruppe, wandert nicht mit
        self.assertNotIn("/net/impulse/speed", advanced_addrs)

    def test_advanced_group_sorts_after_regular_groups(self):
        text = "\n".join([
            "int\t/net/impulse/energyExponent\td\t2\t1\t10",
            "float\tMaster/trace\td\t0.0\t0.0\t1.0",
        ])
        keys = [g["key"] for g in build_groups(parse_settings(text))]
        self.assertEqual(keys[-1], ADVANCED_GROUP_KEY)

    def test_ui_range_override_narrows_display_range(self):
        # /net/impulse/speed: Java-Range 1..1500, UI-Override 1..100 (siehe
        # docs/webui-parameter-review-2026-07-30.md Abschnitt 1, Birk-Freigabe
        # 2026-07-30, nachjustiert auf 100 nach Live-Test) -- der Regler
        # zeigt/erlaubt nur die engere Range.
        text = "int\t/net/impulse/speed\td\t160\t1\t1500"
        param = parse_settings(text)[0]
        d = param.as_dict()
        self.assertEqual(d["min"], 1)
        self.assertEqual(d["max"], 100)

    def test_lifetime_ui_range_is_narrowed(self):
        # 2026-07-30, Birk: Energiezerfall auf 0.1 verengt (volle Java-Range
        # 0.0001..1.0 war am unteren, tatsaechlich genutzten Ende zu grobstufig
        # fuer den Slider). Adresse seit 2026-07-31 /net/impulse/lifetime.
        text = "float\t/net/impulse/lifetime\td\t0.02\t0.0001\t1.0"
        param = parse_settings(text)[0]
        self.assertEqual(param.as_dict()["max"], 0.1)

    def test_ui_range_override_never_exceeds_actual_range(self):
        # Ein aelterer/kleinerer Dump koennte eine engere Java-Range als der
        # Override haben -- das Sicherheitsnetz in ui_range() muss dann auf
        # die tatsaechliche Range zurueckfallen, nicht eine ungueltige weite
        # Anzeige-Range liefern.
        text = "int\t/net/impulse/speed\td\t50\t1\t100"
        param = parse_settings(text)[0]
        d = param.as_dict()
        self.assertEqual(d["min"], 1)
        self.assertEqual(d["max"], 100)

    def test_ui_range_override_does_not_affect_actual_clamping(self):
        # coerce()/normalize() (das, was tatsaechlich gesendet wird) bleibt
        # auf der vollen Java-Range -- ein Wert ueber der UI-Override-Range
        # aber innerhalb der echten Range darf weiterhin gesetzt werden
        # (z.B. wenn der Server-seitige Zustand ihn schon so hatte).
        text = "int\t/net/impulse/speed\td\t160\t1\t1500"
        param = parse_settings(text)[0]
        self.assertEqual(param.coerce(800), 800)


class SpeedCouplingTest(unittest.TestCase):
    def setUp(self):
        self.store = ParameterStore(path="<test>")
        self.store.parameters = parse_settings(SAMPLE)
        self.store.by_address = by_address(self.store.parameters)
        self.store.values = {p.address: p.coerce(p.value)
                             for p in self.store.parameters}

    def applied(self, speed):
        targets, skipped = coupled_values(self.store, speed)
        return {p.address: v for p, v in targets}, skipped

    def test_reference_speed_reproduces_reference_values(self):
        values, skipped = self.applied(160)
        self.assertEqual(skipped, [])
        self.assertAlmostEqual(values["/net/impulse/lifetime"], 0.2)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 1.0)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 3.0)

    def test_double_speed(self):
        values, _ = self.applied(320)
        self.assertAlmostEqual(values["/net/impulse/lifetime"], 0.4)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 0.5)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 1.5)

    def test_half_speed(self):
        values, _ = self.applied(80)
        self.assertAlmostEqual(values["/net/impulse/lifetime"], 0.1)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 2.0)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 6.0)

    def test_results_are_clamped_to_the_files_ranges(self):
        # Speed 1 = Faktor 1/160: nodeDeadTime und interval laufen weit ueber
        # ihr Maximum.
        values, _ = self.applied(1)
        # lifetime: 0.2 * (1/160) = 0.00125, liegt noch innerhalb ihres
        # eigenen Bereichs [1e-4, 1.0] -- keine Klemmung noetig.
        self.assertAlmostEqual(values["/net/impulse/lifetime"], 0.00125)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 10.0)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 40.0)

    def test_missing_addresses_are_reported_not_crashed(self):
        # Dump aus einer Zeit vor den Random-Spawns
        text = "\n".join(line for line in SAMPLE.splitlines()
                         if "randomSpawn" not in line)
        self.store.parameters = parse_settings(text)
        self.store.by_address = by_address(self.store.parameters)
        values, skipped = self.applied(320)
        self.assertNotIn("/net/randomSpawn/interval", values)
        self.assertEqual([s["address"] for s in skipped],
                         ["/net/randomSpawn/interval"])

    def test_all_coupled_targets_are_float_parameters(self):
        # Ints duerfen nicht normalisiert werden -- die Kopplung darf also
        # keinen Int-Parameter treffen, ohne dass das hier auffaellt.
        for address in server.SPEED_COUPLED:
            param = self.store.by_address.get(address)
            if param is not None:
                self.assertEqual(param.type, "float", address)


class OscEncodingTest(unittest.TestCase):
    def test_float_message_layout(self):
        # Adresslaenge 24 = Vielfaches von 4, also ein volles Null-Wort Padding
        packet = build_osc_message("/net/impulse/color/gamma", 0.25)
        self.assertEqual(len(packet) % 4, 0)
        self.assertTrue(packet.startswith(b"/net/impulse/color/gamma\x00\x00\x00\x00"))
        self.assertIn(b",f\x00\x00", packet)
        self.assertEqual(struct.unpack(">f", packet[-4:])[0], 0.25)

    def test_int_message_layout(self):
        packet = build_osc_message("/net/impulse/speed", 320)
        self.assertEqual(len(packet) % 4, 0)
        self.assertIn(b",i\x00\x00", packet)
        self.assertEqual(struct.unpack(">i", packet[-4:])[0], 320)

    def test_address_without_leading_slash(self):
        # mixer.java registriert "Master/trace" genau so; checkAddrPattern
        # vergleicht die Zeichenkette, ein vorangestelltes "/" wuerde nicht
        # mehr passen.
        packet = build_osc_message("Master/trace", 0.5)
        self.assertTrue(packet.startswith(b"Master/trace\x00\x00\x00\x00"))

    def test_padding_for_address_length_multiple_of_four(self):
        packet = build_osc_message("/abc", 1.0)   # 4 Zeichen -> 4 Nullbytes
        self.assertTrue(packet.startswith(b"/abc\x00\x00\x00\x00"))
        self.assertEqual(len(packet), 8 + 4 + 4)

    def test_string_message_layout(self):
        packet = build_osc_message("/preset/load", "standby")
        self.assertEqual(len(packet) % 4, 0)
        self.assertTrue(packet.startswith(b"/preset/load\x00\x00\x00\x00"))
        self.assertIn(b",s\x00\x00", packet)
        self.assertTrue(packet.endswith(b"standby\x00"))

    def test_string_padding_multiple_of_four(self):
        # "acht" -> 4 Zeichen, also vier Nullbytes, nicht eines
        packet = build_osc_message("/preset/load", "acht")
        self.assertTrue(packet.endswith(b"acht\x00\x00\x00\x00"))

    def test_bool_is_rejected(self):
        with self.assertRaises(TypeError):
            build_osc_message("/x", True)

    def test_matches_python_osc_when_available(self):
        try:
            from pythonosc import osc_message_builder
        except ImportError:
            self.skipTest("python-osc nicht installiert")
        for address, value in (("/a/b", 0.25), ("/a/b", 7), ("/laengere/adresse", 1.5),
                               ("/preset/load", "standby"), ("/preset/save", "acht")):
            builder = osc_message_builder.OscMessageBuilder(address=address)
            builder.add_arg(value)
            self.assertEqual(build_osc_message(address, value),
                             builder.build().dgram, address)


class PresetNameTest(unittest.TestCase):
    """Spiegel von PresetStore.isValidName() -- siehe PresetStore.java."""

    def test_accepts_plain_name(self):
        self.assertIsNone(valid_preset_name("standby"))
        self.assertIsNone(valid_preset_name("hang_drum_slow"))
        self.assertIsNone(valid_preset_name("abend-2"))
        self.assertIsNone(valid_preset_name("a"))
        self.assertIsNone(valid_preset_name("x" * 64))

    def test_rejects_empty(self):
        self.assertIsNotNone(valid_preset_name(""))
        self.assertIsNotNone(valid_preset_name(None))

    def test_rejects_too_long(self):
        self.assertIsNotNone(valid_preset_name("x" * 65))

    def test_rejects_uppercase(self):
        # Windows unterscheidet Standby und standby nicht -- waeren dieselbe Datei
        self.assertIsNotNone(valid_preset_name("Standby"))

    def test_rejects_path_traversal(self):
        for name in ("..", "../etc/passwd", "a/b", "a\\b", "a.txt", " a", "a b",
                     "aä"):
            self.assertIsNotNone(valid_preset_name(name), name)


class PresetListTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def write(self, filename):
        with open(os.path.join(self.dir, filename), "w", encoding="utf-8") as handle:
            handle.write("float\t/master/level\tx\t0.1\t0.0\t1.0\n")

    def test_sorted_without_extension(self):
        self.write("standby.txt")
        self.write("abend.txt")
        names, error = list_presets(self.dir)
        self.assertEqual(names, ["abend", "standby"])
        self.assertIsNone(error)

    def test_ignores_other_files_and_directories(self):
        self.write("standby.txt")
        self.write("notizen.md")
        os.mkdir(os.path.join(self.dir, "unterordner.txt"))
        names, _ = list_presets(self.dir)
        self.assertEqual(names, ["standby"])

    def test_skips_invalid_names(self):
        # koennte man ohnehin nicht laden -- PresetStore.list() uebergeht sie auch
        self.write("standby.txt")
        self.write("Gross.txt")
        names, _ = list_presets(self.dir)
        self.assertEqual(names, ["standby"])

    def test_missing_directory_reports_error(self):
        names, error = list_presets(os.path.join(self.dir, "gibtsnicht"))
        self.assertEqual(names, [])
        self.assertIsNotNone(error)


class PresetApplyTest(unittest.TestCase):
    """Uebernahme einer geparsten Preset-Datei in die Regleranzeige."""

    def setUp(self):
        self.store = ParameterStore(path="egal")
        self.store.parameters = parse_settings(SAMPLE)
        self.store.by_address = {p.address: p for p in self.store.parameters}
        self.store.values = {p.address: p.coerce(p.value)
                             for p in self.store.parameters}

    def apply(self, text):
        return server.apply_preset_entries(self.store, parse_settings(text))

    def test_sets_known_addresses(self):
        result = self.apply("float\t/nodes/times/recover\tx\t7.5\t0.0\t10.0\n")
        self.assertAlmostEqual(result["values"]["/nodes/times/recover"], 7.5)
        self.assertAlmostEqual(self.store.values["/nodes/times/recover"], 7.5)

    def test_int_stays_int(self):
        result = self.apply("int\t/net/impulse/speed\tx\t42\t1\t1500\n")
        self.assertEqual(result["values"]["/net/impulse/speed"], 42)
        self.assertIsInstance(result["values"]["/net/impulse/speed"], int)

    def test_unknown_address_is_reported_not_applied(self):
        result = self.apply("float\t/gibt/es/nicht\tx\t1.0\t0.0\t2.0\n")
        self.assertEqual(result["unknown"], ["/gibt/es/nicht"])
        self.assertEqual(result["values"], {})

    def test_value_outside_ui_range_is_reported(self):
        # /net/impulse/speed: Java-Range 1..1500, UI-Range 1..100
        result = self.apply("int\t/net/impulse/speed\tx\t160\t1\t1500\n")
        self.assertEqual(result["values"]["/net/impulse/speed"], 160)
        self.assertEqual(result["outOfRange"],
                         [{"address": "/net/impulse/speed", "value": 160,
                           "shown": 100}])

    def test_value_inside_ui_range_is_not_reported(self):
        result = self.apply("int\t/net/impulse/speed\tx\t16\t1\t1500\n")
        self.assertEqual(result["outOfRange"], [])

    def test_clamps_to_settings_range_not_to_file_range(self):
        # Die min/max-Spalten der Datei werden ignoriert -- es gelten die
        # Grenzen aus remoteSettings.txt, genau wie in PresetStore.applyPreset
        result = self.apply("float\t/nodes/times/recover\tx\t99.0\t0.0\t1000.0\n")
        self.assertAlmostEqual(result["values"]["/nodes/times/recover"], 10.0)

    def test_ignored_addresses_are_skipped_silently(self):
        text = ("int\t/net/activateNode\tx\t5\t0\t100\n"
                "int\t/preset/scheduler/enabled\tx\t0\t0\t1\n")
        result = self.apply(text)
        self.assertEqual(result["values"], {})
        self.assertEqual(result["unknown"], [])


class PresetSaveWaitTest(unittest.TestCase):
    """Warten auf die Datei, die imPulse nach /preset/save schreibt."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.path = os.path.join(self.dir, "neu.txt")

    def test_returns_false_when_nothing_appears(self):
        # der haeufigste Fehlerfall: Web-UI laeuft, imPulse nicht
        self.assertFalse(server.wait_for_preset_file(self.path, None,
                                                     timeout=0.1, step=0.01))

    def test_returns_true_when_file_appears(self):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("x")
        self.assertTrue(server.wait_for_preset_file(self.path, None,
                                                    timeout=0.1, step=0.01))

    def test_unchanged_mtime_counts_as_not_written(self):
        # sonst waere das Ueberschreiben nicht von "nichts passiert" zu
        # unterscheiden
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("x")
        mtime = os.path.getmtime(self.path)
        self.assertFalse(server.wait_for_preset_file(self.path, mtime,
                                                     timeout=0.1, step=0.01))

    def test_changed_mtime_counts_as_written(self):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("x")
        mtime = os.path.getmtime(self.path)
        os.utime(self.path, (mtime + 5, mtime + 5))
        self.assertTrue(server.wait_for_preset_file(self.path, mtime,
                                                    timeout=0.1, step=0.01))

    def test_default_directory_sits_next_to_settings(self):
        settings = os.path.join("a", "data", "remoteSettings.txt")
        self.assertEqual(
            server.default_presets_path(settings),
            os.path.join(os.path.abspath(os.path.join("a", "data")), "presets"))


class RealSettingsFileTest(unittest.TestCase):
    """Gegenprobe an der echten data/remoteSettings.txt im Repo."""

    def setUp(self):
        self.path = os.path.join(server.REPO_ROOT, "data", "remoteSettings.txt")
        if not os.path.exists(self.path):
            self.skipTest("data/remoteSettings.txt fehlt")

    def test_loads_and_groups(self):
        store = ParameterStore(path=self.path)
        store.refresh(force=True)
        self.assertIsNone(store.error)
        self.assertGreater(len(store.parameters), 10)
        snapshot = store.snapshot()
        self.assertTrue(snapshot["groups"])
        for group in snapshot["groups"]:
            self.assertTrue(group["controls"], group["key"])

    def test_every_value_is_inside_its_own_range(self):
        store = ParameterStore(path=self.path)
        store.refresh(force=True)
        for param in store.parameters:
            value = store.values[param.address]
            self.assertGreaterEqual(value, min(param.minimum, param.maximum))
            self.assertLessEqual(value, max(param.minimum, param.maximum))


class SequencerSectionTest(unittest.TestCase):
    """build_sequencer/build_speed_classes und die Adress-Entnahme.

    Die Parameterliste wird hier selbst gebaut statt aus einer Datei gelesen -
    remoteSettings.txt liegt nicht im Repo und haengt am zuletzt gestarteten
    imPulse.
    """

    def _params(self):
        lines = [
            "float\t/net/sequencer/bpm\tx\t60\t20\t200",
            "int\t/net/sequencer/enabled\tx\t0\t0\t1",
        ]
        for i in range(server.SEQUENCER_TRACK_COUNT):
            base = "/net/sequencer/track%d/" % i
            lines += [
                "int\t%senabled\tx\t0\t0\t1" % base,
                "int\t%snoteValue\tx\t4\t1\t16" % base,
                "int\t%srepeatCount\tx\t3\t1\t8" % base,
                "float\t%senergy\tx\t0.6\t0\t1" % base,
                "float\t%sswingJitter\tx\t0\t0\t1" % base,
                "int\t%soriginStripeOverride\tx\t-1\t-1\t29" % base,
                "int\t%soriginTreeFilter\tx\t0\t0\t4" % base,
            ]
        lines += [
            "int\t/net/impulse/speedQuantize/enabled\tx\t0\t0\t1",
            "float\t/net/impulse/speedQuantize/jitter\tx\t0\t0\t1",
        ]
        for suffix, _label in server.SPEED_CLASSES:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPEED_WEIGHT_PREFIX, suffix))
        lines += [
            "int\t%sstaggerEnabled\tx\t0\t0\t1" % server.SPLIT_PREFIX,
            "int\t%sstaggerNoteValue\tx\t16\t1\t16" % server.SPLIT_PREFIX,
        ]
        for suffix, _label in server.SPLIT_WEIGHTS:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPLIT_WEIGHT_PREFIX, suffix))
        return {p.address: p for p in parse_settings("\n".join(lines))}

    def test_sequencer_has_all_six_tracks(self):
        seq = server.build_sequencer(self._params())
        self.assertIsNotNone(seq)
        self.assertEqual(len(seq["tracks"]), server.SEQUENCER_TRACK_COUNT)
        self.assertEqual([t["index"] for t in seq["tracks"]],
                         list(range(server.SEQUENCER_TRACK_COUNT)))

    def test_every_track_carries_all_its_fields(self):
        seq = server.build_sequencer(self._params())
        for track in seq["tracks"]:
            self.assertEqual(sorted(track["fields"]),
                             sorted(server.SEQUENCER_TRACK_FIELDS),
                             "Track %d" % track["index"])

    def test_tree_labels_cover_no_filter_plus_four_trees(self):
        seq = server.build_sequencer(self._params())
        # Index 0 ist "kein Filter", 1..4 die vier Baeume - dieselbe
        # Reihenfolge wie StripeTreeStore.TREE_NAMES auf der Java-Seite.
        self.assertEqual(len(seq["treeLabels"]), 5)
        self.assertEqual(seq["treeLabels"][0], "alle")
        self.assertEqual(seq["treeLabels"][1:],
                         ["vorn", "hinten", "rechts", "links"])

    def test_tree_labels_match_the_java_constant(self):
        """Driftet TREE_NAMES in StripeTreeStore.java, faellt es hier auf."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "StripeTreeStore.java")
        if not os.path.exists(path):
            self.skipTest("StripeTreeStore.java nicht gefunden")
        with open(path, encoding="utf-8") as handle:
            java = handle.read()
        match = re.search(r"TREE_NAMES\s*=\s*\{([^}]*)\}", java)
        self.assertIsNotNone(match, "TREE_NAMES nicht gefunden")
        names = re.findall(r'"([^"]+)"', match.group(1))
        self.assertEqual(names, server.TREE_LABELS[1:])

    def test_note_values_carry_symbol_and_name(self):
        seq = server.build_sequencer(self._params())
        self.assertEqual([n["value"] for n in seq["noteValues"]],
                         [1, 2, 4, 8, 16])
        for note in seq["noteValues"]:
            # Das Symbol allein reicht nicht: nicht jede Windows-Schrift hat
            # U+1D15D..U+1D161, der Name ist der Rueckfall.
            self.assertTrue(note["symbol"])
            self.assertTrue(note["name"])

    def test_missing_sequencer_yields_none_not_an_empty_panel(self):
        # Aelterer imPulse-Stand: das UI soll auf das generische Rendering
        # zurueckfallen, nicht eine leere Sektion zeigen.
        by_address = {p.address: p for p in
                      parse_settings("float\t/net/impulse/speed\tx\t16\t1\t1500")}
        self.assertIsNone(server.build_sequencer(by_address))
        self.assertIsNone(server.build_speed_classes(by_address))
        self.assertIsNone(server.build_split(by_address))

    def test_speed_classes_keep_the_java_order(self):
        speed = server.build_speed_classes(self._params())
        self.assertEqual([w["label"] for w in speed["weights"]],
                         [label for _s, label in server.SPEED_CLASSES])

    def test_special_addresses_are_removed_from_generic_groups(self):
        by_address = self._params()
        seq = server.build_sequencer(by_address)
        speed = server.build_speed_classes(by_address)
        split = server.build_split(by_address)
        taken = server.sequencer_addresses(seq, speed, split)
        self.assertIn("/net/sequencer/bpm", taken)
        self.assertIn("/net/sequencer/track5/energy", taken)
        self.assertIn("/net/impulse/speedQuantize/weight/8x", taken)
        self.assertIn("/net/impulse/split/weight/single", taken)
        self.assertIn("/net/impulse/split/staggerNoteValue", taken)
        # bpm + enabled, sechs Tracks a sechs Felder, quantize enabled+jitter,
        # ein Gewicht je Klasse, dazu die Split-Sektion (zwei Stagger-Regler
        # plus ein Gewicht je Kategorie). Aus den Konstanten gerechnet, nicht
        # als Literal - die Trackzahl steht im Server.
        expected = (2
                    + server.SEQUENCER_TRACK_COUNT
                    * (1 + len(server.SEQUENCER_TRACK_FIELDS))
                    + 2 + len(server.SPEED_CLASSES)
                    + 2 + len(server.SPLIT_WEIGHTS))
        self.assertEqual(len(taken), expected)

    def test_split_keeps_the_java_order(self):
        split = server.build_split(self._params())
        self.assertEqual([w["label"] for w in split["weights"]],
                         [label for _s, label in server.SPLIT_WEIGHTS])
        # Dieselbe Reihenfolge wie SplitFanout: Index 0 = alle Zweige.
        self.assertTrue(split["weights"][0]["address"].endswith("/all"))

    def test_split_carries_the_note_values_for_the_stagger(self):
        """Ohne sie faellt app.js auf einen 1..16-Schieber zurueck, der Werte
        anzeigt, die es nicht gibt (der Sketch rastet auf 1/2/4/8/16)."""
        split = server.build_split(self._params())
        self.assertEqual([n["value"] for n in split["noteValues"]],
                         [1, 2, 4, 8, 16])
        self.assertIsNotNone(split["staggerNoteValue"])
        self.assertIsNotNone(split["staggerEnabled"])

    def test_snapshot_does_not_render_a_parameter_twice(self):
        """Der eigentliche Zweck der Entnahme: kein doppeltes Bedienelement."""
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, "remoteSettings.txt")
        params = self._params()
        with open(path, "w", encoding="utf-8") as handle:
            for address, param in params.items():
                handle.write("%s\t%s\tx\t%g\t%g\t%g\n"
                             % (param.type, address, param.value,
                                param.minimum, param.maximum))
        store = ParameterStore(path=path)
        store.refresh(force=True)
        snapshot = store.snapshot()
        rendered = set()
        for group in snapshot["groups"]:
            for control in group["controls"]:
                if control.get("address"):
                    rendered.add(control["address"])
        overlap = rendered & server.sequencer_addresses(
            snapshot["sequencer"], snapshot["speedClasses"], snapshot["split"])
        self.assertEqual(overlap, set())
        self.assertIsNotNone(snapshot["sequencer"])
        self.assertIsNotNone(snapshot["speedClasses"])
        self.assertIsNotNone(snapshot["split"])


class TabLayoutTest(unittest.TestCase):
    """Die Tab-Zuordnung -- der Punkt, an dem ein Parameter verschwinden kann."""

    def _snapshot(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, "remoteSettings.txt")
        lines = [
            "float\t/master/level\tx\t0.1\t0\t1",
            "float\tMaster/trace\tx\t0\t0\t1",
            "float\tMaster/0/opacity/0.Impulse\tx\t1\t0\t1",
            "float\tMaster/1/opacity/1.Nodes\tx\t1\t0\t1",
            "int\t/net/impulse/speed\tx\t16\t1\t1500",
            "float\t/net/impulse/lifetime\tx\t0.02\t0.0001\t1",
            "float\t/net/impulse/nodeDeadTime\tx\t5\t0\t10",
            "float\t/net/impulse/splitSpeedJitter\tx\t0\t0\t1",
            "float\t/net/impulse/splitLifetimeJitter\tx\t0\t0\t1",
            "float\t/net/impulse/color/r\tx\t1\t0\t1",
            "float\t/net/impulse/speed/randomize/period\tx\t30\t1\t300",
            "int\t/net/impulse/oscMaxCount\tx\t32\t0\t256",
            "int\t/net/randomSpawn/enabled\tx\t1\t0\t1",
            "float\t/net/randomSpawn/interval\tx\t30\t0.05\t40",
            "float\t/net/randomSpawn/energy\tx\t0.6\t0\t1",
            "int\t/net/randomSpawn/count\tx\t1\t1\t30",
            "int\t/net/activateNode\tx\t0\t0\t50",
            "float\t/nodes/times/recover\tx\t4\t0\t10",
            "float\t/net/sequencer/bpm\tx\t60\t20\t200",
            "int\t/net/sequencer/enabled\tx\t0\t0\t1",
            "int\t/net/impulse/speedQuantize/enabled\tx\t0\t0\t1",
            "float\t/net/impulse/speedQuantize/jitter\tx\t0\t0\t1",
        ]
        for i in range(server.SEQUENCER_TRACK_COUNT):
            base = "/net/sequencer/track%d/" % i
            lines.append("int\t%senabled\tx\t0\t0\t1" % base)
            for name in server.SEQUENCER_TRACK_FIELDS:
                lines.append("int\t%s%s\tx\t0\t-1\t29" % (base, name))
        for suffix, _label in server.SPEED_CLASSES:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPEED_WEIGHT_PREFIX, suffix))
        lines += [
            "int\t%sstaggerEnabled\tx\t0\t0\t1" % server.SPLIT_PREFIX,
            "int\t%sstaggerNoteValue\tx\t16\t1\t16" % server.SPLIT_PREFIX,
        ]
        for suffix, _label in server.SPLIT_WEIGHTS:
            lines.append("float\t%s%s\tx\t100\t0\t100"
                         % (server.SPLIT_WEIGHT_PREFIX, suffix))
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        return store.snapshot()

    def test_five_tabs_in_the_briefed_order(self):
        tabs = self._snapshot()["tabs"]
        self.assertEqual([t["id"] for t in tabs],
                         ["mixer", "sound", "spawn", "noten", "physik"])

    def test_every_control_lands_in_exactly_one_tab(self):
        """Der eigentliche Zweck: kein Regler faellt beim Umbau heraus."""
        snapshot = self._snapshot()
        in_groups = set()
        for group in snapshot["groups"]:
            for control in group["controls"]:
                if control.get("address"):
                    in_groups.add(control["address"])
        seen = []
        for tab in snapshot["tabs"]:
            for control in tab["primary"]:
                seen.append(control["address"])
            for group in tab["groups"]:
                for control in group["controls"]:
                    if control.get("address"):
                        seen.append(control["address"])
        self.assertEqual(sorted(seen), sorted(in_groups),
                         "Regler fehlen in den Tabs oder stehen doppelt")
        self.assertEqual(len(seen), len(set(seen)), "Regler doppelt vergeben")

    def test_special_sections_sit_on_their_tab(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        self.assertIn("sequencer", tabs["spawn"]["sections"])
        self.assertIn("speedClasses", tabs["noten"]["sections"])
        self.assertIn("split", tabs["noten"]["sections"])
        self.assertEqual(tabs["mixer"]["sections"], [])

    def test_addresses_go_where_the_brief_says(self):
        self.assertEqual(server.tab_for_address("/master/level"), "mixer")
        self.assertEqual(server.tab_for_address("Master/trace"), "mixer")
        self.assertEqual(server.tab_for_address("/net/sequencer/bpm"), "spawn")
        self.assertEqual(server.tab_for_address("/net/randomSpawn/interval"), "spawn")
        self.assertEqual(server.tab_for_address("/net/activateStripe"), "spawn")
        # speedQuantize gehoert zu den Noten, NICHT zur Physik - die Regel
        # dafuer muss vor der allgemeinen /net/impulse/-Regel stehen.
        self.assertEqual(
            server.tab_for_address("/net/impulse/speedQuantize/enabled"), "noten")
        self.assertEqual(
            server.tab_for_address("/net/impulse/speedQuantize/weight/8x"), "noten")
        # Das Split-Verhalten gehoert zu den Noten: der Versatz haengt am
        # BPM-Raster. Die beiden aelteren splitJitter-Adressen bleiben aber
        # in der Physik - der Schraegstrich in der Regel trennt sie.
        self.assertEqual(
            server.tab_for_address("/net/impulse/split/weight/all"), "noten")
        self.assertEqual(
            server.tab_for_address("/net/impulse/split/staggerNoteValue"), "noten")
        self.assertEqual(
            server.tab_for_address("/net/impulse/splitSpeedJitter"), "physik")
        self.assertEqual(
            server.tab_for_address("/net/impulse/splitLifetimeJitter"), "physik")
        self.assertEqual(server.tab_for_address("/net/impulse/speed"), "physik")
        self.assertEqual(server.tab_for_address("/net/impulse/color/r"), "physik")
        self.assertEqual(server.tab_for_address("/nodes/times/recover"), "physik")
        # Unbekanntes verschwindet nicht, es landet sichtbar in der Physik
        self.assertEqual(server.tab_for_address("/etwas/ganz/neues"), "physik")

    def test_primary_controls_are_not_repeated_in_the_groups(self):
        for tab in self._snapshot()["tabs"]:
            primary = {c["address"] for c in tab["primary"]}
            for group in tab["groups"]:
                for control in group["controls"]:
                    self.assertNotIn(control.get("address"), primary,
                                     "%s steht zweimal im Tab %s"
                                     % (control.get("address"), tab["id"]))

    def test_sc_params_are_split_between_mixer_and_sound(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        mixer = {p["name"] for p in tabs["mixer"]["scParams"]}
        sound = {p["name"] for p in tabs["sound"]["scParams"]}
        self.assertIn("masterVolume", mixer)
        self.assertIn("bellVolume", mixer)
        self.assertIn("droneVolume", mixer)
        self.assertIn("travelMix", sound)
        self.assertIn("brightness", sound)
        self.assertEqual(mixer & sound, set(), "SC-Parameter doppelt vergeben")
        self.assertEqual(mixer | sound, {p["name"] for p in server.SC_PARAMS})

    def test_curated_sc_params_come_first_and_are_flagged(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        names = [p["name"] for p in tabs["mixer"]["scParams"]]
        self.assertEqual(names[:4], server.SC_PRIMARY["mixer"])
        flags = [p["primary"] for p in tabs["mixer"]["scParams"]]
        self.assertEqual(flags[:4], [True]*4)
        self.assertNotIn(True, flags[4:])

    def test_every_tab_primary_address_is_real(self):
        """Eine Adresse in TAB_PRIMARY, die es nicht gibt, faellt sonst nur auf,
        wenn jemand hinsieht."""
        snapshot = self._snapshot()
        known = set()
        for group in snapshot["groups"]:
            for control in group["controls"]:
                if control.get("address"):
                    known.add(control["address"])
        taken = server.sequencer_addresses(snapshot["sequencer"],
                                           snapshot["speedClasses"],
                                           snapshot["split"])
        for tab_id, addresses in server.TAB_PRIMARY.items():
            for address in addresses:
                self.assertTrue(address in known or address in taken,
                                "%s (Tab %s) gibt es im Dump nicht"
                                % (address, tab_id))


class ScParamTest(unittest.TestCase):
    """Die handgepflegte Spiegelung der SC-Registry."""

    def test_every_default_is_inside_its_range(self):
        for entry in server.SC_PARAMS:
            self.assertGreaterEqual(entry["default"], entry["min"], entry["name"])
            self.assertLessEqual(entry["default"], entry["max"], entry["name"])

    def test_names_are_unique(self):
        names = [p["name"] for p in server.SC_PARAMS]
        self.assertEqual(len(names), len(set(names)))

    def test_groups_keep_insertion_order(self):
        groups = server.sc_param_groups()
        self.assertEqual([g["title"] for g in groups],
                         ["Master", "Glocke", "Travel-Sound"])
        for group in groups:
            for param in group["params"]:
                self.assertTrue(param["address"].startswith("/klangnetz/param/"))

    def test_table_has_not_drifted_from_the_scd(self):
        """Der haeufigste Fehler waere eine Tabelle, die von der .scd abdriftet."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "supercollider", "klangnetz_bells.scd")
        if not os.path.exists(path):
            self.skipTest("klangnetz_bells.scd nicht gefunden")
        with open(path, encoding="utf-8") as handle:
            scd = handle.read()
        for entry in server.SC_PARAMS:
            self.assertIn("~registerParam.(\\%s" % entry["name"], scd,
                          "%s steht in SC_PARAMS, aber nicht in der .scd"
                          % entry["name"])

    def test_scd_has_no_parameter_the_table_is_missing(self):
        """Die Gegenrichtung: ein neuer .scd-Parameter faellt sonst still weg."""
        import re
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "supercollider", "klangnetz_bells.scd")
        if not os.path.exists(path):
            self.skipTest("klangnetz_bells.scd nicht gefunden")
        with open(path, encoding="utf-8") as handle:
            scd = handle.read()
        in_scd = set(re.findall(r"~registerParam\.\(\\(\w+)", scd))
        in_table = {p["name"] for p in server.SC_PARAMS}
        self.assertEqual(in_scd - in_table, set(),
                         "in der .scd registriert, fehlt aber in SC_PARAMS")


if __name__ == "__main__":
    unittest.main(verbosity=2)
