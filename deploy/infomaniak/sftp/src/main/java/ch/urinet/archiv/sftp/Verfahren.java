package ch.urinet.archiv.sftp;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.Cipher;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * SSH-Verfahren des Dienstes (design.md Abschnitt 1, Requirement 1.5). Standard sind die Voreinstellungen von MINA SSHD
 * ohne Verfahren mit SHA-1 ({@code ssh-rsa}, {@code hmac-sha1…}), die MINA aus Verträglichkeit noch anbietet. Ältere
 * Verfahren kommen nur über die ausdrückliche Liste hinzu, jeweils hinten (niedrigste Priorität); ein Name, den MINA
 * nicht kennt, bricht den Start ab, damit ein Tippfehler nicht still wirkungslos bleibt.
 */
final class Verfahren {

    private Verfahren() {
    }

    /** Gilt als älteres Verfahren, das nur über die Liste erlaubt ist. */
    static boolean istAelter(String name) {
        return name.equals("ssh-rsa") || name.startsWith("ssh-rsa-cert") || name.startsWith("ssh-dss")
                || name.contains("sha1") || name.contains("md5") || name.contains("cbc") || name.startsWith("arcfour")
                || name.equals("none");
    }

    static void anwenden(SshServer server, List<String> aeltere) {
        Map<String, String> unbekannt = new LinkedHashMap<>();
        for (String name : aeltere) {
            unbekannt.put(name, name);
        }
        server.setKeyExchangeFactories(liste(server.getKeyExchangeFactories(), aeltere, unbekannt,
                n -> {
                    BuiltinDHFactories dh = BuiltinDHFactories.fromFactoryName(n);
                    return dh != null && dh.isSupported() ? ServerBuilder.DH2KEX.apply(dh) : null;
                }));
        server.setCipherFactories(Verfahren.<NamedFactory<Cipher>>liste(server.getCipherFactories(), aeltere,
                unbekannt, n -> {
                    BuiltinCiphers c = BuiltinCiphers.fromFactoryName(n);
                    return c != null && c.isSupported() && c != BuiltinCiphers.none ? c : null;
                }));
        server.setMacFactories(Verfahren.<NamedFactory<Mac>>liste(server.getMacFactories(), aeltere, unbekannt,
                n -> {
                    BuiltinMacs m = BuiltinMacs.fromFactoryName(n);
                    return m != null && m.isSupported() ? m : null;
                }));
        server.setSignatureFactories(Verfahren.<NamedFactory<Signature>>liste(server.getSignatureFactories(),
                aeltere, unbekannt, n -> {
                    BuiltinSignatures s = BuiltinSignatures.fromFactoryName(n);
                    return s != null && s.isSupported() ? s : null;
                }));
        if (!unbekannt.isEmpty()) {
            throw new IllegalArgumentException(Konfiguration.AELTERE_VERFAHREN + ": unbekannte Verfahren "
                    + unbekannt.keySet());
        }
    }

    private static <T extends NamedResource> List<T> liste(List<T> standard, List<String> aeltere,
            Map<String, String> unbekannt, Function<String, T> fabrik) {
        List<T> ergebnis = new ArrayList<>(standard.stream().filter(f -> !istAelter(f.getName())).toList());
        for (String name : aeltere) {
            T f = fabrik.apply(name);
            if (f != null) {
                unbekannt.remove(name);
                if (ergebnis.stream().noneMatch(e -> e.getName().equals(name))) {
                    ergebnis.add(f);
                }
            }
        }
        return ergebnis;
    }

    /** Namen der Verfahren in der Reihenfolge des Servers, für das Start-Log. */
    static Map<String, List<String>> namen(SshServer server) {
        Map<String, List<String>> namen = new LinkedHashMap<>();
        namen.put("schluesseltausch", server.getKeyExchangeFactories().stream().map(NamedResource::getName).toList());
        namen.put("signatur", server.getSignatureFactoriesNames());
        namen.put("verschluesselung", server.getCipherFactoriesNames());
        namen.put("mac", server.getMacFactoriesNames());
        return namen;
    }
}
