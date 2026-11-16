"""Validate that JaCoCo recorded covered lines in app sources."""

# AI assistance: OpenAI Codex.
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def has_covered_app_source(report: ET.Element, source_root: Path) -> bool:
    """Require a compiled class and covered lines backed by a main-source file."""
    if report.tag != "report":
        return False

    for package in report.findall("package"):
        package_dir = source_root / package.attrib["name"]
        compiled_sources = {cls.get("sourcefilename") for cls in package.findall("class")}
        for source in package.findall("sourcefile"):
            filename = source.attrib["name"]
            if filename in compiled_sources and (package_dir / filename).is_file():
                lines = source.find("counter[@type='LINE']")
                if lines is not None and int(lines.get("covered", "0")) > 0:
                    return True
    return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("source_root", type=Path)
    args = parser.parse_args()
    if not has_covered_app_source(ET.parse(args.report).getroot(), args.source_root):
        raise SystemExit("Coverage XML must contain compiled app sources with covered lines")
