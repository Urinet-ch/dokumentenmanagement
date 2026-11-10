package ch.urinet.archiv.sicherheit.adapter.out.protokoll;

import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.application.VerweigerungMelder;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt jede Verweigerung als {@code VERWEIGERT} mit Fehlercode ins Zugriffsprotokoll, in einer eigenen Transaktion
 * (REQUIRES_NEW), damit der Eintrag den Abbruch der Anfrage übersteht (Req 10.7). {@code details.grund} ist die Zeile
 * der Rechtematrix, falls bekannt. Ohne gültigen Mandanten ({@code MANDANT_FEHLT}) gibt es kein Mandanten-Schema: dann
 * nur ein Log-Eintrag mit IDs. Scheitert das Schreiben, bleibt die Antwort 403 bzw. 404; geloggt wird nur der
 * Fehlercode.
 */
@ApplicationScoped
public class ProtokollVerweigerungMelder implements VerweigerungMelder {

    private static final Logger LOG = Logger.getLogger(ProtokollVerweigerungMelder.class);
    private static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final Instance<HttpServerRequest> anfrage;

    public ProtokollVerweigerungMelder(ZugriffProtokoll protokoll, Instance<HttpServerRequest> anfrage) {
        this.protokoll = protokoll;
        this.anfrage = anfrage;
    }

    @Override
    public void verweigert(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                           Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        String fehlercode = grund.fehlercode().name();
        if (grund == Verweigerung.Grund.MANDANT_FEHLT) {
            LOG.infof("Zugriff verweigert ohne Mandanten: fehlercode=%s benutzer=%s", fehlercode,
                    benutzerId.orElse("-"));
            return;
        }
        ZugriffEintrag eintrag = new ZugriffEintrag(benutzerId, rolle.map(Rolle::name), ProtokollAktion.VERWEIGERT,
                dokumentId, ip(), new ProtokollDetails(aktion.map(Aktion::name).orElse(null), null, fehlercode));
        try {
            QuarkusTransaction.requiringNew().run(() -> protokoll.schreiben(eintrag));
        } catch (RuntimeException e) {
            LOG.errorf("Verweigerung nicht protokolliert: fehlercode=%s", fehlercode);
        }
    }

    private Optional<IpAdresse> ip() {
        try {
            return anfrage.isResolvable() ? IpAdresse.aus(anfrage.get().getHeader(REAL_IP)) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
