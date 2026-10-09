#!/usr/bin/env python3
"""Package the entire Quarkus fast-JAR runtime without build tools or local data."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tarfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-hoos\.[0-9]+", args.version):
        parser.error("Expected a fork version such as 2.2.0-hoos.1")
    root = Path(__file__).resolve().parents[2]
    runtime = root / "target/quarkus-app"
    if not (runtime / "quarkus-run.jar").is_file():
        parser.error("Build target/quarkus-app before packaging")
    args.output.mkdir(parents=True, exist_ok=True)
    stage = args.output / f"floci-{args.version}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    shutil.copytree(runtime, stage / "quarkus-app")
    shutil.copytree(root / "licenses", stage / "licenses")
    for source, destination in [
        ("LICENSE", "LICENSE"),
        ("docs/getting-started/installation.md", "INSTALL.md"),
        ("docs/configuration/security-hardening.md", "SECURITY-HARDENING.md"),
        ("docker-compose.published.yml", "compose.yaml"),
        ("tools/release/run.sh", "run.sh"),
        ("tools/release/run.cmd", "run.cmd"),
    ]:
        shutil.copyfile(root / source, stage / destination)
    (stage / "run.sh").chmod(0o755)
    (stage / "version.txt").write_text(args.version + "\n")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    (stage / "build-info.json").write_text(json.dumps({
        "version": args.version,
        "source": "https://github.com/stephenhoos/floci",
        "commit": commit,
        "java": "25",
        "runtime": "Quarkus fast-JAR; keep the entire quarkus-app directory",
    }, indent=2) + "\n")
    base = args.output / f"floci-{args.version}-jvm"
    with tarfile.open(str(base) + ".tar.gz", "w:gz") as archive:
        archive.add(stage, arcname=stage.name)
    shutil.make_archive(str(base), "zip", root_dir=args.output, base_dir=stage.name)
    shutil.copyfile(root / "docker-compose.published.yml", args.output / "compose.yaml")
    files = [Path(str(base) + ".tar.gz"), Path(str(base) + ".zip"), args.output / "compose.yaml"]
    (args.output / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files))
    shutil.rmtree(stage)
    for path in files:
        print(path)


if __name__ == "__main__":
    main()
