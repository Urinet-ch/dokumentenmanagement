package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Rohvorschlag;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.JsonSchemas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter des {@link KiPort} mit Quarkus LangChain4j gegen Infomaniak AI Services (design.md «KI»; Req 7.1, 7.3): genau
 * ein Request je {@link #metadaten}, ohne Wiederholung ({@link KiModell}). Eine Antwort ausserhalb des Schemas (nicht
 * lesbar, leer oder gegen das strikte Schema ungültig, {@link AntwortSchema}) ist ein
 * {@link KiPort.Schemafehler}, jeder andere Fehler ein {@link KiPort.Ausfall}; die Gründe nennen nur Klassennamen (Req
 * 16.7). Ohne Token ({@link KiModell#bereit()}) kein Request, sondern {@link KiPort.OhneZugang}. Nur über
 * {@code KiAuswahl} wählbar.
 */
@ApplicationScoped
@Typed(LangChain4jKiAdapter.class)
public class LangChain4jKiAdapter implements KiPort {

    private final MetadatenExtraktor extraktor;
    private final TokenZaehler zaehler;
    private final KiModell modell;

    public LangChain4jKiAdapter(MetadatenExtraktor extraktor, TokenZaehler zaehler, KiModell modell) {
        this.extraktor = extraktor;
        this.zaehler = zaehler;
        this.modell = modell;
    }

    @Override
    public Antwort metadaten(Anfrage anfrage) {
        if (!modell.bereit()) {
            return new OhneZugang(new Verbrauch(modell.modell(), 0, 0));
        }
        zaehler.zuruecksetzen();
        try {
            Result<MetadatenVorschlag> ergebnis = aufrufen(anfrage);
            Verbrauch verbrauch = verbrauch(ergebnis.tokenUsage());
            if (ergebnis.content() == null) {
                return new Schemafehler("leere Antwort", verbrauch);
            }
            if (!AntwortSchema.gueltig(zaehler.stand().text())) {
                return new Schemafehler("Antwort ausserhalb des Schemas", verbrauch);
            }
            return new Gueltig(roh(ergebnis.content()), verbrauch);
        } catch (RuntimeException e) {
            Verbrauch verbrauch = verbrauch(null);
            return KiFehler.istSchemafehler(e) ? new Schemafehler(KiFehler.grund(e), verbrauch)
                    : new Ausfall(KiFehler.grund(e), verbrauch);
        } finally {
            zaehler.zuruecksetzen();
        }
    }

    /** Ohne Beispiele genau die Methoden des Gate-Laufs, damit die Nachricht unverändert bleibt. */
    private Result<MetadatenVorschlag> aufrufen(Anfrage a) {
        String b = a.beispiele();
        if (a.bildJpeg().isPresent()) {
            Image bild = Image.builder().base64Data(Base64.getEncoder().encodeToString(a.bildJpeg().get()))
                    .mimeType("image/jpeg").build();
            if (b.isEmpty()) {
                return a.fehler().isPresent()
                        ? extraktor.nachfrageBild(a.systemPrompt(), a.qr(), bild, a.fehler().get())
                        : extraktor.extrahiereBild(a.systemPrompt(), a.qr(), bild);
            }
            return a.fehler().isPresent()
                    ? extraktor.nachfrageBildMitBeispielen(a.systemPrompt(), b, a.qr(), bild, a.fehler().get())
                    : extraktor.extrahiereBildMitBeispielen(a.systemPrompt(), b, a.qr(), bild);
        }
        if (b.isEmpty()) {
            return a.fehler().isPresent()
                    ? extraktor.nachfrage(a.systemPrompt(), a.qr(), a.text(), a.fehler().get())
                    : extraktor.extrahiere(a.systemPrompt(), a.qr(), a.text());
        }
        return a.fehler().isPresent()
                ? extraktor.nachfrageMitBeispielen(a.systemPrompt(), b, a.qr(), a.text(), a.fehler().get())
                : extraktor.extrahiereMitBeispielen(a.systemPrompt(), b, a.qr(), a.text());
    }

    /** Tokens aus dem Listener (auch bei nicht parsebarer Antwort), sonst aus dem Ergebnis. */
    private Verbrauch verbrauch(TokenUsage nutzung) {
        TokenZaehler.Stand stand = zaehler.stand();
        if (stand.antworten() > 0 || nutzung == null) {
            return new Verbrauch(modell.modell(), stand.ein(), stand.aus());
        }
        return new Verbrauch(modell.modell(), nutzung.inputTokenCount() == null ? 0 : nutzung.inputTokenCount(),
                nutzung.outputTokenCount() == null ? 0 : nutzung.outputTokenCount());
    }

    @Override
    public String antwortschema() {
        return schema();
    }

    @Override
    public List<String> artCodes() {
        return codes();
    }

    /** Antwortschema aus {@link MetadatenVorschlag}, wie es LangChain4j an das Modell sendet (Prompt-Hash). */
    public static String schema() {
        return JsonSchemas.jsonSchemaFrom(MetadatenVorschlag.class).map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("kein JSON-Schema für MetadatenVorschlag"));
    }

    /** Art-Codes des Antwortschemas in dessen Reihenfolge. */
    public static List<String> codes() {
        return Arrays.stream(MetadatenVorschlag.ArtCode.values()).map(Enum::name).toList();
    }

    static Rohvorschlag roh(MetadatenVorschlag v) {
        Map<Feld, BigDecimal> sicherheit = new EnumMap<>(Feld.class);
        MetadatenVorschlag.Sicherheit s = v.sicherheit();
        if (s != null) {
            sicherheit.put(Feld.ART, s.art());
            sicherheit.put(Feld.TITEL, s.titel());
            sicherheit.put(Feld.DATUM, s.datum());
            sicherheit.put(Feld.PARTNER, s.partnerName());
            sicherheit.put(Feld.BELEGNUMMER, s.belegnummer());
            sicherheit.put(Feld.RICHTUNG, s.richtung());
            sicherheit.put(Feld.BETRAG, s.betrag());
            sicherheit.put(Feld.WAEHRUNG, s.waehrung());
            sicherheit.put(Feld.FAELLIGKEIT, s.faelligkeit());
            sicherheit.put(Feld.VERTRAULICHKEIT, s.vertraulichkeit());
            sicherheit.values().removeIf(java.util.Objects::isNull);
        }
        return new Rohvorschlag(name(v.art()), v.titel(), v.datum(), v.partnerName(), v.partnerUid(), v.partnerIban(),
                v.partnerEmail(), v.belegnummer(), name(v.richtung()), v.betrag(), v.waehrung(), v.faelligkeit(),
                v.qrReferenz(), name(v.vertraulichkeit()), v.tags(), sicherheit);
    }

    private static String name(Enum<?> wert) {
        return wert == null ? null : wert.name();
    }
}
