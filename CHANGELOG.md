# Changelog

## 0.1.0

- A score model carrying no writer syntax, so a new output format is a new
  `ScoreWriter` subclass and nothing else.
- Exact rational score time, including the ScoreJSON wire.
- Three ways to write the same tree: constructors, compact parsers and
  quasiquoters.
- Proportional rhythm through `RhythmTree` and `RhythmCell`, with a divisor that
  reads the meter rather than guessing a power of two.
- Preparation that rewrites what no note head can spell, and validation that
  refuses what does not add up.
- Writers for LilyPond, MusicXML, GUIDO and ScoreJSON, plus SuperCollider
  events and patterns with optional interpretation layers.
- Reading and editing through `ScoreSelection`, `ScoreDiff` and `ScoreEdit`. The
  edit lane is experimental.

The ScoreJSON wire is at v31. Recent pre-release bumps widened closed
vocabularies or added endpoint facts without changing the surrounding tree
shape.

MusicXML reading is not here. The route in is MusicXML to metasonic-score, then
ScoreJSON.
