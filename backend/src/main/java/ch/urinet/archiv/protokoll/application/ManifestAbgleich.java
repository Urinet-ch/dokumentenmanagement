package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.MehrdeutigeVersion;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ManifestEintrag;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.verarbeitung.application.ManifestVergleich;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manifest-Vergleich des Prüflaufs (Req 15.5; Aufgabe 5.3, design.md «Umsetzung (Manifest-Vergleich, Req 15.5)»):
 * Das zuständige Manifest kommt aus der Tabelle {@code manifest}, seine Einträge aus der ältesten Version von
 * {@code manifest/<datum>.json}, gelesen wie im Prüfwerkzeug ({@link ManifestLesen}).
 * <p>
 * Unbrauchbar ist ein Manifest, wenn die JSON fehlt, ihre älteste Version nicht eindeutig ist, sie nicht kanonisch oder
 * ungültig ist, ihre SHA-256 nicht {@code manifest.sha256} entspricht, Mandant, Datum oder Fenster nicht zur Tabelle
 * passen oder ein Eintrag doppelt vorkommt. Signatur, Kette und Zeitstempel prüft der Prüflauf nicht: Das tut
 * {@code archiv manifest pruefen} (Req 15.12); die SHA-256 aus der Tabelle bindet die gelesenen Einträge an das
 * festgehaltene, signierte Manifest. Weitere Versionen unter dem Schlüssel machen es nicht unbrauchbar (gelesen wird
 * die älteste, wie im Prüfwerkzeug); sie meldet das Prüfwerkzeug als Fehler.
 */
@ApplicationScoped
public class ManifestAbgleich implements ManifestVergleich {

    private final AblagePort ablage;
    private final JsonLeser json;
    private final ManifestSpeichern speichern;
    private final MandantKontext kontext;

    public ManifestAbgleich(AblagePort ablage, JsonLeser json, ManifestSpeichern speichern, MandantKontext kontext) {
        this.ablage = ablage;
        this.json = json;
        this.speichern = speichern;
        this.kontext = kontext;
    }

    @Override
    public Optional<Fenster> zustaendig(MandantCode mandant, Instant archiviert) {
        return kontext.mit(mandant, () -> speichern.zustaendig(archiviert))
                .map(z -> new Fenster(z.datum(), z.von(), z.bis(), z.sha256()));
    }

    @Override
    public Manifest lesen(MandantCode mandant, Fenster fenster) {
        Optional<ManifestDatei> datei;
        try {
            datei = ablage.lesenManifest(mandant, fenster.datum(), ManifestTeil.JSON);
        } catch (MehrdeutigeVersion e) {
            return new Unbrauchbar(fenster.datum(), "älteste Version nicht eindeutig");
        }
        if (datei.isEmpty()) {
            return new Unbrauchbar(fenster.datum(), "JSON unter manifest/ fehlt");
        }
        return auswerten(json, mandant, fenster, datei.get().inhalt());
    }

    /** Prüft die gelesenen Bytes gegen die Tabelle und liefert die Einträge (ohne Ablage, für Tests). */
    static Manifest auswerten(JsonLeser json, MandantCode mandant, Fenster fenster, byte[] bytes) {
        ManifestLesen.Lesung lesung = ManifestLesen.lesen(json, bytes);
        if (!lesung.lesbar()) {
            return new Unbrauchbar(fenster.datum(), lesung.fehler());
        }
        if (!Sha256.hexVon(bytes).equalsIgnoreCase(fenster.sha256())) {
            return new Unbrauchbar(fenster.datum(), "SHA-256 passt nicht zur Tabelle manifest");
        }
        ManifestInhalt inhalt = lesung.inhalt();
        if (!inhalt.mandant().equals(mandant.wert()) || !inhalt.datum().equals(fenster.datum())
                || !Objects.equals(inhalt.von(), fenster.von().orElse(null)) || !inhalt.bis().equals(fenster.bis())) {
            return new Unbrauchbar(fenster.datum(), "Mandant, Datum oder Fenster passt nicht zur Tabelle manifest");
        }
        Map<Eintrag, String> eintraege = new HashMap<>();
        for (ManifestEintrag e : inhalt.eintraege()) {
            if (eintraege.putIfAbsent(new Eintrag(e.dokument(), e.variante(), e.nr()), e.sha256()) != null) {
                return new Unbrauchbar(fenster.datum(), "Eintrag doppelt");
            }
        }
        return new Eintraege(fenster.datum(), eintraege);
    }
}
