# Station map validation

## National catalog conformance

On November 6, 2026, the Android sweep compared the pinned iOS catalog at the same 20 lunar-month instants used by iOS NationalScaleTests. Across 5,028 tide stations, the hybrid calculation resolved 100,034 of 100,560 directions (99.48%). It disagreed with the exact next turn 212 times (0.21% of resolved directions). Every disagreement occurred within 30 minutes of a turn. The iOS limits are greater than 99% coverage, less than 1% near-turn lag and zero disagreements away from turns.

The grid test uses the shipped harmonic and subordinate tide and current positions. The East Coast box (25°–45° N, 95°–66° W) retains 29, 138 and 610 stations at zooms 3, 5 and 7, below the 900-station limit. The Charleston harbour box (32.70°–32.87° N, 80.05°–79.83° W) retains 58 of 61 stations at zoom 12.5 (95.1%, above the 80% minimum).

```sh
rtk ./gradlew :core:test :core:nationalTideDirectionSweep
```
