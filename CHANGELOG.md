# Changelog

## 0.1.2

- `Meter#pulses`, `Meter#indispensabilityAt` and `RhythmCell#articulatesIn`:
  derived metric queries for pulse offsets, Barlow ranks at offsets, and group
  articulation.
- ScoreJSON refuses recipe/provenance keys at the score root, staff, measure
  and note levels.
- `ScoreEdit.fillTalea`: fill consecutive measure voices from one `Talea`,
  tying counts that cross barlines.

## 0.1.1

- `SpellingPolicy`: pitch-spelling choices, named accidental
  preferences, motion-aware spelling, `\written` preservation
- `ScoreEdit.spellPitches` and `ScoreSelection#spellPitches`: apply spelling

## 0.1.0

- Core score tree: scores, staves, measures, voices, notes, rests, chords,
  tuplets, ties, graces, markings, spanners and directions, with exact
  durations, pitches, intervals and meters.
- Authoring through constructors, compact notation, quasiquoters, RTM cells,
  `Talea`, and finite pitch/rhythm material.
- Copy-returning selection, editing, diff and history tools through
  `ScoreSelection`, `ScoreEdit`, `ScoreDiff`, `ScoreChange` and `ScoreHistory`.
- Writers for notation backends, ScoreJSON v32 and SuperCollider
  Events/Patterns, with playback profiles.
- Preparation, validation, inspection, doctest tools
