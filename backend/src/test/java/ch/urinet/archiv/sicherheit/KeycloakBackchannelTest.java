package ch.urinet.archiv.sicherheit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakBackchannelTest {
    @Test
    void internerAdminAufrufBehaeltOeffentlichenIssuerAuchOhneDynamischenBackchannel() throws Exception {
        var messung = ExportKeycloak.backchannelPruefen(false);
        assertThat(messung.discoveryIssuer()).isEqualTo(messung.erwartetIssuer());
        assertThat(messung.tokenIssuer()).isEqualTo(messung.erwartetIssuer());
        assertThat(messung.tokenEndpunkt())
                .isEqualTo(messung.erwartetIssuer() + "/protocol/openid-connect/token");
        assertThat(messung.adminStatus()).isEqualTo("200");
    }

    @Test
    void dynamischerBackchannelLiefertInternenTokenEndpunkt() throws Exception {
        var messung = ExportKeycloak.backchannelPruefen(true);
        assertThat(messung.discoveryIssuer()).isEqualTo(messung.erwartetIssuer());
        assertThat(messung.tokenIssuer()).isEqualTo(messung.erwartetIssuer());
        assertThat(messung.tokenEndpunkt())
                .isEqualTo(messung.interneBasis() + "/realms/urinet-archiv/protocol/openid-connect/token");
        assertThat(messung.adminStatus()).isEqualTo("200");
    }
}
