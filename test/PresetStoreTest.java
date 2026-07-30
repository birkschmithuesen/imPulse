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

    System.exit(Check.report("PresetStoreTest"));
  }

  static void write(File file, String content) throws Exception {
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
  }
}
