package ch.urinet.archiv.sicherheit.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class JdbcTokenWiderruf implements TokenWiderruf {

    private static final int CACHE_GROESSE = 10_000;

    private record CacheEintrag(Optional<Instant> ab, long geladenBei) {
    }

    private final Instance<AgroalDataSource> datenquelle;
    private final Duration cacheDauer;
    private final Map<String, CacheEintrag> cache = new HashMap<>();
    private final AtomicLong aenderung = new AtomicLong();

    public JdbcTokenWiderruf(Instance<AgroalDataSource> datenquelle,
                             @ConfigProperty(name = "archiv.verwaltung.widerruf-cache",
                                     defaultValue = "PT3S") Duration cacheDauer) {
        if (cacheDauer.isNegative() || cacheDauer.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Ungültige Cache-Dauer für Token-Widerruf");
        }
        this.datenquelle = datenquelle;
        this.cacheDauer = cacheDauer;
    }

    @Override
    public boolean widerrufen(String benutzer, Instant ausgestellt) {
        Objects.requireNonNull(ausgestellt);
        return ab(benutzer).filter(zeit -> ausgestellt.isBefore(zeit)).isPresent();
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void setzen(String benutzer, Instant ab) {
        benutzerPruefen(benutzer);
        Objects.requireNonNull(ab);
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement upsert =
                verbindung.prepareStatement("insert into public.token_widerruf (benutzer, ab) values (?, ?) "
                        + "on conflict (benutzer) do update set ab = greatest(public.token_widerruf.ab, excluded.ab) "
                        + "returning ab")) {
            upsert.setString(1, benutzer);
            upsert.setObject(2, utc(ab));
            try (ResultSet zeile = upsert.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Token-Widerruf nicht gespeichert");
                }
                Instant gespeichert = zeile.getObject("ab", OffsetDateTime.class).toInstant();
                synchronized (cache) {
                    aenderung.incrementAndGet();
                    cache.put(benutzer, new CacheEintrag(Optional.of(gespeichert), System.nanoTime()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Token-Widerruf nicht gespeichert (SQLState " + e.getSQLState() + ")", e);
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int aufraeumen(Instant vor) {
        Objects.requireNonNull(vor);
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement loeschen =
                verbindung.prepareStatement("delete from public.token_widerruf where ab < ?")) {
            loeschen.setObject(1, utc(vor));
            int anzahl = loeschen.executeUpdate();
            if (anzahl > 0) {
                synchronized (cache) {
                    aenderung.incrementAndGet();
                    cache.clear();
                }
            }
            return anzahl;
        } catch (SQLException e) {
            throw new IllegalStateException("Token-Widerrufe nicht aufgeräumt (SQLState " + e.getSQLState() + ")", e);
        }
    }

    private Optional<Instant> ab(String benutzer) {
        benutzerPruefen(benutzer);
        long version = aenderung.get();
        long jetzt = System.nanoTime();
        synchronized (cache) {
            CacheEintrag eintrag = cache.get(benutzer);
            if (eintrag != null && cacheDauer.toNanos() > jetzt - eintrag.geladenBei()) {
                return eintrag.ab();
            }
            cache.remove(benutzer);
        }
        Optional<Instant> ab = lesen(benutzer);
        if (!cacheDauer.isZero()) {
            synchronized (cache) {
                if (version == aenderung.get()) {
                    if (cache.size() >= CACHE_GROESSE) {
                        cache.clear();
                    }
                    cache.put(benutzer, new CacheEintrag(ab, System.nanoTime()));
                }
            }
        }
        return ab;
    }

    private Optional<Instant> lesen(String benutzer) {
        try (Connection verbindung = datenquelle.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select ab from public.token_widerruf where benutzer = ?")) {
            abfrage.setString(1, benutzer);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next() ? Optional.of(zeile.getObject("ab", OffsetDateTime.class).toInstant())
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Token-Widerruf nicht lesbar (SQLState " + e.getSQLState() + ")", e);
        }
    }

    private static OffsetDateTime utc(Instant zeit) {
        return OffsetDateTime.ofInstant(zeit, ZoneOffset.UTC);
    }

    private static void benutzerPruefen(String benutzer) {
        if (benutzer == null || benutzer.isBlank() || benutzer.length() > 255) {
            throw new IllegalArgumentException("Benutzerkennung ist ungültig");
        }
    }
}
