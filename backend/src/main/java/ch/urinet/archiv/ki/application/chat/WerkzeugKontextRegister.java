package ch.urinet.archiv.ki.application.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    private record Eintrag(WerkzeugKontext kontext, Instant ablauf, Set<UUID> gesehen,
                           Map<UUID, Integer> nummern, List<UUID> nummerierte) {
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
        festhalten(lauf, kontext, List.of(), List.of());
    }

    /** Hält zusätzlich die aus vorherigen Antworten desselben Gesprächs rekonstruierten IDs fest. */
    public void festhalten(Object lauf, WerkzeugKontext kontext, Collection<UUID> gesehene,
                           Collection<UUID> nummerierteTreffer) {
        Objects.requireNonNull(lauf, "lauf");
        Objects.requireNonNull(kontext, "kontext");
        Instant jetzt = uhr.instant();
        eintraege.values().removeIf(e -> !e.ablauf().isAfter(jetzt));
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        ids.addAll(gesehene);
        Eintrag eintrag = new Eintrag(kontext, jetzt.plus(ABLAUF), ids, new LinkedHashMap<>(), new ArrayList<>());
        nummerieren(eintrag, nummerierteTreffer);
        eintraege.put(lauf, eintrag);
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

    /** Hält weitere IDs aus Werkzeugergebnissen dieses Laufs fest; ohne Eintrag wirkungslos. */
    public void gesehen(Object lauf, java.util.Collection<UUID> ids) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag != null) {
            eintrag.gesehen().addAll(ids);
        }
    }

    /** Die in diesem Lauf gelieferten Dokument-IDs; leer ohne Eintrag. */
    public Set<UUID> gesehene(Object lauf) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        return eintrag == null ? Set.of() : Set.copyOf(eintrag.gesehen());
    }

    /** Vergibt stabile Nummern für neue Suchtreffer und gibt deren Nummern zurück. */
    public Map<UUID, Integer> nummerieren(Object lauf, Collection<UUID> ids) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag == null) {
            return Map.of();
        }
        synchronized (eintrag) {
            return nummerieren(eintrag, ids);
        }
    }

    /** Nummerierte Suchtreffer dieses Laufs, älteste zuerst. */
    public List<UUID> nummerierte(Object lauf) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag == null) {
            return List.of();
        }
        synchronized (eintrag) {
            return List.copyOf(eintrag.nummerierte());
        }
    }

    private static Map<UUID, Integer> nummerieren(Eintrag eintrag, Collection<UUID> ids) {
        Map<UUID, Integer> ergebnis = new LinkedHashMap<>();
        for (UUID id : ids) {
            Objects.requireNonNull(id, "id");
            eintrag.gesehen().add(id);
            Integer nummer = eintrag.nummern().get(id);
            if (nummer == null) {
                nummer = eintrag.nummerierte().size() + 1;
                eintrag.nummern().put(id, nummer);
                eintrag.nummerierte().add(id);
            }
            ergebnis.put(id, nummer);
        }
        return Map.copyOf(ergebnis);
    }

    /** Am Ende der Chat-Anfrage, auch bei Fehler oder Abbruch. */
    public void entfernen(Object lauf) {
        if (lauf != null) {
            eintraege.remove(lauf);
        }
    }
}
