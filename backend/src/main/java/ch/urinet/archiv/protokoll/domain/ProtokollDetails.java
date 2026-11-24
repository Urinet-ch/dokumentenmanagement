package ch.urinet.archiv.protokoll.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Inhalt der Spalte {@code details} (jsonb): nur Grund, SHA-256, Fehlercode, bei {@code REGEL_AENDERN} die alte und
 * neue Regel einer Dokumentart, bei {@code SPERRE_KONFLIKT} die bisherige und die neu berechnete Frist, bei den
 * Aktionen {@code KONTAKT_*} die Kontakt-IDs und Zahlen ({@link Kontakte}), bei {@code KONFIGURATION_AENDERN} Version
 * und Quelle ({@link Konfiguration}) und der Kanal eines Auslösers ohne
 * eigenen Endpunkt ({@code kanal}, z. B. {@value #KANAL_CHAT} für das Chat-Werkzeug {@code dokument_zeigen},
 * und bei Wissensentscheiden Seiten-ID und Versionsnummern ({@link WissensEntscheid}),
 * Spec 02, Aufgabe 7.1a), bei Änderungen an einem API-Token dessen ID ({@code tokenId}, Req 2.20), nie Dokument-,
 * Kontakt- oder Konfigurationsinhalte ausser den geänderten Konfigurationselementen mit alt und neu, bei UID und
 * IBANs ohne Werte (Req 9.7, 9.10,
 * 10.2, 16.7, 17.8; Spec 05, Req 1.3). Jedes Feld darf
 * fehlen ({@code null}); ohne {@code kanal} ist der Eintrag an einem REST-Endpunkt entstanden.
 */
public record ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                               FristKonflikt fristKonflikt, Kontakte kontakte, Konfiguration konfiguration,
                               String kanal, String tokenId, WissensEntscheid wissensEntscheid,
                               Integer wissensImportSeiten, Verwaltung verwaltung) {

    public static final ProtokollDetails KEINE = new ProtokollDetails(null, null, null);
    private static final Pattern TOKEN_ID = Pattern.compile("^[a-z0-9]{12}$");
    /** Chat-Werkzeug (Spec 02, design.md «Vertrag Welle 7», E4). */
    public static final String KANAL_CHAT = "CHAT";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** Fehlercode, Code einer Dokumentart oder Wert eines Enums */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public ProtokollDetails {
        if (verwaltung != null && (grund != null || sha256 != null || fehlercode != null || regelAenderung != null
                || fristKonflikt != null || kontakte != null || konfiguration != null || tokenId != null
                || wissensEntscheid != null || wissensImportSeiten != null)) {
            throw new IllegalArgumentException("Verwaltungsdetails dürfen nicht mit anderen Details vermischt werden");
        }
        if (wissensImportSeiten != null && (wissensImportSeiten < 0 || wissensImportSeiten > 200)) {
            throw new IllegalArgumentException("Ungültige Seitenzahl des Wissensimports");
        }
        if (sha256 != null) {
            sha256 = sha256.toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 ist keine SHA-256-Prüfsumme in Hex");
            }
        }
        if (fehlercode != null && !CODE.matcher(fehlercode).matches()) {
            throw new IllegalArgumentException("fehlercode ist kein Fehlercode");
        }
        if (grund != null && grund.isBlank()) {
            grund = null;
        }
        if (kanal != null && !CODE.matcher(kanal).matches()) {
            throw new IllegalArgumentException("kanal ist kein Code");
        }
        if (tokenId != null && !TOKEN_ID.matcher(tokenId).matches()) {
            throw new IllegalArgumentException("tokenId ist keine Token-ID");
        }
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                            FristKonflikt fristKonflikt, Kontakte kontakte, Konfiguration konfiguration,
                            String kanal, String tokenId, WissensEntscheid wissensEntscheid,
                            Integer wissensImportSeiten) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, konfiguration, kanal, tokenId,
                wissensEntscheid, wissensImportSeiten, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                           FristKonflikt fristKonflikt, Kontakte kontakte, Konfiguration konfiguration,
                           String kanal, String tokenId, WissensEntscheid wissensEntscheid) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, konfiguration, kanal, tokenId,
                wissensEntscheid, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                            FristKonflikt fristKonflikt, Kontakte kontakte, Konfiguration konfiguration,
                            String kanal, String tokenId) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, konfiguration, kanal, tokenId, null);
    }

    /** Kompatibel zur Form vor {@code tokenId}. */
    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                            FristKonflikt fristKonflikt, Kontakte kontakte, String kanal) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, null, kanal, null);
    }

    /** Kompatibel zu Einträgen ohne Token-ID. */
    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                            FristKonflikt fristKonflikt, Kontakte kontakte, Konfiguration konfiguration,
                            String kanal) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, konfiguration, kanal, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode) {
        this(grund, sha256, fehlercode, null, null, null, null, null, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung) {
        this(grund, sha256, fehlercode, regelAenderung, null, null, null, null, null);
    }

    public ProtokollDetails(String grund, String sha256, String fehlercode, RegelAenderung regelAenderung,
                            FristKonflikt fristKonflikt) {
        this(grund, sha256, fehlercode, regelAenderung, fristKonflikt, null, null, null, null);
    }

    public static ProtokollDetails fehlercode(String fehlercode) {
        return new ProtokollDetails(null, null, fehlercode);
    }

    public static ProtokollDetails regelAenderung(RegelAenderung aenderung) {
        return new ProtokollDetails(null, null, null, aenderung);
    }

    public static ProtokollDetails fristKonflikt(FristKonflikt konflikt) {
        return new ProtokollDetails(null, null, null, null, konflikt);
    }

    /** Details eines Eintrags {@code KONTAKT_*} mit Grund (Req 17.8, 17.11, 17.15). */
    public static ProtokollDetails kontakte(String grund, Kontakte kontakte) {
        return new ProtokollDetails(grund, null, null, null, null, kontakte, null, null, null);
    }

    public static ProtokollDetails konfiguration(String grund, Konfiguration konfiguration) {
        return new ProtokollDetails(grund, null, null, null, null, null, konfiguration, null, null);
    }

    public static ProtokollDetails wissensEntscheid(String grund, WissensEntscheid entscheid) {
        return new ProtokollDetails(grund, null, null, null, null, null, null, null, null, entscheid);
    }

    public static ProtokollDetails wissensImport(String sha256, int seiten) {
        return new ProtokollDetails(null, sha256, null, null, null, null, null, null, null, null, seiten);
    }

    public static ProtokollDetails verwaltung(Verwaltung verwaltung) {
        return new ProtokollDetails(null, null, null, null, null, null, null, null, null, null, null,
                Objects.requireNonNull(verwaltung));
    }

    public boolean leer() {
        return grund == null && sha256 == null && fehlercode == null && regelAenderung == null
                && fristKonflikt == null && kontakte == null && konfiguration == null && kanal == null
                && tokenId == null && wissensEntscheid == null && wissensImportSeiten == null && verwaltung == null;
    }

    /** Dieselben Details mit dem Kanal {@code kanal}. */
    public ProtokollDetails mitKanal(String kanal) {
        return new ProtokollDetails(grund, sha256, fehlercode, regelAenderung, fristKonflikt, kontakte, konfiguration,
                kanal, tokenId, wissensEntscheid, wissensImportSeiten, verwaltung);
    }

    /** Details eines Eintrags zu einem API-Token: dessen ID (kein Geheimnis). */
    public static ProtokollDetails token(String tokenId) {
        return new ProtokollDetails(null, null, null, null, null, null, null, null, tokenId);
    }

    /**
     * Aufbewahrungsregel einer Dokumentart: Jahre und «ab» (beide {@code null} heisst unbefristet) sowie die
     * gesetzliche Pflicht, die über die Sperre entscheidet. Nur Werte der Regel, keine Werte eines Dokuments.
     */
    public record Regel(Integer aufbewahrungJahre, String aufbewahrungAb, boolean gesetzlichePflicht,
                        String aufbewahrungFeld) {

        public Regel(Integer aufbewahrungJahre, String aufbewahrungAb, boolean gesetzlichePflicht) {
            this(aufbewahrungJahre, aufbewahrungAb, gesetzlichePflicht, null);
        }

        public Regel {
            if (aufbewahrungAb != null && !CODE.matcher(aufbewahrungAb).matches()) {
                throw new IllegalArgumentException("aufbewahrungAb ist kein Code");
            }
            if (aufbewahrungFeld != null && !aufbewahrungFeld.matches("[a-z][a-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("aufbewahrungFeld ist kein Feldname");
            }
        }
    }

    /** Alte und neue Regel der Dokumentart mit dem Code {@code dokumentart}. */
    public record RegelAenderung(String dokumentart, Regel alt, Regel neu) {

        public RegelAenderung {
            if (dokumentart == null || !CODE.matcher(dokumentart).matches()) {
                throw new IllegalArgumentException("dokumentart ist kein Code");
            }
            if (alt == null || neu == null) {
                throw new IllegalArgumentException("alte und neue Regel sind Pflicht");
            }
        }
    }

    /**
     * Konflikt bei der Neuberechnung der Aufbewahrungsfrist (Req 9.7): die bisherige Frist, die bleibt, und die neu
     * berechnete, die nicht übernommen wurde; {@code null} heisst unbefristet.
     */
    public record FristKonflikt(LocalDate bisher, LocalDate neu) {
    }

    /**
     * Kontakt-IDs und Zahlen der Aktionen {@code KONTAKT_*}: beim Zusammenführen {@code quelle} und {@code ziel} (im
     * Eintrag ohne Dokument auch die Zahlen der umgehängten Verweise), beim Markieren {@code kontakt}. Nur IDs und
     * Zahlen, keine Kontaktdaten.
     */
    public record Kontakte(UUID quelle, UUID ziel, UUID kontakt, Integer dokumente, Integer verkaufschancen,
                           Integer korrekturbeispiele) {

        public static Kontakte zusammenfuehren(UUID quelle, UUID ziel) {
            return new Kontakte(quelle, ziel, null, null, null, null);
        }

        public static Kontakte markieren(UUID kontakt) {
            return new Kontakte(null, null, kontakt, null, null, null);
        }
    }

    public record WissensEntscheid(int aenderungNr, UUID seiteId, Integer basisVersion, Integer neueVersion) {
    }

    public enum Verwaltungsphase {
        ANGEFRAGT, ABGESCHLOSSEN, FEHLGESCHLAGEN, UNKLAR
    }

    public enum Verwaltungsvorgang {
        ANLEGEN, SPERREN, ENTSPERREN, DEAKTIVIEREN, REAKTIVIEREN, ZUGANG_ZURUECKSETZEN,
        EINLADUNG_ERNEUT, EMAIL_AENDERN, NAME_AENDERN, GOOGLE_LOESEN, LOESCHEN, ROLLE_ZUWEISEN, ANTRAG, ABWEICHUNG
    }

    public enum Verwaltungsrolle {
        INHABER, MITARBEITER, TREUHAENDER, GESCHAEFTSSTELLE
    }

    public enum Verwaltungsfeld {
        EMAIL, VORNAME, NACHNAME
    }

    public enum Verwaltungsgrund {
        SICHERHEIT, AUSTRITT, RECHTEAENDERUNG, KORREKTUR, SONSTIGES
    }

    public enum Verwaltungsabweichung {
        ROLLE_OHNE_VORGANG, MEHRERE_ROLLEN, KEIN_MANDANT, MEHRERE_MANDANTEN, FREMDE_ROLLE, BENUTZER_FEHLT,
        VORGANG_OFFEN
    }

    public enum Verwaltungsanlass {
        UEBERNAHME
    }

    public record Verwaltung(UUID vorgangId, Verwaltungsphase phase, UUID ziel, Verwaltungsvorgang vorgang,
                             Verwaltungsrolle rolleAlt, Verwaltungsrolle rolleNeu, List<Verwaltungsfeld> felder,
                             Verwaltungsgrund grund, Verwaltungsabweichung abweichung, Verwaltungsanlass anlass) {
        public Verwaltung {
            Objects.requireNonNull(vorgangId);
            Objects.requireNonNull(phase);
            felder = felder == null ? List.of() : List.copyOf(felder);
            if (abweichung != null && vorgang != Verwaltungsvorgang.ABWEICHUNG) {
                throw new IllegalArgumentException("Abweichungscode nur beim Abgleich");
            }
            if (anlass != null && vorgang != Verwaltungsvorgang.ROLLE_ZUWEISEN) {
                throw new IllegalArgumentException("Übernahmeanlass nur beim Rollenvorgang");
            }
        }

        public Verwaltung(UUID vorgangId, Verwaltungsphase phase, UUID ziel, Verwaltungsvorgang vorgang,
                          Verwaltungsrolle rolleAlt, Verwaltungsrolle rolleNeu, List<Verwaltungsfeld> felder,
                          Verwaltungsgrund grund, Verwaltungsabweichung abweichung) {
            this(vorgangId, phase, ziel, vorgang, rolleAlt, rolleNeu, felder, grund, abweichung, null);
        }

        public Verwaltung(UUID vorgangId, Verwaltungsphase phase, UUID ziel, Verwaltungsvorgang vorgang,
                          Verwaltungsrolle rolleAlt, Verwaltungsrolle rolleNeu, List<Verwaltungsfeld> felder,
                          Verwaltungsgrund grund) {
            this(vorgangId, phase, ziel, vorgang, rolleAlt, rolleNeu, felder, grund, null, null);
        }

        public Verwaltung(UUID vorgangId, Verwaltungsphase phase, UUID ziel, Verwaltungsvorgang vorgang,
                          Verwaltungsrolle rolleAlt, Verwaltungsrolle rolleNeu, List<Verwaltungsfeld> felder) {
            this(vorgangId, phase, ziel, vorgang, rolleAlt, rolleNeu, felder, null, null, null);
        }

        public Verwaltung(UUID vorgangId, Verwaltungsphase phase) {
            this(vorgangId, phase, null, null, null, null, List.of(), null, null, null);
        }
    }

    /** Version, Quelle und geänderte Elemente der Fachkonfiguration mit alten und neuen Werten (Spec 05, Req 1.3). */
    public record Konfiguration(int version, String quelle, List<Aenderung> aenderungen) {
        public Konfiguration {
            if (version < 1 || quelle == null || !CODE.matcher(quelle).matches()) {
                throw new IllegalArgumentException("Konfigurationsversion oder Quelle ungültig");
            }
            aenderungen = aenderungen == null ? List.of() : List.copyOf(aenderungen);
        }

        public Konfiguration(int version, String quelle) {
            this(version, quelle, List.of());
        }
    }

    /**
     * Ein geändertes Konfigurationselement: Pfad, alter und neuer Wert als Text ({@code null} = nicht vorhanden).
     * Mit {@code ohneWerte} (Kennungen, Spec 05, Req 1.3) entfallen alter und neuer Wert schon hier, vor jeder
     * Serialisierung; welches Element so gilt, bestimmt der Aufrufer, nicht dieser Record.
     */
    public record Aenderung(String pfad, String alt, String neu, boolean ohneWerte) {
        public Aenderung {
            if (pfad == null || pfad.isBlank()) {
                throw new IllegalArgumentException("pfad ist Pflicht");
            }
            if (ohneWerte) {
                alt = null;
                neu = null;
            }
        }

        public Aenderung(String pfad, String alt, String neu) {
            this(pfad, alt, neu, false);
        }
    }
}
