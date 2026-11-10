package ch.urinet.archiv.dokumentart.application;

import ch.urinet.archiv.dokumentart.domain.Dokumentart;

import java.util.List;
import java.util.Optional;

/** Dokumentarten des Mandanten der laufenden Anfrage (Schema {@code m_<code>}, ADR-002). */
public interface DokumentartRepository {

    /** Aktive und inaktive Arten, nach Code sortiert. */
    List<Dokumentart> alle();

    Optional<Dokumentart> finden(String code);

    boolean existiert(String code);

    void anlegen(Dokumentart art);

    void speichern(Dokumentart art);

    void loeschen(String code);
}
