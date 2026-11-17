package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.ablage.domain.Ansicht;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.dokument.application.DokumentDatei;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Die kurzen Transaktionen der Dateiauslieferung der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2 und 11.8, Req 5.4,
 * 13.1, 13.2, 13.6, 13.8). Jede öffentliche Methode ist eine eigene Transaktion, die mit der Methode endet; den Ablauf
 * und den Stream steuert {@link DateiAuslieferung} ohne Transaktion.
 * <p>
 * {@link #vorbereiten}: Das Dokument muss für die Anfrage sichtbar sein (fester Filter mit den Stufen des
 * {@link Lesezugriff}, sonst 404 {@code NICHT_GEFUNDEN}, auch bei zu hoher Stufe). Gehört es zur Stufe
 * {@code oeffentlich}, entsteht eine vorsignierte URL für die PDF/A-Fassung mit gespeicherter Versions-ID, höchstens
 * {@code archiv.ablage.url-gueltigkeit} (5 Minuten) gültig, und das Protokoll {@code HERUNTERLADEN} wird in derselben
 * Transaktion geschrieben (scheitert der Eintrag, gibt es keine Adresse). Gehört es zu {@code mitglieder} oder
 * {@code vorstand}, liefert die Methode nur den Ort der Version: Für diese Dokumente gibt es nie eine Speicher-URL.
 * <p>
 * {@link #protokollieren}: Eintrag {@code HERUNTERLADEN} mit Schlüssel-Kennung ({@code token:<id>}), Dokument-ID und
 * gekürzter IP ({@link IpAdresse#gekuerzt()}), ohne Rolle, ohne Client-Hash und ohne Header-Werte.
 */
@ApplicationScoped
@Transactional
public class WebseiteDatei {

    private static final Sichtregel NUR_OEFFENTLICH = Sichtregel.fuerWebseite(
            new Sichtregel.Webseite(List.of(Zugriffsstufe.OEFFENTLICH.code()), null, null));

    /** Was die Anfrage bekommt: eine Adresse (Stufe {@code oeffentlich}) oder den Ort für den Stream. */
    public sealed interface Ziel {
    }

    /** Stufe {@code oeffentlich}: vorsignierte URL; das Protokoll ist schon geschrieben. */
    public record Link(VorsignierteUrl url) implements Ziel {
    }

    /** Stufe {@code mitglieder} oder {@code vorstand}: die Version, die zu streamen ist; das Protokoll folgt. */
    public record Stream(DokumentDatei.Dateiort ort) implements Ziel {
    }

    private final DokumentDatei datei;
    private final WebseiteLesen lesen;
    private final ZugriffProtokoll protokoll;

    @jakarta.inject.Inject
    ch.urinet.archiv.dokument.application.DokumentLesen dokumente;

    public WebseiteDatei(DokumentDatei datei, WebseiteLesen lesen, ZugriffProtokoll protokoll) {
        this.datei = datei;
        this.lesen = lesen;
        this.protokoll = protokoll;
    }

    /**
     * @param benutzer Kennung des Schlüssels für das Protokoll
     * @param ip       Adresse aus {@code X-Real-Ip}; das Protokoll speichert nur die gekürzte
     */
    public Ziel vorbereiten(MandantCode mandant, Lesezugriff zugriff, UUID id, Optional<String> benutzer,
                            Optional<IpAdresse> ip) {
        Zugriffsstufe stufe = Zugriffsstufe.ausCode(lesen.details(zugriff, id).zugriff())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (stufe == Zugriffsstufe.OEFFENTLICH) {
            // die Sicht der Adresse (fester Filter mit der Stufe oeffentlich) ist enger als die der Anfrage
            VorsignierteUrl url = datei.herunterladen(NUR_OEFFENTLICH, mandant, id, DateiRolle.PDFA, 0,
                    Ansicht.DOWNLOAD);
            protokollieren(id, benutzer, ip);
            return new Link(url);
        }
        Sichtregel sicht = Sichtregel.fuerWebseite(new Sichtregel.Webseite(zugriff.stufen(), null, null));
        return new Stream(datei.finden(sicht, id, DateiRolle.PDFA, 0));
    }

    /** Schreibt den Eintrag {@code HERUNTERLADEN} und endet mit der Transaktion (Req 13.6, 13.8). */
    public void protokollieren(UUID id, Optional<String> benutzer, Optional<IpAdresse> ip) {
        protokoll.schreiben(new ZugriffEintrag(benutzer, Optional.empty(), ProtokollAktion.HERUNTERLADEN,
                Optional.of(id), ip.map(IpAdresse::gekuerzt), ProtokollDetails.KEINE));
    }

    public DokumentDatei.Dateiort erfassungVorbereiten(ch.urinet.archiv.sicherheit.domain.Benutzer benutzer, UUID id) {
        var dokument = dokumente.lesen(benutzer, id);
        if (dokument.status() == ch.urinet.archiv.dokument.domain.DokumentStatus.VERWORFEN) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        try {
            return datei.finden(Sichtregel.fuer(benutzer), id, DateiRolle.PDFA, 0);
        } catch (FachFehler e) {
            if (e.code() != Fehlercode.NICHT_GEFUNDEN) {
                throw e;
            }
            return datei.finden(Sichtregel.fuer(benutzer), id, DateiRolle.ORIGINAL, 1);
        }
    }

    public void erfassungProtokollieren(UUID id, ch.urinet.archiv.sicherheit.domain.Benutzer benutzer,
                                        Optional<IpAdresse> ip, String bearbeiter) {
        protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer.id()), Optional.of(benutzer.rolle().name()),
                ProtokollAktion.HERUNTERLADEN, Optional.of(id), ip.map(IpAdresse::gekuerzt),
                ProtokollDetails.KEINE, Optional.of(bearbeiter)));
    }
}
