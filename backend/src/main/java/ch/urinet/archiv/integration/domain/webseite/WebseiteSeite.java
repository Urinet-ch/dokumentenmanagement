package ch.urinet.archiv.integration.domain.webseite;

import java.util.List;

/** Eine Seite der Liste: {@code seite} ab 1, {@code gesamt} = Zahl aller sichtbaren Treffer. */
public record WebseiteSeite(List<WebseiteDokument> eintraege, int seite, int proSeite, long gesamt) {

    public WebseiteSeite {
        eintraege = List.copyOf(eintraege);
    }
}
