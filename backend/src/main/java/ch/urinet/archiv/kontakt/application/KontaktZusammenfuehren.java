package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.dokument.application.DokumentAenderungPort;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Führt einen Kontakt (Quelle) in einen aktiven Kontakt (Ziel) zusammen (Req 17.7–17.10; design.md «Kontakte ansehen
 * und pflegen», Schritte 1–8), alles in <b>einer</b> Transaktion: Scheitert ein Teil, auch das Protokoll, ändert sich
 * nichts (Req 10.8). Recht A12 prüft der {@code ZugangFilter} vorher. Das Log nennt nur IDs und Zahlen.
 */
@ApplicationScoped
public class KontaktZusammenfuehren {

    /** Präfix des Grunds in {@code dokument_version} (Req 17.8). */
    public static final String GRUND_PRAEFIX = "Kontakt zusammengeführt: ";

    private static final Logger LOG = Logger.getLogger(KontaktZusammenfuehren.class);

    private final KontaktRegister register;
    private final KontaktVerweise verweise;
    private final DokumentAenderungPort dokumente;
    private final KontaktProtokoll protokoll;

    public KontaktZusammenfuehren(KontaktRegister register, KontaktVerweise verweise, DokumentAenderungPort dokumente,
                                  KontaktProtokoll protokoll) {
        this.register = register;
        this.verweise = verweise;
        this.dokumente = dokumente;
        this.protokoll = protokoll;
    }

    /** Ergebnis: die Quelle danach (für das neue ETag) und die Zahlen der umgehängten Verweise. */
    public record Ergebnis(Kontakt quelle, UUID ziel, KontaktVerweise.Zahlen zahlen) {
    }

    /**
     * Prüfreihenfolge nach dem Sperren: Quelle unbekannt → 404; Ziel gleich Quelle oder unbekannt → 400; Quelle oder
     * Ziel inaktiv → 409; Version der Quelle veraltet → 412. Das Format von {@code If-Match} (428/412) und den Körper
     * (400) prüft die REST-Schicht vorher.
     *
     * @param benutzer Benutzer-ID für {@code dokument_version}
     * @param grund    bereinigt, 1–1000 Zeichen
     */
    @Transactional
    public Ergebnis zusammenfuehren(String benutzer, UUID quelleId, int version, UUID zielId, String grund) {
        Map<UUID, Kontakt> gesperrt = register.sperren(quelleId.equals(zielId) ? List.of(quelleId)
                        : List.of(quelleId, zielId)).stream()
                .collect(Collectors.toMap(Kontakt::id, Function.identity()));
        Kontakt quelle = gesperrt.get(quelleId);
        if (quelle == null) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Kontakt ziel = gesperrt.get(zielId);
        if (quelleId.equals(zielId) || ziel == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (!quelle.aktiv() || !ziel.aktiv()) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (quelle.version() != version) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        List<UUID> umgehaengt = dokumente.partnerUmhaengen(quelleId, zielId, benutzer, GRUND_PRAEFIX + grund);
        KontaktVerweise.Zahlen uebrige = verweise.umhaengen(quelleId, zielId);
        KontaktVerweise.Zahlen zahlen = new KontaktVerweise.Zahlen(umgehaengt.size(), uebrige.verkaufschancen(),
                uebrige.korrekturbeispiele());
        Kontakt danach = register.statusSetzen(quelleId, false, zielId);
        protokoll.zusammengefuehrt(quelleId, zielId, grund, zahlen, umgehaengt);
        LOG.infof("Kontakt %s in %s zusammengeführt: dokumente=%d verkaufschancen=%d korrekturbeispiele=%d", quelleId,
                zielId, zahlen.dokumente(), zahlen.verkaufschancen(), zahlen.korrekturbeispiele());
        return new Ergebnis(danach, zielId, zahlen);
    }
}
