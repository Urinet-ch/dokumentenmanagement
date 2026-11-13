package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;
import ch.urinet.archiv.integration.domain.mail.Zulassungsliste;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Postfach eines Mandanten einrichten oder abschalten (Spec 02, Aufgabe 6.2; Admin-Kommando {@code mail}): Host, Port,
 * Benutzer und Zulassungsliste im Klartext, das Passwort nur verschlüsselt mit dem Datenschlüssel (design.md
 * «Verschluesselung»). Ohne Datenschlüssel lehnt das Archiv ab, statt das Passwort ungeschützt zu speichern.
 */
@ApplicationScoped
public class MailEinrichten {

    private final MandantRegister register;
    private final MailKonfigurationPort konfiguration;
    private final Verschluesselung verschluesselung;

    public MailEinrichten(MandantRegister register, MailKonfigurationPort konfiguration,
                          Verschluesselung verschluesselung) {
        this.register = register;
        this.konfiguration = konfiguration;
        this.verschluesselung = verschluesselung;
    }

    /** Fachlicher Fehler beim Einrichten; die Meldung nennt nie Passwort oder Adressen. */
    public static class Fehler extends RuntimeException {
        public Fehler(String meldung) {
            super(meldung);
        }
    }

    public void einrichten(String code, String host, int port, String benutzer, String passwort,
                           List<String> absender) {
        MandantCode mandant = aktiverMandant(code);
        if (!verschluesselung.verfuegbar()) {
            throw new Fehler("Datenschlüssel archiv_data_key fehlt; das Passwort wird nur verschlüsselt gespeichert");
        }
        if (passwort == null || passwort.isEmpty()) {
            throw new Fehler("Passwort fehlt (erste Zeile der Standardeingabe)");
        }
        Postfach postfach;
        Zulassungsliste zulassung;
        try {
            postfach = new Postfach(host, port, benutzer);
            zulassung = Zulassungsliste.aus(absender);
        } catch (IllegalArgumentException e) {
            throw new Fehler(e.getMessage());
        }
        if (zulassung.adressen().isEmpty()) {
            throw new Fehler("Zulassungsliste ist leer");
        }
        konfiguration.speichern(mandant, postfach, verschluesselung.verschluesseln(
                passwort.getBytes(StandardCharsets.UTF_8), MailEingang.bezug(mandant)), zulassung);
    }

    /** @return {@code true}, wenn es ein Postfach gab */
    public boolean abschalten(String code) {
        return konfiguration.abschalten(aktiverMandant(code));
    }

    private MandantCode aktiverMandant(String code) {
        MandantCode mandant;
        try {
            mandant = new MandantCode(code);
        } catch (IllegalArgumentException e) {
            throw new Fehler("Ungültiger Mandant-Code");
        }
        if (register.aktive().stream().noneMatch(m -> m.code().equals(mandant))) {
            throw new Fehler("Mandant " + mandant.wert() + " ist nicht aktiv");
        }
        return mandant;
    }
}
