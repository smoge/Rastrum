# Changelog

## Unreleased

## 0.1.3

- Phrase fills: `ScoreEdit.fillPhrase` spreads one rhythm cell across
  consecutive bars, `fillMeasuresWithCells` fills bars from a cell chosen per
  bar and `fillTalea` refuses span loss. `RhythmCell.regionAt` locates a share
  in a span, and `flatTimingForm` flattens a cell to its timing.
- Exact fields and sieves: `DiscreteField.membersBetween` enumerates exact
  grids and `MusicSieve`. It parses its own notation with `MusicSieve.parse`,
  embeds in finer lattices with `refined`, compares fields with `sameFieldAs`
  and lowers to predicates.
- Attack taleas: `Talea.fromAttackOffsets` and `fromAttackPulses` build taleas
  from where attacks fall.
- Fixes: breath and caesura follow a split note's ending, and LilyPond writes
  them after its other marks. Pitch cents survive ScoreJSON round trips. Pitch,
  interval and interval-name cents survive source storage.

## 0.1.2

- `Meter.pulses`, `Meter.indispensabilityAt` and `RhythmCell.articulatesIn`:
  derived metric queries for pulse offsets, Barlow ranks at offsets, and group
  articulation.
- ScoreJSON refuses recipe/provenance keys at the score root, staff, measure
  and note levels.
- `ScoreEdit.fillTalea`: fill consecutive measure voices, tying counts that
  cross barlines.

## 0.1.1

- `SpellingPolicy`: pitch-spelling choices, including named accidental
  preferences, motion-aware spelling, `\written` preservation.
- `ScoreEdit.spellPitches` and `ScoreSelection#spellPitches`: apply spelling
  policies to selected material.

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
- Preparation, validation, inspection, executable docs, fixtures, engraving
  checks and generated asset gates.
