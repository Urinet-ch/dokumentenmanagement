package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.ablage.domain.Ansicht;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.dokument.application.DokumentDatei;
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
 * Datei-Link der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.2, Req 5.4): eine vorsignierte URL für die PDF/A-Fassung
 * (Versions-ID wie Spec 01, 14.6), höchstens {@code archiv.ablage.url-gueltigkeit} (5 Minuten) gültig, nur für
 * Dokumente der Stufe {@code oeffentlich}; jedes andere Dokument ist 404 {@code NICHT_GEFUNDEN} (der Stream der Stufen
 * {@code mitglieder} und {@code vorstand} ist Aufgabe 11.8). Die Sicht ist der feste Filter mit der einen Stufe
 * {@code oeffentlich}: Sie liegt in jeder gemeldeten Stufe, ein höheres Dokument bleibt unsichtbar.
 * <p>
 * Protokoll {@code HERUNTERLADEN} in derselben Transaktion nach dem Signieren, mit Schlüssel-Kennung, Dokument-ID und
 * gekürzter IP ({@link IpAdresse#gekuerzt()}), ohne Personendaten und ohne Client-Hash; scheitert der Eintrag, gibt es
 * keine Adresse.
 */
@ApplicationScoped
@Transactional
public class WebseiteDatei {

    private static final Sichtregel NUR_OEFFENTLICH = Sichtregel.fuerWebseite(
            new Sichtregel.Webseite(List.of(Zugriffsstufe.OEFFENTLICH.code()), null, null));

    private final DokumentDatei datei;
    private final ZugriffProtokoll protokoll;

    public WebseiteDatei(DokumentDatei datei, ZugriffProtokoll protokoll) {
        this.datei = datei;
        this.protokoll = protokoll;
    }

    /**
     * @param benutzer Kennung des Schlüssels für das Protokoll
     * @param ip       Adresse aus {@code X-Real-Ip}; das Protokoll speichert nur die gekürzte
     */
    public VorsignierteUrl link(MandantCode mandant, UUID id, Optional<String> benutzer,
                                Optional<IpAdresse> ip) {
        VorsignierteUrl url = datei.herunterladen(NUR_OEFFENTLICH, mandant, id, DateiRolle.PDFA, 0, Ansicht.DOWNLOAD);
        protokoll.schreiben(new ZugriffEintrag(benutzer, Optional.empty(), ProtokollAktion.HERUNTERLADEN,
                Optional.of(id), ip.map(IpAdresse::gekuerzt), ProtokollDetails.KEINE));
        return url;
    }
}
