import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EnergyLevelStoreTest {

  static File temp(String content) throws Exception {
    File file = File.createTempFile("energyLevels", ".txt");
    file.deleteOnExit();
    PrintWriter writer = new PrintWriter(file, "UTF-8");
    writer.print(content);
    writer.close();
    return file;
  }

  static List<String> names(String... items) {
    return new ArrayList<String>(Arrays.asList(items));
  }

  static String join(List<String> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) { sb.append(','); }
      sb.append(items.get(i));
    }
    return sb.toString();
  }

  public static void main(String[] args) throws Exception {
    // ---- Die Level selbst ----
    Check.eq("vier Level", 4, EnergyLevelStore.LEVEL_COUNT);
    Check.eq("Level 0 ist ruhig", "ruhig", EnergyLevelStore.LEVEL_NAMES[0]);
    Check.eq("Level 3 ist dramatisch", "dramatisch", EnergyLevelStore.LEVEL_NAMES[3]);
    Check.eq("Rueckfall ist mittel", 1, EnergyLevelStore.FALLBACK_LEVEL);
    Check.eq("levelIndexOf kennt die Namen", 2,
        EnergyLevelStore.levelIndexOf("dynamisch"));
    Check.eq("levelIndexOf ist unempfindlich gegen Gross-/Kleinschreibung", 0,
        EnergyLevelStore.levelIndexOf("Ruhig"));
    Check.eq("levelIndexOf meldet Unbekanntes mit -1", -1,
        EnergyLevelStore.levelIndexOf("episch"));
    Check.eq("levelIndexOf vertraegt null", -1, EnergyLevelStore.levelIndexOf(null));
    Check.eq("nameOf ausserhalb des Bereichs gibt den Rueckfall",
        "mittel", EnergyLevelStore.nameOf(99));

    // ---- Parsen: Kommentare, Leerzeilen, Tab und Leerzeichen ----
    EnergyLevelStore store = new EnergyLevelStore();
    Check.that("laden gelingt", store.load(temp(
        "# Kopfzeile\n"
        + "\n"
        + "eins\truhig\n"
        + "zwei    mittel\n"
        + "   drei\tdynamisch   \n"
        + "vier\tdramatisch\n"
        + "# fuenf\truhig\n").getPath()));
    Check.eq("vier Zuordnungen", 4, store.assignedCount());
    Check.eq("keine abgelehnte Zeile", 0, store.rejectedCount());
    Check.eq("eins ist ruhig", 0, store.levelOf("eins"));
    Check.eq("zwei mit Leerzeichen getrennt ist mittel", 1, store.levelOf("zwei"));
    Check.eq("drei mit Randleerzeichen ist dynamisch", 2, store.levelOf("drei"));
    Check.eq("vier ist dramatisch", 3, store.levelOf("vier"));
    Check.eq("eine auskommentierte Zeile zaehlt nicht",
        EnergyLevelStore.FALLBACK_LEVEL, store.levelOf("fuenf"));

    // ---- Unbekannter Level-Name wird abgelehnt, nicht geraten ----
    EnergyLevelStore bad = new EnergyLevelStore();
    Check.that("laden gelingt trotz kaputter Zeile", bad.load(temp(
        "eins\truhig\n"
        + "zwei\tepisch\n"
        + "drei\n").getPath()));
    Check.eq("eine gueltige Zuordnung", 1, bad.assignedCount());
    Check.eq("zwei abgelehnte Zeilen", 2, bad.rejectedCount());
    Check.that("der Grund steht in der Meldung",
        bad.lastMessage().indexOf("episch") >= 0);
    Check.eq("die abgelehnte Zeile faellt auf den Rueckfall",
        EnergyLevelStore.FALLBACK_LEVEL, bad.levelOf("zwei"));

    // ---- Doppelter Name: die LETZTE Zeile gewinnt ----
    // Die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
    EnergyLevelStore dup = new EnergyLevelStore();
    dup.load(temp("eins\truhig\neins\tdramatisch\n").getPath());
    Check.eq("die letzte Zeile gewinnt", 3, dup.levelOf("eins"));
    Check.eq("die Ueberschreibung wird gezaehlt", 1, dup.overriddenCount());
    Check.eq("eine Ueberschreibung ist keine zweite Zuordnung",
        1, dup.assignedCount());
    Check.that("die Ueberschreibung wird gemeldet",
        dup.lastMessage().indexOf("eins") >= 0);

    // ---- Dritte Spalte: in der Rotation ja/nein ----
    // Das Level sagt, WIE ein Preset klingt; die dritte Spalte sagt, ob es
    // ueberhaupt zur Wahl steht. Zwei unabhaengige Aussagen: ein Preset kann
    // "mittel" bleiben und trotzdem aus der Rotation raus.
    EnergyLevelStore act = new EnergyLevelStore();
    Check.that("laden gelingt", act.load(temp(
        "eins\truhig\t1\n"
        + "zwei\truhig\t0\n"
        + "drei\truhig\n"
        + "vier    mittel  0\n").getPath()));
    Check.eq("vier Zuordnungen", 4, act.assignedCount());
    Check.eq("keine abgelehnte Zeile", 0, act.rejectedCount());
    Check.that("aktiv mit dritter Spalte 1", act.isActive("eins"));
    Check.that("inaktiv mit dritter Spalte 0", !act.isActive("zwei"));
    Check.that("fehlende dritte Spalte gilt als aktiv", act.isActive("drei"));
    Check.that("mit Leerzeichen getrennt ebenso", !act.isActive("vier"));
    Check.that("ein gar nicht getaggtes Preset ist aktiv",
        act.isActive("nie-erwaehnt"));
    Check.that("isActive vertraegt null", act.isActive(null));
    Check.eq("das Level bleibt vom Schalter unberuehrt", 0, act.levelOf("zwei"));
    Check.eq("zwei inaktive Presets", 2, act.inactiveCount());
    Check.that("der Bericht nennt die inaktiven",
        act.report().indexOf("inaktiv") >= 0);

    // Ein unbrauchbarer Wert in der dritten Spalte wird abgelehnt, nicht
    // geraten - dieselbe Regel wie beim unbekannten Level-Namen. Ein
    // stillschweigendes "gilt als aktiv" waere ein Preset in der Rotation,
    // das der Operator herausgenommen zu haben glaubt.
    EnergyLevelStore badAct = new EnergyLevelStore();
    Check.that("laden gelingt trotz kaputter dritter Spalte", badAct.load(temp(
        "eins\truhig\tja\n"
        + "zwei\tmittel\t1\n").getPath()));
    Check.eq("eine gueltige Zuordnung", 1, badAct.assignedCount());
    Check.eq("eine abgelehnte Zeile", 1, badAct.rejectedCount());
    Check.that("der Grund steht in der Meldung",
        badAct.lastMessage().indexOf("ja") >= 0);
    Check.that("die abgelehnte Zeile taggt gar nichts",
        badAct.isActive("eins"));
    Check.eq("die abgelehnte Zeile faellt auch beim Level auf den Rueckfall",
        EnergyLevelStore.FALLBACK_LEVEL, badAct.levelOf("eins"));

    // Bei doppeltem Namen gewinnt die letzte Zeile auch fuer den Schalter -
    // sonst traegt eine angehaengte Handkorrektur das neue Level, aber den
    // alten Rotationszustand.
    EnergyLevelStore dupAct = new EnergyLevelStore();
    dupAct.load(temp("eins\truhig\t0\neins\tdramatisch\t1\n").getPath());
    Check.eq("die letzte Zeile gewinnt beim Level", 3, dupAct.levelOf("eins"));
    Check.that("die letzte Zeile gewinnt beim Schalter", dupAct.isActive("eins"));
    Check.eq("eine Ueberschreibung", 1, dupAct.overriddenCount());
    // Und umgekehrt: eine angehaengte Zeile OHNE dritte Spalte setzt den
    // Schalter auf den Default zurueck, statt den alten Wert zu behalten.
    EnergyLevelStore dupBack = new EnergyLevelStore();
    dupBack.load(temp("eins\truhig\t0\neins\truhig\n").getPath());
    Check.that("ohne dritte Spalte gilt wieder der Default",
        dupBack.isActive("eins"));

    // ---- presetsForLevel ----
    EnergyLevelStore pool = new EnergyLevelStore();
    pool.load(temp("a\truhig\nb\tdynamisch\nc\truhig\nd\tdramatisch\n").getPath());
    List<String> all = names("d", "c", "b", "a", "unbekannt");
    Check.eq("presetsForLevel behaelt die Reihenfolge von allNames",
        "c,a", join(pool.presetsForLevel(0, all)));
    Check.eq("ein nicht getaggtes Preset zaehlt zum Rueckfall-Level",
        "unbekannt", join(pool.presetsForLevel(1, all)));
    Check.eq("ein Level ohne Preset liefert eine LEERE Liste, nicht null",
        0, pool.presetsForLevel(2, names("d", "a")).size());
    Check.that("presetsForLevel liefert nie null",
        pool.presetsForLevel(2, names("d", "a")) != null);
    Check.eq("ein Level ausserhalb des Bereichs liefert nichts",
        0, pool.presetsForLevel(99, all).size());
    Check.eq("presetsForLevel vertraegt null als Namensliste",
        0, pool.presetsForLevel(0, null).size());
    // Eine Adresse, die nur in der Datei steht, aber keine Preset-Datei mehr
    // hat, darf nicht im Pool auftauchen - sonst versucht der Director ein
    // Preset zu laden, das es nicht gibt.
    Check.eq("nur Namen aus allNames kommen in den Pool",
        "a", join(pool.presetsForLevel(0, names("a"))));

    Check.eq("untaggedCount zaehlt die Namen ohne Eintrag",
        1, pool.untaggedCount(all));
    Check.eq("untaggedCount vertraegt null", 0, pool.untaggedCount(null));

    // ---- presetsForLevel filtert die inaktiven heraus ----
    // Das ist der Zweck der dritten Spalte: der Director soll ein
    // herausgenommenes Preset nie ziehen, auch wenn es getaggt bleibt.
    EnergyLevelStore poolAct = new EnergyLevelStore();
    poolAct.load(temp("a\truhig\t1\nb\truhig\t0\nc\truhig\n").getPath());
    List<String> allAct = names("a", "b", "c");
    Check.eq("ein inaktives Preset kommt nicht in den Pool",
        "a,c", join(poolAct.presetsForLevel(0, allAct)));
    Check.eq("das inaktive bleibt trotzdem in seinem Level",
        0, poolAct.levelOf("b"));
    // Ein Level, dessen Presets alle herausgenommen sind, liefert eine leere
    // Liste - SongStructureDirector faellt dann auf ein anderes Level zurueck,
    // genau wie bei einem Level ohne Presets.
    EnergyLevelStore poolOff = new EnergyLevelStore();
    poolOff.load(temp("a\tdynamisch\t0\nb\tdynamisch\t0\n").getPath());
    Check.eq("ein komplett herausgenommenes Level ist leer",
        0, poolOff.presetsForLevel(2, names("a", "b")).size());
    // Ein nicht getaggtes Preset liegt im Rueckfall-Level UND ist aktiv -
    // sonst verschwaende es still aus der Show, statt nur unklassifiziert zu
    // sein.
    Check.eq("ein nicht getaggtes Preset bleibt waehlbar",
        "x", join(poolOff.presetsForLevel(EnergyLevelStore.FALLBACK_LEVEL,
            names("x"))));

    // ---- Fehlende Datei haelt die Show nicht an ----
    EnergyLevelStore missing = new EnergyLevelStore();
    Check.that("fehlende Datei meldet false",
        !missing.load("/dev/null/gibt-es-nicht/energyLevels.txt"));
    Check.that("der Pfad steht in der Meldung",
        missing.lastMessage().indexOf("energyLevels.txt") >= 0);
    Check.eq("ohne Datei gilt fuer jedes Preset der Rueckfall",
        EnergyLevelStore.FALLBACK_LEVEL, missing.levelOf("irgendwas"));
    Check.eq("ohne Datei liegen alle Presets im Rueckfall-Level",
        "x,y", join(missing.presetsForLevel(EnergyLevelStore.FALLBACK_LEVEL,
            names("x", "y"))));

    // ---- Ein zweites load() ersetzt den Stand, statt ihn zu ergaenzen ----
    EnergyLevelStore twice = new EnergyLevelStore();
    twice.load(temp("a\tdramatisch\n").getPath());
    twice.load(temp("b\truhig\n").getPath());
    Check.eq("nach dem zweiten Laden ist a nicht mehr getaggt",
        EnergyLevelStore.FALLBACK_LEVEL, twice.levelOf("a"));
    Check.eq("nach dem zweiten Laden zaehlt nur die neue Datei",
        1, twice.assignedCount());

    // ---- Gegenprobe an der echten Datei ----
    // Erwartungen werden aus dem Verzeichnis gerechnet, nicht als Zahl
    // hingeschrieben: data/presets/ waechst.
    EnergyLevelStore real = new EnergyLevelStore();
    if (!real.load("data/energyLevels.txt")) {
      Check.that("data/energyLevels.txt ist lesbar: " + real.lastMessage(), false);
    } else {
      PresetStore presets = new PresetStore("data/presets");
      List<String> live = presets.list();
      Check.that("es gibt ueberhaupt Presets", live.size() > 0);
      Check.eq("jedes Preset in data/presets ist getaggt",
          0, real.untaggedCount(live));
      Check.eq("keine abgelehnte Zeile in der echten Datei",
          0, real.rejectedCount());
      Check.eq("kein doppelter Name in der echten Datei",
          0, real.overriddenCount());
      for (int level = 0; level < EnergyLevelStore.LEVEL_COUNT; level++) {
        List<String> forLevel = real.presetsForLevel(level, live);
        // Mindestens zwei je Level: mit nur einem saehe ein wiederbesuchtes
        // Level jedes Mal gleich aus, und "zuletzt gespieltes vermeiden"
        // liefe leer.
        Check.that("Level " + EnergyLevelStore.nameOf(level)
            + " hat mindestens zwei Presets (hat " + forLevel.size() + ")",
            forLevel.size() >= 2);
      }
    }

    System.exit(Check.report("EnergyLevelStoreTest"));
  }
}
