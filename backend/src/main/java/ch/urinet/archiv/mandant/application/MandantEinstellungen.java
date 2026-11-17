package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;
import java.util.Set;

/**
 * Geschäftsjahresende, Name und Kennungen aus {@code public.mandant} sowie selbst ausgestellte Dokumentarten aus dem
 * Mandanten-Schema (Spec 05, Req 2.2).
 */
public interface MandantEinstellungen {

    /** Einstellungen und selbst ausgestellte Arten des aktuellen Mandanten. */
    Werte lesen(MandantCode mandant);

    /**
     * {@code eigeneArten} wird aus {@code dokumentart.selbst_ausgestellt} gebildet; Name und Kennungen sind leer,
     * wenn der Mandant unbekannt ist oder {@code kennungen} fehlt.
     */
    record Werte(Geschaeftsjahresende geschaeftsjahresende, Set<String> eigeneArten, String name, String uid,
                 List<String> ibans) {
        public Werte {
            eigeneArten = Set.copyOf(eigeneArten);
            name = name == null ? "" : name;
            uid = uid == null ? "" : uid;
            ibans = ibans == null ? List.of() : List.copyOf(ibans);
        }

        public Werte(Geschaeftsjahresende geschaeftsjahresende, Set<String> eigeneArten) {
            this(geschaeftsjahresende, eigeneArten, "", "", List.of());
        }
    }
}
