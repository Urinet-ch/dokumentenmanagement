package ch.urinet.archiv.ki.chat;

import ch.urinet.archiv.ki.adapter.out.ChatAuswahl;
import ch.urinet.archiv.ki.adapter.out.chat.ChatNichtVerfuegbar;
import ch.urinet.archiv.ki.adapter.out.stub.ChatAgentStub;
import ch.urinet.archiv.ki.application.chat.ChatAbbruch;
import ch.urinet.archiv.ki.application.chat.ChatAgentPort;
import ch.urinet.archiv.ki.application.chat.ChatAnfrage;
import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link ChatAgentPort} für die Szenarien (features/chat): ersetzt {@link ChatAuswahl} in allen Quarkus-Tests. Ohne
 * Eingriff läuft der echte Dev-Stub ({@link ChatAgentStub}); die Schritte ({@code ChatSchritte}) setzen eine
 * Ereignisfolge, den Adapter ohne Agenten oder ein Verhalten mit Warten (Latch, keine Zeitmessung) und setzen nach
 * jedem Szenario zurück. Jeder Lauf hält Anfrage, Mandanten-Kontext, Schema, Transaktion und Thread fest.
 */
@Mock
@ApplicationScoped
public class TestChatAgent implements ChatAgentPort {

    private static final Duration WARTEN = Duration.ofSeconds(20);

    public enum Verhalten {
        STUB,
        NICHT_VERFUEGBAR,
        FOLGE,
        /** Text, dann Warten, bis der Client den ersten Text gemeldet hat, dann weiterer Text und Ende. */
        ERSTER_TEXT_WARTET,
        /** Text, dann Warten auf die Freigabe durch die Schritte, dann Ende. */
        ANGEHALTEN,
        /** Text, dann Warten auf den Abbruch, ohne Ende. */
        WARTET_AUF_ABBRUCH
    }

    /** Was ein Lauf gesehen hat. */
    public record Lauf(ChatAnfrage anfrage, Optional<MandantCode> kontext, String schema, boolean transaktion,
                       String thread, boolean ersterTextVorEnde, boolean abbruchGesehen) {
    }

    @Inject
    ChatAgentStub stub;

    @Inject
    ChatNichtVerfuegbar ohneAgent;

    @Inject
    MandantKontext mandantKontext;

    @Inject
    MandantAufloesung aufloesung;

    @Inject
    TransactionManager transaktionen;

    private volatile Verhalten verhalten = Verhalten.STUB;
    private volatile List<ChatEreignis> folge = List.of();
    private volatile String text = "";
    private volatile CountDownLatch ersterText = new CountDownLatch(1);
    private volatile CountDownLatch freigabe = new CountDownLatch(1);
    private final List<Lauf> laeufe = new CopyOnWriteArrayList<>();

    @Override
    public void beantworten(ChatAnfrage anfrage, Consumer<ChatEreignis> ausgabe, ChatAbbruch abbruch) {
        boolean transaktion;
        try {
            transaktion = transaktionen.getStatus() != Status.STATUS_NO_TRANSACTION;
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
        Optional<MandantCode> kontext = mandantKontext.aktuell();
        String schema = aufloesung.schema();
        String thread = Thread.currentThread().getName();
        boolean vorEnde = false;
        boolean abbruchGesehen = false;
        try {
            switch (verhalten) {
                case STUB -> stub.beantworten(anfrage, ausgabe, abbruch);
                case NICHT_VERFUEGBAR -> ohneAgent.beantworten(anfrage, ausgabe, abbruch);
                case FOLGE -> folge.forEach(ausgabe);
                case ERSTER_TEXT_WARTET -> {
                    ausgabe.accept(new ChatEreignis.Text(text));
                    vorEnde = ersterText.await(WARTEN.toMillis(), TimeUnit.MILLISECONDS);
                    ausgabe.accept(new ChatEreignis.Text(" Zweiter Teil."));
                    ausgabe.accept(new ChatEreignis.Ende(5, 3));
                }
                case ANGEHALTEN -> {
                    ausgabe.accept(new ChatEreignis.Text(text));
                    if (freigabe.await(WARTEN.toMillis(), TimeUnit.MILLISECONDS)) {
                        ausgabe.accept(new ChatEreignis.Ende(1, 1));
                    } else {
                        ausgabe.accept(new ChatEreignis.Ausfall("KeineFreigabe"));
                    }
                }
                case WARTET_AUF_ABBRUCH -> {
                    ausgabe.accept(new ChatEreignis.Text(text));
                    abbruchGesehen = abbruch.warten(WARTEN);
                    if (!abbruchGesehen) {
                        ausgabe.accept(new ChatEreignis.Ende(1, 1));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ausgabe.accept(new ChatEreignis.Ausfall("Unterbrochen"));
        } finally {
            laeufe.add(new Lauf(anfrage, kontext, schema, transaktion, thread, vorEnde, abbruchGesehen));
        }
    }

    public void zuruecksetzen() {
        verhalten = Verhalten.STUB;
        folge = List.of();
        text = "";
        ersterText = new CountDownLatch(1);
        freigabe = new CountDownLatch(1);
        laeufe.clear();
    }

    public void verhalten(Verhalten wert, String textVorWarten) {
        verhalten = wert;
        text = textVorWarten;
    }

    public void folge(List<ChatEreignis> ereignisse) {
        folge = List.copyOf(ereignisse);
        verhalten = Verhalten.FOLGE;
    }

    /** Der Client hat den ersten Text erhalten. */
    public void ersterTextErhalten() {
        ersterText.countDown();
    }

    public void freigeben() {
        freigabe.countDown();
    }

    public List<Lauf> laeufe() {
        return List.copyOf(laeufe);
    }
}
