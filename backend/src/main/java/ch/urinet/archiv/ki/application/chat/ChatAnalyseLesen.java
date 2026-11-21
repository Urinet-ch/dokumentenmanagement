package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;

@ApplicationScoped
public class ChatAnalyseLesen {
    private static final Set<String> ERLAUBTE_WERKZEUGE = Set.of(
            "dokumente_suchen", "dokument_zeigen", "wissen_index", "wissen_seite_zeigen");
    private final ChatSpeicher speicher;

    public ChatAnalyseLesen(ChatSpeicher speicher) {
        this.speicher = speicher;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public Ergebnis lesen(String nachrichtId, String benutzer) {
        long id;
        if (nachrichtId == null || !nachrichtId.matches("[1-9][0-9]{0,18}")) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        try {
            id = Long.parseLong(nachrichtId);
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (id < 1) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ChatAnalyseDaten daten = speicher.analyseDaten(id, benutzer)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        return new Ergebnis(daten.inhalt(), daten.quellen(), kontextstufe(daten));
    }

    static String kontextstufe(ChatAnalyseDaten daten) {
        if (!daten.vollstaendig() || !"OK".equals(daten.status())) {
            throw new FachFehler(Fehlercode.ANALYSE_NICHT_MOEGLICH);
        }
        String stufe = "NORMAL";
        for (ChatAnalyseDaten.Werkzeug werkzeug : daten.werkzeuge()) {
            if (!werkzeugZulaessig(werkzeug)) {
                throw new FachFehler(Fehlercode.ANALYSE_NICHT_MOEGLICH);
            }
            if ("INTERN".equals(werkzeug.kontextstufe())) {
                stufe = "INTERN";
            }
        }
        return stufe;
    }

    public static boolean werkzeugZulaessig(ChatAnalyseDaten.Werkzeug werkzeug) {
        return ERLAUBTE_WERKZEUGE.contains(werkzeug.name())
                && ("NORMAL".equals(werkzeug.kontextstufe()) || "INTERN".equals(werkzeug.kontextstufe()));
    }

    public record Ergebnis(String inhalt, java.util.List<ChatAnalyseDaten.Quelle> quellen, String kontextstufe) {
    }
}
