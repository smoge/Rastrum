# Changelog

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
