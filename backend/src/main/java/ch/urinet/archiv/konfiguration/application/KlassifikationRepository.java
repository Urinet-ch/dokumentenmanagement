package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.ArtKlassifikation;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;

import java.util.List;
import java.util.Optional;

public interface KlassifikationRepository {

    List<Klassifikation> alle();

    Optional<Klassifikation> finden(String code);

    void anlegen(Klassifikation klassifikation);

    void speichern(Klassifikation klassifikation);

    void loeschen(String code);

    boolean inGebrauch(String code);

    boolean wertInGebrauch(String code, String wert);

    long dokumentAnzahl(String code);

    long wertDokumentAnzahl(String code, String wert);

    boolean mehrfachWirdVerletzt(String code);

    List<ArtKlassifikation> zuordnungen(String art);

    boolean zugeordnet(String art, String klassifikation);

    void zuordnen(String art, ArtKlassifikation zuordnung);

    void zuordnungSpeichern(String art, ArtKlassifikation zuordnung);

    void zuordnungEntfernen(String art, String klassifikation);
}
