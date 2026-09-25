import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

BASE_SHA = "c53140ddbbccb4d0c8f82f9fdf683c7193ee4587"
REPOSITORY = Path(__file__).resolve().parents[1]
PATCHES = [
    REPOSITORY / "patches/0001-effective-interceptor-bindings.patch",
    REPOSITORY / "patches/0002-extension-archive-filter.patch",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("workspace", type=Path)
    parser.add_argument("--owb-source", type=Path)
    parser.add_argument("--export-evidence", type=Path)
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    if workspace == REPOSITORY or REPOSITORY in workspace.parents:
        parser.error("workspace must be outside the reproducer to prevent recursive copying")
    if workspace.exists():
        parser.error("workspace must not exist; previous evidence is never overwritten")
    if args.owb_source and not args.owb_source.is_dir():
        parser.error("--owb-source must be an existing local OpenWebBeans repository")
    if args.export_evidence:
        args.export_evidence = args.export_evidence.resolve()
        if args.export_evidence.exists() or args.export_evidence == workspace or workspace in args.export_evidence.parents:
            parser.error("evidence output must be a new directory outside the workspace")
    for patch in PATCHES:
        if not patch.is_file():
            parser.error(f"missing patch: {patch}")
    workspace.mkdir(parents=True)
    logs = workspace / "logs"
    logs.mkdir()
    reproducer = workspace / "reproducer"
    shutil.copytree(REPOSITORY, reproducer,
                    ignore=shutil.ignore_patterns(".git", "target", "__pycache__", ".DS_Store"))
    patches = [reproducer / "patches" / patch.name for patch in PATCHES]
    manifest = {
        "base_sha": BASE_SHA,
        "patches": {patch.name: hashlib.sha256(patch.read_bytes()).hexdigest() for patch in patches},
        "sources": {str(source.relative_to(reproducer)): hashlib.sha256(source.read_bytes()).hexdigest()
                    for source in reproducer.rglob("*")
                    if source.is_file() and "docs" not in source.relative_to(reproducer).parts
                    and source.suffix in (".java", ".xml", ".properties", ".py")},
        "steps": [],
        "status": "running",
    }

    def save():
        (workspace / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def run(label, command, cwd, expectation="pass"):
        print(f"{label}: running", flush=True)
        started = time.time_ns()
        logfile = logs / f"{label}.log"
        with logfile.open("w") as output:
            output.write("COMMAND " + json.dumps([str(arg) for arg in command]) + "\n")
            output.flush()
            result = subprocess.run([str(arg) for arg in command], cwd=cwd, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=1800)
        suites = []
        report_dir = logs / label
        for report in Path(cwd).rglob("target/surefire-reports/TEST-*.xml"):
            if report.stat().st_mtime_ns < started:
                continue
            root = ET.parse(report).getroot()
            suite = {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            suite["name"] = root.get("name", "")
            suites.append(suite)
            report_dir.mkdir(exist_ok=True)
            shutil.copy2(report, report_dir / report.name)
        step = {"label": label, "command": [str(arg) for arg in command], "cwd": str(cwd),
                "exit_code": result.returncode, "expectation": expectation, "suites": suites}
        manifest["steps"].append(step)
        save()
        if expectation == "pass":
            if result.returncode != 0 or any(suite["failures"] or suite["errors"] for suite in suites):
                raise RuntimeError(f"{label}: unexpected failure ({result.returncode}); see {logfile}")
            if label in ("native-baseline", "native-fixed"):
                expected = {"TestSuite": 1168}
                if label == "native-fixed":
                    expected.update({"TransitiveInterceptorBindingTest": 32, "AbstractMetaDataDiscoveryTest": 13})
                for name, count in expected.items():
                    matches = [suite for suite in suites if suite["name"].split(".")[-1] == name]
                    if len(matches) != 1 or matches[0]["tests"] != count or matches[0]["skipped"]:
                        raise RuntimeError(f"{label}: expected {count} executed tests in {name}")
        else:
            if result.returncode == 0 or not suites:
                raise RuntimeError(f"{label}: expected an executed failing test suite; see {logfile}")
            if expectation == "red-bindings":
                matches = [suite for suite in suites if suite["name"].endswith("BindingGraphTest")]
                if len(matches) != 1 or matches[0]["failures"] < 20 or matches[0]["errors"] or matches[0]["skipped"]:
                    raise RuntimeError(f"{label}: failure did not match the binding defect")
            elif expectation == "red-native":
                for name in ("TransitiveInterceptorBindingTest", "AbstractMetaDataDiscoveryTest"):
                    matches = [suite for suite in suites if suite["name"].endswith(name)]
                    if len(matches) != 1 or not matches[0]["failures"] or matches[0]["errors"] or matches[0]["skipped"]:
                        raise RuntimeError(f"{label}: expected assertion failures in {name}")
            elif expectation == "red-deployment":
                if "AmbiguousResolutionException" not in logfile.read_text():
                    raise RuntimeError(f"{label}: expected duplicate-bean deployment failure")
            elif expectation == "red-retry":
                if "expected: <3> but was: <1>" not in logfile.read_text():
                    raise RuntimeError(f"{label}: expected one invocation instead of three")
        if expectation in ("red-bindings", "red-deployment", "red-retry") or label.startswith(("portable-", "retry-")):
            if not suites or sum(suite["skipped"] for suite in suites):
                raise RuntimeError(f"{label}: missing or skipped reproducer tests")
        if label.startswith("portable-"):
            expected = {"BindingGraphTest": 42, "InterceptorBindingIsolationTest": 1}
            for name, count in expected.items():
                matches = [suite for suite in suites if suite["name"].split(".")[-1] == name]
                if len(matches) != 1 or matches[0]["tests"] != count:
                    raise RuntimeError(f"{label}: expected {count} executed tests in {name}")
        print(f"{label}: verified ({result.returncode})", flush=True)
        return suites

    def mvn(label, project, *arguments, expectation="pass"):
        return run(label, ["mvn", "-B", "-ntp", f"-Dmaven.repo.local={workspace / 'm2'}", *arguments],
                   project, expectation)

    def export_evidence():
        if not args.export_evidence:
            return
        destination = args.export_evidence
        destination.mkdir(parents=True)
        replacements = {str(workspace): "<workdir>", str(REPOSITORY): "<reproducer>", str(Path.home()): "<home>"}
        if args.owb_source:
            replacements[str(args.owb_source.resolve())] = "<owb-source>"

        def normalized(text):
            for path, replacement in sorted(replacements.items(), key=lambda item: len(item[0]), reverse=True):
                text = text.replace(path, replacement)
            return text

        published = json.loads(normalized(json.dumps(manifest)))
        published["normalization"] = "Absolute workspace/source/home paths replaced; XML JVM properties and hostname omitted. Test results unchanged."
        (destination / "manifest.json").write_text(json.dumps(published, indent=2) + "\n")
        for logfile in logs.glob("*.log"):
            (destination / logfile.name).write_text(normalized(logfile.read_text()))
        selected = {"TransitiveInterceptorBindingTest", "AbstractMetaDataDiscoveryTest",
                    "BindingGraphTest", "InterceptorBindingIsolationTest", "RetryInterceptorBindingTest"}
        for report in logs.rglob("TEST-*.xml"):
            root = ET.parse(report).getroot()
            if root.get("name", "").split(".")[-1] not in selected:
                continue
            for suite in root.iter("testsuite"):
                suite.attrib.pop("hostname", None)
                for properties in suite.findall("properties"):
                    suite.remove(properties)
            for element in root.iter():
                if element.text:
                    element.text = normalized(element.text)
                if element.tail:
                    element.tail = normalized(element.tail)
                for name, value in element.attrib.items():
                    element.set(name, normalized(value))
            output = destination / report.relative_to(logs)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(ET.tostring(root, encoding="unicode") + "\n")
            ET.parse(output)

    try:
        run("java-version", ["java", "-version"], workspace)
        run("maven-version", ["mvn", "-version"], workspace)
        baseline = workspace / "baseline"
        if args.owb_source:
            run("clone-baseline", ["git", "clone", "--no-hardlinks", "--no-checkout",
                                  args.owb_source.resolve(), baseline], workspace)
        else:
            run("clone-baseline", ["gh", "repo", "clone", "apache/openwebbeans", baseline,
                                  "--", "--no-checkout"], workspace)
        run("checkout-baseline", ["git", "checkout", "--detach", BASE_SHA], baseline)
        mvn("native-baseline", baseline, "-pl", "webbeans-se,webbeans-tck", "-am", "verify")
        for index, patch in enumerate(patches):
            run(f"baseline-tests-{index}", ["git", "apply", "--include=*/src/test/*", patch], baseline)
        mvn("native-red", baseline, "-pl", "webbeans-impl", "-am",
            "-Dtest=TransitiveInterceptorBindingTest,AbstractMetaDataDiscoveryTest",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test", expectation="red-native")

        fixed = workspace / "fixed"
        run("clone-fixed", ["git", "clone", "--no-hardlinks", "--no-checkout", baseline, fixed], workspace)
        run("checkout-fixed", ["git", "checkout", "--detach", BASE_SHA], fixed)
        run("apply-fixes", ["git", "apply", *patches], fixed)
        mvn("native-fixed", fixed, "-pl", "webbeans-se,webbeans-tck", "-am", "install")
        run("fixed-diff-check", ["git", "diff", "--check"], fixed)

        isolation = reproducer / "cdi-isolation"
        mvn("portable-weld6", isolation, "-Pweld", "test")
        mvn("portable-weld5", isolation, "-Pweld", "-Dversion.weld-se=5.1.3.Final",
            "-Dcdi.api.version=4.0.1", "-Dversion.interceptor-api=2.1.0", "test")
        for version in ("4.0.0", "4.0.1", "4.0.2", "4.0.3", "4.1.0", "4.1.1"):
            mvn(f"portable-owb-{version}", isolation, "-Powb", f"-Dversion.owb={version}",
                "test", expectation="red-bindings")
        mvn("portable-owb-fixed", isolation, "-Powb", "-Dversion.owb=4.1.2-SNAPSHOT", "test")
        mvn("retry-weld", reproducer, "-Pweld", "test")
        mvn("retry-released-discovery", reproducer, "-Powb", "test", expectation="red-deployment")
        mvn("retry-released-isolated", reproducer, "-Powb", "-Dorg.apache.webbeans.scanBeansXmlOnly=true",
            "test", expectation="red-retry")
        mvn("retry-fixed-discovery", reproducer, "-Powb", "-Dversion.owb=4.1.2-SNAPSHOT", "test")
        manifest["status"] = "verified"
        save()
        export_evidence()
        print(f"Verified. Evidence: {workspace / 'manifest.json'}", flush=True)
    except (RuntimeError, subprocess.TimeoutExpired, OSError, ET.ParseError) as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        save()
        raise


if __name__ == "__main__":
    main()
