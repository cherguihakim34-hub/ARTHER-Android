import unittest

from safe_experiment import ExperimentError, run_experiment


class SafeExperimentTests(unittest.TestCase):
    def test_bounded_numeric_simulation(self):
        result = run_experiment(
            "position = 0\n"
            "velocity = 2\n"
            "for step in range(5):\n"
            "    position = position + velocity\n"
            "    results.append(position)\n"
            "print(results)\n"
        )
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["variables"]["position"], 10)
        self.assertEqual(result["results"], [2, 4, 6, 8, 10])
        self.assertIn("[2, 4, 6, 8, 10]", result["stdout"])

    def test_math_functions_and_conditionals(self):
        result = run_experiment(
            "angle = radians(90)\n"
            "value = round(sin(angle), 5)\n"
            "if value > 0.9:\n"
            "    print(\"PASS\")\n"
            "else:\n"
            "    print(\"FAIL\")\n"
        )
        self.assertEqual(result["variables"]["value"], 1.0)
        self.assertEqual(result["stdout"], "PASS")

    def test_imports_are_rejected(self):
        with self.assertRaises(ExperimentError):
            run_experiment("import os\nprint(os.getcwd())")

    def test_attribute_access_is_rejected(self):
        with self.assertRaises(ExperimentError):
            run_experiment("print(pi.__class__)")

    def test_range_is_bounded(self):
        with self.assertRaises(ExperimentError):
            run_experiment("for i in range(5001):\n    print(i)")

    def test_reserved_names_cannot_be_overwritten(self):
        with self.assertRaises(ExperimentError):
            run_experiment("results = []")

    def test_large_string_allocation_is_rejected(self):
        with self.assertRaises(ExperimentError):
            run_experiment('huge = "x" * 1000000000')

    def test_nested_loops_obey_operation_budget(self):
        with self.assertRaises(ExperimentError):
            run_experiment(
                "for i in range(5000):\\n"
                "    for j in range(5000):\\n"
                "        pass\\n"
            )

    def test_empty_source_is_rejected(self):
        with self.assertRaises(ExperimentError):
            run_experiment("   ")


if __name__ == "__main__":
    unittest.main()
