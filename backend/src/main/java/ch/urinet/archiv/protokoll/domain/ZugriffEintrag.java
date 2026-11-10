package ch.urinet.archiv.protokoll.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Eine Zeile des Zugriffsprotokolls (Req 10.2): Benutzer-ID (Claim {@code sub} bzw. {@code token:<id>} bei API-Tokens,
 * nie der Benutzername), Rolle als Name der Archiv-Rolle, Aktion, Dokument-ID, IP aus {@code X-Real-Ip} und
 * {@link ProtokollDetails}. Die Zeit setzt die Datenbank.
 */
public record ZugriffEintrag(Optional<String> benutzer, Optional<String> rolle, ProtokollAktion aktion,
                             Optional<UUID> dokumentId, Optional<IpAdresse> ip, ProtokollDetails details) {

    public ZugriffEintrag {
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(aktion, "aktion");
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(details, "details");
        benutzer = benutzer.filter(b -> !b.isBlank());
    }
}
