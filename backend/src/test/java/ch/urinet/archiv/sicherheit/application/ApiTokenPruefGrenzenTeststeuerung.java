package ch.urinet.archiv.sicherheit.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApiTokenPruefGrenzenTeststeuerung {

    @Inject
    ApiTokenPruefGrenzen grenzen;

    public void szenarioZuruecksetzen() {
        grenzen.fuerSzenarioZuruecksetzen();
    }
}
