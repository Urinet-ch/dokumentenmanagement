package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.konfiguration.application.KonfigurationLesen;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.ErfassungMandant;
import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.ki.domain.KiKlassifikation;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.ki.domain.QrDaten;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.ki.domain.VorschlagNachbearbeitung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Metadaten-Extraktor der Erfassung (Aufgabe 3.2; Req 7.1–7.3, 7.5, 7.6, 7.11, 7.12; design.md «KI»), Ablauf wie im
 * Golden-Lauf: Swiss QR Code vor dem KI-Aufruf lesen, Prompt v6 bauen (mit Korrekturbeispielen aus
 * {@link BeispielAuswahl}, Aufgabe 3.3, Req 7.9), genau ein Aufruf über {@link KiPort}, bei einer
 * Antwort ausserhalb des Schemas genau eine Nachfrage, danach {@link VorschlagNachbearbeitung}. Jeder Aufruf landet in
 * {@code ki_verbrauch}. Antwortet das Modell zweimal ungültig oder fehlt das Token, gibt es keinen Vorschlag (Req 7.3);
 * antwortet es nicht, wirft sie {@link KiAusfall} (Fehlversuch des Auftrags, Req 5.4). Das Log nennt nur die
 * Dokument-ID, die Kennung des Prompts und Klassennamen, nie Inhalte (Req 16.7).
 */
@ApplicationScoped
public class MetadatenExtraktion {

    private static final Logger LOG = Logger.getLogger(MetadatenExtraktion.class);
    static final String NACHFRAGE_GRUND = "Schemafehler";

    private final KiPort ki;
    private final QrLeserPort qrLeser;
    private final SeitenbildPort seitenbild;
    private final KiVerbrauchPort verbrauch;
    private final KonfigurationLesen konfiguration;
    private final ZusatzSchemaPruefer zusatzSchema;
    private final MandantEinstellungen einstellungen;
    private final BeispielAuswahl beispiele;
    private final Duration musterZeitgrenze;

    public MetadatenExtraktion(KiPort ki, QrLeserPort qrLeser, SeitenbildPort seitenbild, KiVerbrauchPort verbrauch,
                               KonfigurationLesen konfiguration, ZusatzSchemaPruefer zusatzSchema,
                               MandantEinstellungen einstellungen, BeispielAuswahl beispiele,
                               @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration musterZeitgrenze) {
        this.ki = ki;
        this.qrLeser = qrLeser;
        this.seitenbild = seitenbild;
        this.verbrauch = verbrauch;
        this.konfiguration = konfiguration;
        this.zusatzSchema = zusatzSchema;
        this.einstellungen = einstellungen;
        this.beispiele = beispiele;
        this.musterZeitgrenze = musterZeitgrenze;
    }

    /**
     * @param text    Text für Modell, Nachbearbeitung und Kopf/Fuss-Erkennung wie im Golden-Lauf ({@code pdftotext
     *                -layout}), Seiten durch Seitenvorschub getrennt; bei {@code pdfa_fehlt} der Volltext
     * @param qr      Swiss QR Code aus {@link #qr(UUID, List)}; leer bei {@code pdfa_fehlt}
     * @param bild    Seitenbild des Bildwegs aus {@link #bild(UUID, List)}: Quelle ist ein Bild (Handy-Erfassung), das Bild
     *                geht statt des Texts an das Modell (Req 7.1); leer für den Textweg
     * @param eingang Eingangsdatum in Europe/Zurich (Regel R3)
     */
    public record Eingabe(UUID dokumentId, MandantCode mandant, String text, Optional<QrDaten> qr,
                          Optional<byte[]> bild, LocalDate eingang) {
        public Eingabe {
            Objects.requireNonNull(dokumentId, "dokumentId");
            Objects.requireNonNull(mandant, "mandant");
            text = text == null ? "" : text;
            Objects.requireNonNull(qr, "qr");
            Objects.requireNonNull(bild, "bild");
            Objects.requireNonNull(eingang, "eingang");
        }
    }

    /**
     * Erster Swiss QR Code auf den gerenderten Seiten (Req 7.11); ohne Seiten keiner. Eine Seite über
     * {@code archiv.ki.render-pixel-max} lässt der Leser aus (Review PR #120).
     */
    public Optional<QrDaten> qr(UUID dokumentId, List<Path> seiten) {
        return seiten.isEmpty() ? Optional.empty() : qrLeser.lesen(dokumentId, seiten);
    }

    /**
     * Seitenbild des Bildwegs wie im Golden-Lauf (Req 7.1); ohne Seiten keines (dann geht der Text an das Modell).
     * Ebenso keines, wenn eine Seite einen ungültigen Kopf oder mehr als {@code archiv.ki.render-pixel-max} Pixel hat
     * ({@link SeiteUngeeignet}, Review PR #120): kein Fehlversuch, denn jeder Versuch brächte dasselbe; das Log warnt
     * mit der ID.
     *
     * @throws IOException wenn eine Seite sonst nicht lesbar ist (Fehlversuch des Auftrags)
     */
    public Optional<byte[]> bild(UUID dokumentId, List<Path> seiten) throws IOException {
        if (seiten.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(seitenbild.jpeg(seiten));
        } catch (SeiteUngeeignet e) {
            LOG.warnf("Seitenbild für Dokument %s nicht erstellt (%s): weiter ohne Seitenbild", dokumentId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Vorschlag nach der Nachbearbeitung; leer, wenn das Modell zweimal ausserhalb des Schemas antwortete (Req 7.3).
     *
     * @throws KiAusfall wenn das Modell nicht antwortete (Timeout, HTTP-Fehler, Netz): Fehlversuch des Auftrags,
     *                   wiederholt nach Req 5.4; ohne Token dagegen leer und ohne Request
     */
    public Optional<Vorschlag> vorschlagen(Eingabe eingabe) {
        Konfiguration konfig = konfiguration.aktuell();
        if (!konfig.kiTexte().kiFreigegeben()) {
            return Optional.empty();
        }
        MandantEinstellungen.Werte werte = einstellungen.lesen(eingabe.mandant());
        List<ArtEintrag> alleArten = arten(konfig, zusatzSchema);
        List<ArtEintrag> arten = alleArten.stream().filter(ArtEintrag::kiVorschlag).toList();
        if (arten.isEmpty()) {
            return Optional.empty();
        }
        Set<String> eigeneArten = alleArten.stream().filter(ArtEintrag::selbstAusgestellt)
                .map(ArtEintrag::code).collect(Collectors.toUnmodifiableSet());
        ErfassungMandant mandant = new ErfassungMandant(werte.name(), konfig.einstellungen().kennungen().uid(),
                konfig.einstellungen().kennungen().ibans(), eigeneArten,
                alleArten.stream().flatMap(art -> art.belegnummerMuster().stream()).toList());
        List<KiKlassifikation> klassifikationen = klassifikationen(konfig, arten);
        String grundPrompt = ErfassungPrompt.system(mandant.name(),
                ErfassungPrompt.artListe(arten, eigeneArten, mandant.name()),
                kiRolle(konfig.kiTexte().rolle()), konfig.kiTexte().feldregeln(), klassifikationen);
        String system = grundPrompt;
        Optional<QrDaten> qr = eingabe.qr();
        Optional<byte[]> bild = eingabe.bild();
        String text = bild.isPresent() ? "" : ErfassungPrompt.gekuerzt(eingabe.text());
        String auswahl = beispiele.fuer(eingabe.dokumentId(), eingabe.mandant(),
                ErfassungPrompt.gekuerzt(eingabe.text()), qr.map(QrDaten::iban), mandant);
        KiPort.Anfrage anfrage = new KiPort.Anfrage(system, ErfassungPrompt.qr(qr), text, bild, Optional.empty(),
                auswahl, arten, klassifikationen);
        String schema = ki.antwortschema(anfrage);
        String kennung = ErfassungPrompt.hash(grundPrompt, schema, ki.artCodes(anfrage), ErfassungPrompt.BILDWEG);
        if (!ErfassungPrompt.GATE_KENNUNG.equals(kennung)) {
            LOG.warnf("KI-Vorschlag für Dokument %s mit Prompt %s statt %s aus Gate 0 (Mandantenname oder Arten "
                    + "weichen ab)", eingabe.dokumentId(), kennung, ErfassungPrompt.GATE_KENNUNG);
        }
        KiPort.Antwort antwort = aufrufen(anfrage);
        if (antwort instanceof KiPort.Schemafehler) {
            antwort = aufrufen(anfrage.nachfrage(NACHFRAGE_GRUND));
        }
        return switch (antwort) {
            case KiPort.Gueltig g -> {
                VorschlagNachbearbeitung.Ergebnis bearbeitung = VorschlagNachbearbeitung.bearbeiteMitProtokoll(
                        g.vorschlag(), qr, eingabe.text(), mandant, alleArten, klassifikationen, eingabe.eingang(),
                        Merkmale.Frist.cpu(musterZeitgrenze));
                bearbeitung.abgebrochen().forEach(nr -> LOG.warnf(
                        "Belegnummer-Muster %d für Dokument %s nach %s abgebrochen, gilt als nicht zutreffend", nr,
                        eingabe.dokumentId(), musterZeitgrenze));
                Vorschlag vorschlag = bearbeitung.vorschlag();
                LOG.infof("KI-Vorschlag für Dokument %s (Prompt %s, Nachbearbeitung %s, QR %s, Bildweg %s)",
                        eingabe.dokumentId(), kennung, VorschlagNachbearbeitung.FASSUNG,
                        qr.isPresent(), bild.isPresent());
                yield Optional.of(vorschlag);
            }
            case KiPort.Schemafehler s -> {
                LOG.warnf("KI-Vorschlag für Dokument %s ohne Ergebnis: auch die Nachfrage ausserhalb des Schemas (%s)",
                        eingabe.dokumentId(), s.grund());
                yield Optional.empty();
            }
            case KiPort.Ausfall a -> throw new KiAusfall(eingabe.dokumentId(), a.grund());
            case KiPort.OhneZugang o -> {
                LOG.warnf("KI-Vorschlag für Dokument %s ohne Ergebnis: kein Token für das Sprachmodell",
                        eingabe.dokumentId());
                yield Optional.empty();
            }
        };
    }

    private static String kiRolle(String rolle) {
        return rolle == null || rolle.isBlank() ? "Archivassistent für ein Schweizer KMU" : rolle;
    }

    private static List<ArtEintrag> arten(Konfiguration konfiguration, ZusatzSchemaPruefer zusatzSchema) {
        return konfiguration.arten().stream()
                .filter(Konfiguration.Art::aktiv)
                .sorted(java.util.Comparator.comparingInt(Konfiguration.Art::reihenfolge))
                .map(art -> new ArtEintrag(art.code(), art.name(), art.beschreibung(), art.vertraulichkeit(),
                        art.gruppen(), art.selbstAusgestellt(), art.kiVorschlag(), art.belegnummerMuster(),
                        art.fremdbelegArt(), art.ohneZahlungsdatenArt(), art.kiFelder(), art.kiHinweise(),
                        art.reihenfolge(), art.zusatzSchema(), art.zusatzFeldTypen(),
                        art.klassifikationen().stream().map(k -> k.klassifikation()).toList(),
                        zusatzSchema.feldBeschreibungen(art.zusatzSchema())))
                .toList();
    }

    private static List<KiKlassifikation> klassifikationen(Konfiguration konfiguration, List<ArtEintrag> arten) {
        Set<String> zugeordnet = arten.stream().flatMap(art -> art.klassifikationen().stream())
                .collect(Collectors.toUnmodifiableSet());
        return konfiguration.klassifikationen().stream()
                .filter(Klassifikation::aktiv)
                .filter(Klassifikation::kiVorschlag)
                .filter(klassifikation -> zugeordnet.contains(klassifikation.code()))
                .map(klassifikation -> new KiKlassifikation(klassifikation.code(), klassifikation.name(),
                        klassifikation.beschreibung(), klassifikation.mehrfach(),
                        klassifikation.werte().stream().filter(KlassifikationWert::aktiv)
                                .map(wert -> new KiKlassifikation.Wert(wert.code(),
                                wert.name(), wert.beschreibung(), wert.aktiv())).toList()))
                .toList();
    }

    private KiPort.Antwort aufrufen(KiPort.Anfrage anfrage) {
        KiPort.Antwort antwort = ki.metadaten(anfrage);
        if (antwort instanceof KiPort.OhneZugang) {
            return antwort;
        }
        try {
            verbrauch.erfassen(KiVerbrauchPort.ERFASSUNG, antwort.verbrauch());
        } catch (RuntimeException e) {
            LOG.warnf("KI-Verbrauch nicht gespeichert (%s)", e.getClass().getName());
        }
        return antwort;
    }
}
