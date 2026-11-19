package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Vergaberegeln;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class BenutzerRollenSchritte {

    private Benutzer akteur;
    private Benutzer ziel;
    private String rollenCode;
    private Set<String> mandantenRollen;
    private Vergaberegeln.Pruefung pruefung;
    private boolean letzterInhaberEntfernt;
    private Rolle zielRolle;
    private boolean zielAktiv;
    private int aktiveInhaber;
    private Rolle pruefRolle;
    private Aktion pruefAktion;

    @Dann("gelten für A20 bis A23 diese Rechte:")
    public void rechte(DataTable tabelle) {
        List<Map<String, String>> zeilen = tabelle.asMaps();
        assertThat(zeilen).extracting(zeile -> zeile.get("Nr.")).containsExactly("A20", "A21", "A22", "A23");
        for (Map<String, String> zeile : zeilen) {
            Aktion aktion = Aktion.valueOf(zeile.get("Nr."));
            for (Rolle rolle : Rolle.values()) {
                Ergebnis erwartet = switch (zeile.get(rolle.code())) {
                    case "erlaubt" -> Ergebnis.ERLAUBT;
                    case "403" -> Ergebnis.VERBOTEN;
                    default -> throw new IllegalArgumentException("Unbekanntes Matrix-Ergebnis");
                };
                assertThat(Rechtematrix.ergebnis(rolle, aktion)).as("%s %s", aktion, rolle).isEqualTo(erwartet);
            }
        }
    }

    @Angenommen("die Rolle «{word}» fragt Aktion «{word}» an")
    public void zugriffPruefen(String rolle, String aktion) {
        pruefRolle = rolle(rolle);
        pruefAktion = Aktion.valueOf(aktion);
    }

    @Dann("wird die Anfrage mit HTTP-Status {int} verweigert")
    public void zugriffVerweigert(int status) {
        assertThat(Rechtematrix.ergebnis(pruefRolle, pruefAktion)).isEqualTo(switch (status) {
            case 403 -> Ergebnis.VERBOTEN;
            default -> throw new IllegalArgumentException("Unbekannter Ablehnungsstatus");
        });
    }

    @Angenommen("«{word}» will einer anderen Person die Rolle «{word}» zuweisen; vergebbar sind «{word}»")
    public void rollenvergabe(String akteurRolle, String neueRolle, String vergebbareRollen) {
        akteur = new Benutzer("akteur", rolle(akteurRolle));
        ziel = new Benutzer("ziel", Rolle.MITARBEITER);
        rollenCode = neueRolle;
        mandantenRollen = rollenSet(vergebbareRollen);
    }

    @Angenommen("«{word}» will sich selbst die Rolle «{word}» zuweisen; vergebbar sind «{word}»")
    public void eigeneRolle(String akteurRolle, String neueRolle, String vergebbareRollen) {
        akteur = new Benutzer("akteur", rolle(akteurRolle));
        ziel = akteur;
        rollenCode = neueRolle;
        mandantenRollen = rollenSet(vergebbareRollen);
    }

    @Wenn("die Rollenvergabe geprüft wird")
    public void rollenvergabePruefen() {
        pruefung = Vergaberegeln.pruefeRollenvergabe(akteur, ziel, rollenCode, mandantenRollen);
    }

    @Dann("lautet das Ergebnis «{word}»")
    public void ergebnis(String erwartet) {
        assertThat(pruefung).isEqualTo(Vergaberegeln.Pruefung.valueOf(erwartet));
    }

    @Angenommen("die Zielrolle «{word}» gehört zu einem {word}en Benutzer und es gibt {int} aktive Inhaber")
    public void zielrolle(String rolle, String status, int anzahlAktiveInhaber) {
        zielRolle = rolle(rolle);
        zielAktiv = status.equals("aktiv");
        aktiveInhaber = anzahlAktiveInhaber;
    }

    @Wenn("die Zielrolle entfernt wird")
    public void zielrolleEntfernen() {
        letzterInhaberEntfernt = Vergaberegeln.entferntLetztenAktivenInhaber(
                zielRolle, zielAktiv, aktiveInhaber);
    }

    @Dann("wird das Entfernen als «{word}» beurteilt")
    public void letztesInhaberErgebnis(String erwartet) {
        assertThat(letzterInhaberEntfernt).isEqualTo(Boolean.parseBoolean(erwartet));
    }

    private static Rolle rolle(String code) {
        return Rolle.valueOf(code.toUpperCase(Locale.ROOT));
    }

    private static Set<String> rollenSet(String codes) {
        return new LinkedHashSet<>(Arrays.asList(codes.split(",")));
    }
}
