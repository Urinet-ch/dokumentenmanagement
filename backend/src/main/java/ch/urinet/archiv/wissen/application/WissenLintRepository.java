package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Lint-Daten im Schema des laufenden Mandanten. Alle Methoden verlangen eine Transaktion. */
public interface WissenLintRepository {
    void budgetsperre();

    long lintVerbrauch(UUID entwurfId);

    List<Wissensseite> seitenLaden();

    UUID anlegen(String akteur, String modell);

    boolean zeitplanBereits(Instant termin);

    Optional<Lauf> laden(UUID id);

    void fortschritt(UUID id, String schritt, Fortschritt fortschritt, long ein, long aus);

    void abschliessen(UUID id, Fortschritt fortschritt, long ein, long aus, boolean unvollstaendig,
                      String hinweis, boolean hatAenderungen);

    Map<WissensLint.PaarId, Instant> paarZeiten();

    record Lauf(UUID id, UUID entwurfId, String akteur, String modell, String schritt, long ein, long aus,
                Fortschritt fortschritt) {
    }

    record Bezug(UUID seiteId, int version) {
    }

    record AuftragSeite(UUID seiteId, int seiteVersion, List<Bezug> bezuege,
                        WissensStufe kontextstufe, List<String> auftraege) {
        public AuftragSeite {
            bezuege = List.copyOf(bezuege);
            auftraege = List.copyOf(auftraege);
        }
    }

    record Fortschritt(List<WissensLint.Befund> befunde, List<WissensLint.Paar> paare, int paarIndex,
                       List<AuftragSeite> seiten, int seitenIndex, List<WissensLint.PaarId> geprueft,
                       WissensStufe kontextstufe, WissensStufe aufrufStufe, boolean nachfrage, List<String> hinweise,
                       List<WissenIngestPort.NeueAenderung> aenderungen) {
        public Fortschritt {
            befunde = befunde == null ? List.of() : List.copyOf(befunde);
            paare = paare == null ? List.of() : List.copyOf(paare);
            seiten = seiten == null ? List.of() : List.copyOf(seiten);
            geprueft = geprueft == null ? List.of() : List.copyOf(geprueft);
            hinweise = hinweise == null ? List.of() : List.copyOf(hinweise);
            aenderungen = aenderungen == null ? List.of() : List.copyOf(aenderungen);
        }

        public static Fortschritt leer() {
            return new Fortschritt(List.of(), List.of(), 0, List.of(), 0, List.of(), WissensStufe.NORMAL,
                    null, false, List.of(), List.of());
        }
    }
}
