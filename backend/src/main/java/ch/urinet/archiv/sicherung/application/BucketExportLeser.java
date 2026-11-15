package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.domain.Pruefsumme;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/** Liest die konkatenierbaren Vollexporte und Zuwächse objektweise, ohne Objektbytes zu puffern. */
public final class BucketExportLeser {

    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;
    private static final int HEADER_ANFANG = 0x5552494e;
    private static final int MAX_TEXT = 1024;
    private static final long MAX_GROESSE = 1L << 40;
    private static final int PUFFER_GROESSE = 64 * 1024;

    private final ZaehlerStrom strom;
    private final DataInputStream eingabe;
    private final boolean einzelnesObjekt;
    private boolean anfang = true;
    private boolean beendet;
    private Objekt offen;

    public BucketExportLeser(InputStream eingabe) {
        this(eingabe, false);
    }

    private BucketExportLeser(InputStream eingabe, boolean einzelnesObjekt) {
        strom = new ZaehlerStrom(eingabe);
        this.eingabe = new DataInputStream(strom);
        this.einzelnesObjekt = einzelnesObjekt;
    }

    /** Liest genau einen Frame ab seiner Kennung, etwa nach einem Sprung zur indizierten Dateiposition. */
    public static BucketExportLeser einzelnesObjekt(InputStream eingabe) {
        return new BucketExportLeser(eingabe, true);
    }

    public Optional<Objekt> naechstes() throws IOException {
        if (offen != null) {
            throw new IllegalStateException("Vor dem nächsten Objekt muss der Hash geprüft werden");
        }
        if (beendet) {
            return Optional.empty();
        }
        if (anfang && !einzelnesObjekt) {
            byte[] header = leseGenau(eingabe, SicherungBucketExport.BUCKET_FORMAT.length());
            if (!Arrays.equals(header, SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII))) {
                throw ungueltigesFormat();
            }
        }
        anfang = false;
        Integer kennung = leseKennungOptional();
        if (kennung == null) {
            if (einzelnesObjekt && strom.position() == 0) {
                throw ungueltigesFormat();
            }
            beendet = true;
            return Optional.empty();
        }
        if (!einzelnesObjekt && kennung == HEADER_ANFANG) {
            byte[] rest = leseGenau(eingabe, SicherungBucketExport.BUCKET_FORMAT.length() - Integer.BYTES);
            byte[] erwartet = SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII);
            if (!Arrays.equals(rest, Arrays.copyOfRange(erwartet, Integer.BYTES, erwartet.length))) {
                throw ungueltigesFormat();
            }
            kennung = leseKennungOptional();
            if (kennung == null) {
                beendet = true;
                return Optional.empty();
            }
        }
        if (kennung != OBJEKT) {
            throw ungueltigesFormat();
        }
        long position = strom.position() - Integer.BYTES;
        String schluessel = leseText();
        String versionId = leseText();
        long groesse = leseLong();
        if (groesse < 0 || groesse > MAX_GROESSE) {
            throw ungueltigesFormat();
        }
        Instant lastModified;
        try {
            lastModified = Instant.ofEpochMilli(leseLong());
        } catch (DateTimeException e) {
            throw ungueltigesFormat();
        }
        offen = new Objekt(position, schluessel, versionId, groesse, lastModified);
        return Optional.of(offen);
    }

    private String leseText() throws IOException {
        int laenge = leseInt();
        if (laenge < 1 || laenge > MAX_TEXT) {
            throw ungueltigesFormat();
        }
        byte[] bytes = leseGenau(eingabe, laenge);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw ungueltigesFormat();
        }
    }

    private int leseInt() throws IOException {
        try {
            return eingabe.readInt();
        } catch (IOException e) {
            throw ungueltigesFormat();
        }
    }

    private long leseLong() throws IOException {
        try {
            return eingabe.readLong();
        } catch (IOException e) {
            throw ungueltigesFormat();
        }
    }

    private Integer leseKennungOptional() throws IOException {
        int erste = eingabe.read();
        if (erste == -1) {
            return null;
        }
        try {
            return (erste << 24) | (eingabe.readUnsignedByte() << 16)
                    | (eingabe.readUnsignedByte() << 8) | eingabe.readUnsignedByte();
        } catch (IOException e) {
            throw ungueltigesFormat();
        }
    }

    private static byte[] leseGenau(DataInputStream eingabe, int laenge) throws IOException {
        byte[] bytes;
        try {
            bytes = eingabe.readNBytes(laenge);
        } catch (IOException e) {
            throw ungueltigesFormat();
        }
        if (bytes.length != laenge) {
            throw ungueltigesFormat();
        }
        return bytes;
    }

    private static SicherungExportFehler ungueltigesFormat() {
        return new SicherungExportFehler("Ungültiges Bucket-Exportformat");
    }

    public enum Pruefstatus {
        BESTAETIGT,
        ABWEICHEND
    }

    public record Pruefung(Pruefstatus status, String sha256) {
        public boolean bestaetigt() {
            return status == Pruefstatus.BESTAETIGT;
        }
    }

    public final class Objekt {

        private final long position;
        private final String schluessel;
        private final String versionId;
        private final long groesse;
        private final Instant lastModified;
        private final Digest digest = new Digest();
        private final InputStream inhalt = new Objektstrom();
        private long rest;
        private boolean geprueft;

        private Objekt(long position, String schluessel, String versionId, long groesse, Instant lastModified) {
            this.position = position;
            this.schluessel = schluessel;
            this.versionId = versionId;
            this.groesse = groesse;
            this.lastModified = lastModified;
            rest = groesse;
        }

        public long position() {
            return position;
        }

        public String schluessel() {
            return schluessel;
        }

        public String versionId() {
            return versionId;
        }

        public long groesse() {
            return groesse;
        }

        public Instant lastModified() {
            return lastModified;
        }

        public InputStream inhalt() {
            return inhalt;
        }

        /** Liest den Rest des Inhalts, dann den Trailer; die Rückgabe enthält nie Objektinhalt. */
        public Pruefung pruefsummePruefen() throws IOException {
            byte[] trailer = trailerLesen();
            String gelesen = HexFormat.of().formatHex(digest.wert());
            String erwartet = HexFormat.of().formatHex(trailer);
            return new Pruefung(gelesen.equals(erwartet) ? Pruefstatus.BESTAETIGT : Pruefstatus.ABWEICHEND,
                    gelesen);
        }

        /** Liest für den Indexlauf den Trailer, ohne die Prüfsumme vor dem Wiederherstellen auszuwerten. */
        public String trailerHashLesen() throws IOException {
            return HexFormat.of().formatHex(trailerLesen());
        }

        private byte[] trailerLesen() throws IOException {
            if (geprueft) {
                throw new IllegalStateException("Die Prüfsumme wurde bereits geprüft");
            }
            byte[] puffer = new byte[PUFFER_GROESSE];
            while (inhalt.read(puffer) != -1) {
                // Der begrenzte Strom aktualisiert den Hash.
            }
            if (leseInt() != SHA256) {
                throw ungueltigesFormat();
            }
            byte[] trailer;
            try {
                trailer = eingabe.readNBytes(32);
            } catch (IOException e) {
                throw ungueltigesFormat();
            }
            if (trailer.length != 32) {
                throw ungueltigesFormat();
            }
            geprueft = true;
            offen = null;
            if (einzelnesObjekt) {
                beendet = true;
            }
            return trailer;
        }

        private final class Objektstrom extends InputStream {

            @Override
            public int read() throws IOException {
                if (rest == 0) {
                    return -1;
                }
                int wert = eingabe.read();
                if (wert < 0) {
                    throw ungueltigesFormat();
                }
                digest.aktualisieren((byte) wert);
                rest--;
                return wert;
            }

            @Override
            public int read(byte[] daten, int ab, int laenge) throws IOException {
                if (ab < 0 || laenge < 0 || laenge > daten.length - ab) {
                    throw new IndexOutOfBoundsException();
                }
                if (laenge == 0) {
                    return 0;
                }
                if (rest == 0) {
                    return -1;
                }
                int gelesen = eingabe.read(daten, ab, (int) Math.min(laenge, rest));
                if (gelesen < 0) {
                    throw ungueltigesFormat();
                }
                if (gelesen == 0) {
                    return read();
                }
                digest.aktualisieren(daten, ab, gelesen);
                rest -= gelesen;
                return gelesen;
            }

            @Override
            public void close() {
                // Der Eigentümer des Readers schliesst den übergeordneten Strom.
            }
        }
    }

    private static final class Digest {
        private final java.security.MessageDigest wert = Pruefsumme.sha256();

        void aktualisieren(byte wert) {
            this.wert.update(wert);
        }

        void aktualisieren(byte[] daten, int ab, int laenge) {
            wert.update(daten, ab, laenge);
        }

        byte[] wert() {
            return wert.digest();
        }
    }

    private static final class ZaehlerStrom extends FilterInputStream {
        private long position;

        private ZaehlerStrom(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int wert = super.read();
            if (wert >= 0) {
                position++;
            }
            return wert;
        }

        @Override
        public int read(byte[] daten, int ab, int laenge) throws IOException {
            int gelesen = super.read(daten, ab, laenge);
            if (gelesen > 0) {
                position += gelesen;
            }
            return gelesen;
        }

        long position() {
            return position;
        }
    }
}
