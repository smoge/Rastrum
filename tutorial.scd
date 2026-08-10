// Rastrum: an overview
//
// One linear walk through the API: short sections building a two-staff piece
// from a single note, writing it out three ways, then playing it. Most
// sections end with something to try.


//////////////////////////////////////////////////////////////////////////////
// 1. Getting started
//////////////////////////////////////////////////////////////////////////////

// A note is a pitch and a duration. Both are objects rather than numbers,
// because both carry more than a number can.

~note = MusicNote(\c, Duration.quarter);
~note.postln;
~note.class.postln;                  // MusicNote

// A bar holds leaves and declares a meter. A staff holds bars. A score holds
// staves. That is the whole tree, and every writer walks the same one.
//
// `MN` below is a shorthand for `MusicNote` and answers one. It holds nothing
// and adds nothing at all, which is why the leaf it builds says MusicNote when
// you ask, and why every posted value and error message says it too. The files
// in examples/ use `MN` throughout. This overview names the model, so it says
// `MusicNote` from here on.

~bar = Measure(Meter(4, 4), [
    MN(\c, Duration.quarter),        // MN is MusicNote, but shorter
    MN(\d, Duration.quarter),
    MN(\e, Duration.half)
]);
~bar.leaves.first.class.postln;      // MusicNote, whichever way it was written
~score = MusicScore([Staff([~bar], "Violin", \treble)], "Overview", "You");

// That is the tree spelled out, and it is worth seeing once. Most of the time
// you will not write it. A bar is usually easier said as *proportions* plus the
// pitches to hang on them: 1, 1, 2 means two short and one twice as long,
// however long the bar happens to be.

~same = RhythmTree.measure(Meter(4, 4), [1, 1, 2], [\c, \d, \e]);
(~same.leaves.collect { |leaf| leaf.dur }
    == ~bar.leaves.collect { |leaf| leaf.dur }).postln;

// The same bar, and the one to reach for when you are writing music rather than
// reading about the model. Section 11 is where it earns its keep. It works out
// tuplet brackets from the meter, which is the part nobody wants to do by hand.
//
// If you would rather say the note values than the shares, its twin takes those
// instead. Same arguments in the same order, and a negative duration is a rest.

~alsoSame = Measure.durations(Meter(4, 4), [1%/4, 1%/4, 1%/2], [\c, \d, \e]);
(~alsoSame.leaves.collect { |leaf| leaf.dur }
    == ~bar.leaves.collect { |leaf| leaf.dur }).postln;

// `1%/4` builds a Rational, the class from the quark Rastrum depends on, and it
// is coerced into a Duration on the way in. Two classes rather than two
// spellings, and what the tree holds is always the Duration. Plain `1/4` is a
// Float, and while Rastrum recovers a rational from it, that recovery is a
// guess. `3/112` comes back as `1/37`. Fine for a quarter, silently wrong
// inside a nested tuplet, so use `%/`.
//
// The spelled-out form stays useful for a leaf that needs saying exactly: a
// tie, a chord, or a note you are about to attach something to.

// Look at it. This is the line that needs LilyPond. Run it again whenever the
// score changes below and the same window shows the new version.
Rastrum.preview(~score, "overview");

// One window, reused, so running a section again replaces what is on screen
// rather than opening a tenth viewer. `name` is for a section that wants its
// own file: everything here writes `overview.pdf`, so a later preview would
// otherwise overwrite an earlier one you were still looking at. It validates a
// prepared copy first, so a bad score fails here rather than inside the
// facade, which prepares and validates again on its own.
~show = { |bars, clef = \treble, layout = \default, name = "overview"|
    var score = MusicScore([Staff(bars.asArray, "Violin", clef)], "Overview");
    Validator.validate(ScorePrepare.run(score));
    Rastrum.preview(score, name, layout: layout)
};

// Shows a refusal, and throws when examples that should fail start passing.
// Used from section 5 on.
~refused = { |label, thunk|
    var caught = false;
    try { thunk.value } { |error|
        caught = true;
        ("  % : %".format(label, error.errorString)).postln;
    };
    if (caught.not) { Error("% was accepted".format(label)).throw };
};

// Try changing a note in ~bar, then run ~show.([~bar]). The same window
// updates, which is how every section from here shows its work.


//////////////////////////////////////////////////////////////////////////////
// 2. Rests, chords, ties
//////////////////////////////////////////////////////////////////////////////


// Three more kinds of leaf. A rest takes time and sounds nothing.

~rest = MusicRest(Duration.quarter);

// A chord is one leaf holding several pitches, not three notes stacked, which
// matters as soon as it ties.

~chord = Chord([\c, \e, \g], Duration.half);
[~chord.pitches.size, ~chord.duration].postln;

// A tie says this note continues into the next rather than being struck again.
// You write it on the note that ties *forward*.

~tied = Measure(Meter(4, 4), [
    MusicNote(\c, Duration.half, true),      // ties into what follows
    MusicNote(\c, Duration.half)             // the same pitch, so it can receive it
]);

// A chord ties per pitch, so part of it can hold while the rest re-attacks.
// `true` means all of them, and a mask says which.

~partial = Chord([\c, \e, \g], Duration.half, [true, false, true]);
[~partial.tiesToNext, ~partial.tiesAll].postln;

~show.([
    Measure(Meter(4, 4), [~rest, MusicNote(\d, Duration.quarter), ~chord]),
    ~tied,
    Measure(Meter(4, 4), [~partial, Chord([\c, \e, \g], Duration.half)])
]);

// Try changing the second note of ~tied to \d, then run section 7. A tie has
// to reach the pitch it continues, and that is one of the refusals.


//////////////////////////////////////////////////////////////////////////////
// 3. Time is exact
//////////////////////////////////////////////////////////////////////////////

// Durations are rationals, never floats, so a triplet eighth is exactly a third
// of a quarter and stays that way through every writer and the JSON wire.

[Duration.quarter, Duration.quarter.dotted, Duration(1, 12)].postln;

// A duration also knows what it looks like on the page: the note value it is
// drawn as, and how many dots.

[Duration.quarter.notation, Duration.quarter.dotted.notation].postln;

// Not every duration is one note head. Five eighths is not, and says so.

[Duration(5, 8).isNotatable, Duration(5, 8).tieRuns].postln;

// A time signature is a pair. A meter is more than that pair. 6/8 and 3/4 last
// exactly as long as each other and are not the same meter, because they divide
// differently, and a 5/8 can be told which way to divide.

[Meter(6, 8).duration == Meter(3, 4).duration,
    Meter(6, 8).groups, Meter(3, 4).groups,
    Meter.grouped(5, 8, [2, 3]).groups].postln;

// A dotted quarter, and a 5/8 bar told to divide 2+3:

~show.([
    Measure(Meter(4, 4), [MusicNote(\c, Duration.quarter.dotted),
        MusicNote(\d, Duration.eighth), MusicNote(\e, Duration.half)]),
    Measure.durations(Meter.grouped(5, 8, [2, 3]), [1%/4, 3%/8], [\g, \a])
]);

// Try Duration(1, 5).isNotatable. A fifth of a whole note is not a note head
// either, and no chain of ties will make it one.


//////////////////////////////////////////////////////////////////////////////
// 4. Pitch is spelled, not numbered
//////////////////////////////////////////////////////////////////////////////

// A pitch keeps its letter, its accidental and its octave apart. C sharp and D
// flat are different pitches that happen to sound alike. A midinote could not
// tell you which was written.

~cSharp = MusicPitch(\c, \sharp);
~dFlat = MusicPitch(\d, \flat);
[~cSharp.midinote, ~dFlat.midinote, ~cSharp == ~dFlat].postln;

// Quarter tones are ordinary here rather than a special case, because the
// alteration is an exact count of semitones rather than a rounded one.

~quarterFlat = MusicPitch(\d, \quarterFlat);
[~quarterFlat.midinote, ~quarterFlat.alter].postln;

// Going the other way, a midinote has to be told how to spell itself.

[MusicPitch.fromMidinote(61, \sharps), MusicPitch.fromMidinote(61, \flats)].postln;

// Four spellings on the page, including the quarter tone:

~show.([Measure(Meter(4, 4), [
    MusicNote(~cSharp, Duration.quarter), MusicNote(~dFlat, Duration.quarter),
    MusicNote(~quarterFlat, Duration.quarter),
    MusicNote(MusicPitch(\e, \doubleSharp), Duration.quarter)])]);

// Try MusicPitch(\c, \sharp, 5). The third argument is the octave, and
// \natural is the accidental to pass when all you want is the octave.


//////////////////////////////////////////////////////////////////////////////
// 5. Intervals are spelled too
//////////////////////////////////////////////////////////////////////////////

// An interval is the spelled distance between two pitches, not a semitone
// count. C to F# and C to Gb sound alike and are different intervals.

~augFourth = MusicPitch(\f, \sharp) - MusicPitch(\c);
~dimFifth = MusicPitch(\g, \flat) - MusicPitch(\c);
[[~augFourth.signedName.number, ~augFourth.signedName.qualityName],
    [~dimFifth.signedName.number, ~dimFifth.signedName.qualityName],
    ~augFourth.chromatic == ~dimFifth.chromatic].postln;

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

// Quarter tones fill the gaps in those same ladders and need no new machinery.
// A neutral third really does sit between minor and major:

~thirds.([\threeQuarterFlat, \flat, \quarterFlat, \natural, \quarterSharp]).postln;
~fifths.([\quarterFlat, \natural, \quarterSharp]).postln;

// An interval can be said rather than measured, which is usually what you want:

[MusicInterval.named(\perfect, 5).semitones,
    MusicInterval.named(\neutral, 3).semitones,
    MusicInterval.named(\subminor, 7).quarterSteps].postln;

// Pitches are points and intervals are the vectors between them, so a pitch
// minus a pitch is an interval and a pitch plus an interval is a pitch:

~fifth = MusicInterval.named(\perfect, 5);
[MusicPitch(\c) + ~fifth, MusicPitch(\g) - ~fifth,
    MusicPitch(\c).transposeBy(~fifth)].postln;

// Two pitches cannot be added. There is no origin to add them from, and
// Rastrum says so rather than answering something:

~refused.("adding two pitches", { MusicPitch(\c) + MusicPitch(\e) });

// Intervals add and subtract among themselves, along a path:

~majorThird = MusicPitch(\e) - MusicPitch(\c);
[(~majorThird + (MusicPitch(\g) - MusicPitch(\e))) == ~fifth,
    (~fifth - ~majorThird) == MusicInterval.named(\minor, 3),
    (~fifth + ~fifth.negated) == MusicInterval.zero].postln;

// Which is what makes transposition worth having. Measure a shape once and it
// moves anywhere, spelling itself on the way:

~figure = [MusicPitch(\c), MusicPitch(\d), MusicPitch(\e), MusicPitch(\g)];
~from = { |root|
    var move = root - MusicPitch(\c);
    Measure(Meter(4, 4), ~figure.collect { |pitch|
        MusicNote(pitch + move, Duration.quarter) })
};
~show.([~from.(MusicPitch(\c)), ~from.(MusicPitch(\e)),
    ~from.(MusicPitch(\a, \flat))], \treble, \default, "intervals");

// `qualityName` keeps altered degrees that have a word. `commonQualityName` is
// the smaller vocabulary, and answers nothing past it.

~wide = (MusicPitch(\f, \doubleSharp) - MusicPitch(\c)).signedName;
[~wide.qualityName, ~wide.commonQualityName].postln;

// Try MusicInterval.named(\major, 5). A fifth cannot be major, and the
// refusal names the qualities each family takes.


//////////////////////////////////////////////////////////////////////////////
// 6. What preparation is for
//////////////////////////////////////////////////////////////////////////////

// Write a note of five eighths and no single note head can spell it. Rastrum
// does not round it and does not quietly fix it: `ScorePrepare` rewrites the bar
// into tied pieces, and a writer handed the unprepared tree refuses.

~awkward = Measure(Meter(4, 4), [
    MusicNote(\c, Duration(5, 8)),
    MusicNote(\d, Duration(3, 8))
]);

// Before and after, as durations:
[~awkward.leaves.collect { |leaf| leaf.dur },
    ScorePrepare.run(~awkward).leaves.collect { |leaf| leaf.dur }].postln;

// Where the note starts decides how it is split, so the same 5/8 spells
// differently an eighth later. That is why preparation needs the bar and not
// just the duration.

ScorePrepare.run(Measure(Meter(4, 4), [
    MusicNote(\c, Duration.eighth),
    MusicNote(\d, Duration(5, 8)),
    MusicNote(\e, Duration.quarter)
])).leaves.collect { |leaf| leaf.dur }.postln;

// The facade prepares for you, so `preview` takes the tree as you wrote it.
// The page shows two tied heads where you wrote one note of five eighths.

~show.([~awkward]);

// Try LilyWriter.new.write(~awkward). The writer refuses an unprepared
// tree rather than guessing. Hand it ScorePrepare.run(~awkward) and it writes.


//////////////////////////////////////////////////////////////////////////////
// 7. When Rastrum refuses
//////////////////////////////////////////////////////////////////////////////

// A wrong score should fail where it was written rather than render as something
// you did not write. `Validator` is what the facade runs before any writer, and
// you can run it yourself.

Validator.validate(~bar).class.postln;      // answers what it was given

"--- what will not be accepted ---".postln;

~refused.("a bar that does not fill its meter",
    { Validator.validate(Measure(Meter(4, 4), [MusicNote(\c, Duration.half)])) });

~refused.("a tie reaching a different pitch",
    { Validator.validate(Measure(Meter(4, 4), [
        MusicNote(\c, Duration.half, true),
        MusicNote(\d, Duration.half)])) });

~refused.("an articulation nobody can spell",
    { MusicNote(\c, Duration.quarter).articulation(\wiggle) });

// And what passes: the same bar with the half note it was missing.

~show.([Measure(Meter(4, 4), [
    MusicNote(\c, Duration.half), MusicNote(\d, Duration.half)])]);

// Try giving that first bar the missing half note and watch it pass. A bar
// that is short *on purpose* is section 12.


//////////////////////////////////////////////////////////////////////////////
// 8. Marks
//////////////////////////////////////////////////////////////////////////////

// A marking belongs to one attack: a dynamic, an articulation, a word. Each
// answers the leaf, so they chain and read in the order you write them.

~marked = Measure(Meter(4, 4), [
    MusicNote(\c, Duration.quarter).dynamic(\mf).articulation(\staccato),
    MusicNote(\d, Duration.quarter).articulation(\tenuto),
    MusicNote(\e, Duration.half).text("sul pont.")
]);
~marked.leaves.first.markings.postln;

// A dynamic and an articulation come from closed lists, which is why section
// 7's \wiggle is refused. Text has no vocabulary by its nature, so what
// `Marking.checkedText` asks is only that it is prose a document can carry.

// A spanner reaches across leaves and is stored as two endpoints, so it cannot
// dangle: `Spanner.slur` puts the start on the first and the stop on the last,
// and answers the leaves, so a group stands where the leaves would.

~slurred = Measure(Meter(4, 4), Spanner.slur([
    MusicNote(\g, Duration.quarter),
    MusicNote(\f, Duration.quarter),
    MusicNote(\e, Duration.half)
]));

// A hairpin and a beam are the same shape, two ends paired by an id, so they
// are written the same way. The beam here is authored by hand, and section 9
// derives one from the meter instead. LilyPond's own inference is switched off
// either way, so a backend cannot add a beam the model never carried.

~beamed = Measure(Meter(2, 4), Spanner.beam([
    MusicNote(\c, Duration.eighth), MusicNote(\d, Duration.eighth),
    MusicNote(\e, Duration.eighth), MusicNote(\f, Duration.eighth)
]));
~beamed.leaves.first.spanners.postln;

// Three bars, so the beam is on the page beside the marking and the slur. The
// last is 2/4 among 4/4, which is an ordinary meter change inside one staff.
~show.([~marked, ~slurred, ~beamed]);

// Try Spanner.crescendo(...) in place of Spanner.slur, and preview again.


//////////////////////////////////////////////////////////////////////////////
// 9. Ornaments, and beams the meter decides
//////////////////////////////////////////////////////////////////////////////

// A grace note is written and does not last. It hangs off the note it ornaments
// rather than standing in the bar, so `graces` is not `children` and nothing
// that sums written time ever reaches it.

~graced = MusicNote(\c, Duration.quarter).grace(MN(\b, Duration.eighth));
~slashed = MusicNote(\e, Duration.quarter)
    .acciaccatura([MN(\f, Duration.sixteenth), MN(\g, Duration.sixteenth)]);
[~graced.graces.size, ~slashed.graceStyle].postln;

// Which is the whole of the arithmetic: a bar holding a graced note is exactly
// as long as one holding the note alone, and no duration anywhere is zero.

~ornamented = Measure(Meter(2, 4), [~graced, ~slashed]);
[~ornamented.duration, ~ornamented.isFull, ~ornamented.leaves.size].postln;

// Two leaves, though five note heads will be printed: a grace leaf is not one of
// the `leaves`, so the tie checker, the metric hierarchy and MusicXML's
// divisions never see it.
// The two styles differ only in spelling, `\grace` against `\acciaccatura` and
// `<grace/>` against `<grace slash="yes"/>`.

// Beams are authored, as section 8 placed one by hand, but they can also be
// derived. `AutoBeam.run` reads the meter and attaches the same endpoints, so it
// is model data rather than a backend trick and every writer sees one decision.

~six = RhythmTree.measure(Meter(6, 8), [1, 1, 1, 1, 1, 1]);
AutoBeam.run(~six);
LilyWriter.new(false).write(Staff([~six], "V", \treble))
    .findRegexp("[a-g]'?8[\\[\\]]?").collect { |m| m[1] }.postln;

// 3+3, not 2+2+2: 6/8 is compound, and beaming it in pairs would print a bar of
// 3/4 wearing the wrong time signature. A grouped meter says it outright.

[AutoBeam.groupSpans(Meter(6, 8)),
 AutoBeam.groupSpans(Meter.grouped(5, 8, [2, 3]))].postln;

~show.([~ornamented, ~six]);

// Try `Spanner.beam(~six.leaves)` before `AutoBeam.run`, and look again.
// A bar beamed by hand is left exactly as written.


//////////////////////////////////////////////////////////////////////////////
// 10. System directions
//////////////////////////////////////////////////////////////////////////////

// "Allegro" is not a property of the first eighth note. It is a property of
// the bar it stands over. So a direction attaches to a Measure, with the same
// word a leaf uses for the same act.

~headed = Measure(Meter(4, 4), [
    MusicNote(\c, Duration.half), MusicNote(\d, Duration.half)
]).tempo("Allegro").rehearsalMark("A");
~headed.directions.postln;

// A direction carries how far into the bar it stands, zero by default. It has to
// land where a leaf begins: LilyPond puts a mark where it stands in the note
// stream, and there is nowhere to put one inside a note.

~headed.tempo("Rit.", Duration(1, 2));
~headed.directions.last.offset.postln;

// Try Duration(1, 4) as that offset instead. Nothing begins a quarter into a
// bar of two half notes, and Validator will say so.

// A tempo can also carry a metronome mark, the one part of a tempo that is a
// number rather than a word. Prose, a mark, or both, and never neither.

~withMark = Measure(Meter(4, 4), [MusicNote(\c, Duration.whole)])
    .tempo("Allegro", unit: Duration.quarter, perMinute: 132);
~markAlone = Measure(Meter(4, 4), [MusicNote(\c, Duration.whole)])
    .metronome(Duration.quarter.dotted, 60);
[~withMark.directions.first, ~markAlone.directions.first].postln;

// Both writers spell it, and the number is what makes a tempo audible rather
// than merely visible: LilyPond's own MIDI reads `4 = 132` and ignores "Allegro",
// and MusicXML needs a <metronome> before an importer treats the words as a
// tempo at all.

LilyWriter.new.write(MusicScore([Staff([~withMark, ~markAlone], "V", \treble)]))
    .findRegexp("\\\\tempo[^|]*").collect { |m| m[1].stripWhiteSpace }.postln;

// A dotted quarter at 60 is 90 quarter notes a minute. One conversion, in the
// model, because a clock and MusicXML's <sound> both count in quarters, and it
// is what makes the mark reach playback in section 17.

[~withMark.directions.first.quarterPerMinute,
 ~markAlone.directions.first.quarterPerMinute].postln;

~show.([~headed, ~withMark, ~markAlone]);

// Try Direction.metronome(Duration(5, 8), 60). A beat no note head can
// carry is not a beat, and it is refused here rather than in a writer.


//////////////////////////////////////////////////////////////////////////////
// 11. Tuplets, and rhythm as proportions (RTM)
//////////////////////////////////////////////////////////////////////////////

// A tuplet scales the time of what it holds. `Tuplet.ratio` says it the way a
// bracket does, three in the time of two.

~triplet = Tuplet.ratio("3:2", [
    MusicNote(\c, Duration.quarter),
    MusicNote(\d, Duration.quarter),
    MusicNote(\e, Duration.quarter)
]);
[~triplet.multiplier, ~triplet.duration, ~triplet.leaves.size].postln;

// Which is what `RhythmTree.measure` from section 1 has been doing for you. A
// positive weight is a note, a negative one a rest, and a nested list subdivides
// its own share, so the triplet above is a bar written as `[1, [1, [1, 1, 1]]]`
// without naming a Tuplet at all. Pitches are handed out to the notes in order.

~rtm = RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], -1, 1],
    [\c, \e, \g, \b, \a]);
~rtm.leaves.collect { |leaf| leaf.dur }.postln;

// Add one more top-level share and the whole bar becomes a 5:4. The inner share
// is still a 3:2, so the middle leaves sit inside two multipliers at once.

~nestedCell = RhythmCell([1, [1, [1, 1, 1]], -1, 2]);
~nested = RhythmTree.measure(Meter(4, 4), ~nestedCell, [\c, \e, \g, \b, \a]);
~multipliers = ScorePrepare.leafMultipliersIn(~nested);
~nested.leaves.collect { |leaf| [leaf.dur, ~multipliers[leaf]] }.postln;

// A cell can be edited by path before it becomes notation. Path [1] names the
// inner triplet. This keeps the outer 5:4 but turns the middle triplet note
// into a rest.

~nestedRest = RhythmTree.measure(Meter(4, 4),
    ~nestedCell.rewriteCellAt([1], { |inner| inner.mutedAt([1]) }),
    [\c, \e, \g, \b]);

// The eighths inside these brackets come out beamed without anything being
// asked for. `render` and `preview` run `AutoBeam.run` after preparation, since
// LilyPond's own inference is switched off and an unbeamed page is nobody's
// default. A bracket divides its own span, so the bar's quarter line does not
// cut the inner triplet.
//
// `Rastrum.writeJSON` does *not* beam. A derived group is engraving policy, and
// the model has no mark saying which endpoints a person wrote, so the wire
// would hand metasonic-score a decision as though it were authored. Pass
// `beam: false` to a notation path, or call `AutoBeam.run` yourself before
// writing JSON, whichever way you want it.
//
// These are worth looking at in `\complexRhythm`, the second of the two layouts:
// notes spaced by how long they last rather than by how much ink their heads
// need, brackets spanning the whole duration they cover, and a tuplet printing
// its ratio rather than a bare count, so tuplets say 3:2 and 5:4. Spacing is a
// LilyPond concept, so it is the writer's argument and the score never learns
// the word.

// Its own file, `overview-tuplets.pdf`, so the sections after this one do not
// overwrite it with an ordinary-spacing page while you are still looking.
~show.([
    Measure(Meter(4, 4), [~triplet, MusicNote(\f, Duration.half)]),
    ~rtm,
    ~nested,
    ~nestedRest
], \treble, \complexRhythm, "overview-tuplets");

// Try [1, 1, 1] against [1, 1, 1, 1] in a 4/4 bar. Three equal parts need a
// bracket and four do not, and RhythmTree works that out from the meter.


//////////////////////////////////////////////////////////////////////////////
// 12. Two voices, and a bar that is short on purpose
//////////////////////////////////////////////////////////////////////////////

// A Voice is one timeline inside a bar. Two of them begin together, so each has
// to fill the bar on its own. A voice that is short is an error rather than a
// guess about where its notes sit.

~twoVoices = Measure(Meter(4, 4), [
    Voice([MusicNote(\g, Duration.half), MusicNote(\a, Duration.half)], "upper"),
    Voice([MusicNote(\c, Duration.whole)], "lower")
]);
[~twoVoices.hasVoices, ~twoVoices.voices.size].postln;

// A pickup is short and says so, and it knows where its span sits inside the
// meter, which is what makes the bar after it start in the right place.

~pickup = Measure.pickup(Meter(4, 4), [MusicNote(\g, Duration.quarter)],
    Duration(1, 4));
[~pickup.barDuration, ~pickup.metricOffset, ~pickup.isAnacrusis].postln;

// Both at once: a short bar, and two voices through it. Every bar of a staff
// has to carry the same voices, so the pickup gets them too.

~show.([
    Measure.pickup(Meter(4, 4), [
        Voice([MusicNote(\g, Duration.quarter)], "upper"),
        Voice([MusicNote(\c, Duration.quarter)], "lower")], Duration(1, 4)),
    ~twoVoices
]);

// Try Measure.partial(...) for a bar cut short at the end of a section. It
// declares its span the same way, but sits at the start of the meter.


//////////////////////////////////////////////////////////////////////////////
// 13. The piece
//////////////////////////////////////////////////////////////////////////////

// Everything above, in two staves. This is the score the rest of the file uses.

~violin = Staff([
    ~pickup,
    Measure(Meter(4, 4), [~triplet, MusicNote(\f, Duration.half)])
        .tempo("Allegro"),
    ~marked,
    ~slurred
], "Violin", \treble);

// The cello is rhythm and pitches and nothing else, so it is written the short
// way. `[1]` is one note filling the bar, and `[1, 1]` is two halves.

~cello = Staff([
    Measure.pickup(Meter(4, 4), [MusicRest(Duration.quarter)], Duration(1, 4)),
    RhythmTree.measure(Meter(4, 4), [1], [[\c, \natural, 3]]),
    RhythmTree.measure(Meter(4, 4), [1, 1], [[\g, \natural, 2], [\a, \natural, 2]]),
    RhythmTree.measure(Meter(4, 4), [1], [[\c, \natural, 3]])
], "Cello", \bass);

~score = MusicScore([~violin, ~cello], "Overview", "You");
[~score.children.size, ~score.leaves.size].postln;

Rastrum.preview(~score, "overview");

// Try putting ~beamed into ~violin in place of ~slurred. It is a 2/4 bar among
// 4/4 ones, so Validator stops you, which is section 7 doing its job.


//////////////////////////////////////////////////////////////////////////////
// 14. The same score, three ways
//////////////////////////////////////////////////////////////////////////////

// One tree, three writers, and none of them is the model's native form. That is
// the whole design: nothing in the score knows what a backslash means.

~ready = ScorePrepare.run(~score);
~lily = LilyWriter.new.write(~ready);
~xml = MusicXMLWriter.new.write(~ready);
~json = ScoreJSONWriter.new.write(~ready);
[~lily.size, ~xml.size, ~json.size].postln;

// The JSON is Rastrum's own, for trading scores with metasonic-score rather
// than for anyone else to read, and it round trips: what comes back writes the
// same LilyPond *and* the same MusicXML as what went out. Both are asked, since
// one backend agreeing with itself is not the property worth having.

~reread = ScoreJSONReader.read(~json);
[LilyWriter.new.write(ScorePrepare.run(~reread)) == ~lily,
    MusicXMLWriter.new.write(ScorePrepare.run(~reread)) == ~xml].postln;

// And it looks the same, because it is the same: this previews ~reread, the
// score that came back from the JSON, not the one that went out.

Rastrum.preview(~reread, "overview");

// Writing files needs no LilyPond. These answer the paths they wrote.
//
//     Rastrum.writeMusicXML(~score, "overview");
//     Rastrum.writeJSON(~score, "overview");
//     Rastrum.render(~score, "overview", compile: false);   // just the .ly


//////////////////////////////////////////////////////////////////////////////
// 15. The score as events
//////////////////////////////////////////////////////////////////////////////

// The same tree also answers SuperCollider events. A tie is not a re-attack, so
// these are the sounding music rather than the written notes. Nothing in the
// piece is tied, so the counts match:

~events = Rastrum.events(~score);
[~events.size, ~ready.leaves.size].postln;

// The 5/8 bar from section 6 is where they part. Preparation wrote it as two
// tied heads, and it still sounds as one note, so it is one event.

[ScorePrepare.run(~awkward).leaves.size, Rastrum.events(~awkward).size].postln;

// Each event carries what a scheduler needs at the top level, and everything
// Rastrum knows about it in one `\rastrum` payload, where it cannot be mistaken
// for a SynthDef control. `dur` is beats, the payload's `duration` is whole
// notes, and the two disagree on units on purpose.

~events.first.keys.asArray.sort.postln;
[~events.first[\dur], ~events.first[\rastrum][\duration]].postln;

// A timeline is a linear stream of events, which is what a Pbind is. Several
// begin together, which is what a Ppar is. A four-voice score answers four
// Pbinds inside one Ppar.

[Rastrum.pbinds(~score).size, Rastrum.pattern(~score).class].postln;

// `pattern` also carries the score's own metronome marks, when it has any, as
// section 17 shows. `pbinds` never does: a tempo is not a timeline.
//
// That pattern is already playable. Boot a server once. The line is left
// commented because starting a server is a decision rather than a step:
//
// s.boot;
//
// Then hear the piece. Without a server these still run and say so rather than
// failing, so the file is safe to evaluate whole either way.

~player = Rastrum.play(~score);
~player.stop;

// The next three sections are that pair with something added each time: a
// SynthDef of your own, a tempo, and what the marks are worth.

// Try ~events.collect { |event| event[\rastrum][\voice] }. The events come
// back grouped by timeline, which is the shape a Pbind is built from.


//////////////////////////////////////////////////////////////////////////////
// 16. Listening to it
//////////////////////////////////////////////////////////////////////////////

// No MIDI file and no LilyPond: the pattern *is* the playable thing, and SC
// schedules it directly. You can look at what it would play before playing it,
// which is worth doing once.

// `next` on a stream answers the event it would schedule. Ask for a few:

~drain = { |pattern, howMany = 8|
    var stream = pattern.asStream, out = List.new;
    howMany.do {
        var event = stream.next(Event.default.copy);
        event !? { out.add(event) };
    };
    out.asArray
};

"--- what the first six steps would play ---".postln;
~drain.(Rastrum.pattern(~score), 6).do { |event|
    ("  % for % beats".format(event[\midinote] ? "rest", event[\dur])).postln };

// `Rastrum.play` is one line over `Rastrum.pattern`, and answers the player so
// you can stop it.

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

// Or lay keys over the pattern yourself, which is all `play` does:

~player = Pbindf(Rastrum.pattern(~score), \instrument, \tut, \pan, -0.3).play;
~player.stop;


//////////////////////////////////////////////////////////////////////////////
// 17. Tempo
//////////////////////////////////////////////////////////////////////////////

// The score above is marked Allegro and plays at 60 bpm. Not a bug: a direction
// is prose on the page, and prose has no speed until you give it one. "Allegro"
// is not 132. It depends on the period, the instrument and the room, and a
// model that stored a number would decide that for every reader.

// So nothing in the pattern carries tempo at all:

[Rastrum.events(~score).any { |event| event.keys.includes(\tempo) },
    PatternWriter.keys].postln;

// What you get is SuperCollider's clock, untouched. `dur` is beats and a
// quarter note is 1.0, so a quarter lasts one second:

[TempoClock.default.tempo, TempoClock.default.tempo * 60].postln;

// If you just want a speed, hand `play` a clock. A TempoClock counts beats per
// *second*, so dividing by 60 is the whole conversion:

~player = Rastrum.play(~score, clock: TempoClock(120 / 60));
~player.stop;

//     TempoClock.default.tempo = 132 / 60;      // or session-wide, if you like
//
// Wherever a tempo is a number here, the beat is a quarter note, because that is
// what `dur` counts. There is no `eighth = 80` form: an eighth is half a beat,
// so eighth = 80 is quarter = 40.
//
// The honest fix is to say what the score's own marks are worth. A tempo belongs
// to a *moment* rather than to a note, since there may be no note at all where
// it changes, so it is a separate list rather than a key on an event.

~speeds = PlaybackTempoMap.new.tempo("Allegro", 132);   // 132 quarters a minute
~speeds.records(~score).postln;

// That list becomes a stream of rests that set the clock and sound nothing,
// playing *beside* the music:

~speeds.tempoEvents(~score).collect { |event|
    [event[\dur], event[\tempo] ? "no change"] }.postln;

~player = Ppar([
    ~speeds.tempoPattern(~score),                // first: it reaches the clock
    Rastrum.pattern(~score, tempo: false)        // before the note
]).play;
~player.stop;
//
// The order matters: two Ppar children due at the same moment are taken in
// order, so the tempo pattern goes first or the first note plays at the old
// speed. A mark the table has no speed for is refused rather than guessed at.
// Try removing the `.tempo("Allegro", 132)` above and asking for `records`.
//
// A metronome mark needs no table at all. The table exists because "Allegro" is
// not a number. `4 = 132` is one, and it is printed, so nothing interprets it,
// and `Rastrum.pattern` puts it in the stream for you. This is what section 10's
// mark bought:

~timed = MusicScore([Staff([
    Measure(Meter(4, 4), [MusicNote(\c, Duration.whole)])
        .tempo("Allegro", unit: Duration.quarter, perMinute: 132),
    Measure(Meter(4, 4), [MusicNote(\e, Duration.whole)])
        .metronome(Duration.quarter.dotted, 60)
], "Violin", \treble)], "Timed");

PlaybackTempoMap.new.records(~timed)
    .collect { |record| [record[\measure], record[\bpm]] }.postln;

// And there it is in the pattern, no map mentioned. 132/60 is 2.2 beats a
// second, and a dotted quarter at 60 is a quarter at 90, so 1.5.

~drain.(Rastrum.pattern(~timed), 4)
    .select { |event| event.keys.includes(\tempo) }
    .collect { |event| event[\tempo] }.postln;

~player = Rastrum.play(~timed);           // at the speed it prints
~player.stop;
//
// So one number on the page reaches every surface: LilyPond prints it and sends
// it to the .midi it writes, MusicXML draws it and plays it, the JSON carries
// it, and the clock is set by it. Nothing had to be told what a word means. A
// score marked only in prose is passed over here rather than refused, and still
// goes through the table above.
//
// Where a direction says both "Allegro" and `4 = 132`, the score's number
// wins and the table is not consulted. Notation beats interpretation.

// Try `.tempo("Allegro", 200)` on a map, then `records(~timed)`. The first
// bar still comes back at 132, because that is what is printed.


//////////////////////////////////////////////////////////////////////////////
// 18. Choosing how it sounds
//////////////////////////////////////////////////////////////////////////////

// Structure is derived and interpretation is chosen. A score says "ff", not an
// amplitude, and "staccato", not a note length, so what a mark is worth to a
// synth lives in an optional table that nothing reaches unless you ask.

~map = PlaybackMap.new
    .instrumentAt(0, 0, \default)          // staff 0, its first timeline
    .instrumentAt(1, 0, \default)
    .useDynamics                           // pppp..ffff -> \amp
    .useArticulations;                     // staccato, tenuto -> \legato

~map.carriedKeys.postln;

// The `mf` and the staccato from section 8 are now numbers a SynthDef can read:

~map.events(~score).select { |event| event[\rastrum][\markings].notEmpty }
    .collect { |event| [event[\amp].round(0.001), event[\legato]] }.postln;

// `~map.pattern` answers what `Rastrum.pattern` does, with those keys in it,
// so everything in section 16 applies, tempo stream included:

~player = Ppar([~speeds.tempoPattern(~score),
    ~map.pattern(~score, tempo: false)]).play;
~player.stop;

// Try .dynamic(\ff, 0.3) on the map. The default table ships as *a*
// mapping, not *the* loudness of those words. The right curve depends on
// the SynthDef, so every entry is replaceable.
