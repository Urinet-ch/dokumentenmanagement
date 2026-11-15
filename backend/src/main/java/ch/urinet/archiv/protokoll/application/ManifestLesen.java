package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Liest ein abgelegtes Manifest ({@code manifest/<datum>.json}) zurück: nur in kanonischer Form nach RFC 8785 und nur
 * gültig nach {@link ManifestInhalt#aus}. Gemeinsam für das Prüfwerkzeug ({@link ManifestPruefen}) und den
 * Manifest-Vergleich im Prüflauf ({@link ManifestAbgleich}), damit beide dasselbe als lesbar gelten lassen.
 */
final class ManifestLesen {

    private ManifestLesen() {
    }

    /** Inhalt oder Grund, warum das Manifest nicht lesbar ist; die Gründe nennen keine Inhalte. */
    static Lesung lesen(JsonLeser json, byte[] bytes) {
        try {
            Map<String, Object> baum = json.objekt(bytes);
            if (!Arrays.equals(Jcs.utf8(baum), bytes)) {
                return new Lesung(null, "Manifest ist nicht in kanonischer Form (RFC 8785)");
            }
            return new Lesung(ManifestInhalt.aus(baum), null);
        } catch (IllegalArgumentException e) {
            return new Lesung(null, "Manifest ist ungültig: " + e.getMessage());
        }
    }

    /** Genau eines ist gesetzt: {@code inhalt} oder {@code fehler}. */
    record Lesung(ManifestInhalt inhalt, String fehler) {
        Lesung {
            if ((inhalt == null) == (fehler == null)) {
                throw new IllegalArgumentException("Genau eines von inhalt und fehler");
            }
        }

        boolean lesbar() {
            return Objects.nonNull(inhalt);
        }
    }
}
