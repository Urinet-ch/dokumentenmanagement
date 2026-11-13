package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kanonische Form nach RFC 8785 (Req 15.1): Beispiele aus RFC 8785, Abschnitte 3.2.2.3 und 3.2.3. */
class JcsTest {

    @Test
    void zahlenWieInEcmaScript() {
        assertThat(Jcs.zahl(0.0)).isEqualTo("0");
        assertThat(Jcs.zahl(-0.0)).isEqualTo("0");
        assertThat(Jcs.zahl(1e21)).isEqualTo("1e+21");
        assertThat(Jcs.zahl(1e20)).isEqualTo("100000000000000000000");
        assertThat(Jcs.zahl(1e-7)).isEqualTo("1e-7");
        assertThat(Jcs.zahl(0.000001)).isEqualTo("0.000001");
        assertThat(Jcs.zahl(333333333.33333329)).isEqualTo("333333333.3333333");
        assertThat(Jcs.zahl(-1.5e-10)).isEqualTo("-1.5e-10");
        assertThat(Jcs.zahl(9007199254740992L)).isEqualTo("9007199254740992");
        assertThat(Jcs.zahl(new BigDecimal("1234.50"))).isEqualTo("1234.5");
        assertThat(Jcs.zahl(42)).isEqualTo("42");
        assertThatThrownBy(() -> Jcs.zahl(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schluesselNachUtf16CodeeinheitenSortiert() {
        Map<String, Object> objekt = new LinkedHashMap<>();
        for (String k : List.of("\u20ac", "\r", "\ufb33", "1", "\ud83d\ude00", "\u0080", "\u00f6")) {
            objekt.put(k, 1);
        }

        assertThat(Jcs.kanonisch(objekt)).isEqualTo(
                "{\"\\r\":1,\"1\":1,\"\u0080\":1,\"\u00f6\":1,\"\u20ac\":1,\"\ud83d\ude00\":1,\"\ufb33\":1}");
    }

    @Test
    void textMitMinimalenEscapes() {
        Map<String, Object> objekt = new LinkedHashMap<>();
        objekt.put("b", List.of("\"\\/\n\u0001ä", true, false));
        objekt.put("a", null);

        assertThat(Jcs.kanonisch(objekt)).isEqualTo("{\"a\":null,\"b\":[\"\\\"\\\\/\\n\\u0001ä\",true,false]}");
    }
}
