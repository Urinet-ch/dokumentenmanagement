package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantBerichtSpeicher;
import ch.urinet.archiv.mandant.domain.MandantBericht;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Speichert je Schema und Monat genau einen Bericht mit wiederholbarem Mailstatus. */
@ApplicationScoped
public class JdbcMandantBerichtSpeicher implements MandantBerichtSpeicher {

    private static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final TypeReference<LinkedHashMap<String, Long>> DOKUMENTE = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, MandantBericht.Tokens>> KI_TOKENS =
            new TypeReference<>() {
            };

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung mandantAufloesung;
    private final ObjectMapper json;

    public JdbcMandantBerichtSpeicher(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                      ObjectMapper json) {
        this.datenquelle = datenquelle;
        this.mandantAufloesung = aufloesung;
        this.json = json;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<Eintrag> lesen(YearMonth monat) {
        try (Connection verbindung = datenquelle.get().getConnection()) {
            return lesen(verbindung, schema(), monat);
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenbericht nicht gelesen", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Eintrag speichernFallsFehlt(MandantBericht bericht) {
        MandantCode mandant = mandantAufloesung.ausKontext();
        if (!mandant.equals(bericht.mandant())) {
            throw new IllegalStateException("Mandantenbericht widerspricht dem Mandanten-Kontext");
        }
        String schema = schema();
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                     + ".mandant_bericht (monat, speicher_bytes, dokumente, ki_tokens, chat_fragen) "
                     + "values (?, ?, ?::jsonb, ?::jsonb, ?) on conflict (monat) do nothing")) {
            einfuegen.setQueryTimeout(ZEITGRENZE_S);
            einfuegen.setDate(1, Date.valueOf(bericht.monat().atDay(1)));
            einfuegen.setLong(2, bericht.speicherBytes());
            einfuegen.setString(3, json(bericht.dokumente()));
            einfuegen.setString(4, json(bericht.kiTokens()));
            einfuegen.setLong(5, bericht.chatFragen());
            einfuegen.executeUpdate();
            return lesen(verbindung, schema, bericht.monat())
                    .orElseThrow(() -> new IllegalStateException("Gespeicherter Mandantenbericht fehlt"));
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantenbericht nicht gespeichert", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void mailVersandt(YearMonth monat) {
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement aktualisieren = verbindung.prepareStatement(
                     "update " + schema() + ".mandant_bericht set mail_versandt = true where monat = ?")) {
            aktualisieren.setQueryTimeout(ZEITGRENZE_S);
            aktualisieren.setDate(1, Date.valueOf(monat.atDay(1)));
            if (aktualisieren.executeUpdate() != 1) {
                throw new IllegalStateException("Mandantenbericht für den Mailstatus fehlt");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Mailstatus des Mandantenberichts nicht gespeichert", e);
        }
    }

    private Optional<Eintrag> lesen(Connection verbindung, String schema, YearMonth monat) throws SQLException {
        String sql = "select speicher_bytes, dokumente, ki_tokens, chat_fragen, mail_versandt from " + schema
                + ".mandant_bericht where monat = ?";
        try (PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            abfrage.setDate(1, Date.valueOf(monat.atDay(1)));
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    return Optional.empty();
                }
                MandantBericht bericht = new MandantBericht(mandantAufloesung.ausKontext(), monat,
                        zeile.getLong("speicher_bytes"), dokumente(zeile.getString("dokumente")),
                        kiTokens(zeile.getString("ki_tokens")), zeile.getLong("chat_fragen"));
                return Optional.of(new Eintrag(bericht, zeile.getBoolean("mail_versandt")));
            }
        }
    }

    private String schema() {
        String schema = mandantAufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Mandantenbericht ohne Mandanten-Schema");
        }
        return schema;
    }

    private String json(Object wert) {
        try {
            return json.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mandantenbericht nicht serialisiert", e);
        }
    }

    private Map<String, Long> dokumente(String wert) {
        try {
            return json.readValue(wert, DOKUMENTE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dokumentzahlen des Mandantenberichts nicht gelesen", e);
        }
    }

    private Map<String, MandantBericht.Tokens> kiTokens(String wert) {
        try {
            return json.readValue(wert, KI_TOKENS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Token-Zahlen des Mandantenberichts nicht gelesen", e);
        }
    }
}
