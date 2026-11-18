package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Benutzer;

import java.util.UUID;

@FunctionalInterface
public interface WissensDokumentSichtbarkeit {

    boolean sichtbar(Benutzer benutzer, UUID dokumentId);
}
