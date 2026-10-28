#!/usr/bin/env python3
"""Builds the bundled exercise library from free-exercise-db and the curation file.

free-exercise-db (https://github.com/yuhonas/free-exercise-db) is public domain (Unlicense).

    curl -sSLo /tmp/exercises.json https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json
    python3 tools/exercise-seed/build_seed.py --dataset /tmp/exercises.json

By default only the curated, featured subset is written (delivery slice 1). `--all` writes every dataset
exercise with the curated ones marked featured; that is the input for the server-side import in slice 4.
Ids are UUIDv5 of (source, sourceId), so re-running the import upserts the same records.
"""

import argparse
import json
import pathlib
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "shared/app/src/commonMain/composeResources/files/exercise_library.json"
CURATION = pathlib.Path(__file__).with_name("curation.tsv")
SOURCE = "free-exercise-db"
ID_NAMESPACE = uuid.UUID("6f1c2b7e-3d4a-4c1e-9f0a-5b8e2d7c9a10")

MUSCLES = {
    "abdominals": "ABDOMINALS", "abductors": "ABDUCTORS", "adductors": "ADDUCTORS", "biceps": "BICEPS",
    "calves": "CALVES", "chest": "CHEST", "forearms": "FOREARMS", "glutes": "GLUTES", "hamstrings": "HAMSTRINGS",
    "lats": "LATS", "lower back": "LOWER_BACK", "middle back": "MIDDLE_BACK", "neck": "NECK",
    "quadriceps": "QUADRICEPS", "shoulders": "SHOULDERS", "traps": "TRAPS", "triceps": "TRICEPS",
}
EQUIPMENT = {
    "barbell": "BARBELL", "dumbbell": "DUMBBELL", "cable": "CABLE", "machine": "MACHINE",
    "kettlebells": "KETTLEBELL", "bands": "BANDS", "body only": "BODY_ONLY", "medicine ball": "MEDICINE_BALL",
    "exercise ball": "EXERCISE_BALL", "foam roll": "FOAM_ROLL", "e-z curl bar": "EZ_BAR", "other": "OTHER", None: "OTHER",
}
TRACKING_TYPES = {"WEIGHT_REPS", "REPS", "TIME", "DISTANCE_TIME"}


def read_curation(path):
    curated = {}
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) != 4:
            sys.exit(f"{path}:{line_no}: expected 4 tab-separated columns, got {len(cols)}")
        source_id, pl, aliases, tracking = (c.strip() for c in cols)
        if tracking and tracking not in TRACKING_TYPES:
            sys.exit(f"{path}:{line_no}: unknown tracking type {tracking!r}")
        if source_id in curated:
            sys.exit(f"{path}:{line_no}: duplicate id {source_id}")
        curated[source_id] = {
            "pl": pl,
            "aliases": [a.strip() for a in aliases.split(";") if a.strip()],
            "tracking": tracking or None,
        }
    return curated


def default_tracking(record):
    if record.get("category") == "cardio":
        return "DISTANCE_TIME"
    if record.get("equipment") == "body only":
        return "REPS"
    return "WEIGHT_REPS"


def convert(record, curation):
    unknown = [m for m in record["primaryMuscles"] + record["secondaryMuscles"] if m not in MUSCLES]
    if unknown or record.get("equipment") not in EQUIPMENT:
        sys.exit(f"{record['id']}: unmapped dataset values {unknown or record.get('equipment')!r}")
    names = {"en": record["name"]}
    if curation:
        names["pl"] = curation["pl"]
    out = {
        "id": str(uuid.uuid5(ID_NAMESPACE, f"{SOURCE}/{record['id']}")),
        "source": SOURCE,
        "sourceId": record["id"],
        "names": names,
        "primaryMuscles": [MUSCLES[m] for m in record["primaryMuscles"]],
        "equipment": EQUIPMENT[record.get("equipment")],
        "trackingType": (curation or {}).get("tracking") or default_tracking(record),
    }
    if curation and curation["aliases"]:
        out["aliases"] = curation["aliases"]
    if record["secondaryMuscles"]:
        out["secondaryMuscles"] = [MUSCLES[m] for m in record["secondaryMuscles"]]
    if record.get("instructions"):
        out["instructions"] = {"en": record["instructions"]}
    if record.get("images"):
        # Storage keys. Slice 4 copies the files to our storage; they are never hotlinked.
        out["images"] = [f"{SOURCE}/{image}" for image in record["images"]]
    if curation:
        out["featured"] = True
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=pathlib.Path, help="free-exercise-db dist/exercises.json")
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    parser.add_argument("--all", action="store_true", help="include every dataset exercise, not only the curated ones")
    args = parser.parse_args()

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    curation = read_curation(CURATION)
    by_id = {r["id"]: r for r in dataset}
    missing = sorted(set(curation) - set(by_id))
    if missing:
        sys.exit(f"curation ids not in the dataset: {missing}")

    records = dataset if args.all else [by_id[i] for i in curation]
    exercises = sorted((convert(r, curation.get(r["id"])) for r in records), key=lambda e: e["sourceId"])

    args.out.parent.mkdir(parents=True, exist_ok=True)
    # One exercise per line keeps diffs reviewable.
    lines = ",\n".join(json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in exercises)
    args.out.write_text(f"[\n{lines}\n]\n", encoding="utf-8")
    print(f"wrote {len(exercises)} exercises ({sum(1 for e in exercises if e.get('featured'))} featured) to {args.out}")


if __name__ == "__main__":
    main()
