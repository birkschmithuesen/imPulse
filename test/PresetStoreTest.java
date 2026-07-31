import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class PresetStoreTest {

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("presets").toFile();
    PresetStore store = new PresetStore(dir.getPath());

    // ---- Namenspruefung ----
    Check.that("einfacher Name", store.isValidName("standby"));
    Check.that("Unterstrich und Ziffern", store.isValidName("hang_drum_slow2"));
    Check.that("Bindestrich", store.isValidName("a-b"));

    Check.that("leerer Name abgewiesen", !store.isValidName(""));
    Check.that("null abgewiesen", !store.isValidName(null));
    Check.that("Grossbuchstabe abgewiesen", !store.isValidName("Standby"));
    Check.that("Punkt abgewiesen", !store.isValidName("stand.by"));
    Check.that("Schraegstrich abgewiesen", !store.isValidName("a/b"));
    Check.that("Pfadausbruch abgewiesen", !store.isValidName("../../etc/passwd"));
    Check.that("Leerzeichen abgewiesen", !store.isValidName("hang drum"));
    Check.that("Meldung nennt den Grund", store.lastMessage().length() > 0);

    StringBuilder tooLong = new StringBuilder();
    for (int i = 0; i < PresetStore.MAX_NAME_LENGTH + 1; i++) {
      tooLong.append('a');
    }
    Check.that("Ueberlaenge abgewiesen", !store.isValidName(tooLong.toString()));
    Check.that("Grenzlaenge angenommen",
        store.isValidName(tooLong.substring(0, PresetStore.MAX_NAME_LENGTH)));

    // ---- Auflistung ----
    Check.eq("leerer Ordner", 0, store.list().size());

    write(new File(dir, "standby.txt"), "float\t/master/level\t\t0.1\t0\t1\n");
    write(new File(dir, "ambient.txt"), "float\t/master/level\t\t0.2\t0\t1\n");
    write(new File(dir, "notizen.md"), "kein Preset\n");
    write(new File(dir, "Grossbuchstabe.txt"), "float\t/x\t\t0\t0\t1\n");

    List<String> names = store.list();
    Check.eq("nur die zwei gueltigen .txt-Dateien", 2, names.size());
    Check.eq("alphabetisch sortiert, erster", "ambient", names.get(0));
    Check.eq("alphabetisch sortiert, zweiter", "standby", names.get(1));

    PresetStore missing = new PresetStore(new File(dir, "gibtsnicht").getPath());
    Check.eq("fehlender Ordner ergibt leere Liste", 0, missing.list().size());
    Check.that("fehlender Ordner wird gemeldet", missing.lastMessage().length() > 0);

    // ---- Lesen ----
    write(new File(dir, "gelesen.txt"),
        "float\t/master/level\tspace for descripiton\t0.42\t0.0\t1.0\n"
        + "int\t/net/impulse/speed\tspace for descripiton\t160\t1\t1500\n"
        + "\n"
        + "float\t/nodes/colors/central/fired/Hue\t\t1.0\t0\t1\n");

    List<String[]> entries = store.read("gelesen");
    Check.that("Datei gelesen", entries != null);
    Check.eq("Leerzeile uebergangen", 3, entries.size());
    Check.eq("Typ der ersten Zeile", "float", entries.get(0)[PresetStore.COL_TYPE]);
    Check.eq("Adresse der ersten Zeile", "/master/level", entries.get(0)[PresetStore.COL_ADDRESS]);
    Check.eq("Wert der ersten Zeile", "0.42", entries.get(0)[PresetStore.COL_VALUE]);
    Check.eq("leere Beschreibung bleibt leere Spalte", "",
        entries.get(2)[PresetStore.COL_DESCRIPTION]);
    Check.eq("jeder Eintrag hat sechs Spalten", PresetStore.COLUMNS, entries.get(1).length);

    Check.that("fehlende Datei ergibt null", store.read("gibtsnicht") == null);
    Check.that("fehlende Datei wird gemeldet",
        store.lastMessage().indexOf("nicht gefunden") >= 0);
    Check.that("unzulaessiger Name ergibt null", store.read("../geheim") == null);

    write(new File(dir, "kaputt.txt"), "float\t/master/level\t0.5\n");
    Check.that("zu wenige Spalten ergeben null", store.read("kaputt") == null);
    Check.that("Spaltenzahl wird gemeldet", store.lastMessage().indexOf("Spalten") >= 0);

    // ---- Schreiben ----
    List<String[]> unsorted = new ArrayList<String[]>();
    unsorted.add(new String[] { "float", "/zzz/last", "d", "1.0", "0", "1" });
    unsorted.add(new String[] { "int", "/aaa/first", "d", "7", "0", "10" });
    unsorted.add(new String[] { "float", "/mmm/middle", "d", "0.5", "0", "1" });

    Check.that("Schreiben gelingt", store.write("geschrieben", unsorted));
    List<String[]> readBack = store.read("geschrieben");
    Check.eq("alle drei Zeilen zurueck", 3, readBack.size());
    Check.eq("nach Adresse sortiert, erste", "/aaa/first", readBack.get(0)[PresetStore.COL_ADDRESS]);
    Check.eq("nach Adresse sortiert, zweite", "/mmm/middle", readBack.get(1)[PresetStore.COL_ADDRESS]);
    Check.eq("nach Adresse sortiert, dritte", "/zzz/last", readBack.get(2)[PresetStore.COL_ADDRESS]);
    Check.eq("Wert unveraendert", "7", readBack.get(0)[PresetStore.COL_VALUE]);

    // Zweimal speichern darf nichts anhaengen - dieselbe Anforderung wie bei
    // NodeCrossingStore, wo genau das der Fehler war.
    Check.that("zweites Schreiben gelingt", store.write("geschrieben", unsorted));
    Check.eq("zweimal speichern verdoppelt nichts", 3, store.read("geschrieben").size());

    Check.that("unzulaessiger Name wird nicht geschrieben", !store.write("../boese", unsorted));
    Check.that("null-Eintraege werden abgewiesen", !store.write("leer", null));

    // Schreiben in einen noch nicht existierenden Ordner legt ihn an.
    PresetStore fresh = new PresetStore(new File(dir, "neuerOrdner").getPath());
    Check.that("Ordner wird angelegt", fresh.write("erstes", unsorted));
    Check.eq("und ist danach lesbar", 1, fresh.list().size());

    // Nach dem Schreiben darf keine Temp-Datei zurueckbleiben.
    File[] leftovers = new File(dir, "neuerOrdner").listFiles();
    boolean tempLeft = false;
    for (int i = 0; i < leftovers.length; i++) {
      if (leftovers[i].getName().endsWith(".tmp")) {
        tempLeft = true;
      }
    }
    Check.that("keine Temp-Datei zurueckgeblieben", !tempLeft);

    // ---- Klemmung ----
    Check.near("innerhalb bleibt unveraendert", 0.5, PresetStore.clampToRange(0.5f, 0f, 1f), 1e-6);
    Check.near("oberhalb wird geklemmt", 1.0, PresetStore.clampToRange(5.0f, 0f, 1f), 1e-6);
    Check.near("unterhalb wird geklemmt", 0.1, PresetStore.clampToRange(-3f, 0.1f, 1f), 1e-6);

    // ---- Snapshot ----
    List<PresetTarget> targets = new ArrayList<PresetTarget>();
    FakeParameter level = new FakeParameter("float", "/master/level", 0.1f, 0f, 1f);
    FakeParameter speed = new FakeParameter("int", "/net/impulse/speed", 160f, 1f, 1500f);
    FakeParameter schedulerOn =
        new FakeParameter("int", "/preset/scheduler/enabled", 1f, 0f, 1f);
    targets.add(level);
    targets.add(speed);
    targets.add(schedulerOn);

    List<String[]> snapshot = PresetStore.snapshot(targets);
    Check.eq("Scheduler-Parameter nicht im Snapshot", 2, snapshot.size());
    boolean schedulerFound = false;
    for (int i = 0; i < snapshot.size(); i++) {
      if (snapshot.get(i)[PresetStore.COL_ADDRESS].startsWith("/preset/scheduler/")) {
        schedulerFound = true;
      }
    }
    Check.that("keine Scheduler-Adresse im Snapshot", !schedulerFound);

    // ---- Anwenden ----
    List<String[]> toApply = new ArrayList<String[]>();
    toApply.add(new String[] { "float", "/master/level", "d", "0.7", "0", "1" });
    toApply.add(new String[] { "int", "/net/impulse/speed", "d", "42", "1", "1500" });

    PresetApplyReport report = PresetStore.apply(toApply, targets);
    Check.eq("zwei Parameter gesetzt", 2, report.applied);
    Check.near("Float uebernommen", 0.7, level.value, 1e-6);
    Check.near("Int uebernommen", 42.0, speed.value, 1e-6);
    Check.that("Bericht ohne Auffaelligkeiten", report.clean());

    // Klemmung auf die Grenzen aus dem Code, NICHT auf die Spalten der Datei.
    List<String[]> tooHigh = new ArrayList<String[]>();
    tooHigh.add(new String[] { "float", "/master/level", "d", "5.0", "0", "99" });
    PresetApplyReport clampReport = PresetStore.apply(tooHigh, targets);
    Check.near("Wert auf die Code-Grenze geklemmt, nicht auf die Dateigrenze",
        1.0, level.value, 1e-6);
    // Geklemmt UND gemeldet: eine stille Klemmung wuerde verbergen, dass ein
    // Preset Werte aus einem frueheren, weiteren Bereich mitbringt.
    Check.eq("Klemmung wird gemeldet", 1, clampReport.adjusted.size());
    Check.that("Meldung nennt die Adresse",
        clampReport.adjusted.get(0).indexOf("/master/level") >= 0);
    Check.eq("trotzdem als gesetzt gezaehlt", 1, clampReport.applied);
    Check.that("und nicht als unbekannt gemeldet", clampReport.unknown.isEmpty());
    Check.that("Bericht gilt nicht als sauber", !clampReport.clean());

    // Kommando-Adressen: still uebergehen, nicht melden.
    List<String[]> withCommands = new ArrayList<String[]>();
    withCommands.add(new String[] { "int", "/net/activateNode", "d", "3", "0", "76" });
    withCommands.add(new String[] { "int", "/net/activateStripe", "d", "5", "0", "29" });
    withCommands.add(new String[] { "float", "/master/level", "d", "0.3", "0", "1" });
    PresetApplyReport commandReport = PresetStore.apply(withCommands, targets);
    Check.eq("nur der echte Parameter gezaehlt", 1, commandReport.applied);
    Check.eq("Kommandos nicht als unbekannt gemeldet", 0, commandReport.unknown.size());

    // Unbekannte Adresse: melden, uebrige Zeilen trotzdem anwenden.
    List<String[]> withUnknown = new ArrayList<String[]>();
    withUnknown.add(new String[] { "float", "/gibt/es/nicht", "d", "0.5", "0", "1" });
    withUnknown.add(new String[] { "float", "/master/level", "d", "0.25", "0", "1" });
    PresetApplyReport unknownReport = PresetStore.apply(withUnknown, targets);
    Check.eq("eine unbekannte Adresse gemeldet", 1, unknownReport.unknown.size());
    Check.eq("Adresse benannt", "/gibt/es/nicht", unknownReport.unknown.get(0));
    Check.near("die andere Zeile wurde trotzdem angewendet", 0.25, level.value, 1e-6);
    Check.that("Bericht nennt die unbekannte Adresse",
        unknownReport.summary().indexOf("/gibt/es/nicht") >= 0);

    // Registrierte Adresse fehlt in der Datei.
    List<String[]> incomplete = new ArrayList<String[]>();
    incomplete.add(new String[] { "float", "/master/level", "d", "0.2", "0", "1" });
    PresetApplyReport incompleteReport = PresetStore.apply(incomplete, targets);
    Check.eq("eine fehlende Adresse gemeldet", 1, incompleteReport.missing.size());
    Check.eq("fehlende Adresse benannt", "/net/impulse/speed",
        incompleteReport.missing.get(0));
    Check.that("Scheduler-Adresse gilt nicht als fehlend",
        incompleteReport.missing.indexOf("/preset/scheduler/enabled") < 0);

    // Unlesbarer Wert.
    List<String[]> broken = new ArrayList<String[]>();
    broken.add(new String[] { "float", "/master/level", "d", "keine Zahl", "0", "1" });
    float before = level.value;
    PresetApplyReport brokenReport = PresetStore.apply(broken, targets);
    Check.eq("unlesbarer Wert gemeldet", 1, brokenReport.unparsable.size());
    Check.near("Wert unveraendert geblieben", before, level.value, 1e-6);
    Check.that("nicht zusaetzlich als unbekannt gemeldet", brokenReport.unknown.isEmpty());

    System.exit(Check.report("PresetStoreTest"));
  }

  static void write(File file, String content) throws Exception {
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
  }

  // Steht fuer einen RemoteControlled*Parameter. Die echten Klassen liegen in
  // AbstractParameter.java und importieren oscP5, das der Testsuite nicht zur
  // Verfuegung steht (test/run.sh hat nur core.jar). Die Klemmung nutzt
  // absichtlich dieselbe Hilfsmethode wie die echten Klassen, damit hier
  // nicht eine zweite Rechnung geprueft wird.
  static class FakeParameter implements PresetTarget {
    final String type;
    final String address;
    final float min;
    final float max;
    float value;

    FakeParameter(String type, String address, float value, float min, float max) {
      this.type = type;
      this.address = address;
      this.value = value;
      this.min = min;
      this.max = max;
    }

    public void presetEntries(List<String[]> out) {
      out.add(new String[] { type, address, "space for descripiton",
          String.valueOf(value), String.valueOf(min), String.valueOf(max) });
    }

    public int applyPreset(String wantedAddress, float newValue) {
      if (!address.equals(wantedAddress)) {
        return PresetStore.PRESET_NOT_MINE;
      }
      value = PresetStore.clampToRange(newValue, min, max);
      return (value == newValue) ? PresetStore.PRESET_APPLIED : PresetStore.PRESET_ADJUSTED;
    }
  }
}
