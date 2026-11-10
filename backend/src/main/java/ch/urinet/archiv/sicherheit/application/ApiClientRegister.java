package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.ApiClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Driven Port: Tabelle {@code public.api_client} (Req 2.5, 2.20). Gelöscht wird nie, nur widerrufen. */
public interface ApiClientRegister {

    /** Eintrag samt Hash, auch wenn widerrufen; leer, wenn es die ID nicht gibt. */
    Optional<Zugang> finden(String id);

    void anlegen(ApiClient client, String hash);

    /** Alle Tokens des Mandanten, auch widerrufene, ohne Hash. */
    List<ApiClient> liste(String mandant);

    /** Setzt {@code aktiv = false}, nur im eigenen Mandanten; {@code false}, wenn es die ID dort nicht gibt. */
    boolean widerrufen(String mandant, String id);

    void zuletztSetzen(String id, Instant zeit);

    /** Eintrag mit dem gespeicherten Hash des Geheimnisses; verlässt die Anwendungsschicht nie. */
    record Zugang(ApiClient client, String hash) {

        @Override
        public String toString() {
            return "Zugang[" + client.id() + "]";
        }
    }
}
