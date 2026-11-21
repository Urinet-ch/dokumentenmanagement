package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class BenutzerLesen {
    private static final Pattern KENNUNG = Pattern.compile("(?!\\.+$)[A-Za-z0-9._~-]{1,200}");
    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final BenutzerAktivitaet aktivitaet;
    private final MandantAufloesung mandant;

    public BenutzerLesen(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status,
                         BenutzerAktivitaet aktivitaet, MandantAufloesung mandant) {
        this.identitaet = identitaet;
        this.status = status;
        this.aktivitaet = aktivitaet;
        this.mandant = mandant;
    }

    public record BenutzerAnsicht(String id, String benutzername, String vorname, String nachname, String email,
                          Set<Rolle> rollen, boolean rollenkonflikt, BenutzerStatus status,
                          boolean totp, boolean google, Instant einladungOffenSeit,
                          BenutzerAktivitaet.LetzteAktion letzteAktion, String etag) {
        @Override
        public String toString() {
            return "Ansicht[geschützt]";
        }
    }

    public List<BenutzerAnsicht> suchen(String suche, int seite, int groesse) {
        try {
            IdentitaetsDienst.seitenanfang(suche, seite, groesse);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        String code = mandant.pruefen().wert();
        return identitaet.benutzerSuchen(code, suche, seite, groesse).stream().map(this::darstellen).toList();
    }

    public BenutzerAnsicht lesen(String id) {
        String code = mandant.pruefen().wert();
        if (id == null || !KENNUNG.matcher(id).matches()) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return darstellen(identitaet.benutzerLesen(code, id));
    }

    private BenutzerAnsicht darstellen(IdentitaetsDienst.Benutzer benutzer) {
        BenutzerStatus zustand = status.lesen(benutzer.id()).map(BenutzerStatusSpeicher.Eintrag::status)
                .orElseGet(() -> !benutzer.aktiv() ? BenutzerStatus.GESPERRT
                        : benutzer.aktionen().isEmpty() ? BenutzerStatus.AKTIV : BenutzerStatus.EINGELADEN);
        BenutzerAktivitaet.Angaben angaben = aktivitaet.lesen(benutzer.id());
        return new BenutzerAnsicht(benutzer.id(), benutzer.benutzername(), benutzer.vorname(), benutzer.nachname(),
                benutzer.email(), benutzer.rollen(), benutzer.rollen().size() != 1, zustand, benutzer.totp(),
                benutzer.google(), zustand == BenutzerStatus.EINGELADEN ? angaben.einladungSeit() : null,
                angaben.letzteAktion(), BenutzerEtag.berechnen(benutzer, zustand));
    }
}
