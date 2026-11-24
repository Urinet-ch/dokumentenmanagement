package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;

/** Liefert das aktuelle Konfigurationsabbild des Mandanten im laufenden Kontext. */
public interface KonfigurationLesen {

    Konfiguration aktuell();

    default Aufbewahrung aufbewahrung() {
        var einstellungen = aktuell().einstellungen();
        return new Aufbewahrung(einstellungen.chat().aufbewahrungMonate(),
                einstellungen.webseitenChat().aufbewahrungTage());
    }

    record Aufbewahrung(Integer chatMonate, Integer webseitenChatTage) {
    }
}
