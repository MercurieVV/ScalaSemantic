#!/usr/bin/env python3
import os
import sys
import json
import glob
import subprocess
import argparse
import difflib

# Regression output files
REGRESSIONS_DIR = "regressions"
FILES = {
    "unit": os.path.join(REGRESSIONS_DIR, "unit-tests.json"),
    "e2e": os.path.join(REGRESSIONS_DIR, "e2e-tests.json"),
    "stryker": os.path.join(REGRESSIONS_DIR, "stryker-mutants.json")
}

def load_json(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)

def save_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")

def collect_unit_tests():
    unit_tests = []
    for root, dirs, files in os.walk("out"):
        for file in files:
            if file == "out.json" and ("testForked.dest" in root or "testOnly.dest" in root):
                path = os.path.join(root, file)
                try:
                    data = load_json(path)
                    # Standard Mill test format: ["", [test1, test2, ...]]
                    if isinstance(data, list) and len(data) > 1 and isinstance(data[1], list):
                        for test in data[1]:
                            if isinstance(test, dict) and "fullyQualifiedName" in test:
                                if test.get("status") == "Success":
                                    unit_tests.append(test["fullyQualifiedName"])
                except Exception as e:
                    print(f"Warning: failed to parse unit test output {path}: {e}", file=sys.stderr)
    return sorted(list(set(unit_tests)))

def collect_e2e_tests():
    e2e_tests = []
    e2e_path = os.path.join("out", "e2e-report.json")
    if os.path.isfile(e2e_path):
        try:
            data = load_json(e2e_path)
            if isinstance(data, list):
                e2e_tests = data
        except Exception as e:
            print(f"Warning: failed to parse E2E report {e2e_path}: {e}", file=sys.stderr)
    return sorted(list(set(e2e_tests)))

def collect_stryker_mutants():
    stryker_mutants = []
    # Find reports in reports/mutation/*/report.json and the root mutation-report.json
    paths = glob.glob("reports/mutation/*/report.json")
    if os.path.isfile("mutation-report.json"):
        paths.append("mutation-report.json")
        
    for path in paths:
        module_name = os.path.basename(os.path.dirname(path))
        if path == "mutation-report.json":
            module_name = "analysis" # Default fallback for sbt-era root report
        try:
            report = load_json(path)
            files = report.get("files", {})
            for file_path, file_data in files.items():
                mutants = file_data.get("mutants", [])
                for mutant in mutants:
                    mutator = mutant.get("mutatorName", "Unknown")
                    status = mutant.get("status", "Unknown")
                    repl = mutant.get("replacement", "")
                    # Clean up replacement to keep it on one line and short
                    repl_clean = repl.replace("\n", "\\n")[:50]
                    # Map NoCoverage to Survived to prevent flaky CI coverage reporting
                    status_mapped = "Survived" if status in ("Survived", "NoCoverage") else status
                    # Format descriptor without line/col to prevent line shift noise
                    desc = f"Stryker:{module_name}:{file_path}:{mutator}:{repl_clean} -> {status_mapped}"
                    stryker_mutants.append(desc)
        except Exception as e:
            print(f"Warning: failed to parse Stryker report {path}: {e}", file=sys.stderr)
    return sorted(list(set(stryker_mutants)))

def generate_all(categories):
    results = {}
    if "unit" in categories:
        results["unit"] = collect_unit_tests()
    if "e2e" in categories:
        results["e2e"] = collect_e2e_tests()
    if "stryker" in categories:
        results["stryker"] = collect_stryker_mutants()
    return results

def get_base_file_content(base_ref, path):
    try:
        cmd = ["git", "show", f"{base_ref}:{path}"]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return result.stdout
    except subprocess.CalledProcessError:
        return None

def is_approved():
    # 1. Environment variable approval
    if os.environ.get("REGRESSION_APPROVED", "").lower() in ("true", "1", "yes"):
        return True

    # 2. Latest commit message approval
    try:
        res = subprocess.run(["git", "log", "-1", "--pretty=%B"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        commit_msg = res.stdout.lower()
        if "[approve-regression]" in commit_msg or "[approve regression]" in commit_msg:
            return True
    except Exception:
        pass

    # 3. GITHUB_EVENT_PATH PR label approval
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path and os.path.exists(event_path):
        try:
            with open(event_path, "r") as f:
                event_data = json.load(f)
                labels = event_data.get("pull_request", {}).get("labels", [])
                for label in labels:
                    if label.get("name") == "regression-approved":
                        return True
        except Exception as e:
            print(f"Warning: failed to read GITHUB_EVENT_PATH: {e}", file=sys.stderr)

    return False

def get_available_mutation_modules():
    modules = []
    for path in glob.glob("reports/mutation/*/report.json"):
        modules.append(os.path.basename(os.path.dirname(path)))
    if os.path.isfile("mutation-report.json"):
        modules.append("analysis")
    return list(set(modules))

def check_consistency(generated, categories):
    mismatch = False
    for category in categories:
        path = FILES[category]
        if not os.path.exists(path):
            print(f"Error: Regression file {path} does not exist. Please generate it first.")
            mismatch = True
            continue
        try:
            committed = load_json(path)
        except Exception as e:
            print(f"Error: Failed to load {path}: {e}")
            mismatch = True
            continue

        actual_gen = generated[category]
        actual_com = committed

        # If checking stryker, filter to keep only modules that were actually run/generated
        if category == "stryker":
            active_modules = get_available_mutation_modules()
            # If no mutation tests were run, skip checking stryker consistency in this run
            if not active_modules:
                print("Info: No Stryker reports found in reports/mutation/. Skipping stryker consistency check.")
                continue
            actual_gen = [m for m in actual_gen if m.split(":")[1] in active_modules]
            actual_com = [m for m in committed if m.split(":")[1] in active_modules]

        if actual_com != actual_gen:
            print(f"Mismatch in {path}!")
            # Print diff
            committed_str = json.dumps(actual_com, indent=2, sort_keys=True).splitlines()
            generated_str = json.dumps(actual_gen, indent=2, sort_keys=True).splitlines()
            diff = difflib.unified_diff(committed_str, generated_str, fromfile=f"committed/{category}", tofile=f"generated/{category}", lineterm='')
            print("\n".join(diff))
            mismatch = True

    if mismatch:
        print("\nTest results do not match committed regression files!")
        print("Please regenerate and commit them using:")
        print("  python3 scripts/generate-regressions.py --write")
        return False
    
    print("Committed regression files are up to date.")
    return True

def check_regressions(base_ref, categories):
    print(f"Checking for regressions against base ref: {base_ref} ...")
    regressions_found = False
    missing_items_by_cat = {}

    for category in categories:
        path = FILES[category]
        base_content = get_base_file_content(base_ref, path)
        if base_content is None:
            print(f"Info: File {path} does not exist on base ref {base_ref}. Skipping category '{category}'.")
            continue

        try:
            base_data = json.loads(base_content)
        except Exception as e:
            print(f"Error: Failed to parse base ref content of {path}: {e}", file=sys.stderr)
            continue

        try:
            curr_data = load_json(path)
        except Exception as e:
            print(f"Error: Failed to load current version of {path}: {e}", file=sys.stderr)
            continue

        actual_base = base_data
        actual_curr = curr_data

        if category == "stryker":
            active_modules = get_available_mutation_modules()
            # If no mutation tests were run, skip checking stryker regressions in this run
            if not active_modules:
                print("Info: No Stryker reports found in reports/mutation/. Skipping stryker regression check.")
                continue
            actual_base = [m for m in base_data if m.split(":")[1] in active_modules]
            actual_curr = [m for m in curr_data if m.split(":")[1] in active_modules]

        # Convert to set for fast lookup
        curr_set = set(actual_curr)
        missing = [item for item in actual_base if item not in curr_set]
        if missing:
            missing_items_by_cat[category] = missing
            regressions_found = True

    if regressions_found:
        print("\n⚠️ REGRESSION DETECTED!")
        print("The following tests/mutants disappeared compared to the base branch:")
        for category, items in missing_items_by_cat.items():
            print(f"\n[{category}] - {len(items)} items removed:")
            for item in items:
                print(f"  - {item}")
        
        if is_approved():
            print("\n✅ Regression was explicitly approved (via PR label, commit message, or environment variable).")
            return True
        else:
            print("\n❌ Check failed. Action required:")
            print("  1. Fix the regression (restore the missing tests or behavior).")
            print("  2. OR obtain explicit approval by doing one of the following:")
            print("     - Add the 'regression-approved' label to the Pull Request.")
            print("     - Include '[approve-regression]' in your latest commit message.")
            print("     - Set the env var REGRESSION_APPROVED=true in your environment.")
            return False
    else:
        print("No regressions detected (no tests or mutants were removed).")
        return True

def main():
    parser = argparse.ArgumentParser(description="Generate and verify regression profiles for unit, property, E2E, and mutation tests.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true", help="Generate and write regression profiles to regressions/")
    group.add_argument("--check", action="store_true", help="Verify that the committed regressions match current test outputs")
    group.add_argument("--check-regression", action="store_true", help="Compare current regressions against base branch to detect deleted items")
    
    parser.add_argument("--base", default="origin/master", help="Base ref/branch to compare against for --check-regression (default: origin/master)")
    parser.add_argument("--categories", nargs="+", choices=["unit", "e2e", "stryker"], default=["unit", "e2e", "stryker"], help="Categories to process (default: unit e2e stryker)")
    
    args = parser.parse_args()

    if args.write:
        generated = generate_all(args.categories)
        for category in args.categories:
            path = FILES[category]
            save_json(path, generated[category])
            print(f"Wrote {path} ({len(generated[category])} items)")
        sys.exit(0)

    elif args.check:
        generated = generate_all(args.categories)
        if not check_consistency(generated, args.categories):
            sys.exit(1)
        sys.exit(0)

    elif args.check_regression:
        if not check_regressions(args.base, args.categories):
            sys.exit(1)
        sys.exit(0)

if __name__ == "__main__":
    main()
