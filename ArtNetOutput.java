// Sendet die LED-Farben als Art-Net an die Pixel2LED-Controller.
//
// Die Firmware adressiert jede LED mit vier Bytes, ein Universum traegt also
// 128 LEDs. Jeder Output beginnt bei DMX-Adresse 1 eines neuen Universums;
// bei 600 LEDs je Output sind das fuenf Universen, von denen das letzte nur
// 88 echte LEDs traegt. Die uebrigen 40 Slots muessen genullt bleiben, sonst
// schreibt die Firmware sie in die ersten LEDs des naechsten Outputs.
//
// Diese Klasse haengt bewusst nur an LedColor und der Java-Standardbibliothek,
// damit der Paketbau ohne Processing-Laufzeit geprueft werden kann.
class ArtNetOutput {

  static final int ARTNET_PORT = 6454;
  static final int CHANNELS_PER_LED = 4;
  static final int LEDS_PER_UNIVERSE = 512 / CHANNELS_PER_LED;   // 128
  static final int OUTPUTS_PER_CONTROLLER = 2;
  static final int DMX_HEADER_LEN = 18;
  static final int DMX_PACKET_LEN = DMX_HEADER_LEN + 512;        // 530
  static final int SYNC_PACKET_LEN = 14;

  // Kanalreihenfolge im DMX-Paket. Das vierte Byte je LED bleibt immer 0.
  // Kommen die Farben vertauscht an, hier drehen: R=2, G=1, B=0.
  static final int R_OFFSET = 0;
  static final int G_OFFSET = 1;
  static final int B_OFFSET = 2;

  final int[] octets;
  final int numLedsPerStripe;
  final int universesPerOutput;

  private float masterLevel = 0.1f;   // Sicherheitsventil, siehe setMasterLevel
  private int sequence = 1;

  ArtNetOutput(int[] octets, int numLedsPerStripe) {
    this.octets = octets;
    this.numLedsPerStripe = numLedsPerStripe;
    int channels = numLedsPerStripe * CHANNELS_PER_LED;
    this.universesPerOutput = (channels + 511) / 512;   // aufgerundet
  }

  int numStripes() {
    return octets.length * OUTPUTS_PER_CONTROLLER;
  }

  int universesPerOutput() {
    return universesPerOutput;
  }

  int packetsPerFrame() {
    // je Controller alle Universen plus ein Sync-Paket
    return octets.length * (OUTPUTS_PER_CONTROLLER * universesPerOutput + 1);
  }

  int portAddress(int controllerIndex, int output, int universeInOutput) {
    return octets[controllerIndex] * 100 + output * universesPerOutput + universeInOutput;
  }

  String targetIp(int controllerIndex) {
    return "2.2.2." + octets[controllerIndex];
  }

  // Zuordnungstabelle fuer die Konsole. Gegen das Web-Interface der Controller
  // pruefbar, bevor irgendetwas angeschlossen ist.
  String describeMapping() {
    StringBuilder sb = new StringBuilder();
    sb.append("Art-Net Zuordnung: ").append(octets.length).append(" Controller, ")
      .append(numStripes()).append(" Stripes, ")
      .append(numStripes() * numLedsPerStripe).append(" LEDs, ")
      .append(packetsPerFrame()).append(" Pakete je Frame\n");
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        int stripe = k * OUTPUTS_PER_CONTROLLER + j;
        int firstLed = stripe * numLedsPerStripe;
        sb.append(String.format(
            "  %-9s Output %d  Universen %d-%d  Stripe %-3d LED %d-%d%n",
            targetIp(k), j,
            portAddress(k, j, 0), portAddress(k, j, universesPerOutput - 1),
            stripe, firstLed, firstLed + numLedsPerStripe - 1));
      }
    }
    return sb.toString();
  }

  // Ein fertig gebauter Frame. Ziel, Bytes und Nutzlaenge je Paket.
  static class Frame {
    final String[] targets;
    final byte[][] data;
    final int[] lengths;

    Frame(String[] targets, byte[][] data, int[] lengths) {
      this.targets = targets;
      this.data = data;
      this.lengths = lengths;
    }
  }

  // Legt die Puffer an und traegt Ziele und Laengen ein. Die aendern sich
  // waehrend des Betriebs nicht, nur die Nutzdaten.
  Frame newFrame() {
    int n = packetsPerFrame();
    String[] targets = new String[n];
    byte[][] data = new byte[n][];
    int[] lengths = new int[n];
    int p = 0;
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        for (int u = 0; u < universesPerOutput; u++) {
          targets[p] = targetIp(k);
          data[p] = new byte[DMX_PACKET_LEN];
          lengths[p] = DMX_PACKET_LEN;
          p++;
        }
      }
      targets[p] = targetIp(k);
      data[p] = new byte[SYNC_PACKET_LEN];
      lengths[p] = SYNC_PACKET_LEN;
      writeSyncHeader(data[p]);
      p++;
    }
    return new Frame(targets, data, lengths);
  }

  // Der Pegel begrenzt die Ausgabe hinter allem - Show, Testbilder,
  // Kalibrierung. Die Stripes ziehen bei voller Helligkeit mehr Strom, als
  // die Einspeisung hergibt.
  void setMasterLevel(float level) {
    if (level < 0f) level = 0f;
    if (level > 1f) level = 1f;
    masterLevel = level;
  }

  float getMasterLevel() {
    return masterLevel;
  }

  void buildFrame(LedColor[] ledColors, Frame frame) {
    int p = 0;
    for (int k = 0; k < octets.length; k++) {
      for (int j = 0; j < OUTPUTS_PER_CONTROLLER; j++) {
        int stripeBase = (k * OUTPUTS_PER_CONTROLLER + j) * numLedsPerStripe;
        for (int u = 0; u < universesPerOutput; u++) {
          byte[] buf = frame.data[p];
          writeDmxHeader(buf, sequence, portAddress(k, j, u));
          int firstInStripe = u * LEDS_PER_UNIVERSE;
          for (int i = 0; i < LEDS_PER_UNIVERSE; i++) {
            int inStripe = firstInStripe + i;
            int o = DMX_HEADER_LEN + i * CHANNELS_PER_LED;
            if (inStripe < numLedsPerStripe) {
              LedColor c = ledColors[stripeBase + inStripe];
              buf[o + R_OFFSET] = level(c.x);
              buf[o + G_OFFSET] = level(c.y);
              buf[o + B_OFFSET] = level(c.z);
            } else {
              buf[o + R_OFFSET] = 0;
              buf[o + G_OFFSET] = 0;
              buf[o + B_OFFSET] = 0;
            }
            buf[o + 3] = 0;
          }
          p++;
        }
      }
      p++;   // Sync-Paket, der Kopf steht schon aus newFrame()
    }
    sequence = (sequence % 255) + 1;
  }

  private byte level(float value) {
    int b = Math.round(value * masterLevel * 255f);
    if (b < 0) b = 0;
    if (b > 255) b = 255;
    return (byte) b;
  }

  private static void writeArtNetId(byte[] p) {
    p[0] = 'A'; p[1] = 'r'; p[2] = 't'; p[3] = '-';
    p[4] = 'N'; p[5] = 'e'; p[6] = 't'; p[7] = 0;
  }

  // Alle Pakete eines Frames tragen dieselbe Sequenznummer. Die Firmware
  // wertet sie nicht aus.
  private static void writeDmxHeader(byte[] p, int sequence, int portAddress) {
    writeArtNetId(p);
    p[8] = 0x00; p[9] = 0x50;            // OpDmx 0x5000, little-endian
    p[10] = 0; p[11] = 14;               // ProtVer
    p[12] = (byte) sequence;
    p[13] = 0;                           // Physical
    p[14] = (byte) (portAddress & 0xFF);          // SubUni
    p[15] = (byte) ((portAddress >> 8) & 0x7F);   // Net
    p[16] = 0x02; p[17] = 0x00;          // Laenge 512, big-endian
  }

  private static void writeSyncHeader(byte[] p) {
    writeArtNetId(p);
    p[8] = 0x00; p[9] = 0x52;            // OpSync 0x5200, little-endian
    p[10] = 0; p[11] = 14;
    p[12] = 0; p[13] = 0;                // Aux1, Aux2
  }

  // ---- Versand ----
  //
  // Drei Puffer: einer wird gebaut, einer wartet, einer wird gesendet. Die
  // Referenzen werden nur unter der Sperre getauscht, ausserhalb beruehrt
  // draw() nur buildBuf und der Sender nur sendBuf. Damit kein Frame beim
  // Senden ueberschrieben wird.
  private final Object lock = new Object();
  private Frame buildBuf, readyBuf, sendBuf;
  private boolean hasNew = false;

  private volatile java.net.DatagramSocket socket;
  private Thread sender;
  private volatile boolean running = false;
  private volatile boolean syncBroadcast = false;
  // Verhindert, dass der Sender vor dem ersten publish() genullte
  // DMX-Puffer ohne Art-Net-Kennung auf die Leitung schickt.
  private volatile boolean hasPublishedOnce = false;

  private static final long PERIOD_NANOS = 25_000_000L;   // 40 fps

  // Taktmessung fuer Test 1c
  private final Object statsLock = new Object();
  private long lastSendNanos = 0;
  private long intervalCount = 0;
  private double intervalSum = 0, intervalSumSq = 0;
  private long intervalMin = Long.MAX_VALUE, intervalMax = 0;

  void start() {
    if (running) return;
    buildBuf = newFrame();
    readyBuf = newFrame();
    sendBuf = newFrame();
    try {
      socket = new java.net.DatagramSocket();
      // Einmalig erlauben, nicht bei jedem Sync-Paket erneut als Systemaufruf.
      socket.setBroadcast(true);
    } catch (java.net.SocketException e) {
      throw new RuntimeException("Art-Net-Socket liess sich nicht oeffnen", e);
    }
    hasPublishedOnce = false;
    running = true;
    sender = new Thread(new Runnable() {
      public void run() { runSendLoop(); }
    }, "artnet-sender");
    sender.setDaemon(true);
    sender.start();
  }

  void stop() {
    running = false;
    if (sender != null) {
      try { sender.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      sender = null;
    }
    if (socket != null) { socket.close(); }
  }

  // Sync einzeln an jeden Controller oder als Broadcast. Welches sauberer
  // laeuft, entscheidet die Messung von uUniPF aus Test 1d.
  void setSyncBroadcast(boolean on) {
    syncBroadcast = on;
  }

  // Aus draw() aufgerufen. Baut ausserhalb der Sperre und tauscht nur die
  // Referenz darin.
  void publish(LedColor[] ledColors) {
    if (!running) return;
    buildFrame(ledColors, buildBuf);
    synchronized (lock) {
      Frame t = buildBuf; buildBuf = readyBuf; readyBuf = t;
      hasNew = true;
    }
    hasPublishedOnce = true;
  }

  private Frame takeFrame() {
    synchronized (lock) {
      if (hasNew) {
        Frame t = sendBuf; sendBuf = readyBuf; readyBuf = t;
        hasNew = false;
      }
      return sendBuf;
    }
  }

  private void runSendLoop() {
    // Absolute Zeitpunkte statt sleep(25), damit sich kein Versatz aufsummiert.
    long deadline = System.nanoTime();
    while (running) {
      deadline += PERIOD_NANOS;
      if (hasPublishedOnce) {
        try {
          sendFrame(takeFrame());
        } catch (Exception e) {
          System.err.println("Art-Net-Versand fehlgeschlagen: " + e);
        }
      }
      long wait = deadline - System.nanoTime();
      if (wait > 0) {
        try {
          Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      } else {
        // zu spaet - Takt neu aufsetzen statt hinterherzuhetzen
        deadline = System.nanoTime();
      }
    }
  }

  private void sendFrame(Frame frame) throws java.io.IOException {
    long now = System.nanoTime();
    synchronized (statsLock) {
      if (lastSendNanos != 0) {
        long d = now - lastSendNanos;
        intervalCount++;
        intervalSum += d;
        intervalSumSq += (double) d * (double) d;
        if (d < intervalMin) intervalMin = d;
        if (d > intervalMax) intervalMax = d;
      }
      lastSendNanos = now;
    }

    for (int p = 0; p < frame.data.length; p++) {
      boolean isSync = frame.lengths[p] == SYNC_PACKET_LEN;
      String host = (isSync && syncBroadcast) ? "2.255.255.255" : frame.targets[p];
      java.net.InetAddress addr = java.net.InetAddress.getByName(host);
      socket.send(new java.net.DatagramPacket(frame.data[p], frame.lengths[p], addr, ARTNET_PORT));
    }
  }

  // {Anzahl, Mittelwert, Minimum, Maximum, Standardabweichung} in Nanosekunden
  long[] intervalStatsNanos() {
    synchronized (statsLock) {
      if (intervalCount == 0) return new long[] { 0, 0, 0, 0, 0 };
      double mean = intervalSum / intervalCount;
      double variance = intervalSumSq / intervalCount - mean * mean;
      if (variance < 0) variance = 0;
      return new long[] { intervalCount, (long) mean, intervalMin, intervalMax, (long) Math.sqrt(variance) };
    }
  }

  void resetIntervalStats() {
    synchronized (statsLock) {
      lastSendNanos = 0; intervalCount = 0; intervalSum = 0; intervalSumSq = 0;
      intervalMin = Long.MAX_VALUE; intervalMax = 0;
    }
  }
}
