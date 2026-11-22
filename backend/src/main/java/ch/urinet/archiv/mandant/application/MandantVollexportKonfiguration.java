package ch.urinet.archiv.mandant.application;

import java.util.List;

/** Konfigurationsabbilder des aktuellen Mandanten für den Vollexport. */
public interface MandantVollexportKonfiguration {

    record Version(int nummer, String json) {
    }

    List<Version> versionen();

    default Version aktuell() {
        List<Version> versionen = versionen();
        return versionen.isEmpty() ? null : versionen.getLast();
    }
}
