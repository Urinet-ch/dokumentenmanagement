package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentFinanz;
import ch.urinet.archiv.dokument.domain.DokumentVertrag;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.Werteformat;
import ch.urinet.archiv.ki.domain.chat.DokumentDaten;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Werkzeug {@code dokument_zeigen} (Spec 02, Req 3.1, 5.2, 5.3): Metadaten, Anfang des Volltexts (höchstens
 * {@value #INHALT_MAX} Zeichen, eigenes Feld {@code inhalt}, damit der Agent ihn als Daten kennzeichnet; ist der Text
 * länger, melden {@code inhaltGekuerzt}, {@code inhaltZeichen} und {@code inhaltHinweis} die Kürzung) und Link,
 * ohne Dateinamen (Spec 01, Req 3.13). Ungültige, fremde, unsichtbare und nicht vorhandene IDs ergeben gleich
 * {@link WerkzeugFehler#NICHT_GEFUNDEN} (Spec 01, Req 2.3).
 * <p>
 * Zugriffsprotokoll (E4, entschieden von Fabian am 14.11.2026): {@code ANZEIGEN} mit Benutzer, Rolle, IP aus dem
 * Kontext, Dokument-ID und {@code details} {@code {"kanal":"CHAT"}}, vor dem Lesen des Inhalts in derselben
 * Transaktion (Spec 01, Req 10.2, 10.8): Scheitert der Eintrag oder der Commit, gibt es keinen Inhalt. Nicht gefunden
 * schreibt wie {@code GET /dokumente/{id}} {@code VERWEIGERT} (Grund A2, Fehlercode {@code NICHT_GEFUNDEN}, Req 10.7) in
 * einer eigenen Transaktion; scheitert das, bleibt die Antwort «nicht gefunden» (wie der {@code VerweigerungMelder}).
 */
@ApplicationScoped
public class DokumentZeigen {

    public static final int INHALT_MAX = 4_000;

    /**
     * Fester Hinweis an das Modell, wenn der Text länger als {@link #INHALT_MAX} Zeichen ist (Systemtext, nie aus
     * Dokumentdaten; {@code %d} = Zahl der gezeigten Zeichen). Er steht ausserhalb des gerahmten Felds {@code inhalt}.
     */
    public static final String HINWEIS_GEKUERZT = "Dokumentinhalt gekürzt: nur der Anfang (%d Zeichen); für weitere Stellen "
            + "die Suche mit passenden Begriffen nutzen oder das Dokument öffnen";

    /** Gezeigter Anfang des Volltexts und ob er gekürzt wurde. */
    record Inhalt(String text, boolean gekuerzt) {
    }

    /**
     * Kürzt auf höchstens {@link #INHALT_MAX} Unicode-Zeichen (Codepoints, nie mitten in einem Surrogatpaar). Die Zahl
     * zählt den Archivtext vor der Neutralisierung ({@code DokumentDaten.neutralisieren}), nicht das gerahmte Feld. Der
     * Aufrufer holt {@code INHALT_MAX + 1} Zeichen: ist es mehr als {@code INHALT_MAX}, war der Text länger.
     */
    static Inhalt kuerzen(String roh) {
        if (roh == null || roh.codePointCount(0, roh.length()) <= INHALT_MAX) {
            return new Inhalt(roh, false);
        }
        String anfang = roh.substring(0, roh.offsetByCodePoints(0, INHALT_MAX));
        // nur Leerraum im Anfang: wie bisher kein Inhalt (der Adapter verwirft Leerraum ebenfalls)
        return anfang.isBlank() ? new Inhalt(null, false) : new Inhalt(anfang, true);
    }

    private static final Logger LOG = Logger.getLogger(DokumentZeigen.class);

    /** Angaben aus {@code dokument_vertrag}, soweit vorhanden. */
    public record Vertrag(String gueltigAb, String gueltigBis, String kuendigungsfrist, String naechsteFrist) {
    }

    /** Ein sichtbares Dokument; fehlende Angaben sind {@code null}. */
    public record Ansicht(String metadaten, String inhalt, Boolean inhaltGekuerzt, Integer inhaltZeichen,
                          String inhaltHinweis, String id, String titel, String art, String datum,
                          String partner, String belegnummer, String betrag, String faelligkeit, Vertrag vertrag,
                          boolean bestaetigt, String link, String vertraulichkeit) {
    }

    private final DokumentRepository dokumente;
    private final WerkzeugDaten daten;
    private final ZugriffProtokoll protokoll;
    private final WerkzeugTransaktion transaktion;

    public DokumentZeigen(DokumentRepository dokumente, WerkzeugDaten daten, ZugriffProtokoll protokoll,
                          WerkzeugTransaktion transaktion) {
        this.dokumente = dokumente;
        this.daten = daten;
        this.protokoll = protokoll;
        this.transaktion = transaktion;
    }

    private static final int WERT_MAX = PartnerAufloesung.NAME_MAX;
    /** Unicode-Leerraum gilt wie Leerzeichen ({@code U}): ein geschütztes Leerzeichen macht aus dem Marker keinen anderen Text. */
    private static final java.util.regex.Pattern WERT_RAHMEN = java.util.regex.Pattern.compile(
            "(?iU)(?:dokument[\\s\\p{Cf}\\p{Pd}_]*inhalt[\\s\\p{Cf}\\p{Pd}_]*\\([^)]*\\)"
                    + "|ende[\\s\\p{Cf}\\p{Pd}_]*dokument[\\s\\p{Cf}\\p{Pd}_]*inhalt)");

    public Ansicht zeigen(WerkzeugKontext kontext, String id) {
        UUID dokumentId = Werkzeugparameter.id(id);
        Optional<Ansicht> ansicht = dokumentId == null ? Optional.empty()
                : transaktion.neu(() -> lesen(kontext, dokumentId));
        if (ansicht.isEmpty()) {
            verweigert(kontext, Optional.ofNullable(dokumentId));
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_GEFUNDEN);
        }
        return ansicht.get();
    }

    private Optional<Ansicht> lesen(WerkzeugKontext kontext, UUID id) {
        Optional<Dokument> gefunden = dokumente.finden(id, kontext.sicht());
        if (gefunden.isEmpty()) {
            return Optional.empty();
        }
        protokoll.schreiben(eintrag(kontext, ProtokollAktion.ANZEIGEN, Optional.of(id), ProtokollDetails.KEINE));
        Dokument d = gefunden.get();
        Inhalt inhalt = kuerzen(daten.inhalt(id, kontext.sicht(), INHALT_MAX + 1).orElse(null));
        DokumentFinanz f = d.finanz();
        DokumentVertrag v = d.vertrag();
        String datum = Werteformat.datum(d.datum());
        String belegnummer = f == null ? null : f.belegnummer();
        String betrag = f == null ? null : Werteformat.betrag(f.betrag(), f.waehrung());
        String faelligkeit = f == null ? null : Werteformat.datum(f.faelligkeit());
        String partner = d.partnerName();
        return Optional.of(new Ansicht(metadaten(d.bestaetigtesDokument(), belegnummer, betrag, datum, faelligkeit,
                partner), inhalt.text(), inhalt.gekuerzt() ? Boolean.TRUE : null,
                inhalt.gekuerzt() ? INHALT_MAX : null,
                inhalt.gekuerzt() ? HINWEIS_GEKUERZT.formatted(INHALT_MAX) : null, id.toString(), d.titel(), d.art(), datum, partner, belegnummer, betrag, faelligkeit,
                v == null ? null : new Vertrag(Werteformat.datum(v.gueltigAb()), Werteformat.datum(v.gueltigBis()),
                        v.kuendigungsfrist(), Werteformat.datum(v.naechsteFrist())),
                d.bestaetigtesDokument(), DokumenteSuchen.link(id), d.wirksameVertraulichkeit()));
    }

    static String metadaten(boolean bestaetigt, String belegnummer, String betrag, String datum, String faelligkeit,
                            String partner) {
        StringBuilder text = new StringBuilder("Metadaten (")
                .append(bestaetigt ? "bestätigt" : "nicht bestätigt").append(')');
        zeile(text, "Belegnummer", belegnummer);
        zeile(text, "Betrag", betrag);
        zeile(text, "Datum", datum);
        zeile(text, "Fälligkeit", faelligkeit);
        zeile(text, "Partner", partner);
        return text.toString();
    }

    private static void zeile(StringBuilder text, String feld, String wert) {
        String sauber = wert == null ? "" : einzeilig(wert);
        if (!sauber.isEmpty()) {
            text.append('\n').append(feld).append(": ").append(sauber);
        }
    }

    /** Belegnummer und Partner sind fremde Daten (OCR, Import): einzeilig, ohne Rahmenmarker, auf {@link #WERT_MAX} gekürzt. */
    static String einzeilig(String wert) {
        return einzeilig(wert, WERT_MAX);
    }

    /** Wie {@link #einzeilig(String)} mit eigener Obergrenze (Titel des öffentlichen Chats, Spec 03, 11.3). */
    public static String einzeilig(String wert, int max) {
        String s = wert.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replaceAll("(?U)\\s+", " ").strip();
        s = PartnerAufloesung.KANDIDATEN_RAHMEN.matcher(s).replaceAll("[Rahmenmarker neutralisiert]");
        s = WERT_RAHMEN.matcher(s).replaceAll("[Rahmenmarker neutralisiert]");
        s = DokumentDaten.METADATEN_KOPF.matcher(s).replaceAll("[Rahmenmarker neutralisiert]");
        if (s.length() <= max) {
            return s;
        }
        // nie mitten in einem Surrogatpaar (Emoji) kürzen: sonst bleibt ein halbes Zeichen stehen
        int ende = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, ende).strip();
    }

    private void verweigert(WerkzeugKontext kontext, Optional<UUID> id) {
        ProtokollDetails details = new ProtokollDetails(Aktion.A2.name(), null, Fehlercode.NICHT_GEFUNDEN.name());
        try {
            transaktion.neu(() -> {
                protokoll.schreiben(eintrag(kontext, ProtokollAktion.VERWEIGERT, id, details));
                return null;
            });
        } catch (RuntimeException e) {
            LOG.errorf("Verweigerung im Chat nicht protokolliert: fehlercode=%s", Fehlercode.NICHT_GEFUNDEN.name());
        }
    }

    private static ZugriffEintrag eintrag(WerkzeugKontext kontext, ProtokollAktion aktion, Optional<UUID> id,
                                          ProtokollDetails details) {
        return new ZugriffEintrag(Optional.of(kontext.benutzer().id()), Optional.of(kontext.rolle().name()), aktion,
                id, kontext.ip(), details.mitKanal(ProtokollDetails.KANAL_CHAT));
    }
}
