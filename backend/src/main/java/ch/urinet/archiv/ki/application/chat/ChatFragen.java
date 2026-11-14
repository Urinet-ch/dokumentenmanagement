package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatAusgabe;
import ch.urinet.archiv.ki.domain.chat.ChatDurchgang;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.chat.Fragegrenze;
import ch.urinet.archiv.ki.domain.chat.Gespraechstitel;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Eine Frage an den Chat (Spec 02, Aufgabe 7.3, Req 3.8, 5.4, 5.5; design.md «Vertrag Welle 7»). Kurze Transaktionen
 * statt einer über den Stream:
 * <ol>
 *   <li>{@link #annehmen}: in einer Transaktion Frage prüfen (400), Gespräch prüfen (404), Rate-Limit aus
 *       {@code chat_nachricht} (429) und Platz in {@link ChatGrenzen} (429), dann Gespräch anlegen und Frage
 *       speichern. Jede angenommene Frage zählt, auch wenn der Durchgang danach scheitert oder abbricht.</li>
 *   <li>{@link #starten}: der Agent läuft auf einem eigenen Thread im {@link MandantKontext} des Anfragenden, mit
 *       Mandant, Benutzer und Sichtregel aus der Anmeldung, festgehalten in {@link ChatAnfrage}; der Thread hat keinen
 *       Request-Kontext, und {@code MandantKontext} verbietet einen Kontext in laufender Transaktion. Während der Agent
 *       auf das Modell wartet, ist keine Datenbankverbindung offen.</li>
 *   <li>danach in eigener Transaktion die Antwort mit Inhalt, Werkzeugen, Quellen, Tokens und Status.</li>
 * </ol>
 * Frage, Antwort, Titel und Werkzeugparameter erscheinen nie im Log, nur IDs, Zahlen und Klassennamen (Spec 01,
 * Req 16.7).
 */
@ApplicationScoped
public class ChatFragen {

    public static final int MAX_FRAGE = 2_000;
    public static final int VERLAUF = 10;
    /** Wortlaut bei Ausfall des Agenten (design.md «Error Handling»). */
    public static final String NICHT_VERFUEGBAR = "Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.";
    /** {@code Retry-After}, wenn schon eine Frage des Benutzers oder die Höchstzahl der Instanz läuft. */
    static final long LAEUFT_SCHON_SEKUNDEN = 5;

    private static final Logger LOG = Logger.getLogger(ChatFragen.class);

    private final ChatAgentPort agent;
    private final ChatSpeicher speicher;
    private final ChatGrenzen grenzen;
    private final MandantKontext kontext;
    private final MandantEinstellungen einstellungen;
    private final TransactionSynchronizationRegistry transaktionen;
    private final int fragenJeStunde;
    private final Clock uhr = Clock.systemUTC();
    // je Frage ein Thread; die Zahl begrenzt ChatGrenzen (archiv.chat.gleichzeitig-gesamt)
    private final ExecutorService ausfuehrung = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("chat-", 0).daemon().factory());

    @Inject
    public ChatFragen(ChatAgentPort agent, ChatSpeicher speicher, ChatGrenzen grenzen, MandantKontext kontext,
                      MandantEinstellungen einstellungen, TransactionSynchronizationRegistry transaktionen,
                      @ConfigProperty(name = "archiv.chat.fragen-je-stunde") int fragenJeStunde) {
        if (fragenJeStunde < 1) {
            throw new IllegalArgumentException("archiv.chat.fragen-je-stunde muss mindestens 1 sein");
        }
        this.agent = agent;
        this.speicher = speicher;
        this.grenzen = grenzen;
        this.kontext = kontext;
        this.einstellungen = einstellungen;
        this.transaktionen = transaktionen;
        this.fragenJeStunde = fragenJeStunde;
    }

    /** Angenommene Frage: Gespräch, Anfrage an den Agenten und belegter Platz (bis {@link #starten} ihn freigibt). */
    public record Annahme(UUID gespraechId, ChatAnfrage anfrage, ChatGrenzen.Platz platz) {
    }

    /** Frage nach Trim mit 1 bis {@value #MAX_FRAGE} Zeichen, sonst 400 {@code ANFRAGE_UNGUELTIG} ohne den Wert. */
    public static String frage(String roh) {
        String text = roh == null ? "" : roh.strip();
        int laenge = text.codePointCount(0, text.length());
        if (laenge < 1 || laenge > MAX_FRAGE) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return text;
    }

    /**
     * Nimmt die Frage an (Schritt a). Reihenfolge der Ablehnungen: 400, 404 (fremdes oder unbekanntes Gespräch, auch
     * eines anderen Benutzers desselben Mandanten), 429 Rate-Limit, 429 Gleichzeitigkeit. Der Platz wird frei, wenn die
     * Transaktion nicht festgeschrieben wird.
     */
    @Transactional
    public Annahme annehmen(Benutzer benutzer, MandantCode mandant, Optional<IpAdresse> ip, String frage,
                            Optional<UUID> gespraechId) {
        String text = frage(frage);
        speicher.benutzerSperren(benutzer.id());
        if (gespraechId.isPresent() && !speicher.gehoertSperren(gespraechId.get(), benutzer.id())) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        Instant jetzt = uhr.instant();
        Optional<Instant> grenze = speicher.grenzFrage(benutzer.id(), jetzt.minus(Fragegrenze.FENSTER), fragenJeStunde);
        if (grenze.isPresent()) {
            throw new ZuVieleAnfragen(Fragegrenze.wartezeitSekunden(grenze.get(), jetzt));
        }
        ChatGrenzen.Platz platz = grenzen.belegen(mandant, benutzer.id())
                .orElseThrow(() -> new ZuVieleAnfragen(LAEUFT_SCHON_SEKUNDEN));
        transaktionen.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    platz.freigeben();
                }
            }
        });
        UUID id = gespraechId.orElseGet(UUID::randomUUID);
        if (gespraechId.isEmpty()) {
            speicher.gespraechAnlegen(id, benutzer.id(), Gespraechstitel.aus(text), jetzt);
        }
        List<VerlaufEintrag> verlauf = VerlaufEintrag.normalisieren(speicher.verlauf(id, VERLAUF));
        speicher.frageSpeichern(id, text, jetzt);
        ChatAnfrage anfrage = new ChatAnfrage(mandant, benutzer, Sichtregel.fuer(benutzer), ip,
                einstellungen.lesen(mandant).name(), text, verlauf);
        return new Annahme(id, anfrage, platz);
    }

    /**
     * Startet den Durchgang auf einem eigenen Thread und gibt die Ausgaben in Reihenfolge an {@code strom} (Schritte b
     * und c); nach {@link ChatAusgabe.Fertig} oder {@link ChatAusgabe.Fehler} kommt nichts mehr. Nicht in einer
     * laufenden Transaktion aufrufen; gibt den Platz der Annahme in jedem Fall frei.
     */
    public void starten(Annahme annahme, Consumer<ChatAusgabe> strom, ChatAbbruch abbruch) {
        Consumer<ChatAusgabe> einmalig = einmalig(strom);
        UUID id = annahme.gespraechId();
        try {
            ausfuehrung.execute(() -> {
                try {
                    kontext.mit(annahme.anfrage().mandant(), () -> durchgang(annahme, einmalig, abbruch));
                } catch (RuntimeException e) {
                    LOG.errorf("Chat-Durchgang für Gespräch %s gescheitert, ursache=%s", id, Ursachen.kette(e));
                    einmalig.accept(new ChatAusgabe.Fehler(NICHT_VERFUEGBAR, id));
                } finally {
                    annahme.platz().freigeben();
                }
            });
        } catch (RejectedExecutionException e) {
            annahme.platz().freigeben();
            LOG.errorf("Chat-Durchgang für Gespräch %s nicht gestartet, ursache=%s", id, Ursachen.kette(e));
            einmalig.accept(new ChatAusgabe.Fehler(NICHT_VERFUEGBAR, id));
        }
    }

    private void durchgang(Annahme annahme, Consumer<ChatAusgabe> strom, ChatAbbruch abbruch) {
        UUID id = annahme.gespraechId();
        ChatDurchgang durchgang = new ChatDurchgang();
        try {
            agent.beantworten(annahme.anfrage(), ereignis -> {
                // nach dem Abbruch zählt nichts mehr, auch kein Ende
                if (!abbruch.gesetzt()) {
                    durchgang.aufnehmen(Objects.requireNonNull(ereignis, "ereignis"), e -> weitergeben(e, strom));
                }
            }, abbruch);
        } catch (RuntimeException e) {
            durchgang.ausfallen(e.getClass().getSimpleName());
            LOG.warnf("Chat-Agent für Gespräch %s mit Ausnahme beendet, ursache=%s", id, Ursachen.kette(e));
        }
        ChatAntwort antwort = durchgang.antwort(abbruch.gesetzt());
        if (antwort.status() == ChatStatus.FEHLER) {
            LOG.warnf("Chat-Agent für Gespräch %s ausgefallen, grund=%s", id, durchgang.ausfallgrund().orElse("-"));
        }
        try {
            speicher.antwortSpeichern(id, antwort, uhr.instant());
        } catch (RuntimeException e) {
            LOG.errorf("Antwort für Gespräch %s nicht gespeichert, ursache=%s", id, Ursachen.kette(e));
            annahme.platz().freigeben();
            strom.accept(new ChatAusgabe.Fehler(NICHT_VERFUEGBAR, id));
            return;
        }
        // die Frage läuft nicht mehr, sobald die Antwort gespeichert ist: eine Folgefrage nach «fertig» ist erlaubt
        annahme.platz().freigeben();
        // gespeichert ist gespeichert: Ausnahmen beim Senden ändern den Status nicht und lösen kein «fehler» mehr aus
        try {
            switch (antwort.status()) {
                case OK -> {
                    strom.accept(new ChatAusgabe.Quellen(antwort.quellen()));
                    strom.accept(new ChatAusgabe.Fertig(id, antwort.tokens().orElseThrow()));
                }
                case FEHLER -> strom.accept(new ChatAusgabe.Fehler(NICHT_VERFUEGBAR, id));
                case ABGEBROCHEN -> LOG.infof("Chat-Gespräch %s: Frage vom Client abgebrochen", id);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Chat-Gespräch %s: Ausgabe nach dem Speichern gescheitert, ursache=%s", id, Ursachen.kette(e));
        }
    }

    private static void weitergeben(ChatEreignis ereignis, Consumer<ChatAusgabe> strom) {
        switch (ereignis) {
            case ChatEreignis.WerkzeugStart start -> strom.accept(new ChatAusgabe.Werkzeug(start.name()));
            case ChatEreignis.Text teil -> strom.accept(new ChatAusgabe.Text(teil.delta()));
            default -> {
                // nur Werkzeugstart und Text gehen sofort an den Client
            }
        }
    }

    /** Lässt nach der letzten Ausgabe nichts mehr durch; Aufrufe nacheinander. */
    private static Consumer<ChatAusgabe> einmalig(Consumer<ChatAusgabe> ziel) {
        return new Consumer<>() {
            private boolean beendet;

            @Override
            public synchronized void accept(ChatAusgabe ausgabe) {
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
