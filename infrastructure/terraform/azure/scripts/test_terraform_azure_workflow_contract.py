from pathlib import Path
import re
import unittest


class TerraformAzureCertificateListContractTests(unittest.TestCase):
    def test_all_custom_domain_certificate_list_calls_use_name(self):
        repo_root = Path(__file__).resolve().parents[4]
        workflow = (repo_root / ".github/workflows/terraform-azure.yml").read_text(
            encoding="utf-8"
        )
        invocations = re.findall(
            r"az containerapp env certificate list\s+\\\s*\n"
            r"\s*--([a-z-]+)\s+([^\s\\]+)",
            workflow,
        )
        self.assertEqual(6, len(invocations))
        self.assertEqual(
            [("name", "wealth-prod-aca-env")] * 6,
            invocations,
        )


if __name__ == "__main__":
    unittest.main()
