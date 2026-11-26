import unittest
from unittest import mock

import ci_browser_quote as q


class QuoteTest(unittest.TestCase):
    def test_zaehlt_browser_rot_und_ignoriert_uebersprungene(self):
        laeufe = [
            {"databaseId": 1, "conclusion": "success", "createdAt": "2026-11-26T01:00:00Z"},
            {"databaseId": 2, "conclusion": "failure", "createdAt": "2026-11-26T02:00:00Z"},
            {"databaseId": 3, "conclusion": "failure", "createdAt": "2026-11-26T03:00:00Z"},
            {"databaseId": 4, "conclusion": "cancelled", "createdAt": "2026-11-26T04:00:00Z"},
        ]
        jobs = {
            1: [{"name": "Browser-Szenarien", "conclusion": "success"}],
            2: [{"name": "Browser-Szenarien", "conclusion": "failure"}],
            3: [{"name": "Browser-Szenarien", "conclusion": "failure"}, {"name": "Backend", "conclusion": "failure"}],
            4: [{"name": "Browser-Szenarien", "conclusion": "failure"}],
        }
        with mock.patch.object(q, "lauf_jobs", side_effect=lambda i: jobs[i]):
            self.assertEqual(q.auswerten(laeufe), (3, 2, 1))

    def test_seit_filtert_aeltere_laeufe(self):
        laeufe = [{"databaseId": 1, "conclusion": "failure", "createdAt": "2026-11-25T01:00:00Z"}]
        with mock.patch.object(q, "lauf_jobs", return_value=[{"name": "Browser-Szenarien", "conclusion": "failure"}]):
            self.assertEqual(q.auswerten(laeufe, "2026-11-26T00:00:00Z"), (0, 0, 0))

    def test_ohne_browser_job_zaehlt_nicht(self):
        with mock.patch.object(q, "lauf_jobs", return_value=[{"name": "Build", "conclusion": "success"}]):
            self.assertEqual(q.auswerten([{"databaseId": 1, "conclusion": "success", "createdAt": "x"}]), (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
