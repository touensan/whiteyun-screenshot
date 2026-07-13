import argparse
import json
import os
import subprocess


PKG = "com.whiteyun.screenshot"
ROOT = "/data/user/0/com.whiteyun.screenshot/files/stitch-debug"


def adb_bytes(serial, *args):
    command = ["adb"]
    if serial:
        command.extend(["-s", serial])
    command.extend(args)
    return subprocess.check_output(command)


def adb_text(serial, *args):
    return adb_bytes(serial, *args).decode("utf-8", "replace")


def app_cat(serial, path):
    return adb_bytes(serial, "exec-out", "run-as", PKG, "cat", path)


def latest_session(serial):
    listing = adb_text(serial, "shell", "run-as", PKG, "ls", "-1", ROOT)
    sessions = sorted(line.strip() for line in listing.splitlines() if line.strip().startswith("stitch_"))
    if not sessions:
        raise RuntimeError("no stitch-debug session found")
    return sessions[-1]


def pull_file(serial, remote_path, local_path):
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    with open(local_path, "wb") as handle:
        handle.write(app_cat(serial, remote_path))


def basename(path):
    return os.path.basename(path.rstrip("/"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--session", default="")
    args = parser.parse_args()

    session = args.session or latest_session(args.serial)
    remote_dir = f"{ROOT}/{session}"
    local_dir = os.path.abspath(os.path.join(args.output_dir, session))
    os.makedirs(local_dir, exist_ok=True)

    manifest_path = f"{remote_dir}/manifest.json"
    manifest = json.loads(app_cat(args.serial, manifest_path).decode("utf-8", "replace"))
    local_manifest = os.path.join(local_dir, "manifest.json")
    with open(local_manifest, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    pulled = []
    preview = manifest.get("previewFile", "")
    if preview:
        local_preview = os.path.join(local_dir, basename(preview))
        pull_file(args.serial, preview, local_preview)
        pulled.append(local_preview)

    for frame in manifest.get("frames", []):
        remote = frame.get("file", "")
        if not remote:
            continue
        local = os.path.join(local_dir, basename(remote))
        pull_file(args.serial, remote, local)
        pulled.append(local)

    result = {
        "session": session,
        "remoteDir": remote_dir,
        "localDir": local_dir,
        "manifest": local_manifest,
        "pulled": pulled,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
