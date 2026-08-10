# Rastrum

Music notation for SuperCollider: a format-neutral score model with LilyPond,
MusicXML, and JSON writers.

## Status

Experimental. Rastrum builds a score tree directly or from proportional rhythm,
then writes LilyPond, MusicXML, ScoreJSON, SuperCollider `Event`s, or patterns.
`ScorePrepare` rewrites durations no single note head can spell into tied,
notatable leaves. `Rastrum.render`, `Rastrum.preview` and
`Rastrum.writeMusicXML` also derive ordinary beam groups by default.
`Rastrum.writeJSON` keeps that engraving policy off the wire.

ScoreJSON is local to Rastrum and metasonic-score, an unreleased Haskell
sibling. It is versioned and specified in
[docs/interchange.md](docs/interchange.md) so the two projects can exchange
whole scores without adopting each other's model. It is not a public standard.

## Install

```supercollider
Quarks.install("Rational");
Quarks.install("https://github.com/smoge/Rastrum");
```

### LilyPond

Needed only to engrave or preview. MusicXML, JSON, events, patterns, and model
operations need no LilyPond.

Rastrum searches for `lilypond` once at startup and remembers what it found:

```supercollider
Rastrum.lilypondPath;      // "/usr/bin/lilypond"
```

If that is nil, or points at the wrong one, set it:

```supercollider
Rastrum.lilypondPath = "/path/to/bin/lilypond";
```

The search uses a login shell, so Homebrew and other shell-configured paths are
visible. If discovery fails, put the assignment in `startup.scd`.

LilyPond reports success on stderr, so some IDEs show `ERROR: Success:
compilation successfully completed` after a good render. A real failure raises
an sclang error naming the `.ly`.

## Quick start

```supercollider
(
~m1 = RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2],
    [\c, [\e, \flat], \g, [\b, \flat], [\c, 5]]);
~m2 = RhythmTree.measure(Meter(3, 4), [1, -1, 1], [[\c, 5], \a]);
~score = MusicScore([Staff([~m1, ~m2], "Violin", \treble)], "Study I");
)

Rastrum.render(~score, "study-01");          // LilyPond -> PDF and .midi
Rastrum.writeMusicXML(~score, "study-01");   // MusicXML file
Rastrum.writeJSON(~score, "study-01");       // ScoreJSON file

LilyWriter.new.write(~score).postln;         // raw string writer
MusicXMLWriter.new.write(~score).postln;
ScoreJSONWriter.new.write(~score).postln;
```

Facade methods prepare and validate by default, and `render`, `preview` and
`writeMusicXML` also derive ordinary beams. Raw writers return strings and leave
both to the caller.

Bars are written as proportions plus pitches because that is the shorter and
more musical of the two routes: a weight is a share of the bar, a negative
weight a rest, and the durations and any tuplet bracket follow from the
meter. See [Proportional rhythm](#proportional-rhythm).

Direct leaves are available when you need chords or exact statements:
`Measure(Meter(4, 4), [MusicNote(\c, Duration.quarter), ...])` builds the same
tree. Everything below describes that model, because it is what both routes
produce and what every writer walks.

For a linear API walk, read [tutorial.scd](tutorial.scd). 

## Design

One model, three document writers. The score tree contains no backend syntax, so
a new document format is a new `ScoreWriter` subclass and nothing else.

LilyPond and MusicXML ship together on purpose. A library that emits only
LilyPond drifts into using LilyPond's model as its own, and by the time a second
backend is wanted the model has to be rebuilt. Abjad and FOSC both carry that
gravity. MusicXML being there from the first commit means any concept only one
writer can express is caught immediately. Durations, tuplets, beams, directions
and pitches stay score facts, and each writer spells them itself.

For a source reading order, start at [Classes/Duration.sc](Classes/Duration.sc).
Its header lists the files by layer.

### Names

No prefix by default. The exceptions are names that would collide or read too
generically without it: `MusicScore`, `MusicRest`, `MusicPitch`, `MusicNote`,
`MusicInterval`, and `MusicIntervalName`. `Score`, `Rest`, `Pitch` and
`Interval` are core names, `Note` is taken by at least one common quark, and
`MusicIntervalName` follows that family. Everything else is plain: `Duration`,
`Meter`, `Chord`, `Tuplet`, `Measure`, `Staff`, `RhythmTree`.

`MN` is only a short constructor for `MusicNote`, not a subclass. The
documentation writes `MusicNote`.

Rastrum extends no core class: no `String:asNote`, no `Integer:note`, nothing
added to `Object` or `Symbol`. The roster below is its global footprint. Check
it before installing into a busy class library:

```supercollider
[\AutoBeam, \Chord, \Direction, \Duration, \EventWriter, \LilyWriter, \Marking, \Measure,
 \Meter, \MN, \MusicInterval, \MusicIntervalName, \MusicNote, \MusicPitch, \MusicRest,
 \MusicScore, \MusicXMLWriter,
 \PatternPlayback, \PatternWriter, \PlaybackMap, \PlaybackTempoMap, \Rastrum,
 \RhythmCell, \RhythmTree, \ScoreContainer, \ScoreElement, \ScoreJSONReader,
 \ScoreJSONWriter, \ScoreLeaf, \ScorePrepare, \ScoreWriter, \Spanner, \Staff,
 \Tuplet, \Validator, \Voice].do { |k|
    if (k.asClass.notNil) { "TAKEN: %".format(k).postln }
};
```

Tests compare that block with the compiled class roster, so adding or removing a
class is a deliberate documentation change too.

### Proportional rhythm

A bar can be written as shares of its span rather than as durations, which is
the shorter and more musical of the two routes. This is the RTM shape, a
proportional rhythm tree: a number is a share, a negative number is silence, and
`[weight, subdivisions]` divides a share further.

```supercollider
RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2],
    [\c, \e, \g, \b, [\c, 5]]);
```

The note values and any tuplet bracket follow from the shares and the meter.
That derivation is meter-aware on purpose. Three equal shares of a 3/4 bar are
plain quarters and three of a 4/4 bar need a bracket, so a blind power-of-two
rule would print a spurious one.

```supercollider
RhythmTree.chooseDivisor(Duration(3, 4), [1, 1, 1]);   // 3, plain quarters
RhythmTree.chooseDivisor(Duration(1, 1), [1, 1, 1]);   // 2, a 3:2 over halves
```

Shares are relative, so a common factor is removed first and the same rhythm
written coarsely or finely is one notation. A duration the shares imply that no
note head can spell is built as it is and left for the preparation pass to tie.

`RhythmCell` is that same list held as a value, so it can be rotated, scaled,
muted, reversed or rewritten one share deep without the caller having to know
which entries are nested. Getting that wrong by hand is silent, which is why the
class exists.

```supercollider
RhythmCell([1, [2, [1, 3]]]).retrograde;   // RhythmCell([ [ 2, [ 3, 1 ] ], 1 ])
```

### Pitches and intervals

`MusicPitch` keeps spelling. `MusicInterval` is the signed spelled distance
between two pitches, so transposition preserves letter motion.

```supercollider
~third = MusicPitch(\e) - MusicPitch(\c);
(MusicPitch(\c) + ~third).letter;                  // e

~wideFourth = MusicInterval.named(\semiAugmented, 4);
~wideFourth.transpose(MusicPitch(\c)).accidental;  // quarterSharp
```

### Metric weight

`Meter` carries more than the pair it prints. `levelOf` answers a metric depth
for any exact position, and that is what decides where a tie may be cut and
whether a rest is readable where it sits.

```supercollider
(0..7).collect { |i| Meter(4, 4).levelOf(Duration(i, 8)) };
// [0, 3, 2, 3, 1, 3, 2, 3]
```

`indispensability` is Clarence Barlow's ranking of how much each pulse is needed
for the meter to be heard as itself. It is composition rather than notation, no
writer reads it, and it is there to thin a rhythm and keep its character. 3/4
and 6/8 hold the same six eighths and rank them differently, which is Barlow's
own argument.

```supercollider
Meter(3, 4).indispensability(Duration(1, 8));   // [ 5, 0, 3, 1, 4, 2 ]
Meter(6, 8).indispensability(Duration(1, 8));   // [ 5, 0, 2, 4, 1, 3 ]
```

### Examples

[tutorial.scd](tutorial.scd) is the linear tour.
[examples/README.md](examples/README.md) indexes focused recipes, and
[docs/api-examples.scd](docs/api-examples.scd) is one runnable API pass.

Method comments also carry checked examples. `tools/doctest.scd` reads a comment
line beginning `>>>`, evaluates it, and compares the answer with what is written
after `->`:

```supercollider
// >>> Duration.quarter.dotted.notation             -> [ Duration(1/4), 1 ]
// >>> Meter(3, 4).indispensability(Duration(1, 8)) -> [ 5, 0, 3, 1, 4, 2 ]
```

```bash
sclang tools/doctest.scd
sclang examples/run-all.scd
```

Run those when changing public examples or comment examples.

### Interchange with metasonic-score

`ScoreJSONWriter` and `ScoreJSONReader` are Rastrum's ends of the contract: a
tagged tree with a `"type"` discriminator, exact rationals as
`[numerator, denominator]` pairs, and no floats except `cents`.

Rastrum reads and writes it. metasonic-score decodes and checks it, but does not
yet rebuild its full score tree or emit it.

```json
{"format":"rastrum-score","version":18,"type":"score","title":"Study I",
 "staves":[{"type":"staff","name":"Violin","clef":"treble","measures":[
   {"type":"measure","meter":[4,4],"elements":[
     {"type":"tuplet","multiplier":[2,3],"elements":[
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2],"tiesToNext":true},
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2]}]}]}]}]}
```

Only whole scores are documents. A bare measure or staff is refused rather than
written as a fragment.
