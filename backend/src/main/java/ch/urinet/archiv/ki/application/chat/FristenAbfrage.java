package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.Werteformat;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Werkzeug {@code fristen(von, bis)} (Spec 02, Req 3.5, 5.2): Kündigungsfristen ({@code naechste_frist}) und
 * Vertragsende ({@code gueltig_bis}) aus {@code dokument_vertrag}, Fälligkeiten aus {@code dokument_finanz} und die
 * Gültigkeit aus einem Zusatzfeld je Art, konfiguriert je Mandant unter {@code archiv.mandant.<code>.fristen.zusatz}
 * ({@code ART:feld}, mehrere mit Komma; Übergang, wird durch Spec 05 ersetzt). Nur {@code ARCHIVIERT}, sichtbar nach der
 * Sichtregel, auch in Gesamtzahl und Datenstand. Spanne höchstens {@value #JAHRE_MAX} Jahre, höchstens
 * {@value #HOECHSTENS} Fristen nach Datum sortiert. Bei eingeschränkter Sicht steht der feste Hinweis
 * {@value #HINWEIS_SICHT} dabei.
 */
@ApplicationScoped
public class FristenAbfrage {

    public static final int JAHRE_MAX = 3;
    public static final int HOECHSTENS = 50;
    public static final String HINWEIS_SICHT = "nur Belege, die du sehen darfst";

    private static final Pattern ART = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern FELD = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** Verweis auf das Dokument der Frist. */
    public record Dokument(String id, String titel, String link) {
    }

    /** Eine Frist; {@code frist} benennt die Quelle, z. B. «Kündigungsfrist» oder «Offerte gültig bis». */
    public record Frist(String datum, String frist, String dokumentart, Dokument dokument, String beschreibung) {
    }

    /** Wann und über wie viele Dokumente die Antwort gilt (Req 4.1: «Datenstand: <Zeitpunkt>, <n> Belege»). */
    public record Datenstand(String zeitpunkt, long anzahl) {
    }

    /** {@code gesamt}: alle Fristen im Zeitraum unter derselben Sicht; {@code hinweis} bei eingeschränkter Sicht. */
    public record Antwort(List<Frist> fristen, long gesamt, Datenstand datenstand, String hinweis) {

        public Antwort {
            fristen = List.copyOf(fristen);
        }
    }

    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final Config konfiguration;
    private final Clock uhr;

    @Inject
    public FristenAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, Config konfiguration) {
        this(daten, transaktion, konfiguration, Clock.systemUTC());
    }

    FristenAbfrage(WerkzeugDaten daten, WerkzeugTransaktion transaktion, Config konfiguration, Clock uhr) {
        this.daten = daten;
        this.transaktion = transaktion;
        this.konfiguration = konfiguration;
        this.uhr = uhr;
    }

    public Antwort fristen(WerkzeugKontext kontext, String vonText, String bisText) {
        LocalDate von = Werkzeugparameter.datum("von", vonText, true);
        LocalDate bis = Werkzeugparameter.datum("bis", bisText, true);
        if (von.isAfter(bis)) {
            throw WerkzeugFehler.ungueltig("von");
        }
        if (bis.isAfter(von.plusYears(JAHRE_MAX))) {
            throw WerkzeugFehler.ungueltig("bis");
        }
        List<WerkzeugDaten.Zusatzfrist> zusatz = zusatz(kontext.mandant());
        WerkzeugDaten.Fristseite seite = transaktion.neu(() -> {
            daten.nurLesend();
            return daten.fristen(von, bis, kontext.sicht(), zusatz, HOECHSTENS);
        });
        return new Antwort(seite.fristen().stream().map(FristenAbfrage::frist).toList(), seite.gesamt(),
                new Datenstand(Werteformat.zeitpunkt(uhr.instant()), seite.dokumente()),
                kontext.sicht().uneingeschraenkt() ? null : HINWEIS_SICHT);
    }

    /** Zusatzfelder des Mandanten; ein ungültiger Eintrag bricht ab (Konfigurationsfehler, kein Wert im Log). */
    List<WerkzeugDaten.Zusatzfrist> zusatz(MandantCode mandant) {
        List<String> eintraege = konfiguration.getOptionalValues("archiv.mandant." + mandant.wert() + ".fristen.zusatz",
                String.class).orElse(List.of());
        List<WerkzeugDaten.Zusatzfrist> zusatz = new ArrayList<>();
        for (String eintrag : eintraege) {
            String[] teile = eintrag.strip().split(":", -1);
            if (teile.length != 2 || !ART.matcher(teile[0]).matches() || !FELD.matcher(teile[1]).matches()) {
                throw new IllegalStateException("archiv.mandant." + mandant.wert() + ".fristen.zusatz ist ungültig");
            }
            zusatz.add(new WerkzeugDaten.Zusatzfrist(teile[0], teile[1]));
        }
        return List.copyOf(zusatz);
    }

    private static Frist frist(WerkzeugDaten.Frist f) {
        String name = switch (f.quelle()) {
            case KUENDIGUNGSFRIST -> "Kündigungsfrist";
            case GUELTIG_BIS -> "gültig bis";
            case FAELLIGKEIT -> "Fälligkeit";
            case ZUSATZ -> (f.artName() == null ? f.art() : f.artName()) + " gültig bis";
        };
        String beschreibung = Stream.of(f.partner(), Werteformat.betrag(f.betrag(), f.waehrung()),
                        f.quelle() == WerkzeugDaten.Quelle.KUENDIGUNGSFRIST ? f.kuendigungsfrist() : null)
                .filter(Objects::nonNull).filter(t -> !t.isBlank()).collect(Collectors.joining(", "));
        return new Frist(datum(f.datum()), name, f.art(),
                new Dokument(f.id().toString(), f.titel(), DokumenteSuchen.link(f.id())),
                beschreibung.isEmpty() ? null : beschreibung);
    }

    /** {@code JJJJ-MM-TT} als {@code TT.MM.JJJJ}; ein ungültiges Datum aus einem Zusatzfeld bleibt, wie es ist. */
    private static String datum(String iso) {
        try {
            return Werteformat.datum(LocalDate.parse(iso));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }
}
