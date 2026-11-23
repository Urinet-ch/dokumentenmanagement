require 'yaml'
require 'json'

vertrag = YAML.safe_load(File.read(File.join(__dir__, '../../../docs/schnittstelle/public-v1.yaml')), aliases: true)
schemas = vertrag.fetch('components').fetch('schemas')
fixtures = {}
{ 'dokumente' => 'WebseiteDokumentListe', 'suche' => 'WebseiteSuchergebnis', 'filter' => 'WebseiteFilter' }.each do |pfad, name|
  schema = schemas.fetch(name)
  fixtures[pfad] = schema.fetch('required').to_h do |feld|
    typ = schema.fetch('properties').fetch(feld).fetch('type')
    wert = { 'array' => [], 'integer' => 1, 'boolean' => false }.fetch(typ)
    [feld, wert]
  end
end
File.write(File.join(__dir__, 'vertrag.json'), JSON.generate(fixtures))
