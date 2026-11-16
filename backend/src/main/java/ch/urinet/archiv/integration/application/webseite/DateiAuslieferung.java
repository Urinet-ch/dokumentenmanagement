package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ansicht;
import ch.urinet.archiv.ablage.domain.DateiStrom;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Gemischte Dateiauslieferung der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.8, Req 13): Stufe {@code oeffentlich}
 * ergibt eine vorsignierte URL, {@code mitglieder} und {@code vorstand} einen Stream über die API.
 * <p>
 * Ablauf für den Stream: (1) kurze Transaktion: Dokument mit dem festen Filter laden, Ort der Version lesen
 * ({@link WebseiteDatei#vorbereiten}); (2) Platz reservieren, sonst 429; (3) Version im Object Storage öffnen, sonst
 * 503; (4) kurze Transaktion: Protokoll {@code HERUNTERLADEN} schreiben ({@link WebseiteDatei#protokollieren}); erst
 * dann der {@link StreamLauf}. Das Protokoll steht damit vor dem ersten Byte, ein abgelehnter (429) oder gestörter
 * (503) Abruf schreibt keinen Eintrag, und der Stream selbst hält weder Transaktion noch Datenbankverbindung (Req
 * 13.8; design.md «Dateiauslieferung und Stream»: sonst hielte ein langsamer Client den Horizont des Änderungs-Feeds
 * zurück). Die Klasse selbst darf nie in einer Transaktion laufen ({@code NEVER}).
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class DateiAuslieferung {

    /** Medientyp der Antwort; die einzige Variante ist die PDF/A-Fassung (Allowlist, Req 13.5). */
    public static final String MEDIENTYP = "application/pdf";

    /** Ergebnis: eine Adresse oder ein Stream. */
    public sealed interface Auslieferung {
    }

    public record Link(VorsignierteUrl url) implements Auslieferung {
    }

    public record Strom(StreamLauf lauf) implements Auslieferung {
    }

    private final WebseiteDatei datei;
    private final AblagePort ablage;
    private final DateiPlaetze plaetze;
    private final StreamWaechter waechter;
    private final Duration leerlauf;
    private final Duration zeitlimit;

    public DateiAuslieferung(WebseiteDatei datei, AblagePort ablage, DateiPlaetze plaetze, StreamWaechter waechter,
                             @ConfigProperty(name = "archiv.webseite.stream-leerlauf") Duration leerlauf,
                             @ConfigProperty(name = "archiv.webseite.stream-zeitlimit") Duration zeitlimit) {
        this.datei = datei;
        this.ablage = ablage;
        this.plaetze = plaetze;
        this.waechter = waechter;
        this.leerlauf = leerlauf;
        this.zeitlimit = zeitlimit;
    }

    /**
     * @param benutzer             Kennung des Schlüssels; auch der Schlüssel der Plätze je Schlüssel
     * @param verbindungSchliessen verwirft die Verbindung zum Client bei einem Zeitlimit (nur beim Stream)
     */
    public Auslieferung ausliefern(MandantCode mandant, Lesezugriff zugriff, UUID id, Optional<String> benutzer,
                                   Optional<IpAdresse> ip, Runnable verbindungSchliessen) {
        WebseiteDatei.Ziel ziel = datei.vorbereiten(mandant, zugriff, id, benutzer, ip);
        if (ziel instanceof WebseiteDatei.Link link) {
            return new Link(link.url());
        }
        var ort = ((WebseiteDatei.Stream) ziel).ort();
        if (!MEDIENTYP.equals(Ansicht.anzeigbarerTyp(ort.schluessel()).orElse(null))) {
            throw new IllegalStateException("Medientyp der Datei " + id + " nicht in der Liste");
        }
        DateiPlaetze.Platz platz = plaetze.reservieren(benutzer.orElse("unbekannt"));
        boolean uebergeben = false;
        try {
            DateiStrom strom = ablage.oeffnen(mandant, ort.ort(), ort.schluessel(), ort.versionId());
            try {
                datei.protokollieren(id, benutzer, ip);
                StreamLauf lauf = new StreamLauf(strom, platz, leerlauf, zeitlimit, waechter.zeitgeber(),
                        verbindungSchliessen);
                uebergeben = true;
                return new Strom(lauf);
            } catch (RuntimeException e) {
                strom.abbrechen();
                throw e;
            }
        } finally {
            if (!uebergeben) {
                platz.close();
            }
        }
    }
}
