package ch.urinet.archiv.ki.application.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serverseitiges Register der {@link WerkzeugKontext}e laufender Chat-Anfragen, unter der Lauf- bzw. Gesprächs-ID, die
 * der Agent (Aufgabe 7.2) den Werkzeugen als {@code @ToolMemoryId} mitgibt (Spec 02, Req 5.1). Die Chat-Anfrage hält
 * den Kontext zu Beginn fest und entfernt ihn am Ende; die Werkzeuge laufen oft in Callbacks ohne Request-Kontext
 * (Streaming) und lesen ihn darum hier, nie aus dem Thread. Die {@link #ABLAUF}zeit ist nur die Sicherung, falls das
 * Entfernen ausbleibt: Ein abgelaufener Kontext gilt als fehlend (fail closed).
 */
@ApplicationScoped
public class WerkzeugKontextRegister {

    /**
     * Grosszügig über der längsten Chat-Anfrage (5 Werkzeug-Runden zu höchstens 20 s je Modellaufruf, design.md
     * «Chat-Agent»), damit ein laufender Durchgang seinen Kontext nicht verliert.
     */
    static final Duration ABLAUF = Duration.ofMinutes(10);

    private record Eintrag(WerkzeugKontext kontext, Instant ablauf) {
    }

    private final Map<Object, Eintrag> eintraege = new ConcurrentHashMap<>();
    private final Clock uhr;

    @Inject
    public WerkzeugKontextRegister() {
        this(Clock.systemUTC());
    }

    WerkzeugKontextRegister(Clock uhr) {
        this.uhr = uhr;
    }

    /** Hält den Kontext unter {@code lauf} fest; ein bestehender Eintrag unter derselben ID wird ersetzt. */
    public void festhalten(Object lauf, WerkzeugKontext kontext) {
        Objects.requireNonNull(lauf, "lauf");
        Objects.requireNonNull(kontext, "kontext");
        Instant jetzt = uhr.instant();
        eintraege.values().removeIf(e -> !e.ablauf().isAfter(jetzt));
        eintraege.put(lauf, new Eintrag(kontext, jetzt.plus(ABLAUF)));
    }

    /** Kontext unter {@code lauf}; leer ohne ID, ohne Eintrag oder nach Ablauf. */
    public Optional<WerkzeugKontext> finden(Object lauf) {
        if (lauf == null) {
            return Optional.empty();
        }
        Eintrag eintrag = eintraege.get(lauf);
        if (eintrag == null) {
            return Optional.empty();
        }
        if (!eintrag.ablauf().isAfter(uhr.instant())) {
            eintraege.remove(lauf, eintrag);
            return Optional.empty();
        }
        return Optional.of(eintrag.kontext());
    }

    /** Am Ende der Chat-Anfrage, auch bei Fehler oder Abbruch. */
    public void entfernen(Object lauf) {
        if (lauf != null) {
            eintraege.remove(lauf);
        }
    }
}
