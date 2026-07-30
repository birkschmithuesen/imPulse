#!/usr/bin/env python3
"""Tests fuer die Logik hinter dem Web-UI.

Laeuft mit der Standardbibliothek allein -- weder Flask noch python-osc noch
eine laufende Installation noetig. Geprueft wird das, was zwischen
remoteSettings.txt und dem OSC-Paket schiefgehen kann:

    python3 webui/test_webui.py
"""

import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server  # noqa: E402
from server import (ADVANCED_ADDRESSES, ADVANCED_GROUP_KEY, Parameter, ParameterStore,  # noqa: E402
                    TRIGGER_ADDRESSES, build_groups, build_osc_message,
                    coupled_values, group_key, parse_settings)


# Ausschnitt aus einem echten Dump (Tabs!), inklusive der Eigenheiten:
# wissenschaftliche Notation, Farbtripel, Adressen ohne fuehrenden Slash.
SAMPLE = "\n".join([
    "int\t/net/impulse/speed\tspace for descripiton\t160\t1\t1500",
    "float\t/net/impulse/energyDecay\tspace for descripiton\t0.01\t1.0E-4\t0.5",
    "float\t/net/impulse/energyDecayfactor\tspace for descripiton\t0.2\t1.0E-4\t1.0",
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
        self.assertEqual(len(params), 12)

    def test_fields(self):
        param = by_address(parse_settings(SAMPLE))["/net/impulse/energyDecay"]
        self.assertEqual(param.type, "float")
        self.assertAlmostEqual(param.value, 0.01)
        self.assertAlmostEqual(param.minimum, 1.0e-4)  # wissenschaftliche Notation
        self.assertAlmostEqual(param.maximum, 0.5)

    def test_int_type(self):
        param = by_address(parse_settings(SAMPLE))["/net/impulse/speed"]
        self.assertTrue(param.is_int)
        self.assertEqual(param.step(), 1.0)

    def test_crlf_and_blank_lines(self):
        text = SAMPLE.replace("\n", "\r\n") + "\r\n   \r\n"
        self.assertEqual(len(parse_settings(text)), 12)

    def test_broken_lines_are_skipped_not_fatal(self):
        text = ("kaputt\n"
                "float\t/a/b\td\tnichtszahl\t0\t1\n"
                "quatsch\t/c/d\td\t1\t0\t1\n"
                + SAMPLE)
        self.assertEqual(len(parse_settings(text)), 12)

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
        # /net/impulse/speed: Java-Range 1..1500, UI-Override 1..400 (siehe
        # docs/webui-parameter-review-2026-07-30.md Abschnitt 1, Birk-Freigabe
        # 2026-07-30) -- der Regler zeigt/erlaubt nur die engere Range.
        text = "int\t/net/impulse/speed\td\t160\t1\t1500"
        param = parse_settings(text)[0]
        d = param.as_dict()
        self.assertEqual(d["min"], 1)
        self.assertEqual(d["max"], 400)

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
        self.assertAlmostEqual(values["/net/impulse/energyDecay"], 0.01)
        self.assertAlmostEqual(values["/net/impulse/energyDecayfactor"], 0.2)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 1.0)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 3.0)

    def test_double_speed(self):
        values, _ = self.applied(320)
        self.assertAlmostEqual(values["/net/impulse/energyDecay"], 0.02)
        self.assertAlmostEqual(values["/net/impulse/energyDecayfactor"], 0.4)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 0.5)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 1.5)

    def test_half_speed(self):
        values, _ = self.applied(80)
        self.assertAlmostEqual(values["/net/impulse/energyDecay"], 0.005)
        self.assertAlmostEqual(values["/net/impulse/energyDecayfactor"], 0.1)
        self.assertAlmostEqual(values["/net/impulse/nodeDeadTime"], 2.0)
        self.assertAlmostEqual(values["/net/randomSpawn/interval"], 6.0)

    def test_results_are_clamped_to_the_files_ranges(self):
        # Speed 1 = Faktor 1/160: energyDecay liefe unter das Minimum,
        # nodeDeadTime und interval weit ueber ihr Maximum.
        values, _ = self.applied(1)
        self.assertAlmostEqual(values["/net/impulse/energyDecay"], 1.0e-4)
        # energyDecayfactor: 0.2 * (1/160) = 0.00125, liegt noch innerhalb
        # ihres eigenen Bereichs [1e-4, 1.0] -- keine Klemmung noetig.
        self.assertAlmostEqual(values["/net/impulse/energyDecayfactor"], 0.00125)
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
        packet = build_osc_message("/net/impulse/energyDecay", 0.25)
        self.assertEqual(len(packet) % 4, 0)
        self.assertTrue(packet.startswith(b"/net/impulse/energyDecay\x00\x00\x00\x00"))
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

    def test_bool_is_rejected(self):
        with self.assertRaises(TypeError):
            build_osc_message("/x", True)

    def test_matches_python_osc_when_available(self):
        try:
            from pythonosc import osc_message_builder
        except ImportError:
            self.skipTest("python-osc nicht installiert")
        for address, value in (("/a/b", 0.25), ("/a/b", 7), ("/laengere/adresse", 1.5)):
            builder = osc_message_builder.OscMessageBuilder(address=address)
            builder.add_arg(value)
            self.assertEqual(build_osc_message(address, value),
                             builder.build().dgram, address)


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
