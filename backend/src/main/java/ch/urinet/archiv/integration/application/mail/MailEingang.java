package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.mail.MailKonfigurationPort.Einstellung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import ch.urinet.archiv.verarbeitung.application.InhaberBenachrichtigung;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abruf der Postfächer aller aktiven Mandanten (Spec 02, Req 1.1; design.md «E-Mail-Eingang», «Error Handling»).
 * Ohne eingerichtetes, aktives Postfach oder ohne Datenschlüssel ruft das Archiv für den Mandanten nichts ab und sagt
 * das einmal im Log (INFO, kein Fehler, kein Alarm), bis sich der Grund ändert. Scheitert der Abruf (Verbindung, TLS,
 * Anmeldung), zählt das Archiv die Fehlschläge in Folge; beim {@value #MELDEN_NACH}. benachrichtigt es den Inhaber,
 * einen Auftrag zum Wiederholen gibt es nicht: Der nächste Takt versucht es wieder. Das Passwort entschlüsselt das
 * Archiv erst für den Abruf; es gelangt nie ins Log (Req 16.7).
 */
@ApplicationScoped
public class MailEingang {

    static final int MELDEN_NACH = 6;

    private static final Logger LOG = Logger.getLogger(MailEingang.class);

    private final MandantRegister register;
    private final MandantKontext kontext;
    private final MailKonfigurationPort konfiguration;
    private final Verschluesselung verschluesselung;
    private final PostfachPort postfach;
    private final MailErfassen erfassen;
    private final InhaberBenachrichtigung benachrichtigung;
    private final int maxJeAbruf;
    /** Je Mandant der zuletzt gemeldete Grund, warum nichts abgerufen wird. */
    private final Map<MandantCode, String> gemeldet = new ConcurrentHashMap<>();

    public MailEingang(MandantRegister register, MandantKontext kontext, MailKonfigurationPort konfiguration,
                       Verschluesselung verschluesselung, PostfachPort postfach, MailErfassen erfassen,
                       InhaberBenachrichtigung benachrichtigung,
                       @ConfigProperty(name = "archiv.mail.max-je-abruf") int maxJeAbruf) {
        if (maxJeAbruf < 1) {
            throw new IllegalArgumentException("archiv.mail.max-je-abruf muss mindestens 1 sein");
        }
        this.register = register;
        this.kontext = kontext;
        this.konfiguration = konfiguration;
        this.verschluesselung = verschluesselung;
        this.postfach = postfach;
        this.erfassen = erfassen;
        this.benachrichtigung = benachrichtigung;
        this.maxJeAbruf = maxJeAbruf;
    }

    /** Bezug des Chiffrats in {@code integration_mail.passwort_verschluesselt} (bindet es an den Mandanten). */
    public static String bezug(MandantCode mandant) {
        return "integration_mail.passwort:" + mandant.wert();
    }

    /** Ein Takt über alle aktiven Mandanten; ein Fehler bei einem Mandanten hält die anderen nicht auf. */
    public void abrufen() {
        for (Mandant mandant : register.aktive()) {
            try {
                abrufen(mandant.code());
            } catch (RuntimeException e) {
                LOG.warnf("Mail-Eingang für Mandant %s fehlgeschlagen, ursache=%s", mandant.code().wert(),
                        Ursachen.kette(e));
            }
        }
    }

    /** @return verarbeitete und gescheiterte Nachrichten; leer, wenn nichts abgerufen wurde */
    public Optional<PostfachPort.Abruf> abrufen(MandantCode mandant) {
        Optional<Einstellung> einstellung = konfiguration.laden(mandant).filter(Einstellung::aktiv);
        if (einstellung.isEmpty()) {
            einmalMelden(mandant, "kein aktives Postfach eingerichtet");
            return Optional.empty();
        }
        if (!verschluesselung.verfuegbar()) {
            einmalMelden(mandant, "Datenschlüssel archiv_data_key fehlt");
            return Optional.empty();
        }
        String passwort;
        try {
            passwort = new String(verschluesselung.entschluesseln(einstellung.get().passwortVerschluesselt(),
                    bezug(mandant)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            einmalMelden(mandant, "Passwort passt nicht zum Datenschlüssel; Postfach neu einrichten");
            return Optional.empty();
        }
        gemeldet.remove(mandant);
        try {
            PostfachPort.Abruf abruf = postfach.abrufen(einstellung.get().postfach(), passwort, maxJeAbruf,
                    nachricht -> kontext.mit(mandant, () -> erfassen.erfassen(mandant, nachricht,
                            einstellung.get().zulassung())));
            konfiguration.erfolgreich(mandant);
            if (abruf.verarbeitet() + abruf.gescheitert() > 0) {
                LOG.infof("Mail-Eingang: mandant=%s verarbeitet=%d gescheitert=%d", mandant.wert(),
                        abruf.verarbeitet(), abruf.gescheitert());
            }
            return Optional.of(abruf);
        } catch (PostfachNichtErreichbar e) {
            int folge = konfiguration.fehlschlagZaehlen(mandant);
            LOG.warnf("Mail-Eingang: Abruf für Mandant %s gescheitert (%d in Folge), stelle=%s, ursache=%s",
                    mandant.wert(), folge, e.getMessage(), Ursachen.kette(e.getCause()));
            if (folge == MELDEN_NACH) {
                benachrichtigung.mailAbrufGestoert(mandant, folge);
            }
            return Optional.empty();
        }
    }

    private void einmalMelden(MandantCode mandant, String grund) {
        if (!grund.equals(gemeldet.put(mandant, grund))) {
            LOG.infof("Mail-Eingang für Mandant %s ruht: %s", mandant.wert(), grund);
        }
    }
}
