package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.ki.domain.ArtEintrag;
import ch.urinet.archiv.ki.domain.ErfassungMandant;
import ch.urinet.archiv.ki.domain.ErfassungPrompt;
import ch.urinet.archiv.ki.domain.QrDaten;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.ki.domain.VorschlagNachbearbeitung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
    private final DokumentartVerwaltung dokumentarten;
    private final MandantEinstellungen einstellungen;
    private final BeispielAuswahl beispiele;

    public MetadatenExtraktion(KiPort ki, QrLeserPort qrLeser, SeitenbildPort seitenbild, KiVerbrauchPort verbrauch,
                               DokumentartVerwaltung dokumentarten, MandantEinstellungen einstellungen,
                               BeispielAuswahl beispiele) {
        this.ki = ki;
        this.qrLeser = qrLeser;
        this.seitenbild = seitenbild;
        this.verbrauch = verbrauch;
        this.dokumentarten = dokumentarten;
        this.einstellungen = einstellungen;
        this.beispiele = beispiele;
    }

    /**
     * @param text    Text für Modell, Nachbearbeitung und Kopf/Fuss-Erkennung wie im Golden-Lauf ({@code pdftotext
     *                -layout}), Seiten durch Seitenvorschub getrennt; bei {@code pdfa_fehlt} der Volltext
     * @param qr      Swiss QR Code aus {@link #qr(List)}; leer bei {@code pdfa_fehlt}
     * @param bild    Seitenbild des Bildwegs aus {@link #bild(List)}: Quelle ist ein Bild (Handy-Erfassung), das Bild
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

    /** Erster Swiss QR Code auf den gerenderten Seiten (Req 7.11); ohne Seiten keiner. */
    public Optional<QrDaten> qr(List<Path> seiten) {
        return seiten.isEmpty() ? Optional.empty() : qrLeser.lesen(seiten);
    }

    /**
     * Seitenbild des Bildwegs wie im Golden-Lauf (Req 7.1); ohne Seiten keines (dann geht der Text an das Modell).
     *
     * @throws IOException wenn eine Seite nicht lesbar ist (Fehlversuch des Auftrags)
     */
    public Optional<byte[]> bild(List<Path> seiten) throws IOException {
        return seiten.isEmpty() ? Optional.empty() : Optional.of(seitenbild.jpeg(seiten));
    }

    /**
     * Vorschlag nach der Nachbearbeitung; leer, wenn das Modell zweimal ausserhalb des Schemas antwortete (Req 7.3).
     *
     * @throws KiAusfall wenn das Modell nicht antwortete (Timeout, HTTP-Fehler, Netz): Fehlversuch des Auftrags,
     *                   wiederholt nach Req 5.4; ohne Token dagegen leer und ohne Request
     */
    public Optional<Vorschlag> vorschlagen(Eingabe eingabe) {
        MandantEinstellungen.Werte werte = einstellungen.lesen(eingabe.mandant());
        ErfassungMandant mandant = new ErfassungMandant(werte.name(), werte.uid(), werte.ibans(), werte.eigeneArten());
        List<ArtEintrag> arten = arten();
        String system = ErfassungPrompt.system(mandant.name(),
                ErfassungPrompt.artListe(arten, mandant.eigeneArten(), mandant.name()));
        Optional<QrDaten> qr = eingabe.qr();
        Optional<byte[]> bild = eingabe.bild();
        String text = bild.isPresent() ? "" : ErfassungPrompt.gekuerzt(eingabe.text());
        String auswahl = beispiele.fuer(eingabe.dokumentId(), eingabe.mandant(), eingabe.text(),
                qr.map(QrDaten::iban), mandant);
        KiPort.Anfrage anfrage = new KiPort.Anfrage(system, ErfassungPrompt.qr(qr), text, bild, Optional.empty(),
                auswahl);
        String kennung = ErfassungPrompt.hash(system, ki.antwortschema(), ki.artCodes(), ErfassungPrompt.BILDWEG);
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
                Vorschlag vorschlag = VorschlagNachbearbeitung.bearbeite(g.vorschlag(), qr, eingabe.text(),
                        mandant, arten, eingabe.eingang());
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

    /**
     * Aktive Arten des Mandanten, die das Antwortschema kennt, in dessen Reihenfolge (die Reihenfolge der Seed-Tabelle
     * im Golden-Lauf; {@code ErfassungPrompt.artListe} stellt die eigenen Arten nach hinten).
     */
    List<ArtEintrag> arten() {
        return ErfassungPrompt.schemaArten(dokumentarten.alle().stream()
                .filter(Dokumentart::aktiv)
                .map(a -> new ArtEintrag(a.code(), a.name(), a.vertraulichkeit().name()))
                .toList(), ki.artCodes());
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
