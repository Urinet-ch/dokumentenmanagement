package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.dokument.application.DokumentErfassen;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokument.application.UploadAnfrage;
import ch.urinet.archiv.dokument.domain.DateiName;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokument.domain.UploadErgebnis;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.suche.application.DokumentSuche;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Webseiten-Erfassung und referenzgebundener Statuszugriff (Spec 03, Req 10.1–10.14). */
@ApplicationScoped
@Transactional
public class WebseitenErfassung {

    private static final Pattern REFERENZ = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final Pattern BEARBEITER = Pattern.compile("^[A-Za-z0-9._:@-]{1,64}$");

    private final WebseitenErfassungPort port;
    private final DokumentErfassen dokumente;
    private final DokumentLesen lesen;
    private final DokumentSuche suche;
    private final BenutzerVerzeichnis verzeichnis;

    public WebseitenErfassung(WebseitenErfassungPort port, DokumentErfassen dokumente, DokumentLesen lesen,
                             DokumentSuche suche, BenutzerVerzeichnis verzeichnis) {
        this.port = port;
        this.dokumente = dokumente;
        this.lesen = lesen;
        this.suche = suche;
        this.verzeichnis = verzeichnis;
    }

    public Erfassungsergebnis hochladen(String webseite, String referenz, Path datei, String dateiname, String hinweis,
                                        Benutzer benutzer) {
        referenzPruefen(referenz);
        port.sperren(webseite, referenz);
        Optional<WebseitenErfassungPort.Referenz> bisher = port.referenzFinden(webseite, referenz);
        if (bisher.isPresent() && !DokumentStatus.VERWORFEN.name().equals(bisher.get().status())) {
            DokumentErfassen.EmpfangeneDatei empfangen = dokumente.pruefsumme(datei);
            if (!DateiName.aus(dateiname).gueltig() || hinweis != null && DokumentErfassen.hinweisUngueltig(hinweis)) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            if (!bisher.get().sha256().equals(empfangen.sha256())) {
                throw new FachFehler(Fehlercode.REFERENZ_VERGEBEN);
            }
            Optional<Dokument> sichtbar = lesen.sichtbar(benutzer, bisher.get().dokumentId());
            return new Erfassungsergebnis(sichtbar.orElse(null), referenz, false, true);
        }
        if (bisher.isPresent()) {
            port.referenzLoeschen(webseite, referenz);
        }

        UploadAnfrage anfrage = new UploadAnfrage(List.of(new UploadAnfrage.Seite(datei, dateiname, Optional.empty())),
                false, Optional.ofNullable(hinweis), new UploadAnfrage.Erfasser(benutzer.id(),
                Optional.of(benutzer), DokumentErfassen.QUELLE_WEBSEITE));
        UploadErgebnis ergebnis = dokumente.erfassen(anfrage).getFirst();
        if (ergebnis instanceof UploadErgebnis.Abgelehnt abgelehnt) {
            throw new FachFehler(abgelehnt.fehler());
        }
        if (ergebnis instanceof UploadErgebnis.Angenommen angenommen) {
            UUID id = angenommen.id();
            port.referenzSetzen(webseite, referenz, id, port.pruefsumme(id));
            return new Erfassungsergebnis(lesen.lesen(benutzer, id), referenz, false, false);
        }
        UploadErgebnis.Dublette dublette = (UploadErgebnis.Dublette) ergebnis;
        if (dublette.vorhandenId().isEmpty()) {
            return new Erfassungsergebnis(null, referenz, true, false);
        }
        UUID id = dublette.vorhandenId().orElseThrow();
        port.referenzSetzen(webseite, referenz, id, port.pruefsumme(id));
        return new Erfassungsergebnis(lesen.lesen(benutzer, id), referenz, true, false);
    }

    public Dokument status(Benutzer benutzer, String webseite, String referenz) {
        referenzPruefen(referenz);
        WebseitenErfassungPort.Referenz eintrag = port.referenzFinden(webseite, referenz)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        return lesen.lesen(benutzer, eintrag.dokumentId());
    }

    public Dokument detail(Benutzer benutzer, UUID id) {
        return lesen.lesen(benutzer, id);
    }

    public Optional<String> referenz(String webseite, UUID dokumentId) {
        return Optional.ofNullable(port.referenzen(webseite, List.of(dokumentId)).get(dokumentId));
    }

    public Liste liste(Benutzer benutzer, String webseite, String status, String reihe, Integer jahr,
                       Boolean veroeffentlicht, String begriff, int seite, int groesse) {
        if (status != null) {
            try {
                DokumentStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
        }
        Suchanfrage anfrage = new Suchanfrage(begriff, reihe, null, null, null, null, null, null, List.of(), status,
                null, null, null, seite, groesse, jahr, veroeffentlicht);
        Suchergebnis ergebnis = suche.suchen(benutzer, anfrage, false);
        List<UUID> ids = ergebnis.treffer().stream().map(treffer -> treffer.id()).toList();
        Map<UUID, String> referenzen = port.referenzen(webseite, ids);
        List<Dokument> eintraege = ids.stream().map(id -> lesen.sichtbar(benutzer, id)).flatMap(Optional::stream)
                .toList();
        return new Liste(eintraege, referenzen, ergebnis.gesamt());
    }

    public void bearbeiterMelden(String webseite, String kennung, String name, String erfasstVon) {
        if (!bearbeiterGueltig(kennung) || name == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        String anzeigename = name.strip();
        if (anzeigename.isEmpty() || anzeigename.codePointCount(0, anzeigename.length()) > 200
                || anzeigename.codePoints().anyMatch(Character::isISOControl)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        String benutzer = webseite + "/" + kennung;
        verzeichnis.sperren(Optional.empty(), benutzer, BenutzerVerzeichnis.Sperre.AKTION);
        if (gleicherBearbeiter(verzeichnis.letzter(Optional.empty(), benutzer), anzeigename)) {
            return;
        }
        verzeichnis.sperren(Optional.empty(), benutzer, BenutzerVerzeichnis.Sperre.EINFUEGEN);
        if (gleicherBearbeiter(verzeichnis.letzter(Optional.empty(), benutzer), anzeigename)) {
            return;
        }
        verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(benutzer, Optional.of(anzeigename),
                Optional.of(Rolle.GESCHAEFTSSTELLE.name()), Optional.empty(), false, VerzeichnisEintrag.Anlass.AKTION,
                Optional.of(erfasstVon)));
    }

    public static boolean bearbeiterGueltig(String kennung) {
        return kennung != null && BEARBEITER.matcher(kennung).matches();
    }

    private static boolean gleicherBearbeiter(Optional<VerzeichnisEintrag> eintrag, String name) {
        return eintrag.filter(e -> !e.beendet())
                .filter(e -> e.rolle().filter(Rolle.GESCHAEFTSSTELLE.name()::equals).isPresent())
                .filter(e -> e.name().filter(name::equals).isPresent()).isPresent();
    }

    private static void referenzPruefen(String referenz) {
        if (referenz == null || !REFERENZ.matcher(referenz).matches()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }

    public record Erfassungsergebnis(Dokument dokument, String referenz, boolean dublette, boolean idempotent) {
    }

    public record Liste(List<Dokument> dokumente, Map<UUID, String> referenzen, long gesamt) {
        public Liste {
            dokumente = List.copyOf(dokumente);
            referenzen = Map.copyOf(referenzen);
        }
    }
}
