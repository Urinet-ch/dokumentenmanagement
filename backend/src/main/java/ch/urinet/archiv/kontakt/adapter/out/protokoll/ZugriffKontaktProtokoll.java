package ch.urinet.archiv.kontakt.adapter.out.protokoll;

import ch.urinet.archiv.kontakt.application.KontaktProtokoll;
import ch.urinet.archiv.kontakt.application.KontaktVerweise;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt die Einträge {@code KONTAKT_*} per Batch ins Zugriffsprotokoll (Req 17.8). Benutzer und Rolle kommen aus
 * der Anmeldung, die IP wie beim Interceptor aus {@code X-Real-Ip}.
 */
@ApplicationScoped
public class ZugriffKontaktProtokoll implements KontaktProtokoll {

    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;

    public ZugriffKontaktProtokoll(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                   Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
    }

    @Override
    public void zusammengefuehrt(UUID quelle, UUID ziel, String grund, KontaktVerweise.Zahlen zahlen,
                                 List<UUID> dokumente) {
        Optional<String> benutzer = benutzer();
        Optional<String> rolle = rolle();
        Optional<IpAdresse> ip = ip();
        List<ZugriffEintrag> eintraege = new ArrayList<>(dokumente.size() + 1);
        ProtokollDetails jeDokument = ProtokollDetails.kontakte(null,
                ProtokollDetails.Kontakte.zusammenfuehren(quelle, ziel));
        for (UUID dokument : dokumente) {
            eintraege.add(new ZugriffEintrag(benutzer, rolle, ProtokollAktion.KONTAKT_ZUSAMMENFUEHREN,
                    Optional.of(dokument), ip, jeDokument));
        }
        eintraege.add(new ZugriffEintrag(benutzer, rolle, ProtokollAktion.KONTAKT_ZUSAMMENFUEHREN, Optional.empty(),
                ip, ProtokollDetails.kontakte(grund, new ProtokollDetails.Kontakte(quelle, ziel, null,
                zahlen.dokumente(), zahlen.verkaufschancen(), zahlen.korrekturbeispiele()))));
        protokoll.schreiben(eintraege);
    }

    @Override
    public void markiert(UUID kontakt, boolean aktiv, String grund) {
        protokoll.schreiben(new ZugriffEintrag(benutzer(), rolle(),
                aktiv ? ProtokollAktion.KONTAKT_AKTIV : ProtokollAktion.KONTAKT_INAKTIV, Optional.empty(), ip(),
                ProtokollDetails.kontakte(grund, ProtokollDetails.Kontakte.markieren(kontakt))));
    }

    private Optional<String> benutzer() {
        return identitaet.isAnonymous() ? Optional.empty() : Optional.ofNullable(identitaet.getPrincipal().getName());
    }

    private Optional<String> rolle() {
        return Rolle.ausRealmRollen(identitaet.getRoles()).map(Rolle::name);
    }

    private Optional<IpAdresse> ip() {
        if (!anfrage.isResolvable()) {
            return Optional.empty();
        }
        try {
            return IpAdresse.aus(anfrage.get().getHeader(REAL_IP));
        } catch (RuntimeException e) {
            // ausserhalb einer HTTP-Anfrage gibt es keine IP
            return Optional.empty();
        }
    }
}
