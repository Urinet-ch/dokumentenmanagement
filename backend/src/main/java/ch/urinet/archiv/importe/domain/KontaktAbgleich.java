package ch.urinet.archiv.importe.domain;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Abgleich einer Kontaktzeile gegen die bestehenden Kontakte (Aufgabe 4.2; Req 13.4, 13.5; design.md «Kontakt und
 * Importe»). Stufen in dieser Reihenfolge: {@code crm_id} → E-Mail (ohne Gross-/Kleinschreibung) → normalisierter Name
 * + PLZ, je exakt. Geprüft werden alle Stufen mit gefülltem Schlüssel:
 * <ul>
 *   <li>genau ein Kontakt über alle Stufen → {@code GEAENDERT}, wenn ein gefülltes Feld der Zeile vom gespeicherten
 *   Wert abweicht, sonst {@code UNVERAENDERT};</li>
 *   <li>mehrere Treffer auf einer Stufe oder Stufen, die auf verschiedene Kontakte zeigen → {@code DUBLETTE}
 *   (Req 13.5);</li>
 *   <li>Treffer über E-Mail oder Name + PLZ, aber mit einer anderen, gefüllten {@code crm_id} → {@code DUBLETTE}
 *   (vorsichtigere Auslegung: die Zeile gehört zu einem anderen CRM-Eintrag);</li>
 *   <li>kein exakter Treffer, aber Namensähnlichkeit ab der Schwelle → {@code DUBLETTE}; sonst {@code NEU}.</li>
 * </ul>
 * Rein: Die Treffer liefert der Aufrufer. Meldungen nennen nie Werte.
 */
public final class KontaktAbgleich {

    /** Höchstens so viele ähnliche Kontakte je Zeile. */
    public static final int KANDIDATEN = 5;

    static final String MEHRERE = "mehrere Kontakte mit %s";
    static final String WIDERSPRUCH = "Kontakt-ID, E-Mail bzw. Name und PLZ zeigen auf verschiedene Kontakte";
    static final String ANDERE_CRM_ID = "Treffer über %s mit anderer Kontakt-ID aus 21.CRM";
    static final String AEHNLICH = "ähnlicher Name";
    /** Meldung für Zeilen, die in der Datei schon vorkommen; {@code {}} steht für die Nummer der früheren Zeile. */
    public static final String IN_DER_DATEI = "doppelt in der Datei (Zeile {})";

    /** Vergleichbare Felder in der Reihenfolge von {@code kontakt}, je normalisiert wie gespeichert. */
    private static final Map<String, UnaryOperator<String>> FELDER = felder();

    private KontaktAbgleich() {
    }

    private static Map<String, UnaryOperator<String>> felder() {
        Map<String, UnaryOperator<String>> felder = new LinkedHashMap<>();
        felder.put(ImportFeld.CRM_ID, String::strip);
        felder.put(ImportFeld.TYP, String::strip);
        felder.put(ImportFeld.NAME, String::strip);
        felder.put(ImportFeld.VORNAME, String::strip);
        felder.put(ImportFeld.STRASSE, String::strip);
        felder.put(ImportFeld.PLZ, Normalisierer::plz);
        felder.put(ImportFeld.ORT, String::strip);
        felder.put(ImportFeld.EMAIL, Normalisierer::email);
        felder.put(ImportFeld.TELEFON, String::strip);
        felder.put("uid", Normalisierer::uid);
        felder.put("iban", Normalisierer::iban);
        felder.put("kategorie", String::strip);
        return Collections.unmodifiableMap(felder);
    }

    /**
     * Feldwerte einer Zeile (aus {@link Zuordnung#werte}), normalisiert wie {@code kontakt} sie speichert (E-Mail klein,
     * PLZ nur Ziffern, UID und IBAN ohne Trenner); leere Werte fehlen.
     */
    public static Map<String, String> vergleichswerte(Map<String, String> werte) {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        FELDER.forEach((feld, normalisieren) -> {
            String wert = werte.get(feld);
            if (wert != null) {
                String normalisiert = normalisieren.apply(wert);
                if (!normalisiert.isEmpty()) {
                    ergebnis.put(feld, normalisiert);
                }
            }
        });
        return ergebnis;
    }

    /**
     * Schlüssel der drei Stufen aus den {@link #vergleichswerte}. Der Name wird bei Personen aus Vorname und Name
     * normalisiert, bei Organisationen aus dem Namen; ohne Typ (Spalte nicht zugeordnet) aus Vorname und Name, wenn ein
     * Vorname da ist, sonst aus dem Namen.
     */
    public static Schluessel schluessel(Map<String, String> vergleichswerte) {
        String typ = vergleichswerte.get(ImportFeld.TYP);
        String vorname = vergleichswerte.get(ImportFeld.VORNAME);
        String name = vergleichswerte.get(ImportFeld.NAME);
        boolean person = "PERSON".equals(typ) || (typ == null && vorname != null);
        String normalisiert = person ? Normalisierer.personName(vorname, name) : Normalisierer.name(name);
        return new Schluessel(vergleichswerte.getOrDefault(ImportFeld.CRM_ID, ""),
                vergleichswerte.getOrDefault(ImportFeld.EMAIL, ""), normalisiert,
                vergleichswerte.getOrDefault(ImportFeld.PLZ, ""));
    }

    /**
     * Befund einer Zeile mit ihren {@link #vergleichswerte}. {@code bestaende} enthält mindestens alle Kontakte, die
     * eine Stufe der Zeile exakt trifft; {@code aehnliche} die Kontakte mit Namensähnlichkeit ab der Schwelle, die
     * besten zuerst (nur gebraucht, wenn keine Stufe trifft).
     */
    public static Befund pruefen(Map<String, String> vergleichswerte, Bestaende bestaende, List<UUID> aehnliche) {
        Schluessel schluessel = schluessel(vergleichswerte);
        List<Bestand> ueberCrmId = bestaende.ueberCrmId(schluessel);
        List<Bestand> ueberEmail = bestaende.ueberEmail(schluessel);
        List<Bestand> ueberNamePlz = bestaende.ueberNamePlz(schluessel);
        Set<UUID> alle = new LinkedHashSet<>();
        List<String> mehrfach = new ArrayList<>();
        stufe(ueberCrmId, "gleicher Kontakt-ID", alle, mehrfach);
        stufe(ueberEmail, "gleicher E-Mail", alle, mehrfach);
        stufe(ueberNamePlz, "gleichem Namen und gleicher PLZ", alle, mehrfach);
        if (!mehrfach.isEmpty()) {
            return Befund.dublette(String.format(MEHRERE, mehrfach.getFirst()), List.copyOf(alle));
        }
        if (alle.size() > 1) {
            return Befund.dublette(WIDERSPRUCH, List.copyOf(alle));
        }
        if (alle.isEmpty()) {
            return aehnliche.isEmpty() ? Befund.NEU
                    : Befund.dublette(AEHNLICH, aehnliche.stream().limit(KANDIDATEN).toList());
        }
        Bestand kontakt = ueberCrmId.isEmpty() ? (ueberEmail.isEmpty() ? ueberNamePlz : ueberEmail).getFirst()
                : ueberCrmId.getFirst();
        String crmId = vergleichswerte.get(ImportFeld.CRM_ID);
        if (ueberCrmId.isEmpty() && crmId != null && kontakt.crmId() != null && !kontakt.crmId().equals(crmId)) {
            return Befund.dublette(String.format(ANDERE_CRM_ID, ueberEmail.isEmpty() ? "Name und PLZ" : "E-Mail"),
                    List.of(kontakt.id()));
        }
        boolean geaendert = vergleichswerte.entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(kontakt.felder().get(e.getKey())));
        return new Befund(geaendert ? Ergebnis.GEAENDERT : Ergebnis.UNVERAENDERT, null, kontakt.id(), List.of());
    }

    /**
     * 64-Bit-Fingerabdrücke (SHA-256, die ersten 8 Byte) der gefüllten Schlüssel und des getroffenen Kontakts, je mit
     * der Stufe als Präfix. Zwei Zeilen derselben Datei mit einem gemeinsamen Fingerabdruck sind doppelt (die spätere
     * wird {@code DUBLETTE}); so muss der Abgleich die Schlüssel nicht im Heap sammeln.
     */
    public static long[] fingerabdruecke(Schluessel schluessel, UUID kontaktId) {
        List<String> teile = new ArrayList<>(4);
        if (!schluessel.crmId().isEmpty()) {
            teile.add("crm_id\u0000" + schluessel.crmId());
        }
        if (!schluessel.email().isEmpty()) {
            teile.add("email\u0000" + schluessel.email());
        }
        if (schluessel.namePlz()) {
            teile.add("name_plz\u0000" + schluessel.name() + "\u0000" + schluessel.plz());
        }
        if (kontaktId != null) {
            teile.add("kontakt\u0000" + kontaktId);
        }
        return teile.stream().mapToLong(KontaktAbgleich::fingerabdruck).toArray();
    }

    private static long fingerabdruck(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    private static void stufe(List<Bestand> treffer, String stufe, Set<UUID> alle, List<String> mehrfach) {
        if (treffer.size() > 1) {
            mehrfach.add(stufe);
        }
        treffer.forEach(b -> alle.add(b.id()));
    }

    /** Normalisierte Schlüssel einer Zeile; leer, wenn das Feld fehlt. */
    public record Schluessel(String crmId, String email, String name, String plz) {

        public Schluessel {
            Objects.requireNonNull(crmId, "crmId");
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(plz, "plz");
        }

        /** Die Stufe Name + PLZ braucht beide Werte (vorsichtigere Auslegung: ohne PLZ kein exakter Treffer). */
        public boolean namePlz() {
            return !name.isEmpty() && !plz.isEmpty();
        }
    }

    /**
     * Ein bestehender Kontakt: {@code felder} wie in {@code kontakt} gespeichert (Schlüssel wie {@link ImportFeld},
     * {@code typ} als {@code ORGANISATION}/{@code PERSON}); sie werden wie die Zeile mit {@link #vergleichswerte}
     * normalisiert, leere fallen weg. {@code nameNormalisiert} wie {@code kontakt.name_normalisiert}.
     */
    public record Bestand(UUID id, String nameNormalisiert, Map<String, String> felder) {

        public Bestand {
            Objects.requireNonNull(id, "id");
            nameNormalisiert = nameNormalisiert == null ? "" : nameNormalisiert;
            felder = Map.copyOf(vergleichswerte(felder));
        }

        public String crmId() {
            return felder.get(ImportFeld.CRM_ID);
        }
    }

    /** Die Kontakte, die die Zeilen eines Blocks über eine Stufe treffen, nach Schlüssel geordnet. */
    public static final class Bestaende {

        private final Map<String, List<Bestand>> nachCrmId = new HashMap<>();
        private final Map<String, List<Bestand>> nachEmail = new HashMap<>();
        private final Map<String, List<Bestand>> nachNamePlz = new HashMap<>();

        public Bestaende(List<Bestand> kontakte) {
            for (Bestand k : kontakte) {
                einordnen(nachCrmId, k.crmId(), k);
                einordnen(nachEmail, k.felder().get(ImportFeld.EMAIL), k);
                String plz = k.felder().get(ImportFeld.PLZ);
                if (!k.nameNormalisiert().isEmpty() && plz != null) {
                    einordnen(nachNamePlz, k.nameNormalisiert() + "\u0000" + plz, k);
                }
            }
        }

        private static void einordnen(Map<String, List<Bestand>> index, String schluessel, Bestand kontakt) {
            if (schluessel != null && !schluessel.isEmpty()) {
                List<Bestand> liste = index.computeIfAbsent(schluessel, s -> new ArrayList<>(1));
                if (liste.stream().noneMatch(b -> b.id().equals(kontakt.id()))) {
                    liste.add(kontakt);
                }
            }
        }

        List<Bestand> ueberCrmId(Schluessel s) {
            return s.crmId().isEmpty() ? List.of() : nachCrmId.getOrDefault(s.crmId(), List.of());
        }

        List<Bestand> ueberEmail(Schluessel s) {
            return s.email().isEmpty() ? List.of() : nachEmail.getOrDefault(s.email(), List.of());
        }

        List<Bestand> ueberNamePlz(Schluessel s) {
            return s.namePlz() ? nachNamePlz.getOrDefault(s.name() + "\u0000" + s.plz(), List.of()) : List.of();
        }
    }

    /** Ergebnis einer Zeile; {@code kontaktId} nur bei einem Treffer, {@code kandidaten} nur bei einer Dublette. */
    public record Befund(Ergebnis ergebnis, String meldung, UUID kontaktId, List<UUID> kandidaten) {

        public static final Befund NEU = new Befund(Ergebnis.NEU, null, null, List.of());

        public Befund {
            Objects.requireNonNull(ergebnis, "ergebnis");
            kandidaten = List.copyOf(kandidaten);
        }

        static Befund dublette(String meldung, List<UUID> kandidaten) {
            return new Befund(Ergebnis.DUBLETTE, meldung, null, kandidaten);
        }

    }
}
