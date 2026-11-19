package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;

import java.util.List;

public interface KonfigurationAenderungsProtokoll {

    /**
     * Schreibt den Eintrag {@code KONFIGURATION_AENDERN}. Ohne Anmeldung (Admin-Kommando, Seed bei der Provisionierung)
     * steht {@code wer} (z. B. {@code betreiber:<name>}, {@code system:seed}) als Benutzer im Protokoll, sonst die
     * angemeldete Identität.
     */
    void schreiben(int version, KonfigurationQuelle quelle, String wer, String grund,
                   List<Konfiguration.Aenderung> aenderungen);
}
