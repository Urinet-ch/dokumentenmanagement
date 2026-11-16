package ch.urinet.archiv.integration.domain.webseite;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Was eine lesende Anfrage der Webseite sehen darf (Spec 03, design.md «Webseiten-Schnittstelle: Anmeldung, Schlüssel,
 * Stufen»): die Webseite des Schlüssels, dessen höchste Stufe, die von der Webseite gemeldete Stufe und der Client-Hash
 * (nur Chat, Req 6.4). Die wirksame Stufe ist nie höher als die des Schlüssels (Kappung, Req 5.3, 3.7); gemeldet über
 * der Stufe des Schlüssels lehnt schon der Anmeldefilter mit 403 ab.
 *
 * @param webseite        Kennung der Webseite aus dem Schlüssel
 * @param hoechsteStufe   höchste Stufe des Schlüssels
 * @param gemeldeteStufe  Stufe der Anfrage (Header {@code X-Archiv-Zugriff}; fehlt er: {@code OEFFENTLICH})
 * @param clientHash      SHA-256 (hex) von IP und Tages-Salz, von der Webseite berechnet; nie die IP selbst
 */
public record Lesezugriff(String webseite, Zugriffsstufe hoechsteStufe, Zugriffsstufe gemeldeteStufe,
                          Optional<String> clientHash) {

    public Lesezugriff {
        Objects.requireNonNull(webseite, "webseite");
        Objects.requireNonNull(hoechsteStufe, "hoechsteStufe");
        Objects.requireNonNull(gemeldeteStufe, "gemeldeteStufe");
        Objects.requireNonNull(clientHash, "clientHash");
    }

    /** {@code min(gemeldete Stufe, Stufe des Schlüssels)}. */
    public Zugriffsstufe wirksameStufe() {
        return Zugriffsstufe.kleinere(gemeldeteStufe, hoechsteStufe);
    }

    /** Codes für {@code zugriff = ANY(:stufen)}: nie ein Wert aus der Anfrage, immer aus der wirksamen Stufe. */
    public List<String> stufen() {
        return wirksameStufe().bisUndMit();
    }

    /** Ob ein Dokument dieser Stufe für die Anfrage sichtbar ist; sonst gilt es als nicht vorhanden (404, Req 5.5). */
    public boolean sichtbar(Zugriffsstufe dokument) {
        return !dokument.hoeherAls(wirksameStufe());
    }
}
