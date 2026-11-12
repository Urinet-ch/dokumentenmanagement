package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Set;

/**
 * Einstellungen eines Mandanten aus {@code public.mandant} (design.md «Mandanten-Einstellungen»): Geschäftsjahresende
 * (Req 9.6) und die eigenen Arten, deren Belegnummer eindeutig sein muss (Req 8.9, 11.3).
 */
public interface MandantEinstellungen {

    /** Einstellungen des Mandanten; ein unbekannter Mandant hat Standard-Ende und keine eigenen Arten. */
    Werte lesen(MandantCode mandant);

    /** {@code eigeneArten} leer, wenn {@code eigene_arten} nicht gesetzt ist. */
    record Werte(Geschaeftsjahresende geschaeftsjahresende, Set<String> eigeneArten) {
        public Werte {
            eigeneArten = Set.copyOf(eigeneArten);
        }
    }
}
