package ch.urinet.archiv.importe.application.quelle;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import ch.urinet.archiv.importe.domain.quelle.QuellenStatus;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Quellenregister, Quellläufe und Prüfsummen-Abfrage (Spec 09, Requirement 10). Uploads zählt
 * {@link QuellLaufZuordnung}; der Trockenlauf legt nichts an, seine Zähler meldet das Werkzeug beim Abschluss (10.5).
 */
@ApplicationScoped
public class QuellenVerwaltung {

    /** Höchstens so viele Prüfsummen je Abfrage (10.6). */
    public static final int PRUEFSUMMEN_MAX = 1000;
    static final int LAEUFE_MAX = 100;
    static final int GEHEIMNIS_MAX = 1000;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final QuellenRegister register;
    private final Verschluesselung verschluesselung;
    private final MandantAufloesung aufloesung;

    public QuellenVerwaltung(QuellenRegister register, Verschluesselung verschluesselung,
                             MandantAufloesung aufloesung) {
        this.register = register;
        this.verschluesselung = verschluesselung;
        this.aufloesung = aufloesung;
    }

    @Transactional
    public List<Quelle> liste() {
        return register.liste();
    }

    @Transactional
    public Quelle anlegen(String code, String name, String typ, String konfiguration, String bemerkung) {
        if (!Quelle.codeGueltig(code) || !Quelle.nameGueltig(name) || !Quelle.bemerkungGueltig(bemerkung)
                || !konfigurationGueltig(konfiguration)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        QuellenTyp quellenTyp = aufzaehlung(QuellenTyp.class, typ);
        if (!register.anlegen(UUID.randomUUID(), code, name.strip(), quellenTyp,
                konfiguration == null ? "{}" : konfiguration, Optional.ofNullable(bemerkung).filter(b -> !b.isBlank()))) {
            throw new FachFehler(Fehlercode.QUELLE_EXISTIERT);
        }
        return quelle(code);
    }

    @Transactional
    public Quelle aendern(String code, int version, String status, String bemerkung, String konfiguration) {
        if (!Quelle.bemerkungGueltig(bemerkung) || (konfiguration != null && !konfigurationGueltig(konfiguration))) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        QuellenStatus neuerStatus = status == null ? null : aufzaehlung(QuellenStatus.class, status);
        quelle(code);
        return register.aendern(code, version, neuerStatus, bemerkung, konfiguration)
                .orElseThrow(() -> new FachFehler(Fehlercode.VERSION_KONFLIKT));
    }

    /** Speichert das Geheimnis (z. B. App-Passwort) nur verschlüsselt; es wird nie zurückgegeben. */
    @Transactional
    public void geheimnisSetzen(String code, String geheimnis) {
        if (geheimnis == null || geheimnis.isEmpty() || geheimnis.length() > GEHEIMNIS_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        quelle(code);
        if (!verschluesselung.verfuegbar()) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        register.geheimnisSetzen(code, verschluesselung.verschluesseln(geheimnis.getBytes(StandardCharsets.UTF_8),
                bezug(code)));
    }

    /**
     * Startet einen Trocken- oder echten Lauf. Läuft schon ein Trocken- oder echter Lauf: 409 {@code QUELLE_LAEUFT};
     * ein automatischer Lauf (Uploads mit Code der Quelle) endet dabei.
     */
    @Transactional
    public QuellLauf laufStarten(String code, String art, Optional<String> benutzer) {
        LaufArt laufArt = aufzaehlung(LaufArt.class, art);
        if (laufArt == LaufArt.AUTOMATISCH) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Quelle quelle = register.finde(code, true).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        Optional<QuellLauf> laufend = register.laufenderLauf(quelle.id());
        if (laufend.isPresent()) {
            if (laufend.get().art() != LaufArt.AUTOMATISCH) {
                throw new FachFehler(Fehlercode.QUELLE_LAEUFT);
            }
            register.laufBeenden(laufend.get().id(), QuellLaufStatus.FERTIG, Optional.empty(), 0, List.of());
        }
        return register.laufAnlegen(quelle.id(), laufArt, benutzer);
    }

    /**
     * Schliesst einen laufenden Lauf ab. Beim Trockenlauf meldet das Werkzeug alle Zähler (sie müssen aufgehen, ohne
     * fachliche Dubletten); bei echten Läufen zählt der Server, das Werkzeug ergänzt nur übersprungene Dateien.
     */
    @Transactional
    public QuellLauf laufAbschliessen(UUID laufId, Optional<Zaehler> zaehler, int uebersprungen,
                                      List<String> meldungen, boolean abgebrochen) {
        if (uebersprungen < 0 || !QuellLauf.meldungenGueltig(meldungen)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        QuellLauf lauf = register.lauf(laufId, true).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (lauf.status() != QuellLaufStatus.LAEUFT) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (lauf.art() == LaufArt.TROCKEN) {
            if (zaehler.isEmpty() || !zaehler.get().gueltigFuer(LaufArt.TROCKEN) || uebersprungen != 0) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
        } else if (zaehler.isPresent()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return register.laufBeenden(laufId, abgebrochen ? QuellLaufStatus.ABGEBROCHEN : QuellLaufStatus.FERTIG,
                zaehler, uebersprungen, meldungen);
    }

    @Transactional
    public List<QuellLauf> laeufe(String code) {
        return register.laeufe(quelle(code).id(), LAEUFE_MAX);
    }

    /**
     * Welche Prüfsummen schon zu einem nicht verworfenen Dokument oder Stapel gehören; ohne IDs (10.6). Dokumente
     * folgen der Sichtregel aus Spec 01, 2.3 und 2.21, Stapel dem Recht A25, da jedes Original alle Vertraulichkeiten
     * enthalten kann. Das Import-Token fragt ohne Sichtfilter.
     */
    @Transactional
    public Set<String> pruefsummen(List<String> sha256, Optional<Benutzer> benutzer) {
        if (sha256 == null || sha256.size() > PRUEFSUMMEN_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Set<String> gesucht = new LinkedHashSet<>();
        for (String wert : sha256) {
            String klein = wert == null ? "" : wert.strip().toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(klein).matches()) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            gesucht.add(klein);
        }
        boolean stapelEinsehbar = benutzer.map(b -> Rechtematrix.ergebnis(b.rolle(), Aktion.A25).zugaenglich())
                .orElse(true);
        return gesucht.isEmpty() ? Set.of() : register.vorhandenePruefsummen(gesucht,
                benutzer.map(Sichtregel::fuer), stapelEinsehbar);
    }

    private Quelle quelle(String code) {
        return register.finde(code, false).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    private String bezug(String code) {
        return "quelle:" + aufloesung.schema() + ":" + code;
    }

    /** Ein JSON-Objekt als Text; geprüft wird nur die Form, der Inhalt gehört zum Typ der Quelle. */
    static boolean konfigurationGueltig(String konfiguration) {
        if (konfiguration == null) {
            return true;
        }
        String text = konfiguration.strip();
        return text.length() <= Quelle.KONFIGURATION_MAX && text.startsWith("{") && text.endsWith("}");
    }

    private static <E extends Enum<E>> E aufzaehlung(Class<E> art, String wert) {
        try {
            return Enum.valueOf(art, wert == null ? "" : wert);
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
    }
}
