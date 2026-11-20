package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.Wissensseite;
import ch.urinet.archiv.wissen.domain.WissensStufe;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FreigabeRepository {
    void manuellenEntwurfAnlegen(UUID entwurfId, String benutzer);

    Optional<Aenderung> sperren(UUID entwurfId, int nummer);

    Optional<Integer> seitenVersionSperren(UUID seiteId);

    void alsVeraltetMarkieren(long aenderungId, int etag);

    void neuerzeugungStarten(UUID entwurfId);

    Optional<NeuerzeugungsDaten> neuerzeugungsDaten(UUID entwurfId);

    void neuerzeugungsModellSetzen(UUID entwurfId, String modell);

    boolean neuererNeuerzeugungsauftrag(UUID entwurfId, String mandant, long auftragId);

    Vorschlag vorschlagLaden(Aenderung aenderung);

    void fassungSpeichern(UUID entwurfId, Aenderung aenderung, Wissensseite fassung, WissensStufe mindeststufe,
                          WissensStufe wirksameStufe, String erzeugtVon, String benutzer, String grund,
                          Set<String> links, boolean widerspruchAufgeloest);

    void verwerfen(long aenderungId, int etag, String benutzer, String grund);

    boolean entwurfAbschliessen(UUID entwurfId);

    void abschlussProtokollieren(UUID entwurfId);

    record Aenderung(long id, UUID seiteId, String entwurfStatus, String anlass, String status, int etag,
                     String aktion, String pfad, Integer basisVersion) {
    }

    record NeuerzeugungsDaten(String anlass, int tokensEin, int tokensAus) {
    }

    record Vorschlag(Wissensseite fassung, Set<String> entfernteAussagen, String kontextstufe,
                     UUID wissensquelleId, String modell, String erstelltVon) {
        public Vorschlag {
            entfernteAussagen = Set.copyOf(entfernteAussagen);
        }
    }
}
