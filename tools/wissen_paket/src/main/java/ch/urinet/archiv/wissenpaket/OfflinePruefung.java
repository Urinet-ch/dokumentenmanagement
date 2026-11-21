package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.wissen.domain.*;
import ch.urinet.archiv.wissen.domain.paket.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

final class OfflinePruefung {
    record Meldung(String code, String pfad) {}
    record Bericht(List<Meldung> befunde, List<Meldung> hinweise) {
        Bericht {
            befunde = List.copyOf(befunde);
            hinweise = List.copyOf(hinweise);
        }
        boolean gueltig() { return befunde.isEmpty(); }
    }

    static Bericht pruefen(WissensPaket paket) {
        List<Meldung> befunde = new ArrayList<>();
        List<Meldung> hinweise = new ArrayList<>();
        paket.hinweise().forEach(h -> hinweise.add(new Meldung(h.code().name(), h.pfad())));
        for (PaketSeite s : paket.seiten()) {
            String pfad = s.pfad();
            List<Quelleneintrag> quellen = new ArrayList<>();
            Map<UUID, WissensPruefung.DokumentQuelle> dokumente = new HashMap<>();
            Set<UUID> seiten = new HashSet<>();
            UUID wissensquelle = null;
            int markiert = 0;
            for (PaketQuelle q : s.sources()) {
                UUID id = UUID.nameUUIDFromBytes((q.sha256() == null ? q.resource() : q.sha256())
                        .getBytes(StandardCharsets.UTF_8));
                Integer von = null, bis = null;
                if (q.sha256() != null) {
                    if (q.seite() != null) {
                        try {
                            String[] zahlen = q.seite().split("-");
                            von = Integer.valueOf(zahlen[0]);
                            bis = zahlen.length == 1 ? null : Integer.valueOf(zahlen[1]);
                        } catch (NumberFormatException ex) {
                            befunde.add(new Meldung("QUELLE_UNGUELTIG", pfad));
                        }
                    }
                    LocalDate datum = null;
                    if (s.typ().equals("Quelle") && s.pfad().startsWith("quellen/") && s.pfad().length() >= 15) {
                        try { datum = YearMonth.parse(s.pfad().substring(8, 15)).atDay(1); }
                        catch (RuntimeException ignored) { /* Dokumentdatum ist offline unbekannt. */ }
                    }
                    dokumente.put(id, new WissensPruefung.DokumentQuelle(true, true, false, null, datum,
                            Optional.empty()));
                    if (q.wissensquelle()) { wissensquelle = id; markiert++; }
                    quellen.add(new Quelleneintrag(q.id(), id, von, bis, q.auszug(), null, q.sha256()));
                } else {
                    seiten.add(id);
                    quellen.add(new Quelleneintrag(q.id(), null, null, null, null, id));
                }
                hinweise.add(new Meldung("QUELLE_NICHT_GEPRUEFT", pfad));
            }
            if (s.typ().equals("Quelle") && markiert != 1) befunde.add(new Meldung("QUELLE_UNGUELTIG", pfad));
            Wissensseite seite = new Wissensseite(null, s.pfad(), Seitentyp.vonDatenbankwert(s.typ()).orElseThrow(),
                    s.titel(), s.beschreibung(), s.tags(), Wissensseite.Status.AKTIV, s.staleAfter(), quellen,
                    Wissensseite.Vertraulichkeit.NORMAL, WissensMarkdown.ausOkf(s.inhalt()), 0, null,
                    null, null, null, false, false, wissensquelle);
            var kontext = new WissensPruefung.Kontext(WissensPruefung.SEITE_MAX_ZEICHEN_STARTWERT, null,
                    Set.of(), dokumente, seiten, Set.of(), Set.of(), Set.of(), false, false, false, null,
                    Wissensseite.Vertraulichkeit.NORMAL, true);
            var ergebnis = WissensPruefung.pruefen(seite, kontext);
            ergebnis.befunde().stream().filter(b -> b.code() != WissensPruefung.Code.AUSZUG_NICHT_PRUEFBAR)
                    .forEach(b -> befunde.add(new Meldung(b.code().name(), pfad)));
            ergebnis.hinweise().forEach(h -> hinweise.add(new Meldung(h.code().name(), pfad)));
        }
        return new Bericht(befunde, hinweise);
    }
}
