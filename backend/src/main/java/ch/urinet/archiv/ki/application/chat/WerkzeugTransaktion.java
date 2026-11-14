package ch.urinet.archiv.ki.application.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/**
 * Eigene, kurze Transaktion je Werkzeugaufruf (Spec 02, Req 5.1), immer neu, damit ein Werkzeug nie in der Transaktion
 * eines Aufrufers liest. Als eigene Bean, weil die Anwendungsdienste sie für Lesen und Protokoll getrennt brauchen
 * (z. B. {@code VERWEIGERT} nach dem Zurückrollen) und ein Aufruf innerhalb derselben Bean keinen Interceptor auslöst.
 * Eine Ausnahme rollt zurück und läuft weiter zum Aufrufer.
 */
@ApplicationScoped
public class WerkzeugTransaktion {

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public <T> T neu(Supplier<T> arbeit) {
        return arbeit.get();
    }
}
