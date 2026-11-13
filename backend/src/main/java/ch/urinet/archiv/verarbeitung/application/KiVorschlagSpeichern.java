package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort;
import ch.urinet.archiv.dokument.application.DokumentVerarbeitungPort.KiStand;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Vorschlag;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.application.PartnerAbgleich;
import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.PartnerAngaben;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kurze Transaktionen des {@link KiVorschlagHandler} (design.md «Pflichten der Handler», (2)): Jede schreibende Methode
 * sichert zuerst den Anspruch auf den Auftrag; ohne Anspruch schreibt sie nichts. Der Aufruf des Sprachmodells liegt
 * ausserhalb jeder Transaktion.
 */
@ApplicationScoped
public class KiVorschlagSpeichern {

    private final DokumentVerarbeitungPort dokumente;
    private final AuftragService auftraege;
    private final PartnerAbgleich abgleich;
    private final KontaktRegister kontakte;

    public KiVorschlagSpeichern(DokumentVerarbeitungPort dokumente, AuftragService auftraege,
                                PartnerAbgleich abgleich, KontaktRegister kontakte) {
        this.dokumente = dokumente;
        this.auftraege = auftraege;
        this.abgleich = abgleich;
        this.kontakte = kontakte;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<KiStand> laden(UUID id) {
        return dokumente.kiStandLaden(id);
    }

    /**
     * Speichert den Vorschlag und setzt {@code EINGANGSKORB} (Req 7.1); verknüpft den Partner nur bei einem eindeutigen
     * Treffer des Abgleichs (Req 7.4). Hat sich das Dokument seit dem Laden geändert (Version, Bestätigung), bleibt es
     * unverändert; steht es dann noch in {@code VERARBEITUNG}, geht es ohne Vorschlag weiter.
     *
     * @return {@code true}, wenn der Vorschlag gespeichert wurde
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean vorschlagSpeichern(Auftrag auftrag, KiStand stand, Vorschlag vorschlag) {
        auftraege.anspruchSichern(auftrag);
        AbgleichErgebnis ergebnis = abgleich.abgleichen(new PartnerAngaben(vorschlag.partnerName(),
                vorschlag.partnerEmail(), vorschlag.partnerUid(), vorschlag.partnerIban()));
        UUID partnerId = null;
        String partnerName = null;
        if (ergebnis instanceof AbgleichErgebnis.Verknuepft v) {
            Optional<Kontakt> kontakt = kontakte.finden(v.kontaktId());
            if (kontakt.isPresent()) {
                partnerId = kontakt.get().id();
                partnerName = kontakt.get().name();
            }
        }
        if (dokumente.vorschlagSpeichern(stand.id(), stand.version(), werte(vorschlag), sicherheit(vorschlag),
                partnerId, partnerName)) {
            return true;
        }
        dokumente.ohneVorschlagWeiter(stand.id());
        return false;
    }

    /** Req 7.3: ohne Vorschlag weiter in den Eingangskorb, nur aus {@code VERARBEITUNG}. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean ohneVorschlagWeiter(Auftrag auftrag, UUID id) {
        auftraege.anspruchSichern(auftrag);
        return dokumente.ohneVorschlagWeiter(id);
    }

    /** Für den Hook nach dem letzten Fehlversuch: der Auftrag ist schon {@code FEHLER}, darum ohne Anspruch. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean nachFehlversuchWeiter(UUID id) {
        return dokumente.ohneVorschlagWeiter(id);
    }

    /** Inhalt von {@code ki_vorschlag}: die Felder mit Wert, Namen wie im Antwortschema (liest der Eingangskorb). */
    static Map<String, Object> werte(Vorschlag v) {
        Map<String, Object> werte = new LinkedHashMap<>();
        setzen(werte, Feld.ART.schluessel(), v.art());
        setzen(werte, Feld.TITEL.schluessel(), v.titel());
        setzen(werte, Feld.DATUM.schluessel(), v.datum() == null ? null : v.datum().toString());
        setzen(werte, Feld.PARTNER.schluessel(), v.partnerName());
        setzen(werte, "partnerUid", v.partnerUid());
        setzen(werte, "partnerIban", v.partnerIban());
        setzen(werte, "partnerEmail", v.partnerEmail());
        setzen(werte, Feld.BELEGNUMMER.schluessel(), v.belegnummer());
        setzen(werte, Feld.RICHTUNG.schluessel(), v.richtung());
        setzen(werte, Feld.BETRAG.schluessel(), v.betrag());
        setzen(werte, Feld.WAEHRUNG.schluessel(), v.waehrung());
        setzen(werte, Feld.FAELLIGKEIT.schluessel(), v.faelligkeit() == null ? null : v.faelligkeit().toString());
        setzen(werte, Feld.QR_REFERENZ.schluessel(), v.qrReferenz());
        setzen(werte, Feld.VERTRAULICHKEIT.schluessel(), v.vertraulichkeit());
        if (!v.tags().isEmpty()) {
            werte.put("tags", v.tags());
        }
        return werte;
    }

    /** Inhalt von {@code ki_sicherheit}: die Sicherheit je vorgeschlagenem Feld (Req 7.6). */
    static Map<String, BigDecimal> sicherheit(Vorschlag v) {
        Map<String, Object> werte = werte(v);
        Map<String, BigDecimal> sicherheit = new LinkedHashMap<>();
        for (Feld feld : Feld.values()) {
            if (werte.containsKey(feld.schluessel())) {
                sicherheit.put(feld.schluessel(), v.sicherheit(feld));
            }
        }
        return sicherheit;
    }

    private static void setzen(Map<String, Object> werte, String schluessel, Object wert) {
        if (wert != null && !(wert instanceof String s && s.isBlank())) {
            werte.put(schluessel, wert);
        }
    }
}
