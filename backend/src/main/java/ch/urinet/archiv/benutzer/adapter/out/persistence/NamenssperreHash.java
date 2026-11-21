package ch.urinet.archiv.benutzer.adapter.out.persistence;

import org.eclipse.microprofile.config.ConfigProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

public final class NamenssperreHash {
    private static final String DATEI = "namenssperre_salz";
    private final byte[] salz;

    NamenssperreHash(byte[] salz) {
        if (salz.length != 32) {
            throw new IllegalArgumentException("Namenssperre-Salz muss 32 Bytes enthalten");
        }
        this.salz = salz.clone();
    }

    public static Optional<NamenssperreHash> ausKonfiguration() {
        var config = ConfigProvider.getConfig();
        var quelle = config.getConfigValue(DATEI);
        if (quelle.getValue() == null || quelle.getValue().isBlank()) {
            return Optional.empty();
        }
        if (quelle.getSourceName() == null || !quelle.getSourceName().startsWith("FileSystemConfigSource[")) {
            throw new IllegalStateException("Namenssperre-Salz muss aus einer Secret-Datei stammen");
        }
        var ziel = config.getConfigValue("archiv.verwaltung.namenssperre-salz");
        if (!"${namenssperre_salz:}".equals(ziel.getRawValue())) {
            throw new IllegalStateException("Namenssperre-Salz darf nicht übersteuert werden");
        }
        try {
            return Optional.of(new NamenssperreHash(Base64.getDecoder().decode(quelle.getValue().strip())));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Namenssperre-Salz muss 32 Bytes als Base64 enthalten");
        }
    }

    public byte[] hash(String benutzername) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salz, "HmacSHA256"));
            return mac.doFinal(benutzername.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Namenssperre nicht verfügbar", e);
        }
    }

    public byte[] pruefwert() {
        return hash("urinet-archiv-namenssperre");
    }
}
