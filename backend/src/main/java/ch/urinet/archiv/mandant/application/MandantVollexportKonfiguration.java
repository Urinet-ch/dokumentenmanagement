package ch.urinet.archiv.mandant.application;

import java.io.IOException;
import java.io.OutputStream;

/** Konfigurationsabbilder des aktuellen Mandanten für den Vollexport. */
public interface MandantVollexportKonfiguration {

    final class ZuGross extends RuntimeException {
        public ZuGross() {
            super("Die aktuelle Konfigurationsdatei überschreitet 1 MiB.", null, false, false);
        }
    }

    record Version(int nummer, String json) {
    }

    @FunctionalInterface
    interface Inhalt {
        void schreiben(OutputStream ziel) throws IOException;
    }

    @FunctionalInterface
    interface Versionsausgabe {
        void schreiben(int nummer, Inhalt inhalt) throws IOException;
    }

    Version aktuell();

    void versionen(Versionsausgabe ausgabe) throws IOException;

    static MandantVollexportKonfiguration leer() {
        return new MandantVollexportKonfiguration() {
            @Override
            public Version aktuell() {
                return null;
            }

            @Override
            public void versionen(Versionsausgabe ausgabe) {
            }
        };
    }
}
