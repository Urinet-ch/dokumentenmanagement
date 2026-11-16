package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.application.chat.GeseheneIds;
import ch.urinet.archiv.ki.application.chat.WerkzeugFehler;
import ch.urinet.archiv.mandant.application.MandantKontext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rahmen jedes Werkzeugaufrufs des öffentlichen Chatbots: Kontext aus dem {@link OeffentlicherKontextRegister} unter der
 * Lauf-ID, sonst {@link WerkzeugFehler#KEIN_KONTEXT} ohne Datenbankzugriff; dann die Arbeit im Mandanten des Kontexts
 * (die Transaktion beginnt erst in der Arbeit). Dazu die Zuordnung einer Gesprächsnummer oder veränderten ID zu einem in
 * dieser Frage gelieferten Treffer ({@link GeseheneIds}); alles andere bleibt unverändert und wird vom festen Filter
 * beurteilt.
 */
@ApplicationScoped
public class OeffentlicheWerkzeuglauf {

    private final OeffentlicherKontextRegister register;
    private final MandantKontext mandanten;

    public OeffentlicheWerkzeuglauf(OeffentlicherKontextRegister register, MandantKontext mandanten) {
        this.register = register;
        this.mandanten = mandanten;
    }

    public <T> T ausfuehren(Object lauf, Function<OeffentlicherKontext, T> arbeit) {
        OeffentlicherKontext kontext = register.finden(lauf)
                .orElseThrow(() -> new WerkzeugFehler(WerkzeugFehler.KEIN_KONTEXT));
        return mandanten.mit(kontext.mandant(), () -> arbeit.apply(kontext));
    }

    public Map<UUID, Integer> nummerieren(Object lauf, Collection<UUID> ids) {
        return register.nummerieren(lauf, ids);
    }

    /**
     * Die Dokument-ID zur Eingabe des Modells: eine Gesprächsnummer oder (leicht veränderte) ID eines Treffers dieser
     * Frage; sonst die Eingabe unverändert. Die ID ist nie gültig, nur weil das Modell sie nennt: {@code dokument_zeigen}
     * prüft sie zusätzlich mit {@link #geliefert}.
     */
    public String zuordnen(Object lauf, String id) {
        return GeseheneIds.zuordnen(id, register.gesehene(lauf), register.nummerierte(lauf));
    }

    /** Hat {@code dokumente_suchen} dieses Dokument in dieser Frage geliefert? */
    public boolean geliefert(Object lauf, UUID id) {
        return id != null && register.gesehene(lauf).contains(id);
    }

    public List<UUID> nummerierte(Object lauf) {
        return register.nummerierte(lauf);
    }
}
