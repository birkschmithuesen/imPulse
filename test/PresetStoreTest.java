import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
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

    System.exit(Check.report("PresetStoreTest"));
  }

  static void write(File file, String content) throws Exception {
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
  }
}
