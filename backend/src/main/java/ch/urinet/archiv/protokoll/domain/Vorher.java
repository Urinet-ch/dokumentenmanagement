package ch.urinet.archiv.protokoll.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Erneuerung der Nachweise des Vortags (Req 15.2): SHA-256 der Signatur und je Dienst des Zeitstempels und der Prüfdaten. */
public record Vorher(String sigSha256, List<ZeitstempelVerweis> zeitstempel) {

    public Vorher {
        Objects.requireNonNull(sigSha256, "sigSha256");
        Sha256.gueltig(sigSha256, "sig_sha256");
        zeitstempel = zeitstempel.stream().sorted(Comparator.comparing(ZeitstempelVerweis::dienst)).toList();
    }
}
