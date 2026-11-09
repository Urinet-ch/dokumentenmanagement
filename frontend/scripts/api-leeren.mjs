// Leert das Ausgabeverzeichnis des API-Clients vor dem Generieren; openapi-generator löscht verwaiste Dateien nicht.
import { readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const ziel = 'src/app/gemeinsam/api';
for (const eintrag of readdirSync(ziel)) {
  if (eintrag !== '.openapi-generator-ignore') {
    rmSync(join(ziel, eintrag), { recursive: true, force: true });
  }
}
