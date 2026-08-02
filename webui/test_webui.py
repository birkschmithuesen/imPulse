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
import socket
import struct
import sys
import tempfile
import threading
import time
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


def close_app_sockets(test, app):
    """Die drei UDP-Sockets einer App beim Testende schliessen.

    Zwei Sender (imPulse, sclang) und der Empfaenger des Aufnahme-
    Rueckkanals. Im Betrieb leben sie so lange wie der Prozess und werden
    nie geschlossen; eine Testsuite legt aber dutzende Apps an und meldete
    sonst ebenso viele ResourceWarnings.
    """
    test.addCleanup(app.config["IMPULSE_RECORD_STATUS"].close)
    test.addCleanup(app.config["IMPULSE_SENDER"].close)
    test.addCleanup(app.config["IMPULSE_SC_SENDER"].close)


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
        # "Impuls-Farbe: Feinheiten", nicht "Impuls-Farbe": so heisst seit
        # dem Farbwaehler-Umbau die Sektion, die den Waehler und den
        # Moduswahlschalter traegt. Zwei Ueberschriften desselben Namens
        # versprechen dasselbe und halten Verschiedenes.
        self.assertEqual(groups["net/impulse/color"]["title"],
                         "Impuls-Farbe: Feinheiten")
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

    def test_no_argument_matches_python_osc_too(self):
        """Beide Encoder muessen bei ``None`` dasselbe leere Paket bauen.

        OscSender waehlt je nach Adresse den einen oder den anderen Weg --
        die Record-Kommandos duerfen davon nicht abhaengen.
        """
        try:
            from pythonosc import osc_message_builder
        except ImportError:
            self.skipTest("python-osc nicht installiert")
        address = server.RECORD_START
        builder = osc_message_builder.OscMessageBuilder(address=address)
        self.assertEqual(build_osc_message(address, None),
                         builder.build().dgram)


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
        ]
        for suffix, _note in server.SPLIT_STAGGER_NOTES:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPLIT_STAGGER_WEIGHT_PREFIX, suffix))
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

    def test_sequencer_carries_a_help_line_for_the_tree_filter(self):
        """Ohne Erklaerung ist "alle" fuer einen Operator ein Raetsel.

        Der Text muss den Vorrang von originStripeOverride nennen: das ist
        die Falle, bei der der Filter eingestellt ist und trotzdem nichts
        tut (siehe OriginSequencer.advanceOrigin).
        """
        seq = server.build_sequencer(self._params())
        self.assertTrue(seq["treeHelp"])
        self.assertIn("originStripeOverride", seq["treeHelp"])

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
        self.assertIn("/net/impulse/split/stagger/weight/sixteenth", taken)
        # bpm + enabled, sechs Tracks a sechs Felder, quantize enabled+jitter,
        # ein Gewicht je Klasse, dazu die Split-Sektion (der Stagger-Schalter,
        # ein Gewicht je Fanout-Kategorie und eines je Notenwert-Klasse). Aus
        # den Konstanten gerechnet, nicht als Literal - die Trackzahl steht im
        # Server.
        expected = (2
                    + server.SEQUENCER_TRACK_COUNT
                    * (1 + len(server.SEQUENCER_TRACK_FIELDS))
                    + 2 + len(server.SPEED_CLASSES)
                    + 1 + len(server.SPLIT_WEIGHTS)
                    + len(server.SPLIT_STAGGER_NOTES))
        self.assertEqual(len(taken), expected)

    def test_split_keeps_the_java_order(self):
        split = server.build_split(self._params())
        self.assertEqual([w["label"] for w in split["weights"]],
                         [label for _s, label in server.SPLIT_WEIGHTS])
        # Dieselbe Reihenfolge wie SplitFanout: Index 0 = alle Zweige.
        self.assertTrue(split["weights"][0]["address"].endswith("/all"))

    def test_split_stagger_weights_follow_the_java_note_values(self):
        """Ein Gewicht je Klasse, in der Reihenfolge der Java-Seite
        (OriginSequencer.NOTE_VALUES: Ganze .. Sechzehntel), dahinter die
        sechste Klasse "gleichzeitig" mit dem Sentinel-Notenwert 0. Der
        Notenwert des Versatzes wird je Aufspaltung gezogen, es gibt also
        keinen festen Regler mehr, den eine Notenwert-Leiste zeigen koennte."""
        split = server.build_split(self._params())
        self.assertEqual([w["noteValue"] for w in split["staggerWeights"]],
                         [1, 2, 4, 8, 16, 0])
        for weight in split["staggerWeights"]:
            # Das Symbol allein reicht nicht: nicht jede Windows-Schrift hat
            # U+1D15D..U+1D161, der Name ist der Rueckfall.
            self.assertTrue(weight["symbol"])
            self.assertTrue(weight["label"])
        self.assertIsNotNone(split["staggerEnabled"])
        self.assertNotIn("staggerNoteValue", split)
        # Die sechste Klasse traegt ein eigenes Label: in NOTE_VALUES gibt es
        # keinen Eintrag fuer den Notenwert 0, ein generisches Lookup liefe
        # dort in einen KeyError statt in eine Beschriftung.
        simultaneous = split["staggerWeights"][-1]
        self.assertTrue(
            simultaneous["address"].endswith("/simultaneous"))
        self.assertEqual(simultaneous["label"],
                         server.SPLIT_STAGGER_SIMULTANEOUS_LABEL[1])

    def test_split_without_stagger_weights_still_renders(self):
        """Aelterer imPulse-Stand ohne die Gewichte: die Sektion soll die
        Fanout-Gewichte trotzdem zeigen, statt ganz zu verschwinden."""
        by_address = {a: p for a, p in self._params().items()
                      if not a.startswith(server.SPLIT_STAGGER_WEIGHT_PREFIX)}
        split = server.build_split(by_address)
        self.assertIsNotNone(split)
        self.assertEqual(split["staggerWeights"], [])
        self.assertEqual(len(split["weights"]), len(server.SPLIT_WEIGHTS))

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
            "float\t/net/impulse/color/g\tx\t1\t0\t1",
            "float\t/net/impulse/color/b\tx\t1\t0\t1",
            "float\t/net/impulse/color/gamma\tx\t1\t0\t4",
            "int\t/net/impulse/color/useSpecificColor\tx\t0\t0\t1",
            "float\t/net/impulse/fadeOut/r\tx\t0.97\t0\t1",
            "float\t/nodes/colors/central/fired/Hue\tx\t0\t0\t1",
            "float\t/nodes/colors/central/fired/Sat\tx\t1\t0\t1",
            "float\t/nodes/colors/central/fired/Bright\tx\t1\t0\t1",
            "float\t/nodes/radius/central\tx\t2\t0\t20",
            "float\t/net/impulse/speed/randomize/period\tx\t30\t1\t300",
            "int\t/net/impulse/oscMaxCount\tx\t32\t0\t256",
            "int\t/net/randomSpawn/enabled\tx\t1\t0\t1",
            "float\t/net/randomSpawn/interval\tx\t30\t0.05\t40",
            "float\t/net/randomSpawn/energy\tx\t0.6\t0\t1",
            "int\t/net/randomSpawn/count\tx\t1\t1\t30",
            "int\t/net/pause/enabled\tx\t0\t0\t1",
            "float\t/net/pause/checkIntervalBars\tx\t8\t1\t64",
            "float\t/net/pause/probability\tx\t0.25\t0\t1",
            "float\t/net/pause/lengthMinBars\tx\t2\t0.5\t32",
            "float\t/net/pause/lengthMaxBars\tx\t6\t0.5\t32",
            "int\t/net/pause/affectsSequencer\tx\t1\t0\t1",
            "int\t/net/pause/affectsRandomSpawn\tx\t1\t0\t1",
            "int\t/net/activateNode\tx\t0\t0\t50",
            "float\t/nodes/times/recover\tx\t4\t0\t10",
            "float\t/net/sequencer/bpm\tx\t60\t20\t200",
            "int\t/net/sequencer/enabled\tx\t0\t0\t1",
            "int\t/net/impulse/speedQuantize/enabled\tx\t0\t0\t1",
            "float\t/net/impulse/speedQuantize/jitter\tx\t0\t0\t1",
            "int\t/net/melody/mode\tx\t4\t0\t7",
            "int\t/net/melody/startNode\tx\t0\t0\t91",
            "int\t/net/melody/rootMidiNote\tx\t45\t24\t84",
            "int\t/net/melody/numOctaves\tx\t3\t1\t6",
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
        ]
        for suffix, _note in server.SPLIT_STAGGER_NOTES:
            lines.append("float\t%s%s\tx\t0\t0\t100"
                         % (server.SPLIT_STAGGER_WEIGHT_PREFIX, suffix))
        for suffix, _label in server.SPLIT_WEIGHTS:
            lines.append("float\t%s%s\tx\t100\t0\t100"
                         % (server.SPLIT_WEIGHT_PREFIX, suffix))
        lines.append("int\t/songStructure/enabled\tx\t0\t0\t1")
        for frm in server.SONG_LEVEL_NAMES:
            for to in server.SONG_LEVEL_NAMES:
                lines.append("float\t/songStructure/matrix/%s/%s\tx\t25\t0\t100"
                             % (frm, to))
        for level in server.SONG_LEVEL_NAMES:
            lines.append("float\t/songStructure/dwell/%s/min\tx\t3\t0.5\t60" % level)
            lines.append("float\t/songStructure/dwell/%s/max\tx\t5\t0.5\t60" % level)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        return store.snapshot()

    def test_seven_tabs_in_the_briefed_order(self):
        tabs = self._snapshot()["tabs"]
        # Die fuenf Kern-Tabs behalten ihre Reihenfolge, "tonleiter" haengt
        # hinter "noten" (thematisch benachbart: Melodie-Zuordnung entscheidet
        # WELCHE Note, Noten-Verhalten WANN/WIE), "farben" und "song" hinten
        # an. Seit 2026-08-02: Melodie-Zuordnung hat einen eigenen Tab statt
        # einer festen Sektion ueber der Tab-Leiste.
        self.assertEqual([t["id"] for t in tabs],
                         ["mixer", "sound", "spawn", "noten", "tonleiter",
                          "physik", "farben", "song"])

    def test_colour_addresses_land_in_the_colour_tab(self):
        for address in ("/net/impulse/color/r",
                        "/net/impulse/color/gamma",
                        "/net/impulse/color/useSpecificColor",
                        "/net/impulse/fadeOut/r",
                        "/nodes/colors/central/fired/Hue",
                        "/nodes/colors/outer/waiting/Bright"):
            self.assertEqual(server.tab_for_address(address),
                             server.TAB_COLORS, address)

    def test_non_colour_impulse_and_node_addresses_stay_in_physics(self):
        # Die Farb-Regeln stehen VOR den allgemeinen -- greifen sie zu breit,
        # leert sich der Physik-Tab, ohne dass irgendwo ein Fehler entsteht.
        for address in ("/net/impulse/speed", "/net/impulse/lifetime",
                        "/net/impulse/splitSpeedJitter",
                        "/nodes/radius/central", "/nodes/times/recover"):
            self.assertEqual(server.tab_for_address(address),
                             server.TAB_PHYSICS, address)

    def test_colour_tab_shows_its_groups_without_the_details_fold(self):
        """Der Farben-Tab hat keine kuratierte Auswahl.

        Farbkarten tragen keine eigene Adresse (kind == "color"), TAB_PRIMARY
        kann sie also nicht nach oben holen. Ohne das expanded-Flag stuende
        der ganze Tab eingeklappt hinter "Erweitert" -- ein Tab, dessen
        Inhalt man erst aufklappen muss.
        """
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        self.assertTrue(tabs[server.TAB_COLORS]["expanded"])
        self.assertFalse(tabs[server.TAB_PHYSICS]["expanded"])

    def test_colour_tab_actually_carries_the_colour_controls(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        colour = tabs[server.TAB_COLORS]
        bases = [c["base"] for group in colour["groups"]
                 for c in group["controls"] if c.get("kind") == "color"]
        self.assertIn("/nodes/colors/central/fired", bases)
        addresses = [c.get("address") for group in colour["groups"]
                     for c in group["controls"]]
        self.assertIn("/net/impulse/color/gamma", addresses)

    def test_palette_section_sits_in_the_colour_tab(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        self.assertIn("palette", tabs[server.TAB_COLORS]["sections"])
        for tab_id, tab in tabs.items():
            if tab_id != server.TAB_COLORS:
                self.assertNotIn("palette", tab["sections"], tab_id)

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
        self.assertIn("songStructure", tabs["song"]["sections"])
        # Seit 2026-08-02 steht die Presets-Sektion ganz oben im Mixer-Tab
        # (vorher: fest ueber der Tab-Leiste) -- deshalb nicht mehr leer.
        self.assertEqual(tabs["mixer"]["sections"], ["presets"])

    def test_song_tab_holds_nothing_but_its_own_panel(self):
        """Alle /songStructure/-Adressen rendert das Panel selbst. Bliebe eine
        als generischer Regler uebrig, stuende sie zweimal auf der Seite."""
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        self.assertEqual(tabs["song"]["groups"], [])
        self.assertEqual(tabs["song"]["primary"], [])
        self.assertEqual(tabs["song"]["scParams"], [])

    def test_addresses_go_where_the_brief_says(self):
        self.assertEqual(server.tab_for_address("/master/level"), "mixer")
        self.assertEqual(server.tab_for_address("Master/trace"), "mixer")
        self.assertEqual(server.tab_for_address("/net/sequencer/bpm"), "spawn")
        self.assertEqual(server.tab_for_address("/net/randomSpawn/interval"), "spawn")
        self.assertEqual(server.tab_for_address("/net/pause/enabled"), "spawn")
        self.assertEqual(server.tab_for_address("/net/pause/checkIntervalBars"), "spawn")
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
            server.tab_for_address("/net/impulse/split/staggerEnabled"), "noten")
        self.assertEqual(
            server.tab_for_address("/net/impulse/split/stagger/weight/eighth"),
            "noten")
        self.assertEqual(
            server.tab_for_address("/net/impulse/splitSpeedJitter"), "physik")
        self.assertEqual(
            server.tab_for_address("/net/impulse/splitLifetimeJitter"), "physik")
        self.assertEqual(server.tab_for_address("/net/impulse/speed"), "physik")
        # Farbe gehoert zu den Farben, NICHT zur Physik - dieselbe
        # Reihenfolge-Falle wie bei speedQuantize.
        self.assertEqual(server.tab_for_address("/net/impulse/color/r"), "farben")
        self.assertEqual(server.tab_for_address("/nodes/times/recover"), "physik")
        self.assertEqual(server.tab_for_address("/songStructure/enabled"), "song")
        self.assertEqual(
            server.tab_for_address("/songStructure/matrix/dramatisch/ruhig"), "song")
        self.assertEqual(
            server.tab_for_address("/songStructure/dwell/ruhig/min"), "song")
        # Unbekanntes verschwindet nicht, es landet sichtbar in der Physik
        self.assertEqual(server.tab_for_address("/etwas/ganz/neues"), "physik")

    def test_no_group_falls_out_of_every_tab(self):
        """Regression: eine Gruppe aus lauter Farbkarten war unerreichbar.

        build_tabs() ordnet eine Gruppe ueber die Adresse ihres ersten
        Reglers zu. Eine Farbkarte traegt aber keine eigene Adresse, sondern
        drei darunter -- die Gruppe /nodes/colors (18 Werte, sechs Karten)
        hatte damit gar keine und fiel aus JEDEM Tab heraus. Kein Fehler,
        kein Symptom, die Regler waren schlicht nicht da.
        """
        snapshot = self._snapshot()
        primary = {c["address"] for tab in snapshot["tabs"]
                   for c in tab["primary"]}
        in_tabs = {group["key"] for tab in snapshot["tabs"]
                   for group in tab["groups"]}
        for group in snapshot["groups"]:
            # Gruppen, deren Regler restlos in die kuratierte Auswahl
            # gewandert sind, duerfen fehlen -- die stehen ja oben im Tab.
            remaining = [c for c in group["controls"]
                         if c.get("address") not in primary]
            if remaining:
                self.assertIn(group["key"], in_tabs,
                              "Gruppe %s steht in keinem Tab" % group["key"])

    def test_primary_controls_are_not_repeated_in_the_groups(self):
        for tab in self._snapshot()["tabs"]:
            primary = {c["address"] for c in tab["primary"]}
            for group in tab["groups"]:
                for control in group["controls"]:
                    self.assertNotIn(control.get("address"), primary,
                                     "%s steht zweimal im Tab %s"
                                     % (control.get("address"), tab["id"]))

    def test_every_sc_param_lands_in_exactly_one_tab(self):
        """Dieselbe Regel wie fuer die imPulse-Regler: genau ein Tab, kein
        Parameter doppelt und keiner unsichtbar."""
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        seen = []
        for tab in tabs.values():
            seen.extend(p["name"] for p in tab["scParams"])
        self.assertEqual(len(seen), len(set(seen)), "SC-Parameter doppelt vergeben")
        self.assertEqual(set(seen), {p["name"] for p in server.SC_PARAMS})
        # Die inhaltliche Verteilung, damit eine versehentliche Umsortierung
        # auffaellt.
        mixer = {p["name"] for p in tabs["mixer"]["scParams"]}
        sound = {p["name"] for p in tabs["sound"]["scParams"]}
        noten = {p["name"] for p in tabs["noten"]["scParams"]}
        self.assertIn("masterVolume", mixer)
        self.assertIn("bellVolume", mixer)
        self.assertIn("droneVolume", mixer)
        self.assertIn("travelMix", sound)
        self.assertIn("brightness", sound)
        self.assertIn("treeBiasAmount", sound)
        # Die Melodie-Parameter gehoeren zum Notenverhalten, nicht zum
        # Klangdesign - sie bestimmen, WELCHE Note ein Knoten bekommt.
        self.assertIn("melodyMode", noten)
        self.assertIn("melodyRootMidiNote", noten)
        self.assertIn("melodyNumOctaves", noten)

    def test_curated_sc_params_come_first_and_are_flagged(self):
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        curated = server.SC_PRIMARY["mixer"]
        count = len(curated)
        names = [p["name"] for p in tabs["mixer"]["scParams"]]
        self.assertEqual(names[:count], curated)
        flags = [p["primary"] for p in tabs["mixer"]["scParams"]]
        self.assertEqual(flags[:count], [True] * count)
        self.assertNotIn(True, flags[count:])

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


class SongStructureSectionTest(unittest.TestCase):
    """build_song_structure und die Anzeige des Live-Zustands.

    Wie beim Sequencer wird die Parameterliste selbst gebaut --
    remoteSettings.txt liegt nicht im Repo und haengt am zuletzt gestarteten
    imPulse.
    """

    def _lines(self):
        lines = ["int\t/songStructure/enabled\tx\t0\t0\t1"]
        for frm in server.SONG_LEVEL_NAMES:
            for to in server.SONG_LEVEL_NAMES:
                lines.append("float\t/songStructure/matrix/%s/%s\tx\t25\t0\t100"
                             % (frm, to))
        for level in server.SONG_LEVEL_NAMES:
            lines.append("float\t/songStructure/dwell/%s/min\tx\t3\t0.5\t60" % level)
            lines.append("float\t/songStructure/dwell/%s/max\tx\t5\t0.5\t60" % level)
        return lines

    def _params(self):
        return {p.address: p for p in parse_settings("\n".join(self._lines()))}

    def test_section_carries_matrix_and_dwell(self):
        song = server.build_song_structure(self._params())
        self.assertIsNotNone(song)
        self.assertEqual(song["levels"], server.SONG_LEVEL_NAMES)
        self.assertEqual(len(song["matrix"]), len(server.SONG_LEVEL_NAMES))
        for row in song["matrix"]:
            self.assertEqual(len(row), len(server.SONG_LEVEL_NAMES))
            for cell in row:
                self.assertTrue(cell["address"].startswith("/songStructure/matrix/"))
        self.assertEqual(len(song["dwell"]), len(server.SONG_LEVEL_NAMES))
        for entry in song["dwell"]:
            self.assertIn("min", entry)
            self.assertIn("max", entry)

    def test_goto_is_a_command_not_a_slider(self):
        """Es steht bewusst NICHT in remoteSettings.txt.

        SongStructureParams.writeToStream() ist leer, damit das UI keinen
        Regler daraus baut -- dieselbe Falle wie bei /net/activate*.
        """
        song = server.build_song_structure(self._params())
        self.assertEqual(song["gotoAddress"], "/songStructure/goto")
        self.assertNotIn("/songStructure/goto", self._params())

    def test_missing_section_yields_none_not_an_empty_panel(self):
        by_address = {p.address: p for p in
                      parse_settings("float\t/net/impulse/speed\tx\t16\t1\t1500")}
        self.assertIsNone(server.build_song_structure(by_address))

    def test_incomplete_matrix_yields_none(self):
        """Ein halbes Gitter waere schlimmer als gar keins: der Operator
        saehe vier Zeilen und wuesste nicht, dass eine fehlt."""
        lines = [l for l in self._lines()
                 if not l.startswith("float\t/songStructure/matrix/ruhig/mittel\t")]
        by_address = {p.address: p for p in parse_settings("\n".join(lines))}
        self.assertIsNone(server.build_song_structure(by_address))

    def test_level_names_match_the_java_constant(self):
        """Driftet LEVEL_NAMES in EnergyLevelStore.java, faellt es hier auf."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "EnergyLevelStore.java")
        if not os.path.exists(path):
            self.skipTest("EnergyLevelStore.java nicht gefunden")
        with open(path, encoding="utf-8") as handle:
            java = handle.read()
        match = re.search(r"LEVEL_NAMES\s*=\s*\{([^}]*)\}", java)
        self.assertIsNotNone(match, "LEVEL_NAMES nicht gefunden")
        self.assertEqual(re.findall(r'"([^"]+)"', match.group(1)),
                         server.SONG_LEVEL_NAMES)

    def test_all_addresses_are_taken_out_of_the_generic_rendering(self):
        params = self._params()
        song = server.build_song_structure(params)
        taken = server.song_structure_addresses(song)
        self.assertEqual(taken, set(params),
                         "eine /songStructure/-Adresse stuende zweimal auf der Seite")

    def test_state_file_is_read_from_disk_not_osc(self):
        """Es gibt keinen OSC-Rueckkanal: imPulse sendet nur an 8002."""
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, "songStructureState.txt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("level\tdynamisch\nlevelIndex\t2\n"
                         "preset\tnacht_dynamisch_schwarm\n"
                         "sinceMillis\t1754049600000\ndwellSeconds\t95\n")
        state = server.read_song_state(path)
        self.assertEqual(state["level"], "dynamisch")
        self.assertEqual(state["levelIndex"], 2)
        self.assertEqual(state["preset"], "nacht_dynamisch_schwarm")
        self.assertEqual(state["sinceMillis"], 1754049600000)
        self.assertEqual(state["dwellSeconds"], 95)

    def test_missing_state_file_is_not_an_error(self):
        """Vor dem ersten Levelwechsel gibt es sie nicht -- das ist der
        Normalfall beim Start, kein Fehler."""
        state = server.read_song_state(os.path.join(tempfile.mkdtemp(), "fehlt.txt"))
        self.assertIsNone(state)

    def test_broken_state_line_is_skipped_not_fatal(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, "songStructureState.txt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("level\truhig\nkaputt\nlevelIndex\tkeineZahl\n")
        state = server.read_song_state(path)
        self.assertEqual(state["level"], "ruhig")
        self.assertIsNone(state["levelIndex"])


class ColorSectionTest(unittest.TestCase):
    """Farbwaehler statt Kanalregler: Impulsfarbe, Modus, acht Stripe-Slots."""

    def full_store(self):
        path = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(path):
            self.skipTest("data/presets/random1.txt fehlt")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        return store

    def test_impulse_card_collects_the_three_rgb_addresses(self):
        colors = server.build_colors(self.full_store().by_address)
        card = colors["impulse"]
        self.assertEqual(card["kind"], "rgb")
        self.assertEqual(
            sorted(c["address"] for c in card["components"].values()),
            ["/net/impulse/color/b", "/net/impulse/color/g",
             "/net/impulse/color/r"])
        self.assertTrue(card["label"])

    def test_mode_carries_both_state_names(self):
        """Beide Zustaende sind ein Modus -- keiner ist "aus"."""
        colors = server.build_colors(self.full_store().by_address)
        self.assertEqual(colors["modeLabels"]["1"], "Spezifische Farbe")
        self.assertEqual(colors["modeLabels"]["0"], "Stripe-Farben")

    def test_eight_stripe_slots_or_none_at_all(self):
        """Ein halber Satz Slots waere schlimmer als keiner: der Operator
        saehe fuenf Farben und wuesste nicht, dass drei fehlen."""
        rows = ["float\t%s%d/%s\tx\t0.5\t0\t1" % (server.STRIPE_COLOR_PREFIX, i, c)
                for i in range(server.STRIPE_COLOR_COUNT)
                for c in ("r", "g", "b")]
        full = {p.address: p for p in parse_settings("\n".join(rows))}
        self.assertEqual(len(server.build_colors(full)["stripes"]),
                         server.STRIPE_COLOR_COUNT)

        partial = {p.address: p for p in parse_settings("\n".join(rows[:-1]))}
        self.assertEqual(server.build_colors(partial)["stripes"], [])

    def test_colour_addresses_leave_the_generic_rendering(self):
        """Sonst stuende jeder Kanal zweimal auf der Seite: einmal im
        Farbwaehler, einmal als Schieber."""
        store = self.full_store()
        snapshot = store.snapshot()
        rendered = set()
        for tab in snapshot["tabs"]:
            for control in tab["primary"]:
                rendered.add(control.get("address"))
            for group in tab["groups"]:
                for control in group["controls"]:
                    rendered.add(control.get("address"))
        for address in ("/net/impulse/color/r", "/net/impulse/color/g",
                        "/net/impulse/color/b",
                        "/net/impulse/color/useSpecificColor",
                        "/net/impulse/fadeOut/r", "/net/impulse/fadeOut/g",
                        "/net/impulse/fadeOut/b"):
            self.assertNotIn(address, rendered)

    def test_gamma_is_not_swallowed_by_the_colour_section(self):
        """/net/impulse/color/gamma ist keine Farbe und bleibt ein Regler."""
        store = self.full_store()
        rendered = set()
        for tab in store.snapshot()["tabs"]:
            for group in tab["groups"]:
                for control in group["controls"]:
                    rendered.add(control.get("address"))
        self.assertIn("/net/impulse/color/gamma", rendered)

    def test_stripe_slots_would_fall_into_the_colour_tab(self):
        """Rueckfall, falls die Sektion einmal nicht gebaut werden kann."""
        self.assertEqual(
            server.tab_for_address(server.STRIPE_COLOR_PREFIX + "3/r"),
            server.TAB_COLORS)

    def test_older_dump_without_stripe_colours_still_builds(self):
        """Ein aelterer imPulse-Stand kennt die Slots nicht -- die Impulsfarbe
        soll trotzdem ein Farbwaehler sein."""
        rows = ["float\t/net/impulse/color/%s\tx\t1\t0\t1" % c
                for c in ("r", "g", "b")]
        by_address = {p.address: p for p in parse_settings("\n".join(rows))}
        colors = server.build_colors(by_address)
        self.assertIsNotNone(colors["impulse"])
        self.assertEqual(colors["stripes"], [])
        self.assertIsNone(colors["mode"])


class FadeOutConversionTest(unittest.TestCase):
    """Zielfarbe + Tempo <-> die drei Zerfallsraten.

    Die Formel steht auf der Server-Seite, weil sie eine Aussage darueber ist,
    wie der Effekt den LED-Puffer multipliziert -- und weil sie nur hier ohne
    jsdom pruefbar ist.
    """

    def test_no_decay_gives_all_ones_whatever_the_colour(self):
        """Der Grenzfall, der die Potenz-Form erzwingt: 1**x == 1. Eine
        multiplikative Gewichtung haette hier je Kanal etwas anderes ergeben."""
        for colour in ((1, 1, 1), (0, 0, 1), (0.3, 0.9, 0.1), (0, 0, 0)):
            self.assertEqual(server.fade_from_target(*colour, decay=1.0),
                             (1.0, 1.0, 1.0), colour)

    def test_black_target_decays_all_channels_equally(self):
        """max(ziel) == 0 -- die Division waere undefiniert. "Welche Farbe
        bleibt uebrig" hat bei Schwarz keine Antwort, also verfaerbt sich
        nichts."""
        self.assertEqual(server.fade_from_target(0, 0, 0, 0.9),
                         (0.9, 0.9, 0.9))

    def test_strongest_channel_gets_exactly_the_base_decay(self):
        """Sein Gewicht ist 1, der Exponent also 1. Daran haengt die ganze
        Kalibrierung: der Fader bedeutet "so schnell verschwindet die Spur"."""
        rates = server.fade_from_target(0.5, 1.0, 0.25, 0.8)
        self.assertAlmostEqual(rates[1], 0.8, places=9)
        self.assertLess(rates[0], rates[1])
        self.assertLess(rates[2], rates[0])

    def test_weaker_channel_disappears_faster(self):
        """Die inhaltliche Aussage der ganzen Umrechnung."""
        red, green, blue = server.fade_from_target(1.0, 0.5, 0.0, 0.95)
        self.assertGreater(red, green)
        self.assertGreater(green, blue)

    def test_round_trip_reproduces_the_shipped_default(self):
        """0.97/0.96/0.56 ist der Auslieferungswert des Sketches. Liesse sich
        er mit dem neuen Bedienelement nicht mehr einstellen, waere der warme
        Schweif der Installation unerreichbar -- daran ist MIN_WEIGHT
        kalibriert, es ist keine geratene Zahl."""
        red, green, blue, decay = server.fade_to_target(0.97, 0.96, 0.56)
        self.assertAlmostEqual(decay, 0.97, places=9)
        back = server.fade_from_target(red, green, blue, decay)
        for expected, actual in zip((0.97, 0.96, 0.56), back):
            self.assertAlmostEqual(expected, actual, places=6)

    def test_round_trip_is_stable_across_the_range(self):
        for target in ((1.0, 0.6, 0.0), (0.2, 1.0, 0.8), (1.0, 1.0, 1.0)):
            for decay in (0.5, 0.8, 0.97, 0.999):
                rates = server.fade_from_target(*target, decay=decay)
                again = server.fade_from_target(*server.fade_to_target(*rates))
                for expected, actual in zip(rates, again):
                    self.assertAlmostEqual(expected, actual, places=6,
                                           msg="%s @ %s" % (target, decay))

    def test_inverse_of_undefined_cases_is_white(self):
        """Zerfaellt gar nichts, gibt es keine Reihenfolge, in der die Kanaele
        verschwinden; zerfaellt alles sofort, bleibt keine Farbe uebrig. Weiss
        behauptet in beiden Faellen keine Faerbung, die es nicht gibt."""
        self.assertEqual(server.fade_to_target(1.0, 1.0, 1.0)[:3], (1.0, 1.0, 1.0))
        self.assertEqual(server.fade_to_target(0.0, 0.0, 0.0)[:3], (1.0, 1.0, 1.0))

    def test_every_result_stays_inside_the_parameter_range(self):
        """Die drei Adressen sind float 0..1 -- ein Wert darueber waere ein
        Puffer, der pro Frame HELLER wird und nie verschwindet."""
        for target in ((0, 0, 0), (1, 1, 1), (1, 0, 0), (0.01, 0.5, 1.0)):
            for decay in (0.0, 0.01, 0.5, 1.0):
                for rate in server.fade_from_target(*target, decay=decay):
                    self.assertGreaterEqual(rate, 0.0)
                    self.assertLessEqual(rate, 1.0)

    def test_build_fade_reads_the_current_values(self):
        path = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(path):
            self.skipTest("data/presets/random1.txt fehlt")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        fade = store.snapshot()["fade"]
        self.assertEqual(fade["addresses"],
                         ["/net/impulse/fadeOut/r", "/net/impulse/fadeOut/g",
                          "/net/impulse/fadeOut/b"])
        self.assertIn("decay", fade)
        for key in ("r", "g", "b"):
            self.assertGreaterEqual(fade["target"][key], 0.0)
            self.assertLessEqual(fade["target"][key], 1.0)


class AddressLabelTest(unittest.TestCase):
    """ADDRESS_LABELS: sprechende Titel statt des rohen Adresssegments.

    Der Grund fuer die Tests hier ist derselbe wie bei TAB_RULES: die
    Zuordnung ist eine inhaltliche Aussage ueber die Java-Seite und steht
    deshalb auf der Server-Seite -- nur dort ist sie ohne jsdom pruefbar.
    """

    def test_exact_address_wins(self):
        label, help_text = server.label_for("/net/impulse/speed")
        self.assertEqual(label, "Grundgeschwindigkeit")
        self.assertTrue(help_text)

    def test_track_number_is_matched_by_pattern(self):
        """Sechs Tracks, ein Eintrag -- sonst sechsmal derselbe Satz."""
        for index in range(server.SEQUENCER_TRACK_COUNT):
            label, help_text = server.label_for(
                "/net/sequencer/track%d/energy" % index)
            self.assertEqual(label, "Energie")
            self.assertTrue(help_text)

    def test_pattern_address_leaves_channel_numbers_alone(self):
        """Master/0/opacity/... ist eine echte eigene Adresse, kein Muster."""
        self.assertEqual(server.pattern_address("Master/0/opacity/0.Impulse"),
                         "Master/0/opacity/0.Impulse")
        self.assertEqual(server.pattern_address("/net/sequencer/track3/energy"),
                         "/net/sequencer/track#/energy")

    def test_colour_components_come_from_the_suffix(self):
        """18 Farbkomponenten, drei Zeilen -- und bewusst ohne Erklaerung."""
        label, help_text = server.label_for("/nodes/colors/outer/fired/Hue")
        self.assertEqual(label, "Farbton")
        self.assertIsNone(help_text)

    def test_unknown_address_falls_back_to_nothing(self):
        """Ein neuer Regler bleibt sichtbar, statt ohne Titel zu verschwinden."""
        self.assertEqual(server.label_for("/gibt/es/nicht/xyz"), (None, None))

    def test_self_explaining_parameters_carry_no_help(self):
        """Birks Beispiel: ein Regler namens "Rot" braucht keinen Satz."""
        for address in ("/net/impulse/color/r", "/net/impulse/color/g",
                        "/net/impulse/color/b"):
            label, help_text = server.label_for(address)
            self.assertTrue(label)
            self.assertIsNone(help_text)

    def test_song_matrix_and_dwell_are_complete(self):
        """16 Zellen und 8 Grenzen kommen aus einer Schleife, nicht von Hand."""
        for frm in server.SONG_LEVEL_NAMES:
            for to in server.SONG_LEVEL_NAMES:
                label, help_text = server.label_for(
                    "/songStructure/matrix/%s/%s" % (frm, to))
                self.assertTrue(label, "%s->%s ohne Titel" % (frm, to))
                self.assertTrue(help_text)
            for edge in ("min", "max"):
                label, _help = server.label_for(
                    "/songStructure/dwell/%s/%s" % (frm, edge))
                self.assertTrue(label, "%s/%s ohne Titel" % (frm, edge))

    def test_every_known_address_has_a_label(self):
        """Der eigentliche Punkt: KEIN Regler bleibt bei der rohen Adresse.

        Die Adressliste kommt aus einem echten Preset (gleiches Format wie
        remoteSettings.txt, das im Repo fehlt -- imPulse schreibt es erst beim
        Start) plus den seither dazugekommenen Adressen.
        """
        preset = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(preset):
            self.skipTest("data/presets/random1.txt fehlt")
        with open(preset, encoding="utf-8") as handle:
            addresses = [p.address for p in parse_settings(handle.read())]
        addresses += [
            "/net/impulse/split/staggerEnabled",
            "/net/impulse/split/weight/all",
            "/net/impulse/split/weight/oneLess",
            "/net/impulse/split/weight/single",
            "/net/impulse/speedQuantize/baseSpeed",
            "/net/activateNode",
            "/net/activateStripe",
            "/preset/scheduler/enabled",
            "/preset/scheduler/interval",
            "/songStructure/enabled",
        ]
        addresses += ["/net/sequencer/track%d/originTreeFilter" % i
                      for i in range(server.SEQUENCER_TRACK_COUNT)]
        # Der Versatz ist seit 2026-08-01 eine Verteilung, kein einzelner
        # staggerNoteValue mehr -- seit 2026-08-02 ueber fuenf Notenwerte
        # plus die sechste Klasse "gleichzeitig".
        addresses += [server.SPLIT_STAGGER_WEIGHT_PREFIX + suffix
                      for suffix, _note in server.SPLIT_STAGGER_NOTES]
        addresses += ["%s%d/%s" % (server.STRIPE_COLOR_PREFIX, slot, channel)
                      for slot in range(server.STRIPE_COLOR_COUNT)
                      for channel in ("r", "g", "b")]
        missing = [a for a in addresses if server.label_for(a)[0] is None]
        self.assertEqual(missing, [], "ohne Titel in ADDRESS_LABELS: %s" % missing)

    def test_no_control_in_the_snapshot_is_unlabelled(self):
        """Die Gegenprobe auf einem VOLLEN Parametersatz, nicht auf SAMPLE.

        data/remoteSettings.txt liegt nicht im Repo (imPulse schreibt es erst
        beim Start), ein Preset hat aber dasselbe Format und dieselbe
        Adressmenge. Geprueft wird der fertige Snapshot: was das UI zeigt,
        nicht was in der Tabelle steht.
        """
        path = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(path):
            self.skipTest("data/presets/random1.txt fehlt")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        snapshot = store.snapshot()
        unlabelled = []
        for tab in snapshot["tabs"]:
            controls = list(tab["primary"])
            for group in tab["groups"]:
                controls.extend(group["controls"])
            for control in controls:
                if not control.get("label"):
                    unlabelled.append(control.get("address") or control.get("base"))
        self.assertEqual(unlabelled, [],
                         "ohne sprechenden Titel im UI: %s" % unlabelled)

    def test_group_headings_are_not_raw_address_prefixes(self):
        """Eine Ueberschrift "/net/randomSpawn" ist derselbe Einwand wie ein
        Regler namens "nodeDeadTime": ein Verweis, keine Beschriftung."""
        path = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(path):
            self.skipTest("data/presets/random1.txt fehlt")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        raw = []
        for tab in store.snapshot()["tabs"]:
            for group in tab["groups"]:
                if group["title"].startswith("/"):
                    raw.append(group["key"])
        self.assertEqual(raw, [],
                         "Gruppe ohne Klartext-Titel: %s" % raw)

    def test_sequencer_legend_explains_the_compact_track_sliders(self):
        """Die Track-Karten sind kompakt beschriftet ("Wdh.") -- die Erklaerung
        steht einmal darunter, nicht 36-mal in den Karten."""
        path = os.path.join(server.REPO_ROOT, "data", "presets", "random1.txt")
        if not os.path.exists(path):
            self.skipTest("data/presets/random1.txt fehlt")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        legend = store.snapshot()["sequencer"]["legend"]
        self.assertTrue(legend)
        for entry in legend:
            self.assertTrue(entry["label"])
            self.assertTrue(entry["text"])
        # originTreeFilter fehlt bewusst: TREE_HELP steht direkt daneben und
        # sagt mehr (den Vorrang des festen Ursprungs).
        self.assertNotIn("Baum-Filter", [e["label"] for e in legend])

    def test_as_dict_carries_label_and_help(self):
        param = by_address(parse_settings(SAMPLE))["/net/impulse/nodeDeadTime"]
        entry = param.as_dict()
        self.assertEqual(entry["label"], "Totzeit pro Knoten")
        self.assertTrue(entry["help"])

    def test_colour_card_and_trigger_are_labelled(self):
        """Beide tragen ihren Titel nicht in der Adresse: die Karte hat gar
        keine, der Trigger nur ein Verb ("activateNode")."""
        groups = {g["key"]: g for g in build_groups(parse_settings(SAMPLE))}
        card = [c for c in groups["nodes/colors"]["controls"]
                if c["kind"] == "color"][0]
        self.assertEqual(card["label"], "Knotenhof: feuert")
        self.assertTrue(card["help"])

        trigger = Parameter("int", "/net/activateNode", "", 0, 0, 99)
        control = [c for c in build_groups([trigger])[0]["controls"]
                   if c["kind"] == "trigger"][0]
        self.assertEqual(control["label"], "Knoten von Hand ausloesen")
        self.assertTrue(control["help"])


class ScParamTest(unittest.TestCase):
    """Die handgepflegte Spiegelung der SC-Registry."""

    def test_every_default_is_inside_its_range(self):
        for entry in server.SC_PARAMS:
            self.assertGreaterEqual(entry["default"], entry["min"], entry["name"])
            self.assertLessEqual(entry["default"], entry["max"], entry["name"])

    def test_every_entry_has_a_label_and_a_description(self):
        """Der Registry-Name ("travelOctavesPerStep") ist die OSC-Kennung, kein
        Titel fuer einen Operator. Beides muss dastehen."""
        for entry in server.SC_PARAMS:
            self.assertTrue(entry.get("label"),
                            "%s ohne label" % entry["name"])
            self.assertTrue(entry.get("description"),
                            "%s ohne description" % entry["name"])

    def test_labels_are_unique(self):
        """Zwei Regler mit demselben Titel waeren im UI nicht zu unterscheiden."""
        labels = [p["label"] for p in server.SC_PARAMS]
        self.assertEqual(len(labels), len(set(labels)))

    def test_names_are_unique(self):
        names = [p["name"] for p in server.SC_PARAMS]
        self.assertEqual(len(names), len(set(names)))

    def test_groups_keep_insertion_order(self):
        """Die Gruppen erscheinen in der Reihenfolge ihres ERSTEN Auftretens.

        Geprueft wird die Regel, nicht die Liste: eine fest eingetragene
        Titelliste waere bei jeder neuen Klangschicht falsch (siehe die
        Bell-Tails, 2026-08-01).
        """
        groups = server.sc_param_groups()
        # Die Reihenfolge ist die Reihenfolge des ersten Auftretens in
        # SC_PARAMS, nicht alphabetisch - deshalb steht "Melodie" zwischen
        # "Glocke" und "Travel-Sound". Geprueft wird die REGEL, nicht die
        # Liste: eine fest eingetragene Titelliste waere bei jeder neuen
        # Klangschicht falsch.
        first_seen = []
        for entry in server.SC_PARAMS:
            if entry["group"] not in first_seen:
                first_seen.append(entry["group"])
        self.assertEqual([g["title"] for g in groups], first_seen)
        self.assertEqual(groups[0]["title"], "Master")
        self.assertEqual(sum(len(g["params"]) for g in groups),
                         len(server.SC_PARAMS))
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


class PaletteFileTest(unittest.TestCase):
    """data/colorPalettes.txt: Parser, Schreiber, Validierung.

    Dieselben Regeln wie bei data/stripeTrees.txt: von Hand editierbar,
    Kommentare mit '#', und bei doppeltem Namen gewinnt die LETZTE Zeile --
    die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
    """

    def test_parses_name_and_three_components(self):
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nkalt\t0.55\t0.7\t0.8\n")
        self.assertEqual(warnings, [])
        self.assertEqual([e["name"] for e in entries], ["warm", "kalt"])
        self.assertAlmostEqual(entries[0]["hue"], 0.08)
        self.assertAlmostEqual(entries[1]["bright"], 0.8)

    def test_comments_and_blank_lines_are_skipped_without_warning(self):
        entries, warnings = server.parse_palette(
            "# Kopf\n\n   \nwarm\t0.08\t0.9\t1.0\n# Ende\n")
        self.assertEqual(len(entries), 1)
        self.assertEqual(warnings, [])

    def test_broken_line_is_reported_not_fatal(self):
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nkaputt\t0.5\nnochwas\ta\tb\tc\n")
        self.assertEqual([e["name"] for e in entries], ["warm"])
        self.assertEqual(len(warnings), 2)

    def test_values_are_clamped_to_zero_one(self):
        entries, _warnings = server.parse_palette("weit\t-3\t7\t0.5\n")
        self.assertEqual(entries[0]["hue"], 0.0)
        self.assertEqual(entries[0]["sat"], 1.0)

    def test_duplicate_name_last_line_wins(self):
        # Wie StripeTreeStore: die Handkorrektur wird angehaengt, "erste
        # gewinnt" wuerde sie still verschlucken. Gemeldet wird sie trotzdem.
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nwarm\t0.10\t0.5\t0.5\n")
        self.assertEqual(len(entries), 1)
        self.assertAlmostEqual(entries[0]["hue"], 0.10)
        self.assertEqual(len(warnings), 1)

    def test_round_trip_through_the_file_format(self):
        entries = [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0},
                   {"name": "kalt", "hue": 0.55, "sat": 0.7, "bright": 0.8}]
        again, warnings = server.parse_palette(server.format_palette(entries))
        self.assertEqual(warnings, [])
        self.assertEqual(again, entries)

    def test_written_numbers_use_a_decimal_point(self):
        # Dieselbe Falle wie Locale.US in LedAnchorStore: ein Komma machte
        # die Datei fuer den eigenen Parser unlesbar.
        text = server.format_palette(
            [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}])
        body = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertIn(".", body[0])
        self.assertNotIn(",", body[0])

    def test_columns_are_separated_by_tabs(self):
        text = server.format_palette(
            [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}])
        body = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertEqual(len(body[0].split("\t")), 4)

    def test_missing_file_is_an_empty_palette_not_an_error(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        entries, warnings = server.load_palette(
            os.path.join(directory, "gibtsnicht.txt"))
        self.assertEqual(entries, [])
        self.assertEqual(warnings, [])

    def test_save_then_load_returns_the_same_palette(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, server.PALETTE_FILENAME)
        entries = [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}]
        server.save_palette(path, entries)
        loaded, warnings = server.load_palette(path)
        self.assertEqual(loaded, entries)
        self.assertEqual(warnings, [])

    def test_save_replaces_instead_of_appending(self):
        # Kein Anhaengen, genau wie NodeCrossingStore.save(): zweimal
        # speichern darf die Palette nicht verdoppeln.
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, server.PALETTE_FILENAME)
        server.save_palette(path, [{"name": "a", "hue": 0.1, "sat": 1,
                                    "bright": 1}])
        server.save_palette(path, [{"name": "b", "hue": 0.2, "sat": 1,
                                    "bright": 1}])
        loaded, _warnings = server.load_palette(path)
        self.assertEqual([e["name"] for e in loaded], ["b"])

    def test_save_leaves_no_temp_file_behind(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, server.PALETTE_FILENAME)
        server.save_palette(path, [])
        self.assertEqual(os.listdir(directory), [server.PALETTE_FILENAME])

    def test_validate_rejects_a_non_list(self):
        entries, error = server.validate_palette({"name": "warm"})
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_rejects_an_unnamed_entry(self):
        entries, error = server.validate_palette(
            [{"name": "  ", "hue": 0.1, "sat": 1, "bright": 1}])
        self.assertIsNone(entries)
        self.assertIn("Namen", error)

    def test_validate_rejects_characters_that_would_break_the_file(self):
        for name in ("wa\trm", "wa\nrm", "#warm"):
            entries, error = server.validate_palette(
                [{"name": name, "hue": 0.1, "sat": 1, "bright": 1}])
            self.assertIsNone(entries, name)
            self.assertIsNotNone(error, name)

    def test_validate_rejects_nan_and_infinity(self):
        for bad in (float("nan"), float("inf")):
            entries, error = server.validate_palette(
                [{"name": "warm", "hue": bad, "sat": 1, "bright": 1}])
            self.assertIsNone(entries)
            self.assertIsNotNone(error)

    def test_validate_rejects_a_missing_component(self):
        entries, error = server.validate_palette(
            [{"name": "warm", "hue": 0.1, "sat": 1}])
        self.assertIsNone(entries)
        self.assertIn("bright", error)

    def test_validate_rejects_a_duplicate_name(self):
        entries, error = server.validate_palette(
            [{"name": "warm", "hue": 0.1, "sat": 1, "bright": 1},
             {"name": "warm", "hue": 0.2, "sat": 1, "bright": 1}])
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_rejects_more_than_the_maximum(self):
        raw = [{"name": "f%d" % i, "hue": 0.1, "sat": 1, "bright": 1}
               for i in range(server.PALETTE_MAX_ENTRIES + 1)]
        entries, error = server.validate_palette(raw)
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_accepts_exactly_the_maximum(self):
        raw = [{"name": "f%d" % i, "hue": 0.1, "sat": 1, "bright": 1}
               for i in range(server.PALETTE_MAX_ENTRIES)]
        entries, error = server.validate_palette(raw)
        self.assertIsNone(error)
        self.assertEqual(len(entries), server.PALETTE_MAX_ENTRIES)

    def test_validate_clamps_and_normalises(self):
        entries, error = server.validate_palette(
            [{"name": " warm ", "hue": 2, "sat": -1, "bright": "0.5"}])
        self.assertIsNone(error)
        self.assertEqual(entries, [{"name": "warm", "hue": 1.0, "sat": 0.0,
                                    "bright": 0.5}])

    def test_empty_list_is_allowed(self):
        # Die letzte Farbe zu entfernen muss moeglich sein.
        entries, error = server.validate_palette([])
        self.assertIsNone(error)
        self.assertEqual(entries, [])

    def test_default_path_sits_next_to_the_settings_file(self):
        path = server.default_palette_path(
            os.path.join("a", "data", "remoteSettings.txt"))
        self.assertEqual(os.path.basename(path), server.PALETTE_FILENAME)
        self.assertEqual(os.path.basename(os.path.dirname(path)), "data")

    def test_the_repo_file_parses_without_warnings(self):
        """Gegenprobe an der echten data/colorPalettes.txt."""
        path = os.path.join(server.REPO_ROOT, "data", server.PALETTE_FILENAME)
        if not os.path.exists(path):
            self.skipTest("data/colorPalettes.txt fehlt")
        entries, warnings = server.load_palette(path)
        self.assertEqual(warnings, [])
        self.assertLessEqual(len(entries), server.PALETTE_MAX_ENTRIES)
        for entry in entries:
            for key in server.PALETTE_COMPONENTS:
                self.assertGreaterEqual(entry[key], 0.0)
                self.assertLessEqual(entry[key], 1.0)


class FadeOutEndpointTest(unittest.TestCase):
    """POST /api/fadeout: Zielfarbe + Tempo rein, drei Zerfallsraten raus.

    Braucht Flask und wird sonst uebersprungen -- dasselbe Muster wie beim
    Palette-Endpoint darunter.
    """

    def setUp(self):
        if server.Flask is None:
            self.skipTest("Flask nicht installiert")
        self.directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.directory)
        self.settings = os.path.join(self.directory, "remoteSettings.txt")
        with open(self.settings, "w", encoding="utf-8") as handle:
            for channel, value in (("r", 0.97), ("g", 0.96), ("b", 0.56)):
                handle.write("float\t/net/impulse/fadeOut/%s\tx\t%s\t0\t1\n"
                             % (channel, value))
        app = server.create_app(self.settings, "127.0.0.1", 9999,
                                os.path.join(self.directory, "presets"),
                                os.path.join(self.directory, "palette.txt"))
        app.config["TESTING"] = True
        self.client = app.test_client()

    def test_posts_all_three_channels(self):
        payload = self.client.post("/api/fadeout",
                                   json={"r": 1.0, "g": 0.5, "b": 0.0,
                                         "decay": 0.9}).get_json()
        self.assertTrue(payload["ok"])
        self.assertEqual([a["address"] for a in payload["applied"]],
                         ["/net/impulse/fadeOut/r", "/net/impulse/fadeOut/g",
                          "/net/impulse/fadeOut/b"])
        values = [a["value"] for a in payload["applied"]]
        self.assertAlmostEqual(values[0], 0.9, places=9)
        self.assertLess(values[1], values[0])
        self.assertLess(values[2], values[1])

    def test_rejects_a_missing_field(self):
        response = self.client.post("/api/fadeout", json={"r": 1, "g": 1})
        self.assertEqual(response.status_code, 400)

    def test_rejects_a_non_number(self):
        response = self.client.post("/api/fadeout",
                                    json={"r": "rot", "g": 1, "b": 1,
                                          "decay": 0.9})
        self.assertEqual(response.status_code, 400)

    def test_index_carries_the_fade_block(self):
        """Ohne den Block koennte der Waehler beim Seitenaufbau nicht auf die
        Zielfarbe gestellt werden, die gerade tatsaechlich gefahren wird."""
        payload = self.client.get("/api/parameters").get_json()
        self.assertIsNotNone(payload["fade"])
        self.assertAlmostEqual(payload["fade"]["decay"], 0.97, places=6)


class PaletteEndpointTest(unittest.TestCase):
    """GET/POST /api/palette.

    Braucht Flask und wird sonst uebersprungen -- dasselbe Muster wie beim
    python-osc-Gegentest weiter oben. Die Suite selbst bleibt ohne
    Fremdabhaengigkeiten lauffaehig.
    """

    def setUp(self):
        if server.Flask is None:
            self.skipTest("Flask nicht installiert")
        self.directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.directory)
        self.settings = os.path.join(self.directory, "remoteSettings.txt")
        with open(self.settings, "w", encoding="utf-8") as handle:
            handle.write("float\t/net/impulse/color/r\tx\t1\t0\t1\n")
        self.palette = os.path.join(self.directory, server.PALETTE_FILENAME)
        app = self.make_app(self.palette)
        app.config["TESTING"] = True
        self.client = app.test_client()

    def make_app(self, palette):
        """create_app plus Aufraeumen des Record-Status-Sockets.

        Jede App bindet einen UDP-Port fuer den Rueckkanal des
        Aufnahmeknopfes; ohne das Schliessen sammelt die Suite offene Sockets
        (und meldet ResourceWarnings). Port 0 statt 8003, damit ein laufendes
        Web-UI auf derselben Maschine die Tests nicht scheitern laesst.
        """
        app = server.create_app(self.settings, "127.0.0.1", 9999,
                                os.path.join(self.directory, "presets"),
                                palette, record_status_port=0)
        close_app_sockets(self, app)
        return app

    def test_get_on_a_missing_file_is_an_empty_palette(self):
        payload = self.client.get("/api/palette").get_json()
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["entries"], [])
        self.assertEqual(payload["warnings"], [])

    def test_post_writes_the_file_and_get_reads_it_back(self):
        body = {"entries": [{"name": "warm", "hue": 0.08, "sat": 0.9,
                             "bright": 1.0}]}
        response = self.client.post("/api/palette", json=body)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(os.path.exists(self.palette))
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual(entries, body["entries"])

    def test_post_answers_with_the_stored_palette(self):
        # Der Browser uebernimmt die Antwort als neuen Zustand -- sie muss
        # also das enthalten, was wirklich in der Datei steht.
        payload = self.client.post("/api/palette", json={"entries": [
            {"name": " warm ", "hue": 5, "sat": 0.9, "bright": 1.0}]}).get_json()
        self.assertEqual(payload["entries"],
                         [{"name": "warm", "hue": 1.0, "sat": 0.9,
                           "bright": 1.0}])

    def test_post_replaces_instead_of_appending(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "a", "hue": 0.1, "sat": 1, "bright": 1},
            {"name": "b", "hue": 0.2, "sat": 1, "bright": 1}]})
        self.client.post("/api/palette", json={"entries": [
            {"name": "b", "hue": 0.2, "sat": 1, "bright": 1}]})
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual([e["name"] for e in entries], ["b"])

    def test_post_with_an_empty_list_clears_the_palette(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "a", "hue": 0.1, "sat": 1, "bright": 1}]})
        response = self.client.post("/api/palette", json={"entries": []})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.client.get("/api/palette").get_json()["entries"],
                         [])

    def test_post_with_an_invalid_entry_is_rejected_and_changes_nothing(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "a", "hue": 0.1, "sat": 1, "bright": 1}]})
        response = self.client.post("/api/palette", json={"entries": [
            {"name": "", "hue": 0.1, "sat": 1, "bright": 1}]})
        self.assertEqual(response.status_code, 400)
        self.assertFalse(response.get_json()["ok"])
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual([e["name"] for e in entries], ["a"])

    def test_post_without_a_list_is_rejected(self):
        response = self.client.post("/api/palette", json={"entries": "warm"})
        self.assertEqual(response.status_code, 400)

    def test_post_without_a_body_is_rejected(self):
        response = self.client.post("/api/palette")
        self.assertEqual(response.status_code, 400)

    def test_index_carries_the_palette_in_the_bootstrap(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}]})
        html = self.client.get("/").get_data(as_text=True)
        self.assertIn("palette", html)
        self.assertIn("warm", html)

    def test_default_palette_path_when_none_is_given(self):
        app = self.make_app(None)
        self.assertEqual(app.config["IMPULSE_PALETTE_PATH"],
                         server.default_palette_path(self.settings))


class MarkupWiringTest(unittest.TestCase):
    """Jedes getElementById() in app.js braucht ein id= in index.html.

    Fuer die UI-Schicht gibt es bewusst kein Testgeruest -- webui/ soll ohne
    Node/npm auskommen, und ein jsdom-Test wuerde genau das einfuehren. Diese
    eine Pruefung geht trotzdem ohne Fremdabhaengigkeit und faengt den
    haeufigsten Verdrahtungsfehler: ein Handle, das null ist, weil das Markup
    nicht mitgezogen wurde. Das faellt sonst erst im Browser auf, und dann als
    stumme Seite -- app.js haengt seine Listener beim Laden an.
    """

    def _read(self, name):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()

    def test_every_element_id_used_by_the_script_exists_in_the_markup(self):
        js = self._read(os.path.join("static", "app.js"))
        html = self._read(os.path.join("templates", "index.html"))
        wanted = set(re.findall(r"getElementById\('([^']+)'\)", js))
        present = set(re.findall(r'id="([^"]+)"', html))
        self.assertTrue(wanted, "keine getElementById-Aufrufe gefunden")
        self.assertEqual(wanted - present, set(),
                         "app.js greift auf IDs zu, die es im Markup nicht gibt")

    def test_the_melody_section_is_wired(self):
        js = self._read(os.path.join("static", "app.js"))
        html = self._read(os.path.join("templates", "index.html"))
        for element_id in ("melody", "melodyFields", "melodyConfirm",
                           "melodyRecompute"):
            self.assertIn('id="%s"' % element_id, html)
            self.assertIn("getElementById('%s')" % element_id, js)
        # Die Sektion startet versteckt und wird erst sichtbar, wenn der
        # Server melody-Daten liefert.
        self.assertIn('id="melody" hidden', html)
        # Der Knopf ist ohne Bestaetigung gesperrt -- das reine Verstellen
        # eines Feldes darf die Neuberechnung nicht ausloesen.
        self.assertIn('id="melodyRecompute" disabled', html)
        self.assertIn("/api/melody/recompute", js)

    def test_nothing_stands_above_the_tab_bar(self):
        """Ganz oben steht seit 2026-08-02 NUR die Tab-Leiste.

        Jeder dauerhaft sichtbare Block darueber schiebt die Tab-Panels nach
        unten und zwingt jeden Aufruf, an ihm vorbeizuscrollen, bevor der
        erste Regler sichtbar wird -- derselbe Grund, aus dem vorher schon
        Presets und Melodie-Zuordnung in die Tabs gewandert sind. Der Test
        prueft die Struktur, nicht das Aussehen: zwischen <body> und der
        Tab-Leiste darf ausser Kommentaren kein Element stehen.
        """
        html = self._read(os.path.join("templates", "index.html"))
        start = html.index("<body>") + len("<body>")
        head = html[start:html.index('<nav class="tab-bar"')]
        head = re.sub(r"<!--.*?-->", "", head, flags=re.S)
        self.assertNotIn("<", head,
                         "ueber der Tab-Leiste steht wieder ein Element: "
                         + head.strip())

    def test_the_headline_block_is_parked_and_hung_into_a_tab(self):
        """Kopfzeile, Status- und Autocommit-Zeile liegen im Abstellplatz.

        Sie tragen feste IDs, an die app.js beim Laden seine Listener haengt,
        und #status/#autocommit/#meta werden aus dem gesamten Code heraus
        beschrieben -- sie duerfen weder neu gebaut werden noch beim
        Tab-Neuaufbau aus dem Dokument fallen.
        """
        html = self._read(os.path.join("templates", "index.html"))
        js = self._read(os.path.join("static", "app.js"))
        parked = html.index('<div id="parked"')
        for element_id in ("headline", "meta", "status", "autocommit",
                           "coupling", "reload"):
            marker = 'id="%s"' % element_id
            self.assertIn(marker, html)
            self.assertGreater(html.index(marker), parked,
                               "%s steht nicht im Abstellplatz" % element_id)
        # Zurueck auf den Abstellplatz vor jedem Neuaufbau, sonst wirft
        # tabPanelsEl.innerHTML = '' die Kopfzeile aus dem Dokument.
        self.assertIn("parkedEl.appendChild(headlineEl)", js)
        # Und wieder hinein, in denselben Tab wie die Presets.
        self.assertIn("panel.appendChild(headlineEl)", js)


class MelodyTest(unittest.TestCase):
    """Die Melodie-Sektion: vier Werte plus ein Knopf, und genau EIN Ort im UI."""

    LINES = [
        "int\t/net/melody/mode\tx\t4\t0\t7",
        "int\t/net/melody/startNode\tx\t7\t0\t91",
        "int\t/net/melody/rootMidiNote\tx\t45\t24\t84",
        "int\t/net/melody/numOctaves\tx\t3\t1\t6",
        "int\t/net/impulse/speed\tx\t16\t1\t1500",
    ]

    def _store(self, lines):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, "remoteSettings.txt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        store = ParameterStore(path=path)
        store.refresh(force=True)
        return store

    def test_all_four_fields_show_up_in_the_briefed_order(self):
        melody = server.build_melody(self._store(self.LINES).by_address)
        self.assertIsNotNone(melody)
        self.assertEqual([f["key"] for f in melody["fields"]],
                         ["mode", "startNode", "rootMidiNote", "numOctaves"])
        self.assertEqual([f["address"] for f in melody["fields"]],
                         ["/net/melody/mode", "/net/melody/startNode",
                          "/net/melody/rootMidiNote", "/net/melody/numOctaves"])

    def test_every_field_carries_label_hint_and_range(self):
        melody = server.build_melody(self._store(self.LINES).by_address)
        for field in melody["fields"]:
            self.assertTrue(field["label"])
            self.assertTrue(field["hint"])
            self.assertIn("min", field)
            self.assertIn("max", field)

    def test_start_node_range_comes_from_the_dump(self):
        """Die Obergrenze haengt an der Kreuzungszahl und aendert sich nach
        jeder Kalibriersitzung -- sie darf nirgends fest verdrahtet sein."""
        lines = [line.replace("\t0\t91", "\t0\t41") for line in self.LINES]
        melody = server.build_melody(self._store(lines).by_address)
        start = next(f for f in melody["fields"] if f["key"] == "startNode")
        self.assertEqual(start["max"], 41)

    def test_missing_addresses_hide_the_section(self):
        """Ein aelterer imPulse-Stand ohne /net/melody/* -- die Sektion bleibt
        weg statt leer dazustehen."""
        store = self._store(["int\t/net/impulse/speed\tx\t16\t1\t1500"])
        self.assertIsNone(server.build_melody(store.by_address))
        self.assertEqual(server.melody_addresses(None), set())

    def test_partial_dump_keeps_what_it_has(self):
        store = self._store(["int\t/net/melody/mode\tx\t4\t0\t7",
                             "int\t/net/impulse/speed\tx\t16\t1\t1500"])
        melody = server.build_melody(store.by_address)
        self.assertEqual([f["key"] for f in melody["fields"]], ["mode"])

    def test_mode_names_match_the_java_table(self):
        """Drei Kopien der Modusliste: MelodyModes.ALL, ~melodyModes in der
        .scd und diese Beschriftung. Die Laenge muss stimmen, sonst zeigt das
        Dropdown weniger Modi, als es gibt."""
        self.assertEqual(len(server.MELODY_MODE_NAMES), 8)
        self.assertEqual(len(set(server.MELODY_MODE_NAMES)), 8)
        # Index 4 ist Phrygisch -- der Default auf allen drei Seiten.
        self.assertEqual(server.MELODY_MODE_NAMES[4], "Phrygisch")

    def test_mode_names_cover_the_dumps_range(self):
        melody = server.build_melody(self._store(self.LINES).by_address)
        mode = next(f for f in melody["fields"] if f["key"] == "mode")
        self.assertEqual(len(melody["modeNames"]), mode["max"] - mode["min"] + 1)

    def test_java_mode_labels_line_up_with_the_names(self):
        """Gegenprobe an MelodyModes.java: gleiche Anzahl, gleiche
        Reihenfolge. Driftet eine der beiden, zeigt das Dropdown den falschen
        Namen zu einer Modus-Nummer -- und der Operator berechnet einen
        anderen Modus, als er ausgewaehlt hat."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "MelodyModes.java")
        if not os.path.exists(path):
            self.skipTest("MelodyModes.java nicht gefunden")
        with open(path, "r", encoding="utf-8") as handle:
            java = handle.read()
        found = re.findall(r'new MelodyMode\("([a-z0-9_-]+)",\s*"([^"]+)"', java)
        self.assertEqual(len(found), len(server.MELODY_MODE_NAMES))
        for (_key, name), label in zip(found, server.MELODY_MODE_NAMES):
            self.assertEqual(name, label)

    def test_addresses_are_taken_out_of_the_generic_rendering(self):
        """Sonst stuende jeder Melodie-Regler zweimal auf der Seite: einmal in
        der Sektion mit Bestaetigung und einmal als generischer Schieber, der
        so aussieht, als taete er etwas."""
        store = self._store(self.LINES)
        snapshot = store.snapshot()
        self.assertIsNotNone(snapshot["melody"])
        taken = server.melody_addresses(snapshot["melody"])
        self.assertEqual(len(taken), 4)
        for group in snapshot["groups"]:
            for control in group["controls"]:
                self.assertNotIn(control.get("address"), taken)

    def test_no_melody_address_survives_into_any_tab(self):
        store = self._store(self.LINES)
        snapshot = store.snapshot()
        for tab in snapshot["tabs"]:
            for control in tab["primary"]:
                self.assertFalse(str(control.get("address", ""))
                                 .startswith("/net/melody/"))
            for group in tab["groups"]:
                for control in group["controls"]:
                    self.assertFalse(str(control.get("address", ""))
                                     .startswith("/net/melody/"))

    def test_recompute_address_is_not_a_parameter(self):
        """/net/melody/recompute ist ein KOMMANDO. Stuende es zwischen den
        Feldern, waere es ein Regler, dessen Bewegung die ganze Zuordnung neu
        wuerfelt."""
        melody = server.build_melody(self._store(self.LINES).by_address)
        self.assertEqual(melody["recomputeAddress"], "/net/melody/recompute")
        addresses = {f["address"] for f in melody["fields"]}
        self.assertNotIn("/net/melody/recompute", addresses)

    def test_melody_parameters_are_excluded_from_presets_in_java(self):
        """Transport, nicht Inhalt -- die Java-Seite muss sie ausschliessen,
        sonst setzte ein Preset-Wechsel sie mit und die naechste
        Neuberechnung liefe mit fremden Werten."""
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "PresetStore.java")
        if not os.path.exists(path):
            self.skipTest("PresetStore.java nicht gefunden")
        with open(path, "r", encoding="utf-8") as handle:
            java = handle.read()
        excluded = java.split("EXCLUDED = {")[1].split("};")[0]
        for key, _label, _hint in server.MELODY_FIELDS:
            self.assertIn('"/net/melody/%s"' % key, excluded)

class RecordOscEncodingTest(unittest.TestCase):
    """Die Record-Kommandos tragen KEIN Argument.

    Ein versehentlich mitgeschicktes Argument waere kein Fehler, den man
    sieht: sclangs OSCdef-Funktionen ignorieren msg[1] hier einfach. Deshalb
    steht die Zusicherung im Test und nicht nur im Kommentar.
    """

    def test_message_without_argument_has_an_empty_type_tag(self):
        data = build_osc_message(server.RECORD_START, None)
        self.assertEqual(data, b"/klangnetz/record/start\x00,\x00\x00\x00")

    def test_round_trip_without_argument(self):
        address, args = server.parse_osc_message(
            build_osc_message(server.RECORD_TOGGLE, None))
        self.assertEqual(address, server.RECORD_TOGGLE)
        self.assertEqual(args, [])

    def test_round_trip_with_int_and_string(self):
        # Genau die Form, die sclang zurueckschickt: Zustand plus Dateiname.
        data = (server._osc_string(server.RECORD_STATUS)
                + server._osc_string(",is")
                + struct.pack(">i", 1)
                + server._osc_string("/tmp/klangnetz_2026-08-01_12-00-00.wav"))
        address, args = server.parse_osc_message(data)
        self.assertEqual(address, server.RECORD_STATUS)
        self.assertEqual(args, [1, "/tmp/klangnetz_2026-08-01_12-00-00.wav"])

    def test_round_trip_with_float(self):
        address, args = server.parse_osc_message(
            build_osc_message("/klangnetz/param/masterVolume", 0.5))
        self.assertEqual(address, "/klangnetz/param/masterVolume")
        self.assertEqual(len(args), 1)
        self.assertAlmostEqual(args[0], 0.5, places=6)

    def test_message_without_a_type_tag_is_read_as_no_arguments(self):
        # Erlaubt laut OSC-Spezifikation und von manchen Sendern so gebaut.
        address, args = server.parse_osc_message(server._osc_string("/x/y"))
        self.assertEqual((address, args), ("/x/y", []))

    def test_unsupported_type_is_an_error_not_a_wrong_value(self):
        data = (server._osc_string("/x/y") + server._osc_string(",b")
                + b"\x00\x00\x00\x00")
        with self.assertRaises(ValueError):
            server.parse_osc_message(data)

    def test_garbage_is_rejected(self):
        with self.assertRaises(ValueError):
            server.parse_osc_message(server._osc_string("kein-slash"))


class FakeSclang:
    """Ein UDP-Empfaenger, der sich wie der Record-Teil der .scd verhaelt.

    Bildet genau die Zustandslogik von ~recordStart/~recordStop/~recordToggle
    nach, inklusive des ignorierten Doppel-Starts und des Dateinamens, der
    nach dem Stop stehen bleibt. Antwortet -- wie sclang -- auf JEDES der vier
    Kommandos mit einer Status-Meldung.
    """

    def __init__(self) -> None:
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", 0))
        self.port = self.socket.getsockname()[1]
        self.status_port = 0
        self.answer = True              # aus = "sclang laeuft nicht"
        self.recording = False
        self.path = ""
        self.seen = []                  # (Adresse, Argumente) in Reihenfolge
        self.starts = 0
        self._lock = threading.Lock()
        self._out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while True:
            try:
                data, _sender = self.socket.recvfrom(4096)
            except OSError:
                return
            try:
                address, args = server.parse_osc_message(data)
            except (ValueError, struct.error):
                continue
            with self._lock:
                self.seen.append((address, args))
                if address == server.RECORD_START and not self.recording:
                    self._start()
                elif address == server.RECORD_STOP:
                    self.recording = False
                elif address == server.RECORD_TOGGLE:
                    if self.recording:
                        self.recording = False
                    else:
                        self._start()
                if not self.answer:
                    continue
                payload = (server._osc_string(server.RECORD_STATUS)
                           + server._osc_string(",is")
                           + struct.pack(">i", 1 if self.recording else 0)
                           + server._osc_string(self.path))
            self._out.sendto(payload, ("127.0.0.1", self.status_port))

    def _start(self) -> None:
        self.starts += 1
        self.recording = True
        self.path = "/repo/recordings/klangnetz_2026-08-01_12-00-%02d.wav" % self.starts

    def addresses(self):
        with self._lock:
            return [a for a, _args in self.seen]

    def close(self) -> None:
        self.socket.close()
        self._out.close()


class RecordEndpointTest(unittest.TestCase):
    """Die vier /api/record/*-Routen gegen ein gefaktes sclang.

    Echte UDP-Pakete auf dem Loopback, weil genau der Weg das Neue ist: ein
    Kommando ohne Argument hin, eine Status-Meldung zurueck. Ein Mock des
    Senders wuerde beides ueberspringen und nur pruefen, dass eine Funktion
    aufgerufen wurde.
    """

    def setUp(self):
        if server.Flask is None:
            self.skipTest("Flask nicht installiert")
        self.directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.directory)
        self.settings = os.path.join(self.directory, "remoteSettings.txt")
        with open(self.settings, "w", encoding="utf-8") as handle:
            handle.write("int\t/net/impulse/speed\tx\t16\t1\t1500\n")
        self.sclang = FakeSclang()
        self.addCleanup(self.sclang.close)
        app = server.create_app(self.settings, "127.0.0.1", 9999,
                                os.path.join(self.directory, "presets"),
                                os.path.join(self.directory, "palette.txt"),
                                sc_port=self.sclang.port,
                                record_status_port=0)
        app.config["TESTING"] = True
        close_app_sockets(self, app)
        self.listener = app.config["IMPULSE_RECORD_STATUS"]
        self.assertTrue(self.listener.active, "Status-Port nicht gebunden")
        # Erst jetzt bekannt: der Listener hat sich einen freien Port geholt.
        self.sclang.status_port = self.listener.port
        self.client = app.test_client()

    def test_start_sends_the_command_without_an_argument(self):
        payload = self.client.post("/api/record/start").get_json()
        self.assertTrue(payload["ok"])
        self.assertEqual(self.sclang.seen[0], (server.RECORD_START, []))

    def test_start_reports_the_state_from_sclang(self):
        payload = self.client.post("/api/record/start").get_json()
        self.assertTrue(payload["answered"])
        self.assertTrue(payload["known"])
        self.assertTrue(payload["recording"])
        self.assertTrue(payload["path"].endswith(".wav"))

    def test_second_start_does_not_open_a_second_file(self):
        first = self.client.post("/api/record/start").get_json()
        second = self.client.post("/api/record/start").get_json()
        # sclang ignoriert den zweiten Start, meldet aber trotzdem -- der
        # Knopf zeigt danach den wahren Zustand, nicht den geklickten.
        self.assertEqual(self.sclang.starts, 1)
        self.assertTrue(second["recording"])
        self.assertEqual(second["path"], first["path"])

    def test_stop_keeps_the_finished_file_name(self):
        started = self.client.post("/api/record/start").get_json()
        stopped = self.client.post("/api/record/stop").get_json()
        self.assertFalse(stopped["recording"])
        self.assertEqual(stopped["path"], started["path"])

    def test_stop_without_a_recording_is_not_an_error(self):
        response = self.client.post("/api/record/stop")
        self.assertEqual(response.status_code, 200)
        payload = response.get_json()
        self.assertTrue(payload["ok"])
        self.assertFalse(payload["recording"])

    def test_toggle_flips_the_state(self):
        self.assertTrue(self.client.post("/api/record/toggle")
                        .get_json()["recording"])
        self.assertFalse(self.client.post("/api/record/toggle")
                         .get_json()["recording"])
        self.assertEqual(self.sclang.addresses(),
                         [server.RECORD_TOGGLE, server.RECORD_TOGGLE])

    def test_status_asks_sclang_and_changes_nothing(self):
        self.client.post("/api/record/start")
        payload = self.client.get("/api/record/status").get_json()
        self.assertEqual(self.sclang.addresses()[-1], server.RECORD_QUERY)
        self.assertEqual(self.sclang.starts, 1)
        self.assertTrue(payload["recording"])

    def test_status_reports_unknown_when_sclang_is_silent(self):
        # Der Fall "Web-UI laeuft, sclang nicht" -- haeufiger als jeder
        # andere. Kein 500er, aber auch kein vorgetaeuschter Zustand.
        self.sclang.answer = False
        response = self.client.get("/api/record/status")
        self.assertEqual(response.status_code, 200)
        payload = response.get_json()
        self.assertTrue(payload["ok"])
        self.assertFalse(payload["answered"])
        self.assertFalse(payload["known"])

    def test_a_stale_answer_is_not_mistaken_for_a_fresh_one(self):
        self.client.post("/api/record/start")
        self.sclang.answer = False
        payload = self.client.post("/api/record/stop").get_json()
        # Der Listener kennt noch den alten Stand ("laeuft"), aber auf DIESES
        # Kommando kam nichts -- answered sagt es, und das UI zeigt deshalb
        # "kein Kontakt" statt einer Aufnahme, die es nicht mehr gibt.
        self.assertFalse(payload["answered"])

    def test_a_foreign_datagram_does_not_change_the_state(self):
        self.client.post("/api/record/start")
        noise = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.addCleanup(noise.close)
        noise.sendto(b"kein OSC", ("127.0.0.1", self.listener.port))
        noise.sendto(build_osc_message("/etwas/anderes", 1),
                     ("127.0.0.1", self.listener.port))
        time.sleep(0.1)
        self.assertTrue(self.listener.snapshot()["recording"])

    def test_bootstrap_carries_the_record_wiring(self):
        html = self.client.get("/").get_data(as_text=True)
        self.assertIn("\"record\"", html)
        self.assertIn("\"statusPort\": %d" % self.listener.port, html)


class RecordSectionTest(unittest.TestCase):
    """Der Knopf haengt im Sound-Tab und braucht keine Adresse aus dem Dump."""

    def test_record_section_sits_in_the_sound_tab(self):
        tabs = server.build_tabs([], None, None, None)
        by_id = {tab["id"]: tab for tab in tabs}
        self.assertIn("record", by_id[server.TAB_SOUND]["sections"])
        for tab_id, tab in by_id.items():
            if tab_id != server.TAB_SOUND:
                self.assertNotIn("record", tab["sections"])

    def test_record_section_comes_first_in_its_tab(self):
        sections = server.build_tabs([], None, None, None)
        sound = next(t for t in sections if t["id"] == server.TAB_SOUND)
        self.assertEqual(sound["sections"][0], "record")


class RecordScdTest(unittest.TestCase):
    """Gegenprobe an der .scd -- die Adressen sind auf beiden Seiten getippt."""

    def setUp(self):
        path = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "supercollider", "klangnetz_bells.scd")
        if not os.path.exists(path):
            self.skipTest("klangnetz_bells.scd nicht gefunden")
        with open(path, encoding="utf-8") as handle:
            self.scd = handle.read()

    def test_every_command_address_is_answered_by_the_scd(self):
        for address in (server.RECORD_START, server.RECORD_STOP,
                        server.RECORD_TOGGLE, server.RECORD_QUERY):
            self.assertIn("'%s'" % address, self.scd,
                          "%s wird gesendet, aber in der .scd nicht empfangen"
                          % address)

    def test_the_scd_sends_exactly_the_status_address_we_listen_for(self):
        self.assertIn("'%s'" % server.RECORD_STATUS, self.scd)

    def test_the_status_port_matches_the_scd(self):
        self.assertIn("~recordStatusPort = %d;" % server.DEFAULT_RECORD_STATUS_PORT,
                      self.scd)

    def test_the_command_port_matches_the_scd(self):
        # Die Kommandos gehen an denselben Port wie die Sound-Parameter.
        self.assertIn("~oscListenPort = %d;" % server.SC_OSC_PORT, self.scd)


if __name__ == "__main__":
    unittest.main(verbosity=2)
