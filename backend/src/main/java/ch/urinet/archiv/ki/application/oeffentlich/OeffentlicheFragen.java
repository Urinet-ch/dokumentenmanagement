package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatFragen;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.application.chat.WerkzeugTransaktion;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatDurchgang;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.Quelle;
import ch.urinet.archiv.ki.domain.oeffentlich.FrageBereinigung;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheAusgabe;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Eine Frage an den öffentlichen Chatbot (Spec 03, Aufgabe 11.3, Req 6.1–6.5; design.md «OeffentlicherChatAgent»).
 * <ol>
 *   <li>{@link #annehmen}: Frage prüfen (400), Token-Budget des Mandanten (429 {@code BUDGET}), Grenzen je Client und je
 *       Webseite sowie Gleichzeitigkeit (429 {@code ZU_VIELE_ANFRAGEN}), Verlauf des Gesprächs aus dem Speicher. Keine
 *       Datenbank-Transaktion; ein Platz gilt bis zum Ende von {@link #starten}.</li>
 *   <li>{@link #starten}: der Agent läuft auf einem eigenen Thread im Mandanten-Kontext der Anfrage. Die Quellen kommen
 *       aus den Werkzeugergebnissen, nie aus dem Modelltext, und werden über den festen Filter nochmals geprüft und mit
 *       Titel, Reihe und Ausgabe angereichert. Ohne Quelle folgt der feste Hinweis auf die Geschäftsstelle (Req 6.3).</li>
 *   <li>danach Speichern von Frage (bereinigt), Quellen und Tokens in {@code oeffentliche_frage} (Req 6.5).</li>
 * </ol>
 * Frage, Antwort, Titel und Werkzeugparameter erscheinen nie im Log, nur IDs, Zahlen und Klassennamen.
 */
@ApplicationScoped
public class OeffentlicheFragen {

    private static final Logger LOG = Logger.getLogger(OeffentlicheFragen.class);

    /** Wortlaut bei Ausfall des Agenten: fest, ohne interne Angaben. */
    public static final String NICHT_VERFUEGBAR = ChatFragen.NICHT_VERFUEGBAR;
    /** Feste Antwort, wenn keine passende Publikation gefunden wird (Req 6.3); {@code %s} ist der Kontakt oder leer. */
    static final String KEIN_TREFFER = "Dazu habe ich keine passende Publikation gefunden. Bitte wenden Sie sich an die "
            + "Geschäftsstelle%s.";

    private final OeffentlicherChatAgentPort agent;
    private final OeffentlicheGrenzen grenzen;
    private final MandantBudget budget;
    private final OeffentlicheGespraeche gespraeche;
    private final OeffentlicheFragenSpeicher speicher;
    private final OeffentlicheDokumente dokumente;
    private final WerkzeugTransaktion transaktion;
    private final WerkzeugDaten daten;
    private final MandantKontext kontext;
    private final MandantEinstellungen einstellungen;
    private final int maxFrage;
    private final String kontakt;
    private final Clock uhr = Clock.systemUTC();
    private final ExecutorService ausfuehrung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("oeffentlicher-chat-", 0).daemon().factory());

    @Inject
    public OeffentlicheFragen(OeffentlicherChatAgentPort agent, OeffentlicheGrenzen grenzen, MandantBudget budget,
                              OeffentlicheGespraeche gespraeche, OeffentlicheFragenSpeicher speicher,
                              OeffentlicheDokumente dokumente, WerkzeugTransaktion transaktion, WerkzeugDaten daten,
                              MandantKontext kontext, MandantEinstellungen einstellungen,
                              @ConfigProperty(name = "archiv.webseite.chat.max-frage") int maxFrage,
                              @ConfigProperty(name = "archiv.webseite.chat.geschaeftsstelle") Optional<String> kontakt) {
        if (maxFrage < 1) {
            throw new IllegalArgumentException("archiv.webseite.chat.max-frage muss mindestens 1 sein");
        }
        this.agent = agent;
        this.grenzen = grenzen;
        this.budget = budget;
        this.gespraeche = gespraeche;
        this.speicher = speicher;
        this.dokumente = dokumente;
        this.transaktion = transaktion;
        this.daten = daten;
        this.kontext = kontext;
        this.einstellungen = einstellungen;
        this.maxFrage = maxFrage;
        this.kontakt = kontakt.map(String::strip).filter(k -> !k.isEmpty()).map(k -> " (" + k + ")").orElse("");
    }

    /** Angenommene Frage: Gespräch, Anfrage an den Agenten und belegter Platz (bis {@link #starten} ihn freigibt). */
    public record Annahme(UUID gespraechId, OeffentlicheAnfrage anfrage, String frage,
                          OeffentlicheGrenzen.Platz platz) {
    }

    /** Frage nach Trim mit 1 bis {@code max-frage} Zeichen, ohne Steuerzeichen; sonst 400 {@code ANFRAGE_UNGUELTIG}. */
    public String frage(String roh) {
        String text = roh == null ? "" : roh.strip();
        int laenge = text.codePointCount(0, text.length());
        if (laenge < 1 || laenge > maxFrage || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return text;
    }

    /** Obergrenze der Frage in Zeichen. */
    public int maxFrage() {
        return maxFrage;
    }

    /**
     * Nimmt die Frage an. Reihenfolge der Ablehnungen: 400, 429 {@code BUDGET}, 429 {@code ZU_VIELE_ANFRAGEN}. Eine
     * abgelehnte Frage zählt in keiner Grenze.
     */
    public Annahme annehmen(MandantCode mandant, Lesezugriff zugriff, String frage, Optional<UUID> gespraechId) {
        String text = frage(frage);
        if (budget.erschoepft(mandant)) {
            throw new FachFehler(Fehlercode.BUDGET);
        }
        OeffentlicheGrenzen.Platz platz = grenzen.annehmen(mandant, zugriff);
        try {
            OeffentlicheGespraeche.Geoeffnet gespraech = gespraeche.oeffnen(gespraechId.orElse(null), mandant, zugriff);
            OeffentlicheAnfrage anfrage = new OeffentlicheAnfrage(mandant, zugriff,
                    einstellungen.lesen(mandant).name(), text, gespraech.verlauf());
            return new Annahme(gespraech.id(), anfrage, text, platz);
        } catch (RuntimeException e) {
            platz.freigeben();
            throw e;
        }
    }

    /**
     * Startet den Durchgang auf einem eigenen Thread und gibt die Ausgaben in Reihenfolge an {@code strom}; nach
     * {@link OeffentlicheAusgabe.Fertig} oder {@link OeffentlicheAusgabe.Fehler} kommt nichts mehr. Gibt den Platz der
     * Annahme in jedem Fall frei.
     */
    public void starten(Annahme annahme, Consumer<OeffentlicheAusgabe> strom, ChatAbbruch abbruch) {
        Consumer<OeffentlicheAusgabe> einmalig = einmalig(strom);
        UUID id = annahme.gespraechId();
        try {
            ausfuehrung.execute(() -> {
                try {
                    kontext.mit(annahme.anfrage().mandant(), () -> durchgang(annahme, einmalig, abbruch));
                } catch (RuntimeException e) {
                    LOG.errorf("Öffentlicher Chat-Durchgang gescheitert, ursache=%s", Ursachen.kette(e));
                    einmalig.accept(new OeffentlicheAusgabe.Fehler(NICHT_VERFUEGBAR, id));
                } finally {
                    annahme.platz().freigeben();
                }
            });
        } catch (RejectedExecutionException e) {
            annahme.platz().freigeben();
            LOG.errorf("Öffentlicher Chat-Durchgang nicht gestartet, ursache=%s", Ursachen.kette(e));
            einmalig.accept(new OeffentlicheAusgabe.Fehler(NICHT_VERFUEGBAR, id));
        }
    }

    private void durchgang(Annahme annahme, Consumer<OeffentlicheAusgabe> strom, ChatAbbruch abbruch) {
        UUID id = annahme.gespraechId();
        OeffentlicheAnfrage anfrage = annahme.anfrage();
        ChatDurchgang durchgang = new ChatDurchgang();
        try {
            agent.beantworten(anfrage, ereignis -> {
                if (!abbruch.gesetzt()) {
                    durchgang.aufnehmen(Objects.requireNonNull(ereignis, "ereignis"), e -> weitergeben(e, strom));
                }
            }, abbruch);
        } catch (RuntimeException e) {
            durchgang.ausfallen(e.getClass().getSimpleName());
            LOG.warnf("Öffentlicher Chat-Agent mit Ausnahme beendet, ursache=%s", Ursachen.kette(e));
        }
        ChatAntwort antwort = durchgang.antwort(abbruch.gesetzt());
        if (antwort.status() == ChatStatus.FEHLER) {
            LOG.warnf("Öffentlicher Chat-Agent ausgefallen, grund=%s", durchgang.ausfallgrund().orElse("-"));
            strom.accept(new OeffentlicheAusgabe.Fehler(NICHT_VERFUEGBAR, id));
            return;
        }
        if (antwort.status() == ChatStatus.ABGEBROCHEN) {
            LOG.info("Öffentlicher Chat: Frage vom Client abgebrochen");
            return;
        }
        List<OeffentlicheQuelle> quellen;
        try {
            quellen = quellen(antwort, anfrage);
        } catch (RuntimeException e) {
            // ohne geprüfte Quellen gibt es keine Antwort: nie Quellen aus dem Modell oder ungeprüft nach aussen
            LOG.errorf("Öffentliche Quellen nicht geprüft, ursache=%s", Ursachen.kette(e));
            strom.accept(new OeffentlicheAusgabe.Fehler(NICHT_VERFUEGBAR, id));
            return;
        }
        String text = antwort.inhalt();
        if (quellen.isEmpty()) {
            String hinweis = KEIN_TREFFER.formatted(kontakt);
            strom.accept(new OeffentlicheAusgabe.Text((text.isBlank() ? "" : "\n\n") + hinweis));
            text = text.isBlank() ? hinweis : text + "\n\n" + hinweis;
        }
        int tokens = antwort.tokens().map(t -> (int) Math.min(Integer.MAX_VALUE, (long) t.tokensEin() + t.tokensAus()))
                .orElse(0);
        try {
            speicher.speichern(FrageBereinigung.fuerSpeicher(annahme.frage()), quellen, tokens, uhr.instant());
        } catch (RuntimeException e) {
            // die Antwort ist fertig; das Protokoll der Fragen fehlt nur in diesem Fall (Log ohne Inhalt)
            LOG.errorf("Öffentliche Frage nicht gespeichert, ursache=%s", Ursachen.kette(e));
        }
        gespraeche.merken(id, anfrage.mandant(), anfrage.zugriff(), annahme.frage(), text);
        try {
            strom.accept(new OeffentlicheAusgabe.Quellen(quellen));
            strom.accept(new OeffentlicheAusgabe.Fertig(id));
        } catch (RuntimeException e) {
            LOG.warnf("Öffentlicher Chat: Ausgabe nach dem Speichern gescheitert, ursache=%s", Ursachen.kette(e));
        }
    }

    /**
     * Quellen aus den Werkzeugergebnissen ({@code Quellen}-Regel des internen Chats), danach über den festen Filter: Nur
     * Dokumente, die dieser Anfrage jetzt sichtbar sind, in der Reihenfolge der Werkzeuge, mit Titel, Reihe und Ausgabe aus
     * der Datenbank.
     */
    private List<OeffentlicheQuelle> quellen(ChatAntwort antwort, OeffentlicheAnfrage anfrage) {
        List<UUID> ids = antwort.quellen().stream().filter(q -> q instanceof Quelle.Dokument)
                .map(q -> ((Quelle.Dokument) q).id()).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<OeffentlichesDokument> sichtbar = transaktion.neu(() -> {
            daten.nurLesend();
            return dokumente.sichtbare(ids, anfrage.zugriff());
        });
        Map<UUID, OeffentlichesDokument> map = new LinkedHashMap<>();
        sichtbar.forEach(d -> map.put(d.id(), d));
        List<OeffentlicheQuelle> ergebnis = new ArrayList<>();
        for (UUID id : ids) {
            OeffentlichesDokument d = map.get(id);
            if (d != null) {
                ergebnis.add(new OeffentlicheQuelle(d.id(), ohneSteuerzeichen(d.titel()), d.reiheCode(),
                        ohneSteuerzeichen(d.reiheName()), d.ausgabe() == null || d.ausgabe().isBlank() ? null
                        : ohneSteuerzeichen(d.ausgabe())));
            }
        }
        return List.copyOf(ergebnis);
    }

    private static String ohneSteuerzeichen(String wert) {
        return wert.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
    }

    private static void weitergeben(ChatEreignis ereignis, Consumer<OeffentlicheAusgabe> strom) {
        switch (ereignis) {
            case ChatEreignis.WerkzeugStart start -> strom.accept(new OeffentlicheAusgabe.Werkzeug(start.name()));
            case ChatEreignis.Text teil -> strom.accept(new OeffentlicheAusgabe.Text(teil.delta()));
            default -> {
                // nur Werkzeugstart und Text gehen sofort an den Client
            }
        }
    }

    /** Lässt nach der letzten Ausgabe nichts mehr durch; Aufrufe nacheinander. */
    private static Consumer<OeffentlicheAusgabe> einmalig(Consumer<OeffentlicheAusgabe> ziel) {
        return new Consumer<>() {
            private boolean beendet;

            @Override
            public synchronized void accept(OeffentlicheAusgabe ausgabe) {
                if (!beendet) {
                    beendet = ausgabe.letzte();
                    ziel.accept(ausgabe);
                }
            }
        };
    }

    @PreDestroy
    void beenden() {
        ausfuehrung.shutdownNow();
    }
}
