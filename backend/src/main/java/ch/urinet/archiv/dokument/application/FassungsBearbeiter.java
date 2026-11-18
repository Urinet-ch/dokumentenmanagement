package ch.urinet.archiv.dokument.application;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

@RequestScoped
public class FassungsBearbeiter {
    private String kennung;

    public void setzen(String kennung) {
        this.kennung = kennung;
    }

    public Optional<String> kennung() {
        return Optional.ofNullable(kennung);
    }
}
