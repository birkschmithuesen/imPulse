import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Sendetakt fuer /net/hitNode: eine kurze FIFO-Warteschlange mit maximaler
// Auslieferungsrate.
//
// ANLASS (Birk, live an der Installation, 2026-08-02): nach einem Deploy
// "Chaos, dann kein Sound mehr". Im Log von scsynth standen ueber 160 000
// "command FIFO full" - die Befehlswarteschlange des Klangservers war
// uebergelaufen. scsynth verwirft in diesem Zustand neue Synth-Erzeugungen,
// OHNE abzustuerzen: der Prozess laeuft weiter, es kommt nur kein Ton mehr.
//
// Ursache am Sender: /net/impulse hat seit jeher eine Drossel
// (ImpulseOscThrottle), /net/hitNode hatte KEINE - ausgerechnet die Nachricht,
// die auf der Klangseite Synths erzeugt (je Treffer sechs: Glocke plus fuenf
// Tails). Jeder gestartete Split-Zweig schickte sein Datagramm sofort. Der
// Polyphonie-Deckel ~maxPolyphony in klangnetz_bells.scd greift dagegen nicht:
// er begrenzt gleichzeitig KLINGENDE Stimmen, nicht die Rate eingehender
// Kommandos je Sekunde. Er bleibt als zweite, unabhaengige Ebene bestehen -
// diese Klasse ergaenzt ihn am Sender, sie ersetzt ihn nicht.
//
// WARUM WARTESCHLANGE UND NICHT AUSWAHL wie bei ImpulseOscThrottle.select():
// ein Node-Treffer ist ein eigenstaendiges musikalisches Ereignis, ein Ton,
// kein austauschbares Abtastwert eines fortlaufenden Stroms. Ein verworfener
// Treffer waere ein Lichtblitz an einer Kreuzung ohne Ton - eine stille
// Diskrepanz, die der Zuschauer sieht und niemand als Fehler meldet. Ein
// Burst wird deshalb ueber ein paar zusaetzliche Millisekunden gestreckt
// statt beschnitten: die Reihenfolge bleibt, die Ereignisse bleiben.
//
// Ohne Processing und ohne oscP5, damit test/run.sh die Klasse uebersetzen
// kann - dasselbe Muster wie ImpulseOscThrottle, SplitStagger und
// OriginSequencer.
class HitNodeOscThrottle {

  // Obergrenze der Warteschlange. Der Notfall-Deckel, damit die Liste bei
  // echter Dauerueberlast nicht unbegrenzt waechst - dieselbe Sorge wie
  // SplitStagger.MAX_PENDING, aber mit umgekehrter Verwurfsregel (siehe
  // enqueue()).
  //
  // 256 sind bei der Auslieferungsrate rund 2,5 Sekunden Rueckstau. Wer laenger
  // wartet, hoert die Glocke so weit nach dem Lichtblitz, dass sie als anderes
  // Ereignis gelesen wird - eine groessere Schlange wuerde den Verlust also nur
  // in eine Verschiebung umwandeln, die genauso falsch klingt.
  static final int MAX_PENDING = 256;

  // Rueckfallrate fuer einen entarteten Reglerwert. Identisch mit dem
  // Auslieferungswert von /net/hitNode/rateHz.
  //
  // Die ABFANGREGEL ist dieselbe wie in ImpulseOscThrottle.due(): !(rateHz > 0f)
  // statt rateHz <= 0f, weil jeder direkte Vergleich mit NaN false ergibt und
  // NaN sonst durchrutschte - aus dem Intervall wuerde NaN und jeder Aufruf
  // meldete "faellig", also ein ungedrosselter Strom, genau der Zustand, gegen
  // den diese Klasse gebaut ist.
  //
  // Die FOLGE ist dagegen bewusst eine andere als dort. Bei /net/impulse heisst
  // Rate 0 "Strom aus" - der Not-Aus waehrend der Show, die Positionsmeldungen
  // sind Zugabe. Ein /net/hitNode-Strom "aus" waere dagegen eine stumme
  // Installation. Also weder Schwall noch Stille: die Drossel arbeitet mit dem
  // Auslieferungswert weiter. Der Regler kann diesen Fall ohnehin nicht
  // herstellen (Bereich 1..200); er faengt eine kaputte Preset-Zeile ab.
  static final float NEUTRAL_RATE_HZ = 100f;

  // Obergrenze des angesparten Sendeguthabens, in Sekunden Sendezeit.
  //
  // Ohne diesen Deckel waere die Drossel in genau dem Fall wirkungslos, fuer
  // den es sie gibt: nach einer ruhigen Minute stuenden bei 100 Hz 6000
  // Guthaben-Einheiten bereit, und der naechste Burst ginge ungebremst raus.
  // 0,05 s sind zwei Frames bei den 40 Hz, mit denen drawMe() laeuft - genug,
  // dass ein verspaeteter Frame seinen Rueckstand aufholt, zu wenig, um einen
  // Schwall zu erlauben.
  static final double MAX_CREDIT_SECONDS = 0.05;

  // Toleranz beim Vergleich des Guthabens mit 1. Ein Guthaben, das sich aus
  // Frame-Abstaenden zusammenaddiert, trifft die 1 nie exakt; ohne die
  // Toleranz verschoebe sich eine faellige Nachricht um einen ganzen Frame.
  // Begruendung und Groessenordnung wie EPSILON in ImpulseOscThrottle.
  private static final double EPSILON = 1e-9;

  private final ArrayDeque<PendingSpawn> pending = new ArrayDeque<PendingSpawn>();

  // Sendeguthaben in Nachrichten. Waechst mit der verstrichenen Zeit mal Rate,
  // gedeckelt auf MAX_CREDIT_SECONDS.
  private double credit = 0.0;

  // Wanduhr des letzten Aufrufs, in Sekunden. NaN heisst "noch nie gerufen" -
  // ein Nullwert waere bei einer Zeitbasis aus System.currentTimeMillis()/1000
  // kein brauchbarer Startpunkt, dieselbe Ueberlegung wie das
  // NEGATIVE_INFINITY in ImpulseOscThrottle.
  private double lastUpdate = Double.NaN;

  private int dropped = 0;

  // Reiht einen Treffer ein.
  //
  // Ueber der Obergrenze faellt der AELTESTE wartende Eintrag, nicht der neue -
  // ausdruecklich umgekehrt zu SplitStagger.schedule(), und aus dem
  // Gegenstueck seiner Begruendung. Dort ist ein wartendes Kind die
  // Fortsetzung einer Bewegung, die der Zuschauer gerade hat ankommen sehen;
  // hier ist ein wartender Treffer ein Ton, dessen Lichtblitz laengst vorbei
  // ist. Zwischen zwei Ereignissen, von denen eines verloren gehen muss, ist
  // das aeltere das weiter von "jetzt" entfernte - und ein neuer Treffer ist
  // die aktuellere sichtbare Wirklichkeit.
  //
  // Der Fall tritt nur bei echter Dauerueberlast ein: bei Auslieferungsrate
  // muesste die Trefferrate ueber 100/s liegen und dort auch bleiben. Ein
  // einzelner Burst laeuft nie hinein, dafuer ist die Schlange da.
  void enqueue(PendingSpawn p) {
    if (p == null) {
      return;
    }
    while (pending.size() >= MAX_PENDING) {
      pending.pollFirst();
      dropped++;
    }
    pending.addLast(p);
  }

  // Die Treffer, die bei dieser Wanduhrzeit rausgehen duerfen, in der
  // Reihenfolge ihres Eintreffens. Sie sind danach aus der Schlange heraus.
  //
  // Es koennen mehrere je Aufruf sein: drawMe() laeuft mit 40 Hz, rateHz steht
  // darueber. Genau einer je Frame haette die tatsaechliche Rate bei 40 Hz
  // gedeckelt statt bei rateHz - eine Drossel, die im Normalbetrieb bremst,
  // waere der Fehler, den dieser Fix gerade nicht machen soll.
  //
  // Eine unbrauchbare Zeitbasis (NaN) liefert nichts und verwirft nichts -
  // dieselbe Regel wie SplitStagger.due(): "nichts faellig" ist der einzig
  // richtige Ausfallmodus, ein Verwerfen waere ein stiller Verlust.
  List<PendingSpawn> due(double nowSeconds, float rateHz) {
    if (Double.isNaN(nowSeconds)) {
      return Collections.emptyList();
    }
    float rate = (rateHz > 0f) ? rateHz : NEUTRAL_RATE_HZ; // faengt NaN mit ab
    if (Double.isNaN(lastUpdate) || nowSeconds < lastUpdate) {
      // Erster Aufruf, oder die Wanduhr ist zurueckgesprungen (Zeitumstellung,
      // NTP-Korrektur). Beides ist derselbe Fall: es gibt keine brauchbare
      // verstrichene Zeit. Das Guthaben steht dann auf genau einer Nachricht,
      // damit der erste Treffer eines Abends ohne zusaetzliche Latenz
      // rausgeht - und nicht auf dem vollen Deckel, der ein Schwall waere.
      lastUpdate = nowSeconds;
      credit = 1.0;
    }
    credit += (nowSeconds - lastUpdate)*rate;
    lastUpdate = nowSeconds;
    double cap = rate*MAX_CREDIT_SECONDS;
    if (cap < 1.0) {
      cap = 1.0; // bei sehr kleiner Rate bleibt eine Nachricht je Intervall
    }
    if (credit > cap) {
      credit = cap;
    }
    if (pending.isEmpty()) {
      return Collections.emptyList();
    }
    List<PendingSpawn> out = null;
    while (!pending.isEmpty() && credit >= 1.0 - EPSILON) {
      credit -= 1.0;
      if (out == null) {
        out = new ArrayList<PendingSpawn>();
      }
      out.add(pending.pollFirst());
    }
    return out == null ? Collections.<PendingSpawn>emptyList() : out;
  }

  int pendingCount() {
    return pending.size();
  }

  // Wieviele Treffer seit dem Start am Deckel verworfen wurden. Wird gemeldet,
  // nicht verschluckt: ein stiller Verlust waere hier ein Ton, der ohne
  // Fehlermeldung ausbleibt - siehe die Meldung in
  // LedNetworkTransportEffect.sendHitNodeStream().
  int droppedCount() {
    return dropped;
  }

  void clear() {
    pending.clear();
  }
}
