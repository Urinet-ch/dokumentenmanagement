import tempfile
import unittest
from pathlib import Path

from tools.pruefe_dokumentverweise import pruefe_verweise


class DokumentverweiseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "docs/betrieb").mkdir(parents=True)
        (self.root / "deploy/infomaniak").mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def schreiben(self, pfad, text="synthetisch"):
        datei = self.root / pfad
        datei.parent.mkdir(parents=True, exist_ok=True)
        datei.write_text(text, encoding="utf-8")

    def test_gueltige_markdown_und_dateiverweise(self):
        self.schreiben("docs/ziel.md")
        self.schreiben("docs/betrieb/lokal.sh")
        self.schreiben("deploy/infomaniak/compose.yml")
        self.schreiben(
            "docs/betrieb/handbuch.md",
            "[Relativ](../ziel.md#abschnitt) [ab Root](/deploy/infomaniak/compose.yml)\n"
            "`./lokal.sh` `deploy/infomaniak/compose.yml`\n"
            "[extern](https://example.invalid/fehlt.md) [Anker](#abschnitt) `/api/v1/dokumente`",
        )

        self.assertEqual([], pruefe_verweise(self.root))

    def test_ungueltige_links_dateiverweise_und_repo_escape(self):
        self.schreiben(
            "docs/betrieb/handbuch.md",
            "[Fehlt](./fehlt.md)\n"
            "`deploy/infomaniak/fehlend.yml`\n"
            "`../../../ausserhalb.md`",
        )

        befunde = pruefe_verweise(self.root)

        self.assertEqual(
            [
                ("docs/betrieb/handbuch.md", 1, "./fehlt.md"),
                ("docs/betrieb/handbuch.md", 2, "deploy/infomaniak/fehlend.yml"),
                ("docs/betrieb/handbuch.md", 3, "../../../ausserhalb.md"),
            ],
            [(b["datei"], b["zeile"], b["ziel"]) for b in befunde],
        )
        self.assertIn("ausserhalb", befunde[-1]["grund"])

    def test_referenzdefinitionen_werden_geprueft(self):
        self.schreiben("docs/betrieb/handbuch.md", "[Referenz]: ../nicht-da.md\n")

        befunde = pruefe_verweise(self.root)

        self.assertEqual("../nicht-da.md", befunde[0]["ziel"])

    def test_quellzeilenbereiche_werden_entfernt(self):
        self.schreiben("backend/src/Beispiel.java")
        self.schreiben("docs/betrieb/handbuch.md", "`backend/src/Beispiel.java:12-15`\n")

        self.assertEqual([], pruefe_verweise(self.root))

    def test_generierte_und_platzhalterpfade_werden_ignoriert(self):
        self.schreiben(
            "docs/betrieb/handbuch.md",
            "`backend/target/bericht.txt` `docs/schnittstelle/public-v<n>.yaml` "
            "`./secrets/…`\n",
        )

        self.assertEqual([], pruefe_verweise(self.root))

    def test_repo_wurzelpfad_ohne_extension_wird_erkannt(self):
        self.schreiben("deploy/README.md")
        self.schreiben("docs/betrieb/handbuch.md", "`./deploy`\n")

        self.assertEqual([], pruefe_verweise(self.root))


if __name__ == "__main__":
    unittest.main()
