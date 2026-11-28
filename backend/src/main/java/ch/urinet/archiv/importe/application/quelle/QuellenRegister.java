package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import ch.urinet.archiv.importe.domain.quelle.QuellenStatus;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Driven Port für Quellenregister und Quellläufe (Spec 09, Requirement 10) im Schema des aktuellen Mandanten; alle
 * Aufrufe laufen in einer Transaktion.
 */
public interface QuellenRegister {

    List<Quelle> liste();

    /** Mit {@code sperren} gesperrt bis zum Ende der Transaktion (Start eines Laufs). */
    Optional<Quelle> finde(String code, boolean sperren);

    /** @return {@code false}, wenn der Code schon vergeben ist */
    boolean anlegen(UUID id, String code, String name, QuellenTyp typ, String konfiguration,
                    Optional<String> bemerkung);

    /** Felder, die {@code null} sind, bleiben; {@code bemerkung} leer löscht sie. Leer bei veralteter Version. */
    Optional<Quelle> aendern(String code, int version, QuellenStatus status, String bemerkung, String konfiguration);

    void geheimnisSetzen(String code, byte[] chiffrat);

    Optional<QuellLauf> laufenderLauf(UUID quelleId);

    /** Legt einen laufenden Lauf an und vermerkt ihn als letzten Lauf der Quelle. */
    QuellLauf laufAnlegen(UUID quelleId, LaufArt art, Optional<String> benutzer);

    Optional<QuellLauf> lauf(UUID id, boolean sperren);

    List<QuellLauf> laeufe(UUID quelleId, int hoechstens);

    /** Beendet einen laufenden Lauf; {@code zaehler} ersetzt die Zähler, sonst bleiben sie. */
    QuellLauf laufBeenden(UUID id, QuellLaufStatus status, Optional<Zaehler> zaehler, int zusaetzlichUebersprungen,
                          List<String> meldungen);

    /** Zählt Uploads eines Laufs hoch: gefunden um die Summe, dazu neu, Dublette nach Prüfsumme und Fehler. */
    void verbuchen(UUID laufId, int neu, int duplettePruefsumme, int fehler);

    /** Verweist ein Dokument auf seinen Quelllauf (10.4), mit {@code quelle} auch die Quelle des Dokuments. */
    void dokumentZuordnen(UUID dokumentId, UUID laufId, Optional<String> quelle);

    /** Prüfsummen, die im Mandanten zu einem nicht verworfenen Dokument gehören (10.6). */
    Set<String> vorhandenePruefsummen(Collection<String> sha256);
}
