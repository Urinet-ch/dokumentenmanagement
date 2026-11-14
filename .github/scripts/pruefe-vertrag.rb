# Vergleich zweier Fassungen des Vertrags der Webseiten-Schnittstelle (Spec 03, Req 11.3, design.md «Nur additiv»).
# Aufruf über pruefe-vertrag.sh: ruby pruefe-vertrag.rb <basis.yaml> <kopf.yaml> bzw. --lesen <datei.yaml>.
# Nur die Standardbibliothek (Psych/YAML), keine Gems: Ruby liegt auf ubuntu-24.04 (GitHub-Runner) und auf macOS bei.
# Eigenes Skript statt eines Werkzeugs wie oasdiff, weil «x-erweiterbar: true» eine Konvention dieses Vertrags ist.
# Exit 0 = nur additiv, 1 = nicht additiv, 2 = nicht prüfbar (Datei fehlt oder ist kein lesbares YAML/OpenAPI).
require 'date'
require 'time'
require 'yaml'

VERBEN = %w[get put post delete options head patch trace].freeze
KOMPONENTEN = %w[schemas responses parameters headers requestBodies securitySchemes].freeze

# Unquotierte Daten (z. B. «example: 2027-07-01») liest Psych als Date/Time. Erlaubt und als ISO-Text verglichen, wie
# JSON und der Java-Test (Jackson) sie lesen; sonst gälte ein gültiger Vertrag als nicht prüfbar (Review PR #140).
def als_text(wert)
  case wert
  when Hash then wert.to_h { |k, v| [als_text(k), als_text(v)] }
  when Array then wert.map { |v| als_text(v) }
  when Time then wert.iso8601
  when Date then wert.iso8601
  else wert
  end
end

def laden(datei)
  inhalt = als_text(YAML.safe_load(File.read(datei, encoding: 'UTF-8'), permitted_classes: [Date, Time],
                                                                        permitted_symbols: [], aliases: false,
                                                                        filename: datei))
  unless inhalt.is_a?(Hash) && inhalt['openapi'].is_a?(String) && inhalt['openapi'].start_with?('3.')
    warn "Kein OpenAPI-Dokument: #{datei}"
    exit 2
  end
  inhalt
rescue Psych::Exception, SystemCallError => e
  warn "Nicht lesbar: #{datei} (#{e.class})"
  exit 2
end

# Prüft die Änderung von a (Basis) nach b (Kopf) und sammelt jeden Verstoss mit Ort.
class Vergleich
  attr_reader :verstoesse

  def initialize(basis, kopf)
    @a = basis
    @b = kopf
    @verstoesse = []
    @kontext = Hash.new { |h, k| h[k] = [] }
    [@a, @b].each { |dok| kontexte(dok) }
  end

  def pruefen
    pfade
    komponenten
    verstoss('/security', 'globale Anmeldung geändert') if @a['security'] != @b['security']
    self
  end

  private

  def verstoss(ort, grund)
    @verstoesse << "#{ort}: #{grund}"
  end

  def hash(wert)
    wert.is_a?(Hash) ? wert : {}
  end

  def aufloesen(dok, knoten)
    10.times do
      break unless knoten.is_a?(Hash) && knoten['$ref'].is_a?(String) && knoten['$ref'].start_with?('#/')
      knoten = knoten['$ref'][2..-1].split('/').reduce(dok) do |k, teil|
        k.is_a?(Hash) ? k[teil.gsub('~1', '/').gsub('~0', '~')] : nil
      end
    end
    knoten
  end

  # Wo ein Schema der Komponenten vorkommt: in Anfragen (Parameter, Inhalt) oder Antworten; in beiden Fassungen.
  def kontexte(dok)
    hash(dok['paths']).each_value do |pfad|
      pfad = hash(pfad)
      VERBEN.each do |verb|
        op = pfad[verb]
        next unless op.is_a?(Hash)
        (Array(pfad['parameters']) + Array(op['parameters'])).each { |p| markieren(dok, p, :anfrage) }
        markieren(dok, op['requestBody'], :anfrage)
        hash(op['responses']).each_value { |r| markieren(dok, r, :antwort) }
      end
    end
  end

  def markieren(dok, knoten, art, gesehen = {})
    case knoten
    when Hash
      ref = knoten['$ref']
      if ref.is_a?(String)
        return if gesehen[[ref, art]]
        gesehen[[ref, art]] = true
        @kontext[ref] |= [art] if ref.start_with?('#/components/schemas/')
        markieren(dok, aufloesen(dok, knoten), art, gesehen)
      end
      knoten.each { |k, v| markieren(dok, v, art, gesehen) unless k == '$ref' }
    when Array
      knoten.each { |v| markieren(dok, v, art, gesehen) }
    end
  end

  def pfade
    hash(@a['paths']).each do |name, pfad_a|
      pfad_b = hash(@b['paths'])[name]
      unless pfad_b.is_a?(Hash)
        verstoss("/paths/#{name}", 'Pfad entfernt oder umbenannt')
        next
      end
      VERBEN.each do |verb|
        op_a = hash(pfad_a)[verb]
        next unless op_a.is_a?(Hash)
        op_b = pfad_b[verb]
        ort = "/paths/#{name}/#{verb}"
        unless op_b.is_a?(Hash)
          verstoss(ort, 'Operation entfernt')
          next
        end
        parameter(ort, Array(hash(pfad_a)['parameters']) + Array(op_a['parameters']),
                  Array(pfad_b['parameters']) + Array(op_b['parameters']))
        anfrage(ort, op_a['requestBody'], op_b['requestBody'])
        antworten(ort, hash(op_a['responses']), hash(op_b['responses']))
      end
    end
  end

  def parameter_liste(dok, liste)
    liste.each_with_object({}) do |p, ergebnis|
      p = hash(aufloesen(dok, p))
      ergebnis["#{p['in']}:#{p['name']}"] = p
    end
  end

  def pflicht?(p)
    p['required'] == true || p['in'] == 'path'
  end

  def parameter(ort, liste_a, liste_b)
    pa = parameter_liste(@a, liste_a)
    pb = parameter_liste(@b, liste_b)
    pa.each do |schluessel, a|
      b = pb[schluessel]
      if b.nil?
        verstoss("#{ort}/parameters/#{schluessel}", 'Parameter entfernt oder umbenannt')
        next
      end
      verstoss("#{ort}/parameters/#{schluessel}", 'Parameter wird Pflicht') if pflicht?(b) && !pflicht?(a)
      schema(a['schema'], b['schema'], "#{ort}/parameters/#{schluessel}/schema", [:anfrage])
    end
    (pb.keys - pa.keys).each do |schluessel|
      verstoss("#{ort}/parameters/#{schluessel}", 'neuer Pflichtparameter') if pflicht?(pb[schluessel])
    end
  end

  def anfrage(ort, roh_a, roh_b)
    a = aufloesen(@a, roh_a)
    b = aufloesen(@b, roh_b)
    ort = "#{ort}/requestBody"
    if a.is_a?(Hash) && !b.is_a?(Hash)
      verstoss(ort, 'Anfrage-Inhalt entfernt')
    elsif !a.is_a?(Hash) && b.is_a?(Hash)
      verstoss(ort, 'neuer Anfrage-Inhalt ist Pflicht') if b['required'] == true
    elsif a.is_a?(Hash)
      verstoss(ort, 'Anfrage-Inhalt wird Pflicht') if b['required'] == true && a['required'] != true
      inhalt(ort, hash(a['content']), hash(b['content']), [:anfrage])
    end
  end

  def inhalt(ort, a, b, art)
    a.each do |typ, medium|
      if b.key?(typ)
        schema(hash(medium)['schema'], hash(b[typ])['schema'], "#{ort}/content/#{typ}/schema", art)
      else
        verstoss("#{ort}/content/#{typ}", 'Medientyp entfernt')
      end
    end
  end

  def antworten(ort, a, b)
    a.each do |code, antwort_a|
      antwort_b = b[code]
      if antwort_b.nil?
        verstoss("#{ort}/responses/#{code}", 'Statuscode entfernt')
        next
      end
      antwort("#{ort}/responses/#{code}", antwort_a, antwort_b)
    end
  end

  def antwort(ort, roh_a, roh_b)
    ref_a = hash(roh_a)['$ref']
    ref_b = hash(roh_b)['$ref']
    return if ref_a && ref_a == ref_b # die Komponente vergleicht #komponenten
    a = hash(aufloesen(@a, roh_a))
    b = hash(aufloesen(@b, roh_b))
    inhalt(ort, hash(a['content']), hash(b['content']), [:antwort])
    hash(a['headers']).each do |name, kopf_a|
      kopf_b = hash(b['headers'])[name]
      if kopf_b.nil?
        verstoss("#{ort}/headers/#{name}", 'Header entfernt')
      else
        schema(hash(aufloesen(@a, kopf_a))['schema'], hash(aufloesen(@b, kopf_b))['schema'],
               "#{ort}/headers/#{name}/schema", [:antwort])
      end
    end
  end

  def komponenten
    KOMPONENTEN.each do |art|
      teil_a = hash(hash(@a['components'])[art])
      teil_b = hash(hash(@b['components'])[art])
      teil_a.each do |name, a|
        ort = "/components/#{art}/#{name}"
        b = teil_b[name]
        if b.nil?
          verstoss(ort, 'Komponente entfernt oder umbenannt')
          next
        end
        case art
        when 'schemas'
          kontext = @kontext["#/components/schemas/#{name}"]
          schema(a, b, ort, kontext.empty? ? %i[anfrage antwort] : kontext)
        when 'responses', 'headers'
          antwort(ort, a, b) if art == 'responses'
          schema(hash(a)['schema'], hash(b)['schema'], "#{ort}/schema", [:antwort]) if art == 'headers'
        when 'parameters'
          parameter(ort, [a], [b])
        when 'requestBodies'
          anfrage(ort, a, b)
        when 'securitySchemes'
          %w[type scheme in name].each do |feld|
            verstoss("#{ort}/#{feld}", 'Anmeldung geändert') if hash(a)[feld] != hash(b)[feld]
          end
        end
      end
    end
  end

  def typen(s)
    t = s['type']
    t.nil? ? nil : Array(t).map(&:to_s).sort
  end

  # Schema-Vergleich ohne Auflösen von Referenzen (das Ziel vergleicht #komponenten); art: :anfrage, :antwort.
  def schema(a, b, ort, art)
    return if a.nil? && b.nil?
    unless a.is_a?(Hash) && b.is_a?(Hash)
      verstoss(ort, 'Schema geändert') if a != b
      return
    end
    verstoss(ort, 'Referenz geändert') if a['$ref'] != b['$ref']
    verstoss("#{ort}/type", 'Typ geändert') if typen(a) != typen(b)
    %w[format const].each { |feld| verstoss("#{ort}/#{feld}", 'Typ geändert') if a[feld] != b[feld] }
    aufzaehlung(a, b, ort)
    eigenschaften(a, b, ort, art)
    if a.key?('items') || b.key?('items')
      a.key?('items') && b.key?('items') ? schema(a['items'], b['items'], "#{ort}/items", art) : verstoss("#{ort}/items", 'Typ geändert')
    end
    zusatz_a = a['additionalProperties']
    zusatz_b = b['additionalProperties']
    if zusatz_a.is_a?(Hash) && zusatz_b.is_a?(Hash)
      schema(zusatz_a, zusatz_b, "#{ort}/additionalProperties", art)
    elsif zusatz_a != zusatz_b && (zusatz_b == false || zusatz_b.is_a?(Hash))
      verstoss("#{ort}/additionalProperties", 'zusätzliche Felder eingeschränkt')
    end
    %w[allOf anyOf oneOf].each do |feld|
      next unless a.key?(feld) || b.key?(feld)
      liste_a = Array(a[feld])
      liste_b = Array(b[feld])
      if liste_a.size != liste_b.size
        verstoss("#{ort}/#{feld}", 'Zusammensetzung geändert')
      else
        liste_a.each_with_index { |teil, i| schema(teil, liste_b[i], "#{ort}/#{feld}/#{i}", art) }
      end
    end
  end

  def aufzaehlung(a, b, ort)
    werte_a = a['enum']
    werte_b = b['enum']
    return if werte_a.nil? && werte_b.nil?
    if werte_a.nil? || werte_b.nil?
      verstoss("#{ort}/enum", werte_a.nil? ? 'Aufzählung neu' : 'Aufzählung entfernt')
      return
    end
    (Array(werte_a) - Array(werte_b)).each { |w| verstoss("#{ort}/enum", "Wert #{w} entfernt") }
    neu = Array(werte_b) - Array(werte_a)
    erweiterbar = a['x-erweiterbar'] == true && b['x-erweiterbar'] == true
    neu.each { |w| verstoss("#{ort}/enum", "neuer Wert #{w} ohne x-erweiterbar: true") } unless erweiterbar
    verstoss("#{ort}/x-erweiterbar", 'nachträglich erweiterbar') if b['x-erweiterbar'] == true && a['x-erweiterbar'] != true
  end

  def eigenschaften(a, b, ort, art)
    felder_b = hash(b['properties'])
    hash(a['properties']).each do |name, teil|
      if felder_b.key?(name)
        schema(teil, felder_b[name], "#{ort}/properties/#{name}", art)
      else
        verstoss("#{ort}/properties/#{name}", 'Feld entfernt oder umbenannt')
      end
    end
    pflicht_a = Array(a['required'])
    pflicht_b = Array(b['required'])
    if art.include?(:anfrage)
      (pflicht_b - pflicht_a).each { |f| verstoss("#{ort}/required", "neues Pflichtfeld #{f} in einer Anfrage") }
    end
    if art.include?(:antwort)
      (pflicht_a - pflicht_b).each { |f| verstoss("#{ort}/required", "Feld #{f} in einer Antwort nicht mehr Pflicht") }
    end
  end
end

if ARGV[0] == '--lesen' && ARGV.size == 2
  laden(ARGV[1])
  exit 0
end
unless ARGV.size == 2
  warn 'Aufruf: pruefe-vertrag.rb <basis.yaml> <kopf.yaml> | --lesen <datei.yaml>'
  exit 2
end
verstoesse = Vergleich.new(laden(ARGV[0]), laden(ARGV[1])).pruefen.verstoesse
verstoesse.each { |v| puts "Nicht additiv: #{v}" }
exit(verstoesse.empty? ? 0 : 1)
