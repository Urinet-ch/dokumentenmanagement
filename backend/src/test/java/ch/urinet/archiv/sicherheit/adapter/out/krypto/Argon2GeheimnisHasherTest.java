package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2GeheimnisHasherTest {

    private final Argon2GeheimnisHasher hasher = new Argon2GeheimnisHasher();

    @Test
    void hashIstPhcMitOwaspParameternUndEigenemSalt() {
        String erster = hasher.hashen("geheimnis-a");
        String zweiter = hasher.hashen("geheimnis-a");

        assertThat(erster).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain("geheimnis-a");
        assertThat(erster).isNotEqualTo(zweiter);
        assertThat(hasher.pruefen("geheimnis-a", erster)).isTrue();
        assertThat(hasher.pruefen("geheimnis-a", zweiter)).isTrue();
    }

    @Test
    void falschesGeheimnisOderKaputterHashErgibtFalse() {
        String hash = hasher.hashen("geheimnis-a");

        assertThat(hasher.pruefen("geheimnis-b", hash)).isFalse();
        assertThat(hasher.pruefen("geheimnis-a", hash.substring(0, hash.length() - 4))).isFalse();
        assertThat(hasher.pruefen("geheimnis-a", hash.replace("m=19456", "m=999999"))).isFalse();
        assertThat(hasher.pruefen("geheimnis-a", "kein-hash")).isFalse();
        assertThat(hasher.pruefen("geheimnis-a", null)).isFalse();
    }
}
