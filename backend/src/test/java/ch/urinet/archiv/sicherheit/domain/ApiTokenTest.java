package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenTest {

    private final SecureRandom zufall = new SecureRandom();

    @Test
    void neuesTokenHatDasFormatUndLaesstSichLesen() {
        ApiToken token = ApiToken.neu(zufall);

        assertThat(token.text()).matches("ua_[a-z0-9]{12}_[A-Za-z0-9_-]{43}");
        ApiToken gelesen = ApiToken.lesen(token.text()).orElseThrow();
        assertThat(gelesen.id()).isEqualTo(token.id());
        assertThat(gelesen.geheimnis()).isEqualTo(token.geheimnis());
        assertThat(ApiToken.principal(token.id())).isEqualTo("token:" + token.id());
    }

    @Test
    void falscherAufbauErgibtLeer() {
        String gueltig = ApiToken.neu(zufall).text();

        assertThat(ApiToken.lesen(gueltig.substring(0, gueltig.length() - 1))).isEmpty();
        assertThat(ApiToken.lesen(gueltig + "x")).isEmpty();
        assertThat(ApiToken.lesen(gueltig.toUpperCase())).isEmpty();
        assertThat(ApiToken.lesen("ua_")).isEmpty();
        assertThat(ApiToken.lesen(null)).isEmpty();
    }

    @Test
    void toStringVerraetDasGeheimnisNicht() {
        ApiToken token = ApiToken.neu(zufall);

        assertThat(token.toString()).doesNotContain(token.geheimnis()).contains(token.id());
    }
}
