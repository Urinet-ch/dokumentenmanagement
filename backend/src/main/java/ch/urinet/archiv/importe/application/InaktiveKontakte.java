package ch.urinet.archiv.importe.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Welche dieser Kontakte inaktiv sind (Req 17.13: kein Ziel eines Dubletten-Entscheids); unbekannte fehlen. */
@FunctionalInterface
public interface InaktiveKontakte {

    Set<UUID> unter(Collection<UUID> ids);
}
