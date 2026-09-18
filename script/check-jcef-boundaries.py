#!/usr/bin/env python3
"""Check the independent JCEF and agent module boundaries in spec/code/server/."""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def check_module(root: Path, name: str, package: str, extra_artifacts: tuple[str, ...] = (),
        extra_packages: tuple[str, ...] = ()) -> list[str]:
    module = root / "chat2db-community-server" / name
    errors = []
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(module / "pom.xml")
    allowed_artifacts = {"chat2db-community-tools", *extra_artifacts}
    for dependency in pom.findall(".//m:dependency", namespace):
        group = dependency.findtext("m:groupId", namespaces=namespace)
        artifact = dependency.findtext("m:artifactId", namespaces=namespace)
        if group in {"ai.chat2db", "${project.groupId}", "${pom.groupId}"}:
            if artifact not in allowed_artifacts:
                errors.append(f"{name}/pom.xml: forbidden project dependency {group}:{artifact}")

    allowed = (package + ".", "ai.chat2db.community.tools.", *extra_packages)
    for source in sorted((module / "src").rglob("*.java")):
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            for reference in re.findall(r"\bai\.chat2db\.(?:\w+\.)*\w+", line):
                if not (reference + ".").startswith(allowed):
                    errors.append(f"{source.relative_to(root)}:{number}: forbidden reference {reference}")
    return errors


def check(root: Path) -> list[str]:
    # The desktop JCEF module owns in-app updates, so it may use the updater module and its package.
    errors = check_module(root, "chat2db-community-jcef", "ai.chat2db.community.jcef",
            ("chat2db-community-updater",), ("ai.chat2db.community.updater.",))
    errors += check_module(root, "chat2db-community-agent", "ai.chat2db.community.agent")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = check(args.root.resolve())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("JCEF and agent module boundaries passed: each depends only on tools.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
