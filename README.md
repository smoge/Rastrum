# Rastrum

Music notation for SuperCollider: a score model with LilyPond, MusicXML, GUIDO
and ScoreJSON writers.

Rastrum builds a score tree directly, or from proportional rhythm (RTM). It
reads and rewrites that tree in the terms a musician would use, then writes it
out as LilyPond, MusicXML, GUIDO, ScoreJSON, SuperCollider `Event`s, or
patterns.

`ScorePrepare` takes durations no single note head can spell and ties them into
leaves that can. `Rastrum.render`, `Rastrum.preview`, `Rastrum.writeMusicXML`
and `Rastrum.writeGuido` derive ordinary beam groups by default.

`Rastrum.writeJSON` doesn't. A derived beam is engraving policy, not a score
fact, and policy stays off the wire.

ScoreJSON is a proposed interchange format for scores: versioned, open, and not
tied to one program's model. Two programs can trade a score without either one
moving into the other's house.

**Status: experimental.** The API may still change.


## Minimal Example

```supercollider
~score = MusicScore.oneStaff([Measure("4/4", "c4 d4:grace{db8} e4 f4")], "Violin");
Rastrum.render(~score, "example");
```

That writes `example.pdf` and `example.midi`. The grace prints before the host
note and adds no bar time.

## Another Example

```supercollider
(
~m1 = Measure("4/4",                                  // the pp into that f
    "cresc[c4:pp:tenuto 3:2[d8 eb8 f+8] <d e-'>4:text{pizz.}:f] "
    "<e' d#+'>16:ff:stac r8.")
    .metronome("4", 96, text: "Allegro");
~m2 = Measure.proportions("5/8[3+2]", "(1 (1 (1 1 1)) -1 2)",
    "c:mp:text{ord.} d e- g f#+:ppp:tenuto");   // bowed again, after the pizz.

~phrase = ScoreSelection(~m2).runs.first;            // the run before the rest

Spanner.slur(Spanner.beam(~phrase));                 // beamed, and under a slur
Spanner.diminuendo(ScoreSelection(~m2).pitched);     // mp fading to ppp

~score = MusicScore.oneStaff([~m1, ~m2], "Violin", \treble, "Rastrum");
)

Rastrum.render(~score, "study");              // LilyPond -> PDF and .midi
Rastrum.writeMusicXML(~score, "study");       // MusicXML file
Rastrum.writeJSON(~score, "study");           // ScoreJSON file
Rastrum.writeGuido(~score, "study");          // GUIDO file
```

![Example score, engraved](assets/study.png)

That's the page `render` writes: the `pp` crescendo into the pizzicati, the
marked triplet, quarter-tones, the metronome mark, the `ff` staccato sixteenth
and dotted rest completing its beat, and the grouped 5/8 bowed again, slurred
and fading from `mp` to a held `ppp`, each from the line above that says it.

You can read the same score as a model tree, with a path beside each node.
`treeString` answers that text as a String. `postTree` posts it and answers the
score:

```supercollider
~score.treeString;
```

```text
[] Score "Rastrum" staves: 1
|
`- [0] Staff "Violin" clef: treble bars: 2
   |
   +- [0,0] Measure 4/4 holds: 1 offset: 0 dirs: tempo "Allegro" 1/4=96
   |  |
   |  +- [0,0,0] Note c[4] 1/4 marks: pp, tenuto spanners: hairpin:start#1 crescendo
   |  |
   |  +- [0,0,1] Tuplet 3:2 3/8=>1/4
   |  |  |
   |  |  +- [0,0,1,0] Note d[4] 1/8=>1/12
   |  |  |
   |  |  +- [0,0,1,1] Note eb[4] 1/8=>1/12
   |  |  |
   |  |  `- [0,0,1,2] Note f+[4] 1/8=>1/12
   |  |
   |  +- [0,0,2] Chord <d[4] e-[5]> 1/4 marks: "pizz.", f spanners: hairpin:stop#1
   |  |
   |  +- [0,0,3] Chord <e[5] d#+[5]> 1/16 marks: ff, staccato
   |  |
   |  `- [0,0,4] Rest 3/16
   |
   `- [0,1] Measure 5/8 holds: 5/8 offset: 0
      |
      +- [0,1,0] Note c[4] 1/8 marks: mp, "ord." spanners: beam:start#1, slur:start#1, hairpin:start#1 diminuendo
      |
      +- [0,1,1] Tuplet 3:2 3/16=>1/8
      |  |
      |  +- [0,1,1,0] Note d[4] 1/16=>1/24
      |  |
      |  +- [0,1,1,1] Note e-[4] 1/16=>1/24
      |  |
      |  `- [0,1,1,2] Note g[4] 1/16=>1/24 spanners: beam:stop#1, slur:stop#1
      |
      +- [0,1,2] Rest 1/8
      |
      `- [0,1,3] Note f#+[4] 1/4 marks: ppp, tenuto spanners: hairpin:stop#1
```

Bracketed paths are receiver-relative child paths: `[0,0,1]` is this score's
tuplet. A bare time is written time. `3/8=>1/4` is written time sounding as
shown. Non-child attachments are summarized beside their owner.
`treeString((attachments: \sidecars))` gives each attachment its own row, marked
`@markings[0]` rather than a path. `attachments: \none` leaves only structure,
the view to use when reading paths.

The rows are display, not source. `Note`, `Rest` and `Score` are the display
names for `MusicNote`, `MusicRest` and `MusicScore`. The plain names are already
taken.

A sforzando is its own marking, not another dynamic. Its name states the attack
level: `smpz`, `smfz`, `sfz`, `sffz`, `sfffz` and `sffffz` for `mp` through
`ffff`. A dynamic beside one says where the note settles for the rest of its
length, and a score prints the pair as one glyph. A leaf carries at most one
sforzando and one dynamic. That pair is the most loudness a page draws in one
place.

```supercollider
MN("c4:sfz");        // an attack at the level its name states, f here
MN("c2:sfz:pp");     // the compound, settling onto pp for the rest of the note
```

A bar is one string. Spans it can't say stay as objects around it. A written
`cresc[...]` group ends on leaves and holds whatever stands between them, so the
hairpin can reach across the triplet without naming each leaf. A span past one
bar still uses a `Spanner` helper on the leaves it joins. A beam against a
bracket is authorial too. The eighth is beamed into the triplet here by hand,
not by `AutoBeam`.

The second bar states rhythm as proportions and pitches as a list. That lets a
cell rotate or move to another meter without touching the pitch material. The
list says what sits on each leaf, with the same suffixes the bar grammar reads,
so dynamics are spelled the same way in both places. It doesn't say durations.
The shares already did.

Spans are requested in musical terms. `ScoreSelection` reads a tree the way a
player reads a part, so the span attaches to the run before the rest, not to a
counted position. A group helper takes that selection directly. Each helper
answers its run, so the beam and slur over one phrase can be one line.

Façade methods prepare and validate by default. `render`, `preview`,
`writeMusicXML` and `writeGuido` also derive ordinary beams. Raw writers return
strings and leave those passes to the caller.

### Three Input Styles

All three styles build the same score tree. Use core constructors for generated
or transformed music, specialized parsers for ordinary notation, and
quasiquoters when you want parser syntax without quoted strings.

**(a) Core constructors**, spelling out every object:

```supercollider
~core = MusicScore.oneStaff([
    Measure(Meter(4, 4), [
        MusicNote(MusicPitch(\c), Duration.quarter)
            .dynamic(\mp)
            .articulation(\tenuto),
        Tuplet.ratio(3, 2, [
            MusicNote(MusicPitch(\d), Duration.eighth),
            MusicNote(MusicPitch(\e, \flat), Duration.eighth),
            MusicNote(MusicPitch(\f, \quarterSharp), Duration.eighth)
        ]),
        Chord([
            MusicPitch(\g), MusicPitch(\b), MusicPitch(\d, octave: 5)
        ], Duration.quarter).text("pizz."),
        MusicNote(MusicPitch(\c, octave: 5), Duration.quarter)
            .dynamic(\f)
    ]).metronome("4", 96, text: "Allegro"),
    Measure.proportions(
        Meter.grouped(5, 8, [2, 3]),
        [1, [1, [1, 1]], -1, 2],
        "c d e g")
], "Violin", \treble, "Study I");
```

**(b) Specialized parsers**, reading the same facts from the slot they fill:

```supercollider
~parsed = MusicScore.oneStaff([
    Measure("4/4",
        "c4:mp:tenuto 3:2[d8 eb8 f+8] <g b d'>4:text{pizz.} c'4:f")
        .metronome("4", 96, text: "Allegro"),
    Measure.proportions("5/8[2+3]", "(1 (1 (1 1)) -1 2)", "c d e g")
], "Violin", \treble, "Study I");

MusicPitch("c-[5]");        // pitch spelling
Duration("4.");             // dotted quarter
Meter("5/8[2+3]");          // grouped meter
MN("c-4:mf:staccato");      // one marked note
Chord("<c+ e g>2:ff");      // one marked chord
```

**(c) Quasiquoters**, which reuse those same parsers. Start the
preprocessor in one evaluation of its own:

```supercollider
Rastrum.startQuasiquoter;
```

A file or selection is preprocessed whole before any of it runs. A quasiquote
block in the same evaluation as the line that turns it on is still raw text.
Evaluate the start first, then the blocks:

```text
~quotedBar = [measure|
    4/4 c4:mp:tenuto 3:2[d8 eb8 f+8] <g b d'>4:text{pizz.} c'4:f
|];
~quotedCell = [rtm| (1 (1 (1 1)) -1 2) |];
~quotedNote = [note| c-[5]8.:mf |];
~quotedRun = [run| c4 r4 <e g>2 |];        // an Array of leaves

// Several bars, `|` between them, the meter stated only where it changes.
~quotedBars = [measures| 4/4 c4 d4 e2 | c4 r4 e2 | 3/4 c4 d4 e4 |];

~quoted = MusicScore.oneStaff([
    ~quotedBar.metronome("4", 96, text: "Allegro"),
    Measure.proportions("5/8[2+3]", ~quotedCell, "c d e g")
], "Violin", \treble, "Study I");
```

A syntax error near `KEYBINOP` usually means the preprocessor wasn't started first.

Once it is running, sclang reads a quasiquote block as ordinary sclang because
the block has already been rewritten to constructor calls. A syntax highlighter
works one step earlier, on the file as written. It can lose track around raw
blocks, especially when they contain apostrophe register marks or sit inside a
larger expression.

An editor mode could learn the block syntax too. Nothing in the quasiquoter
prevents that. It isn't this quark's layer. Rastrum ships classes and a
preprocessor, not editor modes.

Spanners, voices and bar-level objects stay ordinary objects around compact leaves:

```supercollider
~phrase = Measure("3/4", [
    Voice(Spanner.slur("c4 d4 e4"), "upper"),
    Voice(Spanner.crescendo("<g b>4 <a c'>4 <b d'>4"), "lower")
]);
```

The bar parser reads a run of tokens: pitch plus note value, `c*5/8` for an
exact rational duration, `r` for a rest, `'` and `,` for register, `<c e g>4`
for a chord, trailing `~` for a tie, `3:2[c4 d4 e4]` for a bracket,
`crescendo[c4 d4]` or `cresc[c4 d4]` for a hairpin, and `gliss[c4 e4]` for a
glissando chain. A hairpin run may hold a bracket between endpoints. Above
leaves and brackets, everything stays an object: voices, slurs, beams,
directions and barlines.

A written line puts the meter first, as in `"4/4 c4 d4 e2"`, and several bars
are separated by `|` with the meter stated only where it changes. A semicolon
after the meter is optional. Use it where it improves readability.

That form suits a bar you already know. When rhythm is proportional, generated
or transformed, write shares and let the meter derive note values and any bracket.

## Design

One model, four document writers. The score tree carries no writer syntax, so a
new document format is a new `ScoreWriter` subclass and nothing else. Durations,
tuplets, beams, directions and pitches stay score facts. Each writer spells them
for its own format.

Keeping several backends live keeps the model honest. A concept only one format
can express is either modeled deliberately or refused by name. GUIDO is the
narrowest backend, and the handful of things it can't draw are refused rather
than approximated.

### Proportional Rhythm

A bar can speak in shares instead of durations. That's the shorter and more
musical route.

An RTM shape is a proportional rhythm tree: a number is a share, a negative
number is silence, and `[weight, subdivisions]` divides a share further.

For example:

```supercollider
RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2],
    [\c, \e, \g, \b, [\c, 5]]);
```

A cell can also use traditional RTM spelling. Reach for that form when nesting
is what makes the line hard to read:

```supercollider
RhythmTree.measure("4/4", "(1 (1 (1 1 1)) 2)", "c e g b c'");
```

![The same cell, engraved](assets/proportional-rhythm.png)

Keep the array when the structure itself is the subject: paths, rewrites, and
what each share contains.

After `Rastrum.startQuasiquoter` has been evaluated, the same cell can be
written without the String quotes:

```text
~cell = [rtm| (1 (1 (1 1 1)) 2) |];
RhythmTree.measure("4/4", ~cell, "c e g b c'");
```

`Measure.proportions` is `RhythmTree.measure` under the result's name. Use
`Measure.proportions` when the subject is the bar, and `RhythmTree.measure` when
the subject is the rhythm. A meter can be written the way it prints, as in
`Meter("4/4")`, or with explicit subdivisions, as in `Meter("5/8[2+3]")`.

The note values and any tuplet bracket follow from the shares and meter. That
derivation is meter-aware on purpose. Three equal shares of a 3/4 bar are plain
quarters. A blind power-of-two rule would bracket them anyway. Three equal
shares of a 4/4 bar need a bracket:

```supercollider
RhythmTree.chooseDivisor(Duration(3, 4), [1, 1, 1]);   // 3, plain quarters
RhythmTree.chooseDivisor(Duration(1, 1), [1, 1, 1]);   // 2, a 3:2 over halves
```

Shares are relative. A common factor is removed first, so the same rhythm
written coarsely or finely becomes one notation. If the shares imply a duration
no note head can spell, the model builds it as-is and leaves the preparation
pass to tie it.

`RhythmCell` is that same list held as a value. It can be rotated, scaled,
muted, reversed or rewritten one share deep without the caller tracking which
entries are nested. Doing that by hand is easy to get wrong silently. That's why
the class exists:

```supercollider
RhythmCell([1, [2, [1, 3]]]).retrograde;   // RhythmCell([ [ 2, [ 3, 1 ] ], 1 ])
```

`replaceCellAt` is the same idea one step further: a cell can be grown inside
itself, and the note values still follow from the shares and meter. The upper
staff below is the seed. The lower staff substitutes that seed into its own
second share, and both rotate one step per bar.

```supercollider
~seed = RhythmCell("(2 (2 (1 1 1)) 3)");
~grown = ~seed.replaceCellAt([1], ~seed);

~plan = ["4/4", "5/8[2+3]"];

MusicScore.staves([
    (name: "Marimba", clef: \treble, measures: ~plan.collect { |meter, i|
        ~seed.rotated(i).measure(meter, "c' eb' g' bb' d'") }),
    (name: "Vibraphone", clef: \treble, measures: ~plan.collect { |meter, i|
        ~grown.rotated(i).measure(meter, "c d f g bb c' eb'") })
]);
```

![Nested tuplets from one substituted cell](assets/rtm-manipulation.png)

Nothing here is a special case. The brackets are what the shares came to against
each meter. No depth was requested.

### Pitches and Intervals

`MusicPitch` keeps its spelling. `MusicInterval` is the signed, spelled distance
between two pitches, so transposition preserves letter motion:

```supercollider
~third = MusicPitch(\e) - MusicPitch(\c);
(MusicPitch(\c) + ~third).letter;                  // e

~wideFourth = MusicInterval.named(\semiAugmented, 4);
~wideFourth.transpose(MusicPitch(\c)).accidental;  // quarterSharp
```

### Metric Weight

`Meter` carries more than its printed pair. `levelOf` answers a metric depth for
any exact position. That decides where a tie may be cut and whether a rest is
readable where it sits:

```supercollider
(0..7).collect { |i| Meter(4, 4).levelOf(Duration(i, 8)) };
// [0, 3, 2, 3, 1, 3, 2, 3]
```

`indispensability` is Clarence Barlow's ranking of how much each pulse is needed
for the meter to be heard as itself. It's composition data, not notation. No
writer reads it. It's there to thin a rhythm and keep its character. 3/4 and 6/8
hold the same six eighth notes and rank them differently, which is Barlow's own
argument:

```supercollider
Meter(3, 4).indispensability(Duration(1, 8));   // [ 5, 0, 3, 1, 4, 2 ]
Meter(6, 8).indispensability(Duration(1, 8));   // [ 5, 0, 2, 4, 1, 3 ]
```


### Preparation and Validation

Two passes stand between a tree you wrote and a page. They stay separate because
they answer different questions.

`ScorePrepare` rewrites what no note head can spell. Five eighths is not a note
head, so it becomes two tied leaves. Where the note starts decides how it is
split. The music doesn't change: same attacks, same sounding durations, only the
heads a reader sees.

`Validator` refuses what doesn't add up. Full bars have to add up. A bar short
on purpose has to say where it sits. Every voice has to fill its bar, a tie has
to reach the same pitch, spanner endpoints have to pair up, directions have to
land where a leaf begins, and closed vocabularies stay closed.

```supercollider
ScorePrepare.run(Measure("4/4", "c*5/8 d*3/8"));   // one leaf became two tied
Validator.validate(Measure("4/4", "c4 d4"));   // refused: the bar is half full
```

Facade methods run both passes before any writer. Raw writers don't, so hand
them `ScorePrepare.run(...)` yourself or they refuse the unprepared tree instead
of guessing.

### Beams, Graces and Glissandi

A beam is authored as a spanner, `Spanner.beam("c8 d8 e8 f8")`, or derived from
the meter by `AutoBeam`. Deriving it once in the model lets every backend draw
the same decision instead of inferring its own. LilyPond's own inference is
turned off for that reason.

```supercollider
~six = Measure.proportions("6/8", "(1 1 1 1 1 1)");
AutoBeam.run(~six);
AutoBeam.groupsIn(~six).collect { |group| group.size };   // [ 3, 3 ]
```

3+3, not 2+2+2: 6/8 is compound, and beaming it in pairs would print a bar of
3/4 wearing the wrong time signature. A bar beamed by hand is left exactly as written.

A grace is written and doesn't last. It hangs off the note it ornaments rather
than standing in the bar, so `graces` isn't `children` and nothing that sums
written time ever reaches it. That's what keeps every duration nonzero.

```supercollider
MN("c4").grace("b8");
MN("e4").acciaccatura("f16 g16");
Measure("2/4", "c4:grace{b8} e4:acciaccatura{f16 g16}");
```

The suffix form carries the same fact, so one graced note doesn't force the
whole bar out of string form. Every leaf kind can host a grace group.

A glissando connects neighboring attacks. In a chain, each pair gets one line.

```supercollider
Measure("4/4", "gliss[c4 e4 d4] r4");
Measure("2/4", "gliss[<c e g>4 <d f a>4]");
```

Chord glissandi pair notes by written position, so both chords need the same
number of note heads.

### Layout Profiles

`LilyProfile` is LilyPond's engraving policy: paper size, staff size, the
`\paper` and `\layout` blocks, from a closed and typed settings vocabulary. The
model and other backends never hear it.

```supercollider
// [ default, complexRhythm, landscapeComplexRhythm, openComplexRhythm ]
LilyProfile.names;

Rastrum.preview(~score, "study-01", layout: \complexRhythm);
Rastrum.preview(~score, "study-01", layout: LilyProfile(\complexRhythm,
    (systemSpacing: 22, markupSystemPadding: 3)));
```

`\complexRhythm` adds proportional spacing, full brackets and complete ratios.
Use `\openComplexRhythm` for deeper nesting.

## Reading and Editing a Score

Everything above builds a tree and hands it somewhere. `ScoreSelection` goes the
other way and reads one in terms you would use out loud. Filters answer
selections, so they chain. Accessors answer plain arrays.

```supercollider
~notes = ScoreSelection(~score).inStaff("Violin").notes;
[~notes.size, ~notes.offsets.first, ~notes.offsets.last];
```

A leaf alone doesn't say where it is, so a selection holds records: the leaf
plus the staff, bar, timeline and exact moment that make it addressable. Offsets
are `Duration`s, so a triplet quarter is a sixth, not 0.1666.

A leaf is a note head, and a note is sometimes more than one head. `logicalTies`
is the layer between a leaf and a note, so a note written as two tied heads is
asked about once:

```supercollider
~doubled = ScoreSelection(~score).inStaff("Violin").mapLogicalTies { |run|
    MusicInterval.named(\major, 3).transpose(run[\pitch]) };
```

Reading changes nothing. `mapLeaves`, `mapLogicalTies` and `transposeBy` change
a copy and hand it back. The score you started from stays where it was.

Which edits a selection can feed follows from its shape. One contiguous run in
one timeline goes to a run edit. A selection with gaps, as when a filter or
index leaves some leaves out, is refused there and goes to leaf-wise edits
instead. Those put a new leaf where each old one stood and keep the shape.
`atIndices`, `excludingIndices` and `everyNth` make scattered selections
directly. They count the selection you already have and keep its order.

`ScoreEdit` is the checked way back in when you want an addressed edit, not a
whole-selection transform. A run is read as a selection, then one validated copy
is built around the change. `replaceRun` keeps one selected leaf for one
replacement leaf. `reshapeRun` keeps the occupied time and lets the shape
change. Both refuse edits that would silently drop part of a tuplet, tie or
spanner.

`splitLeafByProportions` and `splitLeafByDurations` divide one leaf into
fragments. Notes and chords tie across them. Rests stay rests. `joinLeaves` is
their inverse. `fillMeasures` fills whole bars from one cell, with the row
reading across the bars of a lane.

```supercollider
~loop = MusicScore.oneStaff([
    Measure("4/4", "c4 d4 e4 f4"),
    Measure("4/4", "g4 a4 b4 c'4")
], "Violin");

~span = ScoreSelection(~loop).inStaff("Violin")
    .inMeasure(1)            // bar indices count from zero
    .withinBar(0, "1/2");    // its first half

ScoreEdit.replaceRun(~loop, ~span, "a4 b4");
ScoreEdit.reshapeRun(~loop, ~span, "3:2[a8 b8 c'8] d4");
```

A selection cuts into groups several ways. Adjacency gives
`contiguousGroups`, `runs` and `pitchGroups`. Position gives
`groupByMeasure`. A plan you bring gives `partitionByCounts` in leaves
or `partitionByDurations` in written time.

The two common groupings are phrases and bars, and they are opposites.
`runs` breaks where a rest stands and reads through a barline.
`groupByMeasure` breaks at every barline and reads through a rest, one
group per bar per voice. The two bars below hold no rest, so the whole
line is one phrase:

```supercollider
~held = ScoreSelection(~loop).pitched;

~held.runs.collect { |group| group.size };             // [ 8 ]
~held.groupByMeasure.collect { |group| group.size };   // [ 4, 4 ]
```

For phrase-level work, `ScoreEdit` takes a whole selection. Markings go on by
kind, so `setMarking` replaces the dynamic and leaves the articulation. Spans go
over *phrases*: rests split them, barlines do not. `detachSlurs` and
`detachHairpins` take one off again. Touch any leaf under it and both ends go.

```supercollider
~sung = ScoreEdit.setMarking(~loop, ScoreSelection(~loop),
    Marking.dynamic(\mf));
~slurred = ScoreEdit.slurRuns(~sung, ScoreSelection(~sung));
~bare = ScoreEdit.detachSlurs(~slurred, ScoreSelection(~slurred));
```

Pitch edits use the same selection shape. `transposeBy` moves a line by a
spelled interval. `assignPitches` deals a finite row, one pitch per *sounding
note*. Tied heads share one, a chord takes one per pitch, and rests take none.

```supercollider
~line = { |score| ScoreSelection(score).inStaff("Violin") };

ScoreEdit.transposeBy(~loop, ~line.(~loop), MusicInterval.named(\major, 3));
ScoreEdit.assignPitches(~loop, ~line.(~loop), "g a b");
```

`ScoreLocator` bridges a clock moment to a selection. That's how an outside
position becomes an editable address. `ScoreDiff` observes two scores and
answers plain delta records. `ScoreHistory` holds those records with a cursor
over them, so `undo` and `redo` move between recorded scores. There is no patch
application, inverse diff or score reconstruction anywhere in them.

`ScoreLocator` prepares by default. If you need paths for later edits,
ask for the tree as handed in.

```supercollider
~where = ScoreLocator(~loop, false).selectionAt(Duration(1, 4)).paths.first;
~after = ScoreEdit.replaceLeafAt(~loop, ~where, MusicRest(Duration.quarter));
~log = ScoreHistory.start(~loop).recorded(~after, "rest the second beat");

~where;              // [ 0, 0, 1 ]
~log.changeCount;    // 1
```

## Playback

The same tree also answers SuperCollider events and patterns. These are playback
values, not text documents. A tie is not another attack, so this interpretation
follows sounding attacks rather than written note heads: the 5/8 note prepared
as two heads is one event.

Each event carries what a scheduler needs at the top level. Everything else
Rastrum knows about it lives in one inert `\rastrum` payload, where it can't
collide with a SynthDef control. One timeline is a `Pbind`. Several beginning
together are a `Ppar`.

```supercollider
Rastrum.events(~score);         // an Array of Events
Rastrum.pbinds(~score);         // one Pbind per timeline
Rastrum.pattern(~score);        // a Ppar of them, score metronome marks included
Rastrum.play(~score, \default); // answers the player, so you can stop it
```

Structure is derived. Interpretation is chosen. A score says `ff`, not an
amplitude, and `staccato`, not a note length. What a mark is worth to a synth
lives in optional tables that nothing reaches unless you ask:

```supercollider
~map = PlaybackMap.new
    .instrumentAt(0, 0, \default)   // staff 0, its first timeline
    .useDynamics                    // ppppp..fffff maps to \amp
    .useArticulations;              // staccato, tenuto maps to \legato

~map.pattern(~score);
```

Tempo is its own table because a tempo governs a moment, not a note, and may
land where no note begins. Prose has no speed until you map it, and a prose mark
with no entry is refused rather than played at a guessed speed. A printed
metronome mark is already a number, so it bypasses the map and reaches every
surface, playback included.

```supercollider
~speeds = PlaybackTempoMap.new.tempo("Allegro", 132);
Ppar([
    ~speeds.tempoPattern(~score),            // first, so it reaches the clock
    Rastrum.pattern(~score, tempo: false)    // before the note due with it
]).play;
```

`PlaybackProfile` composes optional layers in one stated order and answers which
keys the result carries. Every slot is optional. An empty profile is ordinary
structural playback, so you can start there and add one layer at a time.

```supercollider
~profile = PlaybackProfile.new
    .playbackMap_(PlaybackMap.new.useDynamics.useHairpins)
    .graceMap_(PlaybackGraceMap.new)
    .controlMap_(PlaybackControlMap.new.panAt(0, 0, -0.5));

~profile.carriedKeys;
~profile.pattern(~score);
```

## Interchange

ScoreJSON is the proposed wire format. `ScoreJSONWriter` and `ScoreJSONReader`
are Rastrum's ends of it: a tagged tree with a `"type"` discriminator, exact
rationals as `[numerator, denominator]` pairs, and no floats except `cents`.
Nothing in the shape is particular to Rastrum. Any project that can write the
tree can hand a score to any project that can read one.

Current ScoreJSON documents say `"format":"score-json"` and `"version":32`, and
the version is bumped on any change to the schema.

Rastrum reads and writes the current version.


```json
{"format":"score-json","version":32,"type":"score","title":"Study I",
 "staves":[{"type":"staff","name":"Violin","clef":"treble","measures":[
   {"type":"measure","meter":[4,4],"elements":[
     {"type":"tuplet","multiplier":[2,3],"elements":[
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2],"tiesToNext":true},
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2]},
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2]}]}]}]}]}
```

Only whole scores are documents. A bare measure or staff is refused rather than
written as a fragment. LilyPond, MusicXML and GUIDO are one-way document views.
ScoreJSON is the one that reads back, so a score returned from it is an ordinary
score again. The same writers work on it.
