package ch.urinet.archiv.benutzer.application;

public interface BenutzerVerweise {
    record Anzahl(long protokolleintraege, long dokumente) {
    }

    Anzahl zaehlen(String benutzer);
}
