package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.protokoll.domain.IpAdresse;

import java.net.InetAddress;
import java.util.Arrays;

/** Literal IPv4-/IPv6-Netz aus der Betriebskonfiguration; es wird nie eine Namensauflösung ausgeführt. */
record WebseitenNetz(byte[] netz, int praefix) {

    static WebseitenNetz lesen(String text) {
        String[] teile = text.strip().split("/", -1);
        if (teile.length < 1 || teile.length > 2) {
            throw new IllegalArgumentException("Netz ungültig");
        }
        byte[] adresse = InetAddress.ofLiteral(teile[0]).getAddress();
        int bits = adresse.length * Byte.SIZE;
        int praefix = teile.length == 1 ? bits : Integer.parseInt(teile[1]);
        if (praefix < 0 || praefix > bits) {
            throw new IllegalArgumentException("Präfix ungültig");
        }
        byte[] netz = adresse.clone();
        for (int i = 0; i < netz.length; i++) {
            int verbleibend = praefix - i * Byte.SIZE;
            int maske = verbleibend >= Byte.SIZE ? 0xff : verbleibend <= 0 ? 0 : 0xff << (Byte.SIZE - verbleibend);
            netz[i] = (byte) (Byte.toUnsignedInt(netz[i]) & maske);
        }
        return new WebseitenNetz(netz, praefix);
    }

    boolean enthaelt(IpAdresse ip) {
        byte[] adresse;
        try {
            adresse = InetAddress.ofLiteral(ip.text()).getAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (adresse.length != netz.length) {
            return false;
        }
        int volleBytes = praefix / Byte.SIZE;
        int restBits = praefix % Byte.SIZE;
        if (!Arrays.equals(Arrays.copyOf(adresse, volleBytes), Arrays.copyOf(netz, volleBytes))) {
            return false;
        }
        if (restBits == 0) {
            return true;
        }
        int maske = 0xff << (Byte.SIZE - restBits);
        return (Byte.toUnsignedInt(adresse[volleBytes]) & maske)
                == (Byte.toUnsignedInt(netz[volleBytes]) & maske);
    }
}
