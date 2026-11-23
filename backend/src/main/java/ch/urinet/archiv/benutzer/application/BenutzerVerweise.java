package ch.urinet.archiv.benutzer.application;

public interface BenutzerVerweise {
    record Loeschvorschau(long protokolleintraege, long dokumente) {
    }

    Loeschvorschau zaehlen(String benutzer);
}
