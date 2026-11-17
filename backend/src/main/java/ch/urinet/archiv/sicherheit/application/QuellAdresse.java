package ch.urinet.archiv.sicherheit.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Eine einzige vertrauenswürdige Auflösung der Quelladresse einer Anfrage (Prüfgrenze, Absenderprüfung): Der Header
 * {@code X-Real-Ip} (Traefik) zählt nur, wenn der unmittelbare Peer in einem konfigurierten Netz liegt
 * ({@code archiv.api-token.vertrauenswuerdige-proxies}, CIDR-Liste) und der Wert genau eine IPv4- oder IPv6-Adresse ist.
 * Sonst gilt die Verbindungsadresse; ein Client kann die Quelle so nie selbst setzen. Ohne Netze (leere Liste) wird der
 * Header nie ausgewertet; produktiv warnt der Start, wenn nur Loopback eingetragen ist. Quarkus' eigene Proxy-Weiterleitung kennt {@code X-Real-Ip} nicht und ist deshalb nicht im Spiel.
 */
@ApplicationScoped
public class QuellAdresse {

    private static final Logger LOG = Logger.getLogger(QuellAdresse.class);
    private static final Pattern ZEICHEN = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<Netz> netze;

    @Inject
    public QuellAdresse(
            @ConfigProperty(name = "archiv.api-token.vertrauenswuerdige-proxies") Optional<List<String>> netze,
            @ConfigProperty(name = "quarkus.profile", defaultValue = "prod") String profil) {
        this(netze.orElse(List.of()));
        if ("prod".equals(profil) && this.netze.stream().allMatch(Netz::loopback)) {
            LOG.warn("Nur Loopback als vertrauenswürdiges Proxy-Netz: Clients hinter dem Proxy teilen eine Quelladresse "
                    + "(ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE setzen)");
        }
    }

    public QuellAdresse(List<String> netze) {
        List<Netz> gelesen = new ArrayList<>();
        for (String eintrag : netze) {
            if (!eintrag.isBlank()) {
                gelesen.add(Netz.lesen(eintrag.strip()));
            }
        }
        this.netze = List.copyOf(gelesen);
    }

    /** Ohne Netz wird {@code X-Real-Ip} nie ausgewertet; produktiv warnt der Start davor. */
    public boolean ohneNetze() {
        return netze.isEmpty();
    }

    /** Quelladresse als Text; leer ohne Verbindungsadresse. */
    public Optional<String> aufloesen(String peer, String realIpHeader) {
        Optional<InetAddress> verbindung = adresse(peer);
        if (verbindung.isEmpty()) {
            return Optional.empty();
        }
        if (realIpHeader != null && netze.stream().anyMatch(netz -> netz.enthaelt(verbindung.get()))) {
            Optional<InetAddress> weitergegeben = adresse(realIpHeader.strip());
            if (weitergegeben.isPresent()) {
                return weitergegeben.map(InetAddress::getHostAddress);
            }
        }
        return verbindung.map(InetAddress::getHostAddress);
    }

    private static Optional<InetAddress> adresse(String text) {
        if (text == null || !ZEICHEN.matcher(text).matches() || !text.contains(".") && !text.contains(":")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private record Netz(byte[] basis, int laenge) {

        static Netz lesen(String text) {
            int strich = text.indexOf('/');
            String ip = strich < 0 ? text : text.substring(0, strich);
            Optional<InetAddress> gelesen = QuellAdresse.adresse(ip);
            if (gelesen.isEmpty()) {
                throw new IllegalArgumentException("Vertrauenswürdiges Proxy-Netz ungültig");
            }
            byte[] bytes = gelesen.get().getAddress();
            int laenge = bytes.length * 8;
            if (strich >= 0) {
                try {
                    laenge = Integer.parseInt(text.substring(strich + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Vertrauenswürdiges Proxy-Netz ungültig", e);
                }
                if (laenge < 0 || laenge > bytes.length * 8) {
                    throw new IllegalArgumentException("Vertrauenswürdiges Proxy-Netz ungültig");
                }
            }
            return new Netz(bytes, laenge);
        }

        boolean loopback() {
            try {
                return InetAddress.getByAddress(basis).isLoopbackAddress();
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }

        boolean enthaelt(InetAddress kandidat) {
            byte[] bytes = kandidat.getAddress();
            if (bytes.length != basis.length) {
                return false;
            }
            for (int bit = 0; bit < laenge; bit++) {
                int maske = 0x80 >> (bit % 8);
                if ((bytes[bit / 8] & maske) != (basis[bit / 8] & maske)) {
                    return false;
                }
            }
            return true;
        }
    }
}
