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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Metadaten-Extraktor der Erfassung (Aufgabe 3.2; Req 7.1–7.3, 7.5, 7.6, 7.11, 7.12; design.md «KI»), Ablauf wie im
 * Golden-Lauf: Swiss QR Code vor dem KI-Aufruf lesen, Prompt v6 bauen, genau ein Aufruf über {@link KiPort}, bei einer
 * Antwort ausserhalb des Schemas genau eine Nachfrage, danach {@link VorschlagNachbearbeitung}. Jeder Aufruf landet in
 * {@code ki_verbrauch}. Antwortet das Modell nicht oder zweimal ungültig, gibt es keinen Vorschlag (Req 7.3); das Log
 * nennt nur die Dokument-ID und Klassennamen, nie Inhalte (Req 16.7).
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

    public MetadatenExtraktion(KiPort ki, QrLeserPort qrLeser, SeitenbildPort seitenbild, KiVerbrauchPort verbrauch,
                               DokumentartVerwaltung dokumentarten, MandantEinstellungen einstellungen) {
        this.ki = ki;
        this.qrLeser = qrLeser;
        this.seitenbild = seitenbild;
        this.verbrauch = verbrauch;
        this.dokumentarten = dokumentarten;
        this.einstellungen = einstellungen;
    }

    /**
     * @param volltext Volltext des Dokuments, Seiten durch Seitenvorschub getrennt
     * @param seiten   gerenderte Seiten der PDF/A (für QR und Bildweg); leer bei {@code pdfa_fehlt}
     * @param bildweg  Quelle ist ein Bild (Handy-Erfassung): das Seitenbild geht an das Modell (Req 7.1)
     * @param eingang  Eingangsdatum in Europe/Zurich (Regel R3)
     */
    public record Eingabe(UUID dokumentId, MandantCode mandant, String volltext, List<Path> seiten, boolean bildweg,
                          LocalDate eingang) {
        public Eingabe {
            Objects.requireNonNull(dokumentId, "dokumentId");
            Objects.requireNonNull(mandant, "mandant");
            volltext = volltext == null ? "" : volltext;
            seiten = List.copyOf(seiten);
            Objects.requireNonNull(eingang, "eingang");
        }
    }

    /** Vorschlag nach der Nachbearbeitung; leer, wenn das Modell ausfiel oder zweimal ungültig antwortete. */
    public Optional<Vorschlag> vorschlagen(Eingabe eingabe) {
        MandantEinstellungen.Werte werte = einstellungen.lesen(eingabe.mandant());
        ErfassungMandant mandant = new ErfassungMandant(werte.name(), werte.uid(), werte.ibans(), werte.eigeneArten());
        List<ArtEintrag> arten = arten();
        String system = ErfassungPrompt.system(mandant.name(),
                ErfassungPrompt.artListe(arten, mandant.eigeneArten(), mandant.name()));
        Optional<QrDaten> qr = eingabe.seiten().isEmpty() ? Optional.empty() : qrLeser.lesen(eingabe.seiten());
        Optional<byte[]> bild = Optional.empty();
        if (eingabe.bildweg() && !eingabe.seiten().isEmpty()) {
            try {
                bild = Optional.of(seitenbild.jpeg(eingabe.seiten()));
            } catch (IOException | RuntimeException e) {
                LOG.warnf("KI-Vorschlag für Dokument %s ohne Ergebnis: Seitenbild nicht erstellt (%s)",
                        eingabe.dokumentId(), e.getClass().getName());
                return Optional.empty();
            }
        }
        String text = bild.isPresent() ? "" : ErfassungPrompt.gekuerzt(eingabe.volltext());
        KiPort.Anfrage anfrage = new KiPort.Anfrage(system, ErfassungPrompt.qr(qr), text, bild, Optional.empty());
        KiPort.Antwort antwort = aufrufen(anfrage);
        if (antwort instanceof KiPort.Schemafehler) {
            antwort = aufrufen(anfrage.nachfrage(NACHFRAGE_GRUND));
        }
        return switch (antwort) {
            case KiPort.Gueltig g -> {
                Vorschlag vorschlag = VorschlagNachbearbeitung.bearbeite(g.vorschlag(), qr, eingabe.volltext(),
                        mandant, arten, eingabe.eingang());
                LOG.infof("KI-Vorschlag für Dokument %s (Prompt %s, Nachbearbeitung %s, QR %s, Bildweg %s)",
                        eingabe.dokumentId(), ErfassungPrompt.VERSION, VorschlagNachbearbeitung.FASSUNG,
                        qr.isPresent(), bild.isPresent());
                yield Optional.of(vorschlag);
            }
            case KiPort.Schemafehler s -> {
                LOG.warnf("KI-Vorschlag für Dokument %s ohne Ergebnis: auch die Nachfrage ausserhalb des Schemas (%s)",
                        eingabe.dokumentId(), s.grund());
                yield Optional.empty();
            }
            case KiPort.Ausfall a -> {
                LOG.warnf("KI-Vorschlag für Dokument %s ohne Ergebnis: keine Antwort (%s)", eingabe.dokumentId(),
                        a.grund());
                yield Optional.empty();
            }
        };
    }

    /**
     * Aktive Arten des Mandanten, die das Antwortschema kennt, in dessen Reihenfolge (die Reihenfolge der Seed-Tabelle
     * im Golden-Lauf; {@code ErfassungPrompt.artListe} stellt die eigenen Arten nach hinten).
     */
    List<ArtEintrag> arten() {
        List<String> codes = ki.artCodes();
        return dokumentarten.alle().stream()
                .filter(Dokumentart::aktiv)
                .filter(a -> codes.contains(a.code()))
                .sorted(Comparator.comparingInt(a -> codes.indexOf(a.code())))
                .map(a -> new ArtEintrag(a.code(), a.name(), a.vertraulichkeit().name()))
                .toList();
    }

    private KiPort.Antwort aufrufen(KiPort.Anfrage anfrage) {
        KiPort.Antwort antwort = ki.metadaten(anfrage);
        try {
            verbrauch.erfassen(KiVerbrauchPort.ERFASSUNG, antwort.verbrauch());
        } catch (RuntimeException e) {
            LOG.warnf("KI-Verbrauch nicht gespeichert (%s)", e.getClass().getName());
        }
        return antwort;
    }
}
