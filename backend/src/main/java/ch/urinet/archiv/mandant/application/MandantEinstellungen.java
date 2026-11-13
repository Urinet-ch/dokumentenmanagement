package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.List;
import java.util.Set;

/**
 * Einstellungen eines Mandanten aus {@code public.mandant} (design.md «Mandanten-Einstellungen»): Geschäftsjahresende
 * (Req 9.6), die eigenen Arten, deren Belegnummer eindeutig sein muss (Req 8.9, 11.3), sowie Name und Kennungen (eigene
 * UID und IBANs aus {@code kennungen}) für den Metadaten-Extraktor (Req 7.5, 7.12).
 */
public interface MandantEinstellungen {

    /** Einstellungen des Mandanten; ein unbekannter Mandant hat Standard-Ende und keine eigenen Arten. */
    Werte lesen(MandantCode mandant);

    /**
     * {@code eigeneArten} leer, wenn {@code eigene_arten} nicht gesetzt ist; {@code name} leer und {@code uid} leer bzw.
     * {@code ibans} ohne Einträge, wenn der Mandant unbekannt ist oder {@code kennungen} fehlt.
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
