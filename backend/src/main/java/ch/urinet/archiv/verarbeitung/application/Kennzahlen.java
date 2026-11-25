package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.ki.application.KiBeispielZaehler;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenZaehler;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Kennzahlen je aktiven Mandanten (Req 16.4): offene, laufende und fehlgeschlagene Aufträge, Dokumente je Status ausser
 * den Endzuständen, die Grösse des Eingangskorbs und die Anzahl KI-Beispiele. Nur Zähler, keine Inhalte. Die
 * Korrekturquote je Feld wird beim Bestätigen eines KI-Vorschlags fortgeschrieben (Aufgabe 3.3).
 */
@ApplicationScoped
public class Kennzahlen {

    /** Status der Aufträge mit eigener Kennzahl; {@code FERTIG} wächst nur und sagt nichts über den Zustand. */
    public static final List<String> AUFTRAG_STATUS = List.of("OFFEN", "LAEUFT", "FEHLER");

    /** Status der Dokumente mit eigener Kennzahl, ohne die Endzustände {@code ARCHIVIERT} und {@code VERWORFEN}. */
    public static final List<String> DOKUMENT_STATUS = List.of("NEU", "VERARBEITUNG", "EINGANGSKORB", "ARCHIVIERUNG",
            "ARCHIVIERUNG_FEHLER", "FEHLER");

    private static final Logger LOG = Logger.getLogger(Kennzahlen.class);

    private final MandantRegister register;
    private final AuftragZaehler auftraege;
    private final DokumentUeberwachung dokumente;
    private final KiBeispielZaehler kiBeispiele;
    private final OeffentlicheFragenZaehler oeffentlicheFragen;

    public Kennzahlen(MandantRegister register, AuftragZaehler auftraege, DokumentUeberwachung dokumente,
                      KiBeispielZaehler kiBeispiele, OeffentlicheFragenZaehler oeffentlicheFragen) {
        this.register = register;
        this.auftraege = auftraege;
        this.dokumente = dokumente;
        this.kiBeispiele = kiBeispiele;
        this.oeffentlicheFragen = oeffentlicheFragen;
    }

    /** Stand eines Mandanten; KI-Beispiel-Zähler ist bei isoliertem Zählfehler leer. */
    public record Stand(MandantCode mandant, Map<String, Long> auftraege, Map<String, Long> dokumente,
                        long eingangskorb, OptionalLong kiBeispiele, OptionalLong oeffentlicheFragenOhneBeleg) {
    }

    /** Erhebt den Stand je aktiven Mandanten; Fehler bei übrigen Zählern lassen den Mandanten aus, jeweils mit WARN. */
    public List<Stand> erheben() {
        List<Stand> stand = new ArrayList<>();
        for (Mandant mandant : register.aktive()) {
            try {
                Map<String, Long> auftrag = vollstaendig(AUFTRAG_STATUS, auftraege.anzahlJeStatus(mandant.code()));
                Map<String, Long> dokument = vollstaendig(DOKUMENT_STATUS,
                        dokumente.anzahlJeStatus(mandant.code()));
                long korb = DokumentStatus.EINGANGSKORB_STATUS.stream()
                        .mapToLong(s -> dokument.getOrDefault(s.name(), 0L)).sum();
                OptionalLong kiBeispielAnzahl;
                try {
                    kiBeispielAnzahl = OptionalLong.of(kiBeispiele.anzahl(mandant.code()));
                } catch (RuntimeException e) {
                    LOG.warnf("KI-Beispiel-Kennzahl für Mandant %s nicht erhoben, ursache=%s", mandant.code().wert(),
                            Ursachen.kette(e));
                    kiBeispielAnzahl = OptionalLong.empty();
                }
                OptionalLong fragenOhneBeleg;
                try {
                    fragenOhneBeleg = OptionalLong.of(oeffentlicheFragen.anzahlOhneBeleg(mandant.code()));
                } catch (RuntimeException e) {
                    LOG.warnf("Kennzahl öffentliche Fragen ohne Beleg für Mandant %s nicht erhoben, ursache=%s",
                            mandant.code().wert(), Ursachen.kette(e));
                    fragenOhneBeleg = OptionalLong.empty();
                }
                stand.add(new Stand(mandant.code(), auftrag, dokument, korb, kiBeispielAnzahl, fragenOhneBeleg));
            } catch (RuntimeException e) {
                LOG.warnf("Kennzahlen für Mandant %s nicht erhoben, ursache=%s", mandant.code().wert(),
                        Ursachen.kette(e));
            }
        }
        return stand;
    }

    private static Map<String, Long> vollstaendig(List<String> status, Map<String, Long> anzahl) {
        Map<String, Long> ergebnis = new LinkedHashMap<>();
        status.forEach(s -> ergebnis.put(s, anzahl.getOrDefault(s, 0L)));
        return ergebnis;
    }
}
