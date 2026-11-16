package ch.urinet.archiv.ki.application.oeffentlich;

import jakarta.enterprise.context.ApplicationScoped;

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
 * Register der {@link OeffentlicherKontext}e laufender öffentlicher Fragen unter der Lauf-ID, die der Agent den
 * Werkzeugen als {@code @ToolMemoryId} mitgibt (wie {@code WerkzeugKontextRegister} des internen Chats, aber ohne
 * Benutzer und Rolle). Je Frage ein Eintrag mit den Dokumenten, die {@code dokumente_suchen} in dieser Frage geliefert
 * hat: Nur sie nimmt {@code dokument_zeigen} an (nummeriert oder mit der ID). Die {@link #ABLAUF}zeit ist nur die
 * Sicherung, falls das Entfernen ausbleibt; ein abgelaufener Kontext gilt als fehlend (fail closed).
 */
@ApplicationScoped
public class OeffentlicherKontextRegister {

    static final Duration ABLAUF = Duration.ofMinutes(10);

    private record Eintrag(OeffentlicherKontext kontext, Instant ablauf, Set<UUID> gesehen,
                           Map<UUID, Integer> nummern, List<UUID> nummerierte) {
    }

    private final Map<Object, Eintrag> eintraege = new ConcurrentHashMap<>();
    private final Clock uhr;

    public OeffentlicherKontextRegister() {
        this(Clock.systemUTC());
    }

    OeffentlicherKontextRegister(Clock uhr) {
        this.uhr = uhr;
    }

    public void festhalten(Object lauf, OeffentlicherKontext kontext) {
        Objects.requireNonNull(lauf, "lauf");
        Objects.requireNonNull(kontext, "kontext");
        Instant jetzt = uhr.instant();
        eintraege.values().removeIf(e -> !e.ablauf().isAfter(jetzt));
        eintraege.put(lauf, new Eintrag(kontext, jetzt.plus(ABLAUF), ConcurrentHashMap.newKeySet(),
                new LinkedHashMap<>(), new ArrayList<>()));
    }

    /** Kontext unter {@code lauf}; leer ohne ID, ohne Eintrag oder nach Ablauf. */
    public Optional<OeffentlicherKontext> finden(Object lauf) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag == null) {
            return Optional.empty();
        }
        if (!eintrag.ablauf().isAfter(uhr.instant())) {
            eintraege.remove(lauf, eintrag);
            return Optional.empty();
        }
        return Optional.of(eintrag.kontext());
    }

    /** Vergibt stabile Nummern für neue Suchtreffer dieser Frage und gibt die Nummern der übergebenen IDs zurück. */
    public Map<UUID, Integer> nummerieren(Object lauf, Collection<UUID> ids) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag == null) {
            return Map.of();
        }
        synchronized (eintrag) {
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
    }

    /** Die in dieser Frage gelieferten Dokument-IDs; leer ohne Eintrag. */
    public Set<UUID> gesehene(Object lauf) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        return eintrag == null ? Set.of() : Set.copyOf(eintrag.gesehen());
    }

    /** Nummerierte Suchtreffer dieser Frage, älteste zuerst. */
    public List<UUID> nummerierte(Object lauf) {
        Eintrag eintrag = lauf == null ? null : eintraege.get(lauf);
        if (eintrag == null) {
            return List.of();
        }
        synchronized (eintrag) {
            return List.copyOf(eintrag.nummerierte());
        }
    }

    /** Am Ende der Frage, auch bei Fehler oder Abbruch. */
    public void entfernen(Object lauf) {
        if (lauf != null) {
            eintraege.remove(lauf);
        }
    }
}
