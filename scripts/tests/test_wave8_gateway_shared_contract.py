from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
APPLICATION = ROOT / "api-gateway" / "src" / "main" / "resources" / "application.yml"
BUILD = ROOT / "api-gateway" / "build.gradle"


class TestWave8GatewaySharedContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.application = APPLICATION.read_text(encoding="utf-8")
        cls.build = BUILD.read_text(encoding="utf-8")

    def test_application_freezes_tracing_and_approved_durations_once(self):
        self.assertRegex(
            self.application,
            r"(?m)^  reactor:\n    context-propagation: auto$",
        )
        expected = {
            "idle-threshold": ("APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD", "30m"),
            "eligibility-timeout": ("APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT", "45s"),
            "reset-timeout": ("APP_DEMO_LOGIN_RESET_RESET_TIMEOUT", "10s"),
            "overall-timeout": ("APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT", "60s"),
        }
        self.assertEqual(1, self.application.count("  demo-login-reset:\n"))
        for property_name, (environment_name, value) in expected.items():
            self.assertEqual(
                1,
                self.application.count(
                    f"    {property_name}: ${{{environment_name}:{value}}}"
                ),
            )
            self.assertEqual(1, self.application.count(environment_name))

    def test_tracing_test_dependency_is_declared_once(self):
        dependency = (
            "testImplementation "
            "'org.springframework.boot:spring-boot-micrometer-tracing-test'"
        )
        self.assertEqual(1, self.build.count(dependency))

    def test_portfolio_service_is_isolated_to_wave8_source_set(self):
        self.assertIn("wave8IntegrationTest", self.build)
        self.assertEqual(
            1,
            self.build.count(
                "wave8IntegrationTestImplementation project(':portfolio-service')"
            ),
        )
        self.assertNotIn("testImplementation project(':portfolio-service')", self.build)
        self.assertNotIn("implementation project(':portfolio-service')", self.build)

    def test_dedicated_task_is_collected_by_root_integration_gate(self):
        self.assertRegex(
            self.build,
            r"(?s)tasks\.register\('wave8IntegrationTest', Test\).*?"
            r"testClassesDirs = sourceSets\.wave8IntegrationTest\.output\.classesDirs.*?"
            r"classpath = sourceSets\.wave8IntegrationTest\.runtimeClasspath",
        )
        integration_block = re.search(
            r"(?s)tasks\.named\('integrationTest'\) \{(?P<body>.*?)\n\}",
            self.build,
        )
        self.assertIsNotNone(integration_block)
        self.assertIn(
            "dependsOn tasks.named('wave8IntegrationTest')",
            integration_block.group("body"),
        )


if __name__ == "__main__":
    unittest.main()
