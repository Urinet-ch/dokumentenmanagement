package ch.urinet.archiv.protokoll.adapter.in.rest;

import ch.urinet.archiv.protokoll.application.Protokolliert;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.UriInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * Schreibt für {@link Protokolliert} den Eintrag vor der Aktion und in derselben Transaktion (Req 10.2, 10.8): Läuft
 * schon eine ({@code @Transactional} liegt aussen, Priorität +200), tritt er ihr bei, sonst umschliesst er die Aktion mit
 * einer eigenen. Scheitert der Eintrag oder später der Commit, ist beides zurückgerollt und die Antwort 500.
 * <p>
 * Die Erklärung ist die Bindung, mit der CDI den Interceptor aufruft (an der Methode, sonst an der Bean-Klasse, auch für
 * geerbte Methoden) – dieselbe Stelle, die {@code EndpunktWaechterTest} prüft. Die Dokument-ID kommt wie im
 * {@code ZugangFilter} aus dem Pfad-Parameter {@code id} der Anfrage, unabhängig davon, wie die Methode ihn annimmt.
 */
@Protokolliert(aktion = ProtokollAktion.ANZEIGEN)
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 300)
public class ZugriffProtokollInterceptor {

    static final String DOKUMENT_ID = "id";
    static final String REAL_IP = "X-Real-Ip";

    private final ZugriffProtokoll protokoll;
    private final SecurityIdentity identitaet;
    private final Instance<HttpServerRequest> anfrage;
    private final Instance<UriInfo> pfad;

    public ZugriffProtokollInterceptor(ZugriffProtokoll protokoll, SecurityIdentity identitaet,
                                       Instance<HttpServerRequest> anfrage, Instance<UriInfo> pfad) {
        this.protokoll = protokoll;
        this.identitaet = identitaet;
        this.anfrage = anfrage;
        this.pfad = pfad;
    }

    @AroundInvoke
    Object protokollieren(InvocationContext aufruf) throws Exception {
        Protokolliert erklaerung = aufruf.getInterceptorBinding(Protokolliert.class);
        ZugriffEintrag eintrag = new ZugriffEintrag(benutzer(), rolle(), erklaerung.aktion(), dokumentId(),
                ip(), ProtokollDetails.KEINE);
        return QuarkusTransaction.joiningExisting().call(() -> {
            protokoll.schreiben(eintrag);
            return aufruf.proceed();
        });
    }

    private Optional<String> benutzer() {
        return identitaet.isAnonymous() ? Optional.empty() : Optional.ofNullable(identitaet.getPrincipal().getName());
    }

    private Optional<String> rolle() {
        return Rolle.ausRealmRollen(identitaet.getRoles()).map(Rolle::name);
    }

    private Optional<UUID> dokumentId() {
        if (!pfad.isResolvable()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(pfad.get().getPathParameters().getFirst(DOKUMENT_ID))
                    .flatMap(ZugriffProtokollInterceptor::uuid);
        } catch (RuntimeException e) {
            // ausserhalb einer HTTP-Anfrage (z. B. Worker) gibt es keinen Pfad
            return Optional.empty();
        }
    }

    private static Optional<UUID> uuid(String wert) {
        try {
            return Optional.of(UUID.fromString(wert));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<IpAdresse> ip() {
        if (!anfrage.isResolvable()) {
            return Optional.empty();
        }
        try {
            return IpAdresse.aus(anfrage.get().getHeader(REAL_IP));
        } catch (RuntimeException e) {
            // ausserhalb einer HTTP-Anfrage (z. B. Worker) gibt es keine IP
            return Optional.empty();
        }
    }
}
