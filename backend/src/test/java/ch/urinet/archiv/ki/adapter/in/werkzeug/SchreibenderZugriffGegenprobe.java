package ch.urinet.archiv.ki.adapter.in.werkzeug;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.dokument.application.NeuesDokument;

/** Gegenprobe zu {@code WerkzeugeNurLesendTest}: ein Werkzeug, das ein Dokument anlegen würde. Nur Testcode. */
public class SchreibenderZugriffGegenprobe {

    private final DokumentRepository dokumente;

    SchreibenderZugriffGegenprobe(DokumentRepository dokumente) {
        this.dokumente = dokumente;
    }

    void dokumentLoeschenOderAnlegen(NeuesDokument dokument) {
        dokumente.anlegen(dokument);
    }

    /** Ein Werkzeug, das am Port vorbei den Adapter eines anderen Fachbereichs benutzt. */
    public static class FremderAdapter {
        void benutzen(ch.urinet.archiv.dokument.adapter.out.persistence.DokumentVersionEintrag eintrag) {
            eintrag.hashCode();
        }
    }

    public static class WissensRepositoryZugriff {
        void benutzen(ch.urinet.archiv.wissen.application.WissenLesenRepository repository) {
            repository.index(null, null);
        }
    }

    public static class WissensKonfigurationZugriff {
        void benutzen(ch.urinet.archiv.wissen.application.WissenKonfiguration konfiguration) {
            konfiguration.modell();
        }
    }
}
