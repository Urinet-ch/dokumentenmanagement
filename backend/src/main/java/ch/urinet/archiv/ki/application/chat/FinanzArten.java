package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Welche Arten für {@code kennzahl} und {@code offene_posten} zählen: Liste je Mandant und Richtung unter
 * {@code archiv.mandant.<code>.kennzahl.einnahmen-arten} und {@code ...ausgaben-arten} (Entscheid Fabian 14.11.2026, E1;
 * Übergang, wird durch Spec 05 ersetzt). Ein Eintrag ist {@code ART} oder {@code ART:feld}; mit {@code :feld} zählt ein
 * Beleg nur, wenn das Zusatzfeld eine Monatsangabe {@code JJJJ-MM} trägt (Lohnabrechnung ja, Lohnausweis mit
 * {@code JJJJ} nein). Ohne Eintrag zählt keine Art (fail closed), ein ungültiger Eintrag bricht ab.
 */
@ApplicationScoped
public class FinanzArten {

    private static final Pattern ART = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern FELD = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final Config konfiguration;

    @Inject
    public FinanzArten(Config konfiguration) {
        this.konfiguration = konfiguration;
    }

    public List<WerkzeugDaten.ArtRegel> arten(MandantCode mandant, WerkzeugDaten.Richtung richtung) {
        String schluessel = "archiv.mandant." + mandant.wert() + ".kennzahl."
                + (richtung == WerkzeugDaten.Richtung.EINNAHME ? "einnahmen-arten" : "ausgaben-arten");
        List<WerkzeugDaten.ArtRegel> arten = new ArrayList<>();
        for (String eintrag : konfiguration.getOptionalValues(schluessel, String.class).orElse(List.of())) {
            String[] teile = eintrag.strip().split(":", -1);
            boolean gueltig = teile.length <= 2 && ART.matcher(teile[0]).matches()
                    && (teile.length == 1 || FELD.matcher(teile[1]).matches());
            if (!gueltig) {
                throw new IllegalStateException(schluessel + " ist ungültig");
            }
            arten.add(new WerkzeugDaten.ArtRegel(teile[0], teile.length == 2 ? teile[1] : null));
        }
        return List.copyOf(arten);
    }
}
