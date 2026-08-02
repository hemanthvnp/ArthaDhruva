"""Top-level orchestrator: run extract -> readable -> clean -> preprocess in order."""

from __future__ import annotations

import argparse
import time

from pipeline import clean, config, extract, preprocess, readable


def run(quarters: list[config.Quarter], stages: list[str], force: bool) -> None:
    config.ensure_output_dirs()
    stage_fns = {
        "extract": extract.extract_all,
        "readable": readable.convert_all,
        "clean": clean.clean_all,
        "preprocess": preprocess.preprocess_all,
    }
    for stage in stages:
        print(f"\n=== Stage: {stage} ({len(quarters)} quarter(s)) ===")
        t0 = time.time()
        stage_fns[stage](quarters, force=force)
        print(f"=== Stage {stage} finished in {time.time() - t0:.1f}s ===")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the full mortgage data pipeline.")
    parser.add_argument("--quarter", action="append", help="Specific quarter label (e.g. 2025Q3); repeatable")
    parser.add_argument(
        "--stage",
        action="append",
        choices=["extract", "readable", "clean", "preprocess"],
        help="Specific stage to run; repeatable. Defaults to all stages in order.",
    )
    parser.add_argument("--force", action="store_true", help="Re-run stages even if outputs already exist")
    args = parser.parse_args()

    quarters = [config.parse_quarter(q) for q in args.quarter] if args.quarter else config.all_quarters()
    stages = args.stage or ["extract", "readable", "clean", "preprocess"]

    run(quarters, stages, force=args.force)


if __name__ == "__main__":
    main()
