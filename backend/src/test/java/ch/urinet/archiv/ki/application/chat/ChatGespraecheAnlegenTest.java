package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 04, Requirement 21.1: Modus prüfen, Recht A15 für WISSEN vor jedem Zugriff auf den Speicher. */
class ChatGespraecheAnlegenTest {

    private static final Benutzer BENUTZER = new Benutzer("b", Rolle.MITARBEITER);

    @Test
    void modusFehltOderIstArchivOderWissen() {
        assertThat(ChatGespraecheAnlegen.modus(null)).isEqualTo(Chatmodus.ARCHIV);
        assertThat(ChatGespraecheAnlegen.modus("ARCHIV")).isEqualTo(Chatmodus.ARCHIV);
        assertThat(ChatGespraecheAnlegen.modus("WISSEN")).isEqualTo(Chatmodus.WISSEN);
    }

    @Test
    void unbekannterModusIstEineUngueltigeAnfrage() {
        for (String ungueltig : new String[] {"", "wissen", "ALLES", " ARCHIV"}) {
            assertThatThrownBy(() -> ChatGespraecheAnlegen.modus(ungueltig)).as(ungueltig)
                    .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
    }

    @Test
    void wissensmodusFragtA15VorDemSpeicherUndLegtOhneRechtNichtsAn() {
        List<String> aufrufe = new ArrayList<>();
        ChatSpeicher speicher = (ChatSpeicher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ChatSpeicher.class}, (p, m, a) -> {
                    aufrufe.add(m.getName());
                    throw new AssertionError("Speicher darf ohne Recht nicht berührt werden: " + m.getName());
                });
        List<Aktion> geprueft = new ArrayList<>();
        Berechtigung ohneRecht = new Berechtigung(null, null) {
            @Override
            public void wissensseitenAktion(Benutzer benutzer, Aktion aktion, boolean sichtbar) {
                geprueft.add(aktion);
                throw new IllegalStateException("403");
            }
        };
        ChatGespraecheAnlegen anlegen = new ChatGespraecheAnlegen(speicher, ohneRecht);
        assertThatThrownBy(() -> anlegen.anlegen(BENUTZER, Chatmodus.WISSEN)).hasMessage("403");
        assertThat(geprueft).containsExactly(Aktion.A15);
        assertThat(aufrufe).isEmpty();
    }

    @Test
    void archivmodusBrauchtKeinWissensrecht() {
        List<String> aufrufe = new ArrayList<>();
        ChatSpeicher speicher = (ChatSpeicher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ChatSpeicher.class}, (p, m, a) -> {
                    aufrufe.add(m.getName());
                    throw new IllegalStateException("weiter");
                });
        Berechtigung verbietetAlles = new Berechtigung(null, null) {
            @Override
            public void wissensseitenAktion(Benutzer benutzer, Aktion aktion, boolean sichtbar) {
                throw new AssertionError("A15 gilt nur für WISSEN");
            }
        };
        assertThatThrownBy(() -> new ChatGespraecheAnlegen(speicher, verbietetAlles)
                .anlegen(BENUTZER, Chatmodus.ARCHIV)).hasMessage("weiter");
        assertThat(aufrufe).containsExactly("benutzerSperren");
    }
}
