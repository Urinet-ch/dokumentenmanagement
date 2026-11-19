package ch.urinet.archiv.wissen.application;

public interface WissenEinstellungenRepository {
    WissenEinstellungen.Werte lesen();

    void speichern(WissenEinstellungen.Werte werte);
}
