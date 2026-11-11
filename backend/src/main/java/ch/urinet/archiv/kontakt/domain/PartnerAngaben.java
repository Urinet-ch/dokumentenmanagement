package ch.urinet.archiv.kontakt.domain;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;

/**
 * Was die Erfassung über den Partner eines Belegs weiss (Req 7.4); jedes Feld darf fehlen. Die Werte werden vor dem
 * Abgleich normalisiert (design.md «Normalisierung»).
 */
public record PartnerAngaben(String name, String email, String uid, String iban) {

    public String nameNormalisiert() {
        return Normalisierer.name(name);
    }

    public String emailNormalisiert() {
        return Normalisierer.email(email);
    }

    public String uidNormalisiert() {
        return Normalisierer.uid(uid);
    }

    public String ibanNormalisiert() {
        return Normalisierer.iban(iban);
    }
}
