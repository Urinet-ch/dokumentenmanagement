package ch.urinet.archiv.sicherheit.application;

/** Driven Port: Hash des Token-Geheimnisses (Argon2id, Req 2.5); Parameter und Salt stehen im Hash selbst. */
public interface GeheimnisHasher {

    String hashen(String geheimnis);

    /** Vergleich in konstanter Zeit; ein unlesbarer Hash ergibt {@code false}. */
    boolean pruefen(String geheimnis, String hash);
}
