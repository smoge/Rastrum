// Rastrum: An Overview
//
// A Score is the model. Page formats, JSON and SuperCollider Events
// are interpretations of it. None of them is the model.
//
//     Score to LilyPond       one way, document output
//     Score to MusicXML       one way, document output
//     Score to GUIDO          one way, document output
//     Score to ScoreJSON      two way, exchange format
//     Score to Event stream   one way, SuperCollider playback
//
// Each interpretation forgets something or chooses something. A MIDI
// note forgets spelling. A duration forgets grouping. An Event stream
// forgets notation. A playback map chooses what marks mean to a
// SynthDef. Only ScoreJSON forgets nothing. That is why it is the one
// that reads back.
//
// Structure is derived. Interpretation is chosen. The file below is still a
// program. Evaluate it top to bottom, or block by block.

// Three input syntax styles are valid
//
// Rastrum deliberately keeps three ways to write the same musical value:
//
//   Core constructors are ordinary sclang objects, best when code is
//   generating or transforming music: `MusicNote(\c,
//   Duration.quarter)`.
//
//   Parser strings are also ordinary sclang, best when notation is
//   clearer than the object graph: `Measure("4/4", "c4 d4 e2")`. This
//   tutorial uses them as the default notation spelling.
//
//   Quasiquoters are notation without String quotes, useful when a
//   file has deliberately opted into Rastrum's preprocessor:
//   `[measure| 4/4 c4 d4 e2 |]`. This tutorial shows them only where
//   the quasiquoter itself is the subject.
//
// Quasiquoters are not valid sclang until Rastrum's preprocessor has
// started. Run `Rastrum.startQuasiquoter` before compiling a file or
// selection that contains `[measure| ... |]`, `[run| ... |]`,
// `[measures| ... |]`, `[note| ... |]` or `[rtm| ... |]`.

// The examples in this document try to demonstrate:
//
//   Preparation changes note heads, not scheduled attacks and durations.
//
//   A duration split must sum back to the duration, and every piece
//   must be a notatable head.
//
//   ScoreJSON reads back to an ordinary prepared score. LilyPond,
//   MusicXML and GUIDO are document outputs. ScoreJSON is an exchange
//   format.
//
//   Pitches are defined in an affine space. Pitches are points.
//   Intervals are vectors with their own algebra. Add an interval to
//   a pitch and you get a pitch. Subtract two pitches and you get an
//   interval. Add two pitches and Rastrum refuses because there is no
//   origin.
//
//   Grace notes are a special case, they don't take time.
//
//   Logical ties are another special case, they are one sounding
//   event, when more than one note head is needed to write the
//   duration.
//
//  Also: Meter(6, 8) is not Meter(3, 4), even though the durations
//  match. MusicPitch(\c, \sharp) is not MusicPitch(\d, \flat), even
//  though the midinotes match.

// Validation: What is Checked
//
// By construction: Durations are exact rationals. Pitches store
//   spelling separately from midinote. A chord is one leaf. The group
//   spanner helpers attach both ends of a span at once.
//
// By `Validator`, which the facade runs before any writer: Full bars
//   add up, declared short bars say where they sit, voices fill their
//   bars, ties reach the same pitch, manual spanner endpoints pair
//   up, directions land on leaf starts, and closed vocabularies stay
//   closed.
//
// By writers: A leaf handed to a document writer must already be
//   notatable as one head with dots. `ScorePrepare` is what rewrites
//   the cases that are not.

////////////////////////////////////////////////////////////////////////
// 1. The Score model has three input styles

// Below, the same bar in three spellings. The first is ordinary
// objects. The second asks the parser to build the same shape. The
// third is the quasiquote spelling and what it expands to.

// (a) Core Data Types
//---------------------
~coreStyle = Measure(Meter(4, 4), [

    MusicNote(MusicPitch(\c), Duration.quarter)
	.dynamic(\mp)
	.articulation(\tenuto),

	Tuplet.ratio(3, 2, [
        MusicNote(MusicPitch(\d), Duration.eighth),
        MusicNote(MusicPitch(\e, \flat), Duration.eighth),
        MusicNote(MusicPitch(\f, \quarterSharp), Duration.eighth)
    ]),

	Chord([MusicPitch(\g), MusicPitch(\b), MusicPitch(\d, octave: 5)],
        Duration.quarter).text("pizz."),

	MusicNote(MusicPitch(\c, octave: 5), Duration.quarter).dynamic(\f)
]);


// (b) Specialized Parsers
//------------------------

~parserStyle = Measure("4/4", "c4:mp:tenuto 3:2[d8 eb8 f+8] <g b d'>4:text{pizz.} c'4:f");


// (c) Quasiquoters
//------------------

/*
	Rastrum.startQuasiquoter;

	[measure| 4/4 c4:mp:tenuto 3:2[d8 eb8 f+8] <g b d[5]>4:text{pizz.} c[5]4:f |]
*/

// Previous example is equivalent to:
~quoterStyle = RastrumQuasiquoter.translate(
    "[measure| 4/4 c4:mp:tenuto 3:2[d8 eb8 f+8] <g b d[5]>4:text{pizz.} c[5]4:f |]"
).interpret;

// NOTE Regarding Quasiquotation in Rastrum: (1) The raw line is left
// in a comment because SC highlighters can lose track when a `.scd`
// file contains live blocks, especially with `'` and `,` inside them.
// (2) Also, a syntax error when using quasiquotation usually means
// the preprocessor was not started correctly.

// A smaller tree spells out the basic parts. A note is a pitch and a
// duration, not two numbers. A number rounds, and a number can't say
// which enharmonic spelling you want.

~note = MusicNote(\c, Duration.quarter);
~note.postln;

// A bar holds leaves and declares a meter. A staff holds bars. A
// score holds staves. That is the tree. Every writer and playback
// path walks the same one.

~bar = Measure(Meter(4, 4), [
    MusicNote(\c, Duration.quarter),
    MusicNote(\d, Duration.quarter),
    MusicNote(\e, Duration.half)
]);

// `MN` can also be used. It's just a compact spelling for `MusicNote`.

~score = MusicScore.oneStaff(~bar, "Violin", \treble, "Overview", "You");

// The same bar, written out. A parser string is one timeline of leaves: each
// token a pitch and the length it lasts, `r` for a rest.

~written = Measure("4/4", "c4 d4 e2");
~written.leaves.collect { |leaf| leaf.dur }.postln;

// The trailing number is a duration here, so "c4" is a C quarter. In
// a pitch slot, write MusicPitch("c[4]") for C in octave 4. Register
// uses ' and , from octave 4. LilyPond's marks, not its baseline:
// here bare c is middle C and c' is the octave above it.

~registerBar = Measure("2/4", "c'4 c,4");
~registerBar.leaves.collect { |leaf| leaf.pitch.octave }.postln;

// Most bars are shorter as proportions plus pitches. "(1 1 2)" means
// two short notes and one twice as long, however long the bar is.

~same = Measure.proportions("4/4", "(1 1 2)", "c d e");
~same.leaves.collect { |leaf| leaf.dur }.postln;

// If you already know the printed values, `Measure.durations` takes them as a
// run, and a negative one is a rest.

~said = Measure.durations("4/4", "4 4 -2", "c d");
~said.leaves.collect { |leaf| leaf.class }.postln;

// A note value can be written the way it prints: `Duration("4.")` is a dotted
// quarter. With a slash it is an exact rational instead, `Duration("3/4")`.

// Look at it. This is the line that needs LilyPond. Run it again whenever the
// score changes below and the same window shows the new version.
//
// LilyPond progress is quiet by default. Real failures still raise an error
// naming the `.ly`.
Rastrum.preview(~score, "overview");

// Reuse one preview file. The helper validates a prepared copy before
// previewing:
~show = { |bars, clef = \treble, layout = \default, name = "overview"|
    var score = MusicScore.oneStaff(bars.asArray, "Violin", clef, "Overview");
    Validator.validate(ScorePrepare.run(score));
    Rastrum.preview(score, name, layout: layout)
};

// Try changing a note in ~bar, then run ~show.([~bar]).

// The writer sees the same score facts behind all three input styles.

[
    LilyWriter.new.write(~coreStyle) == LilyWriter.new.write(~parserStyle),
    LilyWriter.new.write(~parserStyle) == LilyWriter.new.write(~quoterStyle)
].postln;

~show.([~parserStyle], \treble, \default, "overview-input-styles");


// 2. Rests, chords, and ties

// Three more kinds of leaf. A rest takes time and sounds nothing.

~rest = MusicRest("4");

// A chord is one leaf holding its pitches, not separate notes stacked. That
// matters as soon as it ties. In a written run, the pitches go between angle
// brackets and the value follows them.

~chord = Chord("<c e g>2");
~chordBar = Measure("4/4", "<c e g>2 d4 e4");
~chordBar.leaves.size.postln;    // Note: three leaves, not five

// A tie says this note continues into the next rather than being struck again.
// In a written run, `~` ties the leaf forward.

~tied = Measure("4/4", "c2~ c2");

// A chord ties per pitch, so part of it can hold while the rest attacks again.
// `true` means all of them, and a mask says which.

~partial = Chord([\c, \e, \g], "2", [true, false, true]);
[~partial.tiesToNext, ~partial.tiesAll].postln;

~restChordBar = Measure("4/4", "r4 d4 <c e g>2");

~show.([
    ~restChordBar,
    ~tied,
    Measure("4/4", [~partial, Chord("<c e g>2")])
]);

// Try something like `Measure("4/4", "c2~ d2")` and other variations,
// then run as shown in section 7. A tie has to reach the pitch it
// continues, and that is one of the refusals. Validation does not
// allow it.

// Different bars written at once are separated by `|`. The first
// states its meter and a later bar states one only where it changes,
// as a page does. Otherwise, the same meter is carried on to the next
// bar.

~bars = ScoreNotation.measureRun("4/4 c4 d4 e2 | c4 r4 e2 | 3/4 c4 d4 e4");
[~bars.size, ~bars.collect { |bar| bar.meter.count }].postln;

~show.(~bars);



// 3. Time is exact, grouping is NOT duration

// Durations are rationals, never floats, so a triplet eighth is
// exactly a third of a quarter and stays that way through every
// writer and the ScoreJSON format.

[Duration.quarter, Duration.quarter.dotted, Duration(1, 12)].postln;

// Use `%/` when you write a rational expression. Plain `1/4` is a
// Float. `1%/4` is exact, a Rational type. If you write as a string,
// as in "3/4" below, the Rational class parses the string as a
// `3%/4` Rational for you. For example:

[Duration(1%/4), Duration("3/4")].postln;

// A duration also knows what it looks like on the page: the note value it is
// drawn as, and how many dots.

[Duration.quarter.notation, Duration.quarter.dotted.notation].postln;

// Not every duration is one note head. Five eighths can't be written
// with one single notehead, and says so:

[Duration(5, 8).isNotatable, Duration(5, 8).tieRuns].postln;

// A Meter is more than just a pair of numbers. 6/8 and 3/4 last
// exactly as long as each other and are not the same meter, because
// they divide differently. That false equality has consequences for
// beams and rhythm trees.

[Meter(6, 8).duration == Meter(3, 4).duration,
    Meter(6, 8) == Meter(3, 4)].postln;
[Meter(6, 8).groups, Meter(3, 4).groups,
    Meter.grouped(5, 8, [2, 3]).groups].postln;

// A meter can be written the way it prints, which is worth it once
// the grouping is in play: `Meter("6/8")`, or with explicit
// subdivisions, like in `Meter("5/8[2+3]")`.

// A dotted quarter, and a 5/8 bar told to divide 2+3:

~dottedBar = Measure("4/4", "c4. d8 e2");
~groupedBar = Measure("5/8[2+3]", "g4 a4.");

~show.([ ~dottedBar, ~groupedBar]);

// Try Duration(1, 5).isNotatable. In this case, a fifth of a whole
// note is not a note head either, and no chain of ties will make it
// one, unless it is notated inside a Tuplet.


// 4. Pitch

// A pitch keeps its letter, accidental and octave apart. C sharp and
// D flat sound alike in equal temperament tuning system. They are not
// alike. Ask a midinote which one you wrote and it lost that answer
// before you asked. That's why MusicPitch keeps this semantic
// difference.

~cSharp = MusicPitch(\c, \sharp);
~dFlat = MusicPitch(\d, \flat);
[~cSharp.midinote == ~dFlat.midinote, ~cSharp == ~dFlat].postln;

// Quartertones are ordinary accidental in Rastrum model, rather than
// a special case, because the alteration is an exact count of
// semitones rather than a rounded one:

~quarterFlat = MusicPitch(\d, \quarterFlat);
[~quarterFlat.midinote, ~quarterFlat.alter].postln;

// A pitch string is letter, optional accidental, optional octave.
// Accidentals are: bb b- b - + # #+ ##. For example, "d-" is quarter
// flat.

// Going the other way, a midinote has to be told how to spell itself:

[MusicPitch.fromMidinote(61, \sharps), MusicPitch.fromMidinote(61, \flats)].postln;

// Four spellings on the page, including the quarter tone:

~pitchSpellings = Measure("4/4", "c#4 db4 d-4 e##4");
~show.([~pitchSpellings]);

// Try MusicPitch(\c, \sharp, 5), MusicPitch("c#'") or
// MusicPitch("c#[5]") for the same MusicPitch. The third argument is
// the octave, and \natural is the accidental to pass when all you
// want is the octave.


// 5. Pitch as affine space

// An interval is the spelled distance between two pitches, not a semitone
// count. C to F# and C to Gb sound alike and are different intervals.

~augFourth = MusicPitch(\f, \sharp) - MusicPitch(\c);
~dimFifth = MusicPitch(\g, \flat) - MusicPitch(\c);
[[~augFourth.signedName.number, ~augFourth.signedName.qualityName],
    [~dimFifth.signedName.number, ~dimFifth.signedName.qualityName],
    [~augFourth.chromatic, ~dimFifth.chromatic]].postln;   // six semitones each

// The number comes from the letters and the quality from the accidentals, in
// that order. Walk one letter pair up the accidental ladder and the whole
// vocabulary appears. Thirds take the major family:

~thirds = { |accidentals| accidentals.collect { |a|
    (MusicPitch(\e, a) - MusicPitch(\c)).signedName.qualityName } };
~thirds.([\doubleFlat, \flat, \natural, \sharp]).postln;

// Fifths take the perfect family, which has no major or minor:

~fifths = { |accidentals| accidentals.collect { |a|
    (MusicPitch(\g, a) - MusicPitch(\c)).signedName.qualityName } };
~fifths.([\flat, \natural, \sharp]).postln;

// Quarter-tones fill the gaps in those same ladders and need no new
// machinery. A "neutral third" really does sit between minor and
// major:

~thirds.([\threeQuarterFlat, \flat, \quarterFlat, \natural, \quarterSharp]).postln;

// An interval can be said rather than measured:

[ MusicInterval.named(\perfect, 5).semitones,
  MusicInterval.named(\neutral, 3).semitones,
  MusicInterval.named(\subminor, 7).quarterSteps ].postln;

// This is affine space math. Pitches are points. Intervals are
// vectors with their own algebra. A pitch minus a pitch is an
// interval. A pitch plus an interval is a pitch:

~fifth = MusicInterval.named(\perfect, 5);

[ MusicPitch(\c) + ~fifth, MusicPitch(\g) - ~fifth,
  MusicPitch(\c).transposeBy(~fifth) ].postln;

// Two pitches cannot be added. There is no chosen origin to add them
// from. Try MusicPitch(\c) + MusicPitch(\e), and Rastrum refuses
// that. On the other hand, intervals add and subtract among
// themselves: a major third plus a minor third is the fifth above:

~majorThird = MusicPitch(\e) - MusicPitch(\c);
~minorThird = MusicPitch(\g) - MusicPitch(\e);
(~majorThird + ~minorThird).signedName.postln;

// Which is what makes transposition worth having. Measure a shape
// once and it moves anywhere, spelling itself on the way:

~figure = [MusicPitch(\c), MusicPitch(\d), MusicPitch(\e), MusicPitch(\g)];

~from = { |root|
    var move = root - MusicPitch(\c);
    Measure("4/4", ~figure.collect { |pitch|
        MusicNote(pitch + move, Duration.quarter) })
};

~show.([~from.(MusicPitch(\c)), ~from.(MusicPitch(\e)),
    ~from.(MusicPitch(\a, \flat))], \treble, \default, "intervals");

// `qualityName` keeps altered degrees that have a word. `commonQualityName` is
// the smaller vocabulary, and answers nothing past it.

~wide = (MusicPitch(\f, \doubleSharp) - MusicPitch(\c)).signedName;
[~wide.qualityName, ~wide.commonQualityName].postln;

// Try MusicInterval.named(\major, 5). A fifth cannot be major, and
// the model refuses it.



// 6. Preparation preserves meaning


// The five eighths note example again: no single note head can spell
// it. The model will not round it, and Rastrum will not quietly
// guess. `ScorePrepare` rewrites the bar into tied pieces, and a
// writer handed the unprepared tree will refuse it:

~awkward = Measure("4/4", "c*5/8 d*3/8");

// The same durations can be supplied to the named constructor when
// code is computing them:
//
Measure.durations("4/4", "5/8 3/8", "c d");

// Before and after, as durations. One leaf became two:

[ ~awkward.leaves.collect { |leaf| leaf.dur },
    ScorePrepare.run(~awkward).leaves.collect { |leaf| leaf.dur } ].postln;

// The heads changed. The music did not. Same attacks, same sounding durations.
// `Rastrum.events` is section 16, and here it is only the witness.

[
	Rastrum.events(~awkward).collect { |event| event[\rastrum][\duration] },
	Rastrum.events(ScorePrepare.run(~awkward)).collect { |event| event[\rastrum][\duration] }
].postln;

// Where the note starts decides how it is split, so the same 5/8
// spells differently an eighth later. That is why preparation needs
// the bar and not just the duration.

ScorePrepare.run(Measure.durations("4/4", "8 5/8 4", "c d e")).leaves.collect { |leaf| leaf.dur }.postln;

// The façade prepares for you, so `preview` takes the tree as you
// wrote it. The page shows two tied heads where you wrote one note of
// five eighths.

~show.([~awkward]);

// Try LilyWriter.new.write(~awkward). The writer refuses an
// unprepared tree rather than guessing. Hand it
// ScorePrepare.run(~awkward) and it just works.



// 7. Refusals

// A wrong score should fail where it was written rather than render
// as something you cannot be written.
//
// Try each line on its own:
//
//     Validator.validate(Measure("4/4", "c2"))
//     Validator.validate(Measure("4/4", "c2~ d2"))
//     MN("c4").articulation(\wiggle)
//
// They are refused where the mistake is written.

~fixedBar = Measure("4/4", "c2 d2");

~show.([~fixedBar]);

// Try giving that first bar the missing half note and it will pass. A
// bar can also be short on purpose (see section 12).



// 8. Marks and spanners

// A marking belongs to one attack: a dynamic, a sforzando, an
// articulation, a technical mark, or text:

~marked = Measure("4/4", "c4:mf:staccato d4:tenuto e2:text{sul pont.}");

~marked.leaves.first.markings.postln;

// Dynamics, sforzandos, articulations and technical marks come from
// lists defined in the model, which is why something not in those
// lists is refused, as in `.articulation(\wiggle)` above.

~newMarks = Measure("2/4", "c4:sfz d4:upbow");
~sweptMarks = Measure("2/4", "sfz:pp[downbow[e4 f4]]");

// Text is a different case. It has no vocabulary by nature, so you
// can write freely.

// A spanner reaches across leaves and is stored as two endpoints
// sharing an id. `Spanner.slur` puts the start on the first and the
// stop on the last, it answers the run. When you place endpoints,
// `Validator` checks that they pair up correctly.

~slurred = Measure("4/4", Spanner.slur("g4 f4 e2"));

// A hairpin and a beam share the same shape, two ends paired by an
// id. The beam below is authored by hand, the one in section 9
// derives one from the meter instead.

// Presently, Rastrum turns LilyPond's own inference off in both
// cases. The reason is that one of the backends (LilyPond in this
// case, but the same principle applies to the others) cannot add a
// beam the model does not carry.

~beamed = Measure("2/4", Spanner.beam("c8 d8 e8 f8"));

// A hairpin also has a written spelling. The direction heads a
// bracket. Colons inside still belong to the leaves:

~swelling = Measure("3/4", "crescendo[e'4:p g'4 b'4:mp]");

// A glissando is written the same way, but it joins neighboring
// attacks. Three notes under one bracket make two lines:

~sliding = Measure("4/4", "gliss[c'4 e'4 d'4] r4");

~sliding.leaves.collect { |leaf| leaf.spanners.size }.postln;

// The outer notes hold one endpoint each, the middle holds two, and
// the rest holds none. The constructor spelling writes the same page:

~slidingToo = Measure("4/4",
    Spanner.glissando("c'4 e'4 d'4") ++ [MusicRest("4")]);

[LilyWriter.new.write(~sliding) == LilyWriter.new.write(~slidingToo)].postln;

// Chords slide too. They pair note head to note head in written order,
// so both ends need the same count.

~slidingChords = Measure("2/4", "gliss[<c' e' g'>4 <d' f' a'>4]");

// Eight bars, each span beside the marking. A 2/4 among 4/4 bars is
// an ordinary meter change inside one staff:
//
~show.([~marked, ~newMarks, ~sweptMarks, ~slurred, ~beamed, ~swelling,
    ~sliding, ~slidingChords]);


// 9. Ornaments, and beams the meter decides

// The marks above sat on leaves that stand in the bar. A grace
// doesn't. A grace note is written and does not "last". It hangs off
// the note it ornaments rather than standing in the bar, so `graces`
// isn't `children`, and nothing that adds to "written time" ever
// reaches it.

// A grace leaf is a note or a chord. `"r8"` is refused where it is
// written.

~graced = MN("c4").grace("b8");
~slashed = MN("e4").acciaccatura("f16 g16");

[~graced.graces.size, ~slashed.graceStyle].postln;

// A leaf token says it too, the head naming the style and the braces
// holding the run. Use whichever suits the material: the method where
// the leaf is already an object, the token where the bar is a String.

~written = Measure("2/4", "c4:grace{b8} e4:acciaccatura{f16 g16}");
~written.leaves.collect { |leaf| leaf.graceStyle }.postln;

// This is the entire arithmetic. A bar holding a graced note is
// exactly as long as one holding the note alone, and no duration
// anywhere is zero.

~ornamented = Measure("2/4", [~graced, ~slashed]);
[~ornamented.duration, ~ornamented.leaves.size].postln;

// Two leaves, though five note heads will be printed: a grace leaf is
// not one of the `leaves`, so the tie checker, the metric hierarchy
// and MusicXML divisions never see it.

// Beams can be authored by hand, as above, or derived from the meter.
// `AutoBeam.run` reads the meter and attaches the same endpoints, so
// every writer sees one decision rather than inferring its own:

~six = Measure.proportions("6/8", "(1 1 1 1 1 1)");

AutoBeam.run(~six);
AutoBeam.groupsIn(~six).collect { |group| group.size }.postln;

// 3+3, not 2+2+2: 6/8 is compound, and beaming it in pairs would
// print a bar of 3/4 wearing the wrong time signature. A grouped
// meter says it outright.

~show.([~ornamented, ~six]);

// Change the example `Spanner.beam(~six.leaves)` before
// `AutoBeam.run`, and look again. A bar beamed by hand is left as
// written.


// 10. Directions

// Before, we hung a marking on one attack. "Allegro" is not that kind
// of thing. It is NOT a property of the first eighth note. It is a
// property of the entire system it stands over. So a direction
// attaches to a Measure, with the same word a leaf uses for the same
// act.

~headed = Measure("4/4", "c2 d2").tempo("Allegro").rehearsalMark("A");

~headed.directions.postln;

// A direction carries how far into the bar it stands, zero by
// default. It has to land where a leaf begins: LilyPond puts a mark
// where it stands in the note stream, and there is nowhere to put one
// inside a note.

~headed.tempo("Rit.", Duration(1, 2));

// Change the example above to Duration(1, 4) as that offset
// instead. Nothing begins a quarter into a bar of two half notes, and
// Validator will not accept it.

// A tempo can also carry a metronome mark, the one part of a tempo
// that is a number rather than a word. Prose, a mark, or both, never
// neither.

~withMark = Measure("4/4", "c1").tempo("Allegro", beat: "4", bpm: 132);
~markAlone = Measure("4/4", "c1").metronome("4.", 60);

[~withMark.directions.first, ~markAlone.directions.first].postln;

// The number is what makes a tempo audible rather than merely
// visible, and a dotted quarter at 60 is 90 quarter notes a minute.
// One conversion, in the model, because a clock and MusicXML <sound>
// both count in quarters, and it's what makes the mark reach playback
// in section 18.

// A raw clock and a stored PlaybackTempoMap value are quarter-notes
// per minute. A written mark and a prose map entry may name any
// notatable beat, and both are normalized to quarters: see
// PlaybackTempoMap#tempo in section 18.

[ ~withMark.directions.first.quarterPerMinute,
  ~markAlone.directions.first.quarterPerMinute].postln;

~show.([~headed, ~withMark, ~markAlone]);

// Presently, `Direction.metronome(Duration(5, 8), 60)` isn't
// accepted. A beat no note head can carry isn't a "beat". It could
// compute a tempo in principle, but right now it is refused here
// rather than in a writer.


// 11. Tuplets and rhythm as proportions (RTM)

// The first bar in the first section held a 3:2 bracket and said
// nothing about it. A tuplet scales the time of what it holds.
// `Tuplet.ratio` says it the way a bracket does, three in the time of
// two. Its children slot takes the run a bar takes, and a list of
// built leaves is what to reach for once one of them needs something
// said about it.

~triplet = Tuplet.ratio("3:2", "c4 d4 e4");

[~triplet.multiplier, ~triplet.duration].postln; // three quarters in the space of two

// A bracket is also a token in a written run. It is the one container
// the run admits, because the token says the counts and the leaves
// under them.

~writtenTuplets = Measure("4/4", "3:2[c4 d4 e4] f2");

~nestedWritten = Measure("1/4", "5:4[c16 3:2[d16 e16 f16] g16 a16]");

// `Measure.proportions` is `RhythmTree.measure` named from the bar
// side. Here the rhythm is the subject, so call `RhythmTree.measure`
// directly and spell the shape as an array. Positive shares sound
// (notes, chords, etc), negative shares rest, and a nested share
// subdivides its span. Pitches are assigned to sounding attack points
// in order.

~rtm = RhythmTree.measure("4/4", [1, [1, [1, 1, 1]], -1, 1], "c e g b a");

~rtm.leaves.collect { |leaf| leaf.dur }.postln;

// Add one more top level share and the whole bar becomes a 5:4, while
// the inner share stays a 3:2, so the middle leaves sit inside two
// multipliers at once. A parenthesized RTM string is the compact
// spelling of the same shape:

~nestedCell = RhythmCell("(1 (1 (1 1 1)) -1 2)");
~nested = ~nestedCell.measure("4/4", "c e g b a");
~multipliers = ScorePrepare.leafMultipliersIn(~nested);

~nested.leaves.collect { |leaf| [leaf.dur, ~multipliers[leaf]] }.postln;

// A cell can be edited by path before it becomes notation. Path [1] names the
// inner triplet. This keeps the outer 5:4 but turns the middle triplet note
// into a rest.

~nestedRest = ~nestedCell
    .rewriteCellAt([1], { |inner| inner.mutedAt([1]) })
    .measure("4/4", "c e g b");

// `render` and `preview` derive beams after preparation. `writeJSON`
// doesn't: derived beams are engraving policy, not authored score
// facts.

// `\complexRhythm` adds proportional spacing, full brackets and
// complete ratios. Give it more room by overriding the profile:

//   LilyProfile(\complexRhythm, (systemSpacing: 22, markupSystemPadding: 3))

// Use `\openComplexRhythm` for more complex music materials.

// Its own PDF file, so the sections after this one do not overwrite
// it with an ordinary spacing page while you are still looking.

~show.([
    ~writtenTuplets,
    ~nestedWritten,
    ~rtm,
    ~nested,
    ~nestedRest
], \treble, \complexRhythm, "overview-tuplets");

// Try writing and comparing a [1, 1, 1] against [1, 1, 1, 1] in a 4/4
// bar. Three equal parts need a Tuplet, and four don't.

// RhythmTree works that out from the meter behind the scenes for you.


// 12. Voices, and bars that are short on purpose

// A Voice is one timeline inside a bar. Two of them begin together,
// so each has to fill the bar on its own. A voice that is short is an
// error.

// A timeline is a run of leaves, so each voice is written the way a bar is.

~twoVoices = Measure("4/4", [
    Voice("g2 a2", "upper"),
    Voice("c1",    "lower")
]);

// A pickup is a *different* context. It's short and says so. It also
// knows where its span sits inside the meter. That is what makes the
// bar after it start in the right place.

~pickup = Measure.pickup("4/4", "g4", "4");

[~pickup.barDuration, ~pickup.metricOffset].postln;

// Both at once: a short bar, and two voices through it. Every bar of a staff
// has to carry the same voices, so the pickup gets them too.

~voicePickup = Measure.pickup("4/4", [
    Voice("g4", "upper"),
    Voice("c4", "lower")], "4");

~show.([~voicePickup, ~twoVoices]);

// A Measure.partial(...) for a bar cut short at the end of a section
// declares its span the same way, but sits at the start of the meter.


// 13. Creating a Score

// Everything above, in two staves.

~violin = Staff([
    ~pickup,
    Measure("4/4", "3:2[c4 d4 e4] f2").tempo("Allegro"),
    ~marked,
    ~slurred
], "Violin", \treble);

// The cello is rhythm and pitches and nothing else, so it's written
// directly. Commas put it in the lower register.

~cello = Staff([
    Measure.pickup("4/4", "r4", "4"),
    Measure("4/4", "c,1"),
    Measure("4/4", "g,,2 a,,2"),
    Measure("4/4", "c,1")
], "Cello", \bass);

~score = MusicScore([~violin, ~cello], "Overview", "You");

Rastrum.preview(~score, "overview");

// Try putting ~beamed into ~violin in place of ~slurred. A meter
// change inside one staff is fine, as shown before, this two-staff
// score is different. The violin's third bar would be 2/4 while the
// cello's third bar is still 4/4, so Validator refuses at cross-staff
// barline.


// 14. Writers

// There is one model, but different writers. Currently, four document
// writers, none is the model's native form.

~ready = ScorePrepare.run(~score);
~lily = LilyWriter.new.write(~ready);
~xml = MusicXMLWriter.new.write(~ready);
~gmn = GuidoWriter.new.write(~ready);
~json = ScoreJSONWriter.new.write(~ready);

// LilyPond, MusicXML and GUIDO are one way document views. ScoreJSON
// is a two-way interchange format. A score read back from it is an
// ordinary score again, so the same writers work on it.

~reread = ScoreJSONReader.read(~json);

// It looks the same because the score facts came back, not because
// the page writer remembered anything.

Rastrum.preview(~reread, "overview");

// Writing files needs no LilyPond. These answer the paths they wrote.
//
//     Rastrum.writeMusicXML(~score, "overview")
//     Rastrum.writeGuido(~score, "overview")
//     Rastrum.writeJSON(~score, "overview")
//     Rastrum.render(~score, "overview", compile: false) // just the .ly file


// 15. Reading the Score

// Everything so far builds a tree and hands it somewhere.
// `ScoreSelection` goes the other way. It reads one and changes
// nothing: the violin's notes, where each falls, what the cello is
// doing.

~notes = ScoreSelection(~score).inStaff("Violin").notes;

[~notes.size, ~notes.offsets.first, ~notes.offsets.last].postln;

// Filters answer another selection, so they chain, and a record says
// where its leaf stands: which staff, which bar, which timeline, and
// the exact moment it begins. Offsets are Durations, so, for
// instance, a triplet quarter is a sixth and not 0.1666.

~notes.first.keys.asArray.sort.postln;

// A leaf is a note head, and a note is sometimes more than one with
// ties. The 5/8 note we've seen before is written as two tied heads
// and "counts" as one note, and `logicalTies` counts what sounds, not
// how it is notated.

[ ScoreSelection(ScorePrepare.run(~awkward)).size,
  ScoreSelection(ScorePrepare.run(~awkward)).logicalTies.size ].postln;

// What you can find you can rewrite, as a copy. `mapLogicalTies` asks
// once per sounding note, so a note written as two tied heads moves
// as one rather than as two decisions that could disagree.

~third = MusicInterval.named(\major, 3);

~doubled = ScoreSelection(~score).inStaff("Violin").mapLogicalTies { |run|
    ~third.transpose(run[\pitch]) };

// The violin moved and the score you started from didn't: the copy is
// the only thing that changed, and the "Cello" staff wasn't in the
// selection at all.

[ ScoreSelection(~doubled).inStaff("Violin").notes.leaves.first.pitch,
  ScoreSelection(~score).inStaff("Violin").notes.leaves.first.pitch ].postln;

// Try `ScoreSelection(~ready).logicalTies.size` against
// `ScoreSelection(~ready).pitched.size`. They match because this
// piece has no tied notes. `~ready.leaves.size` is one larger: a rest
// is a leaf, not a note.

// `ScoreEdit` is the checked way back in when you want an addressed
// edit rather than a whole selection transform. A run is read as a
// selection, then one validated copy is built around the change.

~editSpan = ScoreSelection(~score).inStaff("Violin").inMeasure(2).withinBar(0, "1/2");

~sameSpan = { |score| ScoreSelection(score).inStaff("Violin").inMeasure(2).withinBar(0, "1/2")};

~spanShape = { |selection|
    selection.leaves.collect { |leaf| [leaf.pitch.spelling, leaf.dur.rational.asString] }
};

~replaced = ScoreEdit.replaceRun(~score, ~editSpan, "a4 b4");

~reshaped = ScoreEdit.reshapeRun(~score, ~editSpan, "3:2[a8 b8 c'8] d4");

[
    "before"     -> ~spanShape.(~editSpan),
    "replaceRun" -> ~spanShape.(~sameSpan.(~replaced)),
    "reshapeRun" -> ~spanShape.(~sameSpan.(~reshaped))
].postln;

// `replaceRun` keeps one selected leaf for one replacement leaf.
// `reshapeRun` keeps the occupied time and lets the shape change.
// Both refuse edits that would silently drop part of a tuplet, tie or
// spanner.


// 16. The Event Stream Writer

// The same model also answers SuperCollider events. A tie is not
// another attack, so this interpretation follows sounding attacks
// rather than written note heads.

~events = Rastrum.events(~score);

// The 5/8 note from before shows the claim. Preparation wrote it as
// two heads. It sounds as one note, so it is one event.

[
	ScorePrepare.run(~awkward).leaves.size,
	Rastrum.events(~awkward).size
].postln;

// Each event carries what a scheduler needs at the top level, and everything
// Rastrum knows about it in one `\rastrum` payload, where it cannot be mistaken
// for a SynthDef control. `dur` is beats, the payload's `duration` is whole
// notes, and the two disagree on units on purpose.

~events.first.keys.asArray.sort.postln;

[~events.first[\dur], ~events.first[\rastrum][\duration]].postln;

// A timeline is a linear stream of events. That is a Pbind. Several begin
// together. That is a Ppar. This piece is two staves of one timeline each, so
// two Pbinds inside one Ppar.

[
	Rastrum.pbinds(~score).size,
	Rastrum.pattern(~score).class ].postln;

// `pattern` also carries the score's own metronome marks, when it has
// any. `pbinds` never does.

// Try `~events.collect { |event| event[\rastrum][\voice] }`. The
// events come back grouped by timeline, which is the shape a Pbind is
// built from.


// 17. Listening to it

// `Rastrum.play` is one line over `Rastrum.pattern`, and answers the
// player so you can stop it.

~player = Rastrum.play(~score);

~player.stop;

// `\default` is the SynthDef it uses, because SC's default event names that one
// and Rastrum overrides nothing. Your own is a SynthDef away. Anything reading
// `freq`, `amp` and `gate` will do:

SynthDef(\tut, { |out = 0, freq = 440, amp = 0.1, gate = 1, pan = 0|
    var env = EnvGen.kr(Env.adsr(0.01, 0.3, 0.4, 0.4), gate, doneAction: 2);
    Out.ar(out, Pan2.ar(SinOsc.ar(freq) * env * amp, pan));
}).add;

~player = Rastrum.play(~score, \tut);

~player.stop;

// Or lay keys over the pattern, which is all `play` does:

~player = Pbindf(Rastrum.pattern(~score), \instrument, \tut, \pan, -0.3).play;

~player.stop;


// 18. Tempo

// The score is marked "Allegro", but prose has no speed until one
// maps it. Rastrum doesn't decide that word's bpm for every reader,
// so nothing in the pattern carries tempo at all.

// What you get is SuperCollider's clock. `dur` is beats and a quarter
// note is 1.0, so a quarter lasts one second:

[ TempoClock.default.tempo, TempoClock.default.tempo * 60 ].postln;

// If you just want a speed, hand `play` a clock. A TempoClock counts
// beats per second, not per minute:

~player = Rastrum.play(~score, clock: TempoClock(120 / 60));

~player.stop;

// TempoClock.default.tempo = 132 / 60

// Numeric tempo uses quarter note beats, the unit `dur` counts. A raw
// clock has no `eighth = 80` form, so write the equivalent quarter
// tempo, or name the beat on the map below and let it convert.

// To make prose audible, map score marks to bpm. The map produces
// clock changes separate from notes, because tempo may land where no
// note begins.

~speeds = PlaybackTempoMap.new.tempo("Allegro", 132);   // 132 bpm

~speeds.records(~score).postln;

// A word may name its beat, as a written mark does. The table keeps
// only the quarter note equivalent, because that is what a clock
// reads. Semantically these are different, but in practice these two
// have the same speed:

PlaybackTempoMap.new.tempo("Allegro", beat: "8", bpm: 264).tempos.postln;

// A metronome mark already contains a number, so it bypasses the map.
// `Rastrum.pattern` puts that printed number in the stream.

~timed = MusicScore.oneStaff([
    Measure("4/4", "c1").tempo("Allegro", beat: "4", bpm: 132),
    Measure("4/4", "e1").metronome("4.", 60)
], "Violin", \treble, "Timed");

PlaybackTempoMap.new.records(~timed)
    .collect { |record| [record[\measure], record[\bpm]] }.postln;

// The same numbers are what playback uses, no map mentioned. 132/60
// is 2.2 beats a second, and a dotted quarter at 60 is a quarter at
// 90, i.e., 1.5.

~timedStream = Rastrum.pattern(~timed).asStream;
4.collect { ~timedStream.next(Event.default.copy) }
    .select  { |event| event.keys.includes(\tempo) }
    .collect { |event| event[\tempo] }.postln;

~player = Rastrum.play(~timed);

~player.stop;

// A printed metronome number reaches every surface: LilyPond,
// MusicXML, ScoreJSON and playback. Prose only tempo still needs the
// table above.

// If a direction has both "Allegro" and `4 = 132`, the printed number
// wins.

// Try `.tempo("Allegro", 200)` on a map, then `records(~timed)`. The first
// bar still comes back at 132, because that is what is printed.

// A ritardando or accelerando is tempo over a span. The group helper attaches
// both endpoints, and the start says where the ramp is heading.

~rampBars = Direction.tempoRamp([
    Measure("4/4", "c1").metronome("4", 120),
    Measure("4/4", "d1")
], "rit.", beat: "4", bpm: 60);

~ramped = MusicScore.oneStaff(~rampBars, "Violin", \treble, "Ritardando");

~rampTree = Rastrum.prepared(~ramped);

~rampPoints = PlaybackTempoMap.new.records(~rampTree, false);

[
    PlaybackTempoMap.rampsIn(~rampTree, ~rampPoints)
        .collect { |ramp| [ramp[\sourceBpm], ramp[\targetBpm]] },

    PlaybackTempoMap.new.tempoEvents(~ramped)
        .select { |event| event[\tempo].notNil }.size
].postln;

// A TempoClock has point tempi, not curves, so playback lowers the
// ramp to small tempo changes. The written grid and the note events
// do not move.


// 19. Interpretation

// Structure is derived and interpretation is chosen. A score says
// "ff", not an amplitude, and "staccato", not a note length, so what
// a mark is worth to a synth lives in an optional table that nothing
// reaches unless you ask. For example:

~map = PlaybackMap.new
    .instrumentAt(0, 0, \default)          // staff 0, its first timeline
    .instrumentAt(1, 0, \default)
    .useDynamics                           // pppp..ffff maps to \amp
    .useArticulations;                     // staccato, tenuto maps to \legato

~map.carriedKeys.postln;

// The `mf` and the staccato from before are now numbers a SynthDef
// can read:

~map.events(~score).select { |event| event[\rastrum][\markings].notEmpty }
    .collect { |event| [event[\amp].round(0.001), event[\legato]] }.postln;

// `~map.pattern` answers what `Rastrum.pattern` does, with those keys
// in it, so the materials the tempo stream from before apply:

~player = Ppar([~speeds.tempoPattern(~score),
    ~map.pattern(~score, tempo: false)
]).play;

~player.stop;

// Change the code above to .dynamic(\ff, 0.3) on the map. The default
// table is one mapping of those words, not the loudness they carry.
// The right curve depends on the SynthDef, so every entry is
// replaceable.


// 20. The Quasiquoter

// The examples above use ordinary sclang constructors and specialized
// parser strings for different data types. The quasiquoter is an opt
// in front end for five constructor targets. This tutorial keeps
// those blocks out of the file because the usual SuperCollider syntax
// highlighter can lose track after them. The raw spelling appears in
// comments, and the executable lines below call the same translator
// directly.

// That does not mean this extra syntax `[measure| ... |]` can't be
// added at the editor level in principle. As long as the preprocessor
// is started, the sclang interpreter will read them correctly.

// In an interactive session, evaluate the start on its own before raw
// blocks in your sketch or score file: a file or selection is
// preprocessed whole before any of it runs, so a block in the same
// evaluation as the line that turns it on is still raw text.

// This is the setup a session with quasiquotation needs:
Rastrum.startQuasiquoter;

~qq = { |source| RastrumQuasiquoter.translate(source).interpret };

/*
[measure| 4/4 3:2[c4 d4 e4] f2 |]
*/

~qqBar = ~qq.("[measure| 4/4 3:2[c4 d4 e4] f2 |]");

/*
[measure| 1/4 5:4[c16 3:2[d16 e16 f16] g16 a16] |]
*/

~qqNested = ~qq.("[measure| 1/4 5:4[c16 3:2[d16 e16 f16] g16 a16] |]");

/*
[measures| 4/4 c4 d4 e2 | c4 r4 e2 |]
*/

~qqBars = ~qq.("[measures| 4/4 c4 d4 e2 | c4 r4 e2 |]");

/*
[rtm| (1 (1 (1 1 1)) -1 2) |]
*/

~qqCell = ~qq.("[rtm| (1 (1 (1 1 1)) -1 2) |]");

/*
[run| c4 r4 <e g>2 |]
*/

~qqRun = ~qq.("[run| c4 r4 <e g>2 |]");

~qqRunBar = Measure("4/4", ~qqRun);

~show.(~qqBars ++ [~qqBar, ~qqNested, ~qqRunBar,
    ~qqCell.measure("4/4", "c d e f g a")],
    \treble, \complexRhythm, "overview-quasiquoter");

Rastrum.stopQuasiquoter;

// The five tags are `[note| ... |]`, `[run| ... |]`, `[measure| ...
// |]`, `[measures| ... |]` and `[rtm| ... |]`. A run answers an Array
// of leaves, and a measures block answers an Array of bars, so they
// go where those arrays already go.

// There is no `[score| ... |]` in the current implementation.

// A quasiquoter takes the interpreter's one preprocessor slot. If
// another quark already owns it, `Rastrum.startQuasiquoter` refuses
// unless you pass `true`. `Rastrum.stopQuasiquoter` puts the previous
// one back.
