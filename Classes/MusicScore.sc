// Containers: Tuplet, Voice, Measure, Staff, MusicScore.
//
// MusicScore rather than Score, because `Score` is core (NRT).


// Note [A bracket is two facts]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `ratio` is the multiplier, and it reduces because it is arithmetic: 6:4 and
// 3:2 scale time identically. `actualNotes` and `normalNotes` are printed over
// the bracket, and those do not reduce. A composer who wrote 6:4 did not write
// 3:2.
//
// The two describe one bracket, so they must agree. Otherwise a writer would
// print one bracket while every duration under it used another. The constructor,
// `Validator`, the JSON writer, and the JSON reader all check this boundary.
//
// A tuplet built from a multiplier alone has no authored pair to keep, so its
// counts are the reduced ones. That is the honest answer: a multiplier says
// nothing about how it was counted.
//
// All three fields are read-only for the same reason. Setting the multiplier by
// itself would leave the counts describing a different bracket. The wire refuses
// that state, so memory must not admit it either.



// A bracket: children whose written time is scaled to fit a different span.
// Five in the time of four is `Tuplet.ratio(5, 4, notes)`.
//
// `multiplier` is that scaling, and it inverts the printed pair. Five notes in
// the time of four each last four fifths, so a 5:4 stores 4/5. Downstream passes
// read that value. LilyPond and MusicXML spell the printed pair later.
//
// `actualNotes` and `normalNotes` keep the pair as authored, because 6:4 and
// 3:2 scale time alike and are not the same bracket.
// Note [A bracket is two facts] above says what follows from that.
Tuplet : ScoreContainer {
    var <ratio, <actualNotes, <normalNotes;

    *new { |ratio, children| ^super.new(children).initTuplet(ratio, nil, nil) }

    // The bracket as it is printed and as it is spoken: `Tuplet.ratio(5, 4,
    // ...)` or `Tuplet.ratio("5:4", ...)`, both meaning five in the time of
    // four.
    //
    // The stored multiplier is the inverse. Five notes in the time of four last
    // four fifths each. This is the way in to the notation, as `MusicPitch.named`
    // is a way in to a step and an alteration.
    //
    // A separate constructor rather than more for `new` to accept, because
    // `new` already takes a string: `Duration.asDuration` parses "4/5".
    // Admitting "5:4" beside it would put two ratio languages in one argument
    // position, differing by one character and meaning opposite layers of the
    // model. Kept apart, each constructor has one contract and can say so when
    // given the other's language.
    //
    // >>> Tuplet.ratio("5:4").actualNotes   -> 5
    // >>> Tuplet.ratio(5, 4).multiplier     -> Duration(4/5)
    *ratio { |actual, normal, children|
        var counts;
        if (actual.isKindOf(String) or: { actual.isKindOf(Symbol) }) {
            // "5:4" means the second argument is the children. There is no
            // third argument.
            counts = this.prParseRatio(actual);
            ^this.prBuild(counts[0], counts[1], normal)
        };
        ^this.prBuild(this.prCheckCount(actual, "actual"),
            this.prCheckCount(normal, "normal"), children)
    }

    // Counts kept as authored. The multiplier they imply is stored beside them.
    *prBuild { |actual, normal, children|
        ^super.new(children).initTuplet(Duration(normal, actual), actual, normal)
    }

    // Rebuilt from a tree that already has both facts. Preparation needs this
    // when it copies a bracket or cuts one at a barline. A fragment of a 6:4 is
    // still part of a 6:4, and the number over each half says so.
    *like { |tuplet, children|
        ^this.prBuild(tuplet.actualNotes, tuplet.normalNotes, children)
    }

    // The exact constructor under a name that says which layer it speaks.
    // Identical to `new` and deliberately so: no second validation, no second
    // parser, no rule of its own.
    //
    // It exists because `Tuplet.ratio(5, 4, ...)` and `aTuplet.ratio` are the
    // same word for inverted values, while `Tuplet.multiplier(...)` and
    // `aTuplet.multiplier` are the same word for the same value. The constructor
    // names say which layer they speak.
    *multiplier { |value, children|
        ^this.new(value, children)
    }

    // Parses "5:4" as [actual, normal]: a colon and two whole numbers, nothing
    // else. A slash is how the *multiplier* is written, so "4/5" is refused by
    // name rather than read here as 4 in the time of 5.
    *prParseRatio { |string|
        var parts = string.asString.split($:).collect { |part| part.stripWhiteSpace };
        var wrong = parts.any { |part|
            part.isEmpty or: { part.every { |char| char.isDecDigit }.not }
        };
        if (parts.size != 2 or: { wrong }) {
            Error("Tuplet: \"%\" is not a tuplet ratio. One is written "
                "actual:normal, as in \"5:4\" for five in the time of four. A "
                "multiplier like \"4/5\" is the other way round and belongs to "
                "Tuplet.multiplier(\"4/5\", children).".format(string)).throw
        };
        ^[this.prCheckCount(parts[0].asInteger, "actual"),
          this.prCheckCount(parts[1].asInteger, "normal")]
    }

    // Both sides are counts of notes, so both are whole and positive. A bracket
    // over nothing, or over minus three notes, is not a rhythm.
    *prCheckCount { |value, what|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error("Tuplet: the % count of a bracket is %, which is not a whole "
                "number of notes. `Tuplet.ratio(5, 4, ...)` is five in the time "
                "of four.".format(what, value)).throw
        };
        ^value
    }

    initTuplet { |argRatio, argActual, argNormal|
        // Caught here because this is where a string becomes a multiplier, and
        // because a colon reaching `Duration.asDuration` otherwise surfaces as
        // a missing `asFloat` several layers down.
        if (argRatio.isKindOf(String) or: { argRatio.isKindOf(Symbol) }) {
            if (argRatio.asString.contains(":")) {
                Error("Tuplet: \"%\" is a printed ratio, not a multiplier. Five "
                    "in the time of four is Tuplet.ratio(\"5:4\", children); this "
                    "constructor takes the multiplier that implies, 4/5.".format(
                        argRatio)).throw
            }
        };
        ratio = Duration.asDuration(argRatio);
        // Absent counts are the reduced ones. See
        // Note [A bracket is two facts]. Supplied ones are held to the rule
        // `ratio` holds them to. Derived ones are not, because a multiplier
        // that is not positive is the validator's to report as a multiplier
        // rather than as a count.
        actualNotes = if (argActual.isNil) {
            ratio.denominator
        } {
            Tuplet.prCheckCount(argActual, "actual")
        };
        normalNotes = if (argNormal.isNil) {
            ratio.numerator
        } {
            Tuplet.prCheckCount(argNormal, "normal")
        };
        // Checked here rather than in the constructors, because sclang has no
        // private methods: this is a door like any other, and the one place
        // both facts are set. Cross-multiplied rather than divided, so a count
        // of no notes is caught by the comparison instead of dividing by it.
        if ((normalNotes * ratio.denominator) != (actualNotes * ratio.numerator)) {
            Error("Tuplet: a bracket printed %:% does not scale time by %. The "
                "counts and the multiplier describe the same bracket, so they "
                "cannot disagree - build one with Tuplet.ratio(%, %, children) or "
                "the other with Tuplet.multiplier(%, children).".format(
                    actualNotes, normalNotes, ratio,
                    actualNotes, normalNotes, ratio)).throw
        };
        ^this
    }

    // >>> Tuplet.ratio(3, 2).isTrivial   -> false
    // >>> Tuplet.ratio(2, 2).isTrivial   -> true
    multiplier { ^ratio }
    isTrivial { ^ratio.numerator == ratio.denominator }

    // Whether the printed counts are already the multiplier's own terms. True
    // for an ordinary triplet, which is why nothing about one changes. False
    // for a 6:4, which prints differently from the 3:2 it scales by.
    //
    // >>> Tuplet.ratio(3, 2).countsAreReduced   -> true
    // >>> Tuplet.ratio(6, 4).countsAreReduced   -> false
    // >>> Tuplet.ratio(6, 4).multiplier         -> Duration(2/3)
    countsAreReduced {
        ^(actualNotes == ratio.denominator) and: { normalNotes == ratio.numerator }
    }

    accept { |writer| ^writer.visitTuplet(this) }

    printOn { |stream|
        stream << "Tuplet(" << actualNotes << ":" << normalNotes
               << ", " << children.size << ")"
    }
}


// An independent timeline inside a bar. Two voices in one measure both start at
// the barline and both last the whole bar. They do not follow one another.
//
// Optional, not implied: a bar with no Voice children is a single timeline and
// answers `[measure]` to `voices`, so everything that walks timelines reads
// both shapes and ordinary music needs no special case.
Voice : ScoreContainer {
    var <>name;

    *new { |children, name| ^super.new(children).initVoice(name) }

    initVoice { |argName| name = argName; ^this }

    accept { |writer| ^writer.visitVoice(this) }

    printOn { |stream|
        stream << "Voice(" << children.size;
        if (name.notNil) { stream << ", " << name };
        stream << ")"
    }
}


// A bar. Usually it fills its meter, but not always: a pickup is short and sits
// at the *end* of its notional bar, while a final incomplete bar is short and
// sits at the start.
//
// Two exact facts distinguish those, and one flag could not. `barDuration` is
// how much written time the bar occupies. `metricOffset` is where that span
// begins inside the nominal meter. A quarter-note pickup to 4/4 lasts 1/4
// beginning at 3/4, the last beat of the notional bar rather than the first, so
// the metric rule measures its contents from three quarters in, and a 5/8 in
// a pickup is spelled differently from the same 5/8 in a final short bar.
Measure : ScoreContainer {
    var <>meter, <barDuration, <metricOffset;

    // What is said over this bar rather than to one of its notes: a tempo, a
    // rehearsal mark, an instruction. Kept here because that is where it
    // belongs: "Allegro" is a property of the bar it stands over, not of the
    // first eighth note.
    //
    // Held in the order they were attached, and replaced rather than mutated by
    // the setter, so a copied bar never shares the list with the bar it came
    // from. This is the rule a leaf's markings follow, for the same reason.
    var <directions;

    // The clef this bar changes to, or nil for "carry on with the one in
    // force".
    //
    // On the bar rather than on the staff because that is where a change
    // happens: a piano part crosses to the bass clef at a barline and back
    // again, and a single clef per staff cannot say so. The staff's own clef is
    // still what the part opens in.
    //
    // Not a `Direction`: those are *said* over a bar and all carry prose, where
    // a clef is how the notes after it are read and has a closed vocabulary.
    var <clef;

    *new { |meter, children| ^super.new(children).initMeasure(meter, nil, nil) }

    // An explicitly short bar: `barDuration` of written time, beginning
    // `metricOffset` into the meter.
    *partial { |meter, children, barDuration, metricOffset|
        ^super.new(children).initMeasure(meter, barDuration, metricOffset)
    }

    // An anacrusis: short, and sitting at the end of its notional bar, which is
    // where a pickup is heard. That placement is the whole reason
    // `metricOffset` exists, so the common case gets a name rather than an
    // argument to remember.
    *pickup { |meter, children, barDuration|
        var span = Duration.asDuration(barDuration);
        var full = (meter ?? { Meter(4, 4) }).duration;
        ^super.new(children).initMeasure(meter, span, full - span)
    }

    // A bar of silence: one rest lasting the whole meter.
    //
    // Not four quarter rests that add up but one rest, which is what
    // `wholeBarRests` recognizes and what both formats draw with their own
    // shape.
    //
    // >>> Measure.rest(Meter(3, 4)).wholeBarRests.size   -> 1
    *rest { |meter|
        var bar = meter ?? { Meter(4, 4) };
        ^this.new(bar, [MusicRest(bar.duration)])
    }

    // A bar written as the durations it holds, with the pitches to hang on
    // them.
    //
    // The twin of `RhythmTree.measure`, argument for argument, and the same
    // idea for the other way of thinking about a bar. One says *shares* of the
    // span and lets the meter work out the note values. This one says the note
    // values and checks they fill the span:
    //
    //     RhythmTree.measure(Meter(4, 4), [1, 1, 2],        [\\c, \\d, \\e]);
    //     Measure.durations(Meter(4, 4), [1%/4, 1%/4, 1%/2], [\\c, \\d, \\e]);
    //
    // A negative duration is a rest, as a negative weight is there. Pitches
    // cycle and default to middle C, as they do there. Durations coerce as
    // every duration does. `1%/4` is the exact short spelling and
    // `Duration.quarter` the explicit one. See
    // Note [A Float is not an exact duration] in Duration.sc before reaching
    // for `1/4`.
    //
    // No tuplets: a list of durations is flat, so anything needing a bracket
    // wants the proportional route, which derives one from the meter.
    //
    // Absolute durations can miss their span, while shares always fill theirs.
    // Refusing that here keeps the intended numbers in hand, before a writer
    // has to guess what happened.
    // A negative duration is a rest of that length.
    //
    // >>> Measure.durations(Meter(4, 4), [Duration(1, 2), Duration(-1, 2)])
    //     .leaves[1].class   -> MusicRest
    *durations { |meter, durations, pitches|
        var bar = meter ?? { Meter(4, 4) };
        var pitchStream = RhythmTree.pitchStream(pitches);
        var exact = (durations ? []).collect { |each| Duration.asDuration(each) };
        var total = exact.inject(Duration(0, 1)) { |sum, each| sum + each.abs };

        if (exact.isEmpty) {
            Error("Measure.durations: a bar needs something in it. "
                "Measure.rest(meter) is the bar of silence.").throw
        };
        if (total != bar.duration) {
            Error("Measure.durations: these durations come to %, and a bar of % "
                "holds %. A bar has to hold what it declares - Measure.partial "
                "or Measure.pickup is how a short bar says it is short.".format(
                    total, bar, bar.duration)).throw
        };
        ^this.new(bar, exact.collect { |each|
            if (each < Duration(0, 1)) {
                MusicRest(each.abs)
            } {
                MusicNote(pitchStream.next, each)
            }
        })
    }

    initMeasure { |argMeter, argBarDuration, argMetricOffset|
        directions = [];
        meter = argMeter ?? { Meter(4, 4) };
        barDuration = if (argBarDuration.isNil) {
            meter.duration
        } {
            Duration.asDuration(argBarDuration)
        };
        metricOffset = if (argMetricOffset.isNil) {
            Duration(0, 1)
        } {
            Duration.asDuration(argMetricOffset)
        };
        if (barDuration <= Duration(0, 1)) {
            Error("Measure: a bar of % occupies no time. A partial bar is shorter "
                "than its meter, not empty.".format(barDuration)).throw
        };
        if (metricOffset < Duration(0, 1)) {
            Error("Measure: a metric offset of % is before the barline.".format(
                metricOffset)).throw
        };
        if ((metricOffset + barDuration) > meter.duration) {
            Error("Measure: % of music beginning % into a % bar runs past the end "
                "of the meter. A partial bar sits inside its meter, not across "
                "it.".format(barDuration, metricOffset, meter)).throw
        };
        ^this
    }

    // A bar that fills its meter from the barline is the ordinary case, and
    // nothing downstream needs to treat it specially.
    // >>> RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]).isPartial   -> false
    isPartial {
        ^(barDuration != meter.duration) or: { metricOffset != Duration(0, 1) }
    }

    // An anacrusis is a partial bar whose span *ends* at the barline, the last
    // beat of its notional bar. That is a different fact from being short, and
    // the two writers both need it, so it is asked here once rather than
    // recomputed in each of them from `barDuration` and `metricOffset`.
    //
    // >>> Measure.pickup(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).isAnacrusis   -> true
    // >>> Measure.partial(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).isAnacrusis   -> false
    isAnacrusis {
        ^this.isPartial and: { (metricOffset + barDuration) == meter.duration }
    }

    // The other placement a short bar can have: it begins at the barline and
    // stops early, which is what a truncated final bar does.
    //
    // >>> Measure.partial(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).sitsAtBarline   -> true
    sitsAtBarline { ^metricOffset == Duration(0, 1) }

    // Returns this bar, so a direction goes on inline. The same word a leaf
    // uses for the same act: `attach` puts a thing on a thing.
    attach { |direction|
        if (direction.isKindOf(Direction).not) {
            Error("Measure: % is not a Direction. Build one with Direction.tempo, "
                "Direction.rehearsalMark or Direction.text - a marking goes on a "
                "leaf, not on a bar.".format(direction)).throw
        };
        directions = directions ++ [direction];
        ^this
    }

    // The same sugar a leaf carries for its markings, on the thing a direction
    // belongs to. Every one delegates to `attach` and builds through
    // `Direction`'s own factory, so what a direction may be is decided in one
    // place, and each answers this bar so it goes on inline where the bar is
    // built. `text` is the word a leaf uses too, because the receiver says
    // which is meant: prose over a bar is a direction, prose on an attack is a
    // marking.
    //
    // `offset` is how far into this bar the direction stands, zero by default,
    // so "Rit." over the third beat is `.tempo("Rit.", Duration(1, 2))`.
    //
    // A tempo also takes a metronome mark, by name where it is the point of the
    // call: `.tempo("Allegro", unit: Duration.quarter, perMinute: 132)`. With
    // no words to say, `.metronome(Duration.quarter, 132)` is the same
    // direction without the empty first argument.
    tempo { |value, offset = 0, unit, perMinute|
        ^this.attach(Direction.tempo(value, offset, unit, perMinute))
    }
    metronome { |unit, perMinute, offset = 0, text|
        ^this.attach(Direction.metronome(unit, perMinute, offset, text))
    }
    rehearsalMark { |value, offset = 0|
        ^this.attach(Direction.rehearsalMark(value, offset))
    }
    text { |value, offset = 0| ^this.attach(Direction.text(value, offset)) }

    directions_ { |list| directions = (list ? []).asArray.copy; ^this }

    // Answers this, so a clef change goes on inline where the bar is built:
    // `Measure(Meter(4, 4), elements).clef_(\bass)`. Held to the same closed
    // vocabulary a staff's clef is, and in the same place, so there is one
    // answer to what a clef may be.
    clef_ { |value| clef = Staff.checkedClef(value); ^this }
    hasDirections { ^directions.notEmpty }

    // >>> Measure(Meter(4, 4), [
    //     RhythmTree.voice(Meter(4, 4), [1, 1], name: \up),
    //     RhythmTree.voice(Meter(4, 4), [1, 1, 1, 1], name: \down)])
    //     .voices.size   -> 2
    // >>> Measure.rest(Meter(4, 4)).hasVoices   -> false
    hasVoices { ^children.any { |child| child.isKindOf(Voice) } }

    // A bar is either one timeline or a set of voices. A Voice standing beside
    // a loose note has no answer to "when does that note start", so both the
    // validator and the preparation pass refuse it, and they ask here, so the
    // rule has one definition rather than two that can drift.
    mixesVoicesWithElements {
        ^this.hasVoices and: {
            children.every { |child| child.isKindOf(Voice) }.not
        }
    }

    // Returns the independent timelines in this bar. A bar with no Voice
    // children has exactly one, and it is the bar itself, so anything that
    // walks timelines reads the two shapes the same way and one-voice music
    // needs no special case anywhere.
    voices { ^if (this.hasVoices) { children.asArray } { [this] } }

    // Returns the rest of every timeline that is silent for the whole bar: one
    // plain rest, alone in its timeline, lasting the bar's declared span.
    //
    // Notation gives a silent bar its own shape rather than a rest of that
    // length, so both writers need this answer. Asked here because it is a fact
    // about the tree rather than about either format.
    //
    // A partial bar has none. The shape says the *measure* is silent, and a
    // pickup or a truncated bar is not a whole measure. The shape would claim
    // all of it while the span says less.
    //
    // A rest carrying an attachment has none either. The shape replaces the
    // rest rather than decorating it, so a dynamic on one would have nowhere to
    // go, and silently dropping it is worse than spelling the rest the ordinary
    // way. A grace group counts, and the backends proved why: LilyPond writes
    // the group before the shape and MusicXML returns at the shape, so the same
    // bar came out as two different pieces of music.
    wholeBarRests {
        if (this.isPartial) { ^[] };
        ^this.voices.collect { |voice|
            var only = if (voice.children.size == 1) { voice.children[0] } { nil };
            if (only.notNil
                and: { only.isKindOf(MusicRest) }
                and: { only.hasMarkings.not }
                and: { only.hasSpanners.not }
                and: { only.hasGraces.not }
                and: { only.dur == barDuration }) { only } { nil }
        }.reject { |leaf| leaf.isNil }
    }

    // Voices run alongside each other, so the bar lasts as long as its longest
    // one rather than as long as all of them end to end.
    duration {
        ^if (this.hasVoices) {
            this.voices.collect { |voice| voice.duration }
                .reduce { |a, b| if (a > b) { a } { b } }
        } {
            super.duration
        }
    }

    // Every voice must fill the bar's declared span, not the voices between
    // them, and not the meter. A partial bar is full when it holds what it
    // says it does.
    //
    // >>> RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]).isFull   -> true
    isFull { ^this.voices.every { |voice| voice.duration == barDuration } }

    accept { |writer| ^writer.visitMeasure(this) }

    printOn { |stream|
        stream << "Measure(" << meter;
        if (this.isPartial) { stream << ", " << barDuration << " at " << metricOffset };
        stream << ")"
    }
}


// A part: its bars in order, a name, and the clef it opens in.
//
// The clef belongs here because a part is read in one, `\treble` unless said
// otherwise. A bar that crosses to another says so itself. See `Measure#clef`.
Staff : ScoreContainer {
    // The clefs both writers can spell, and the sign and line each one is.
    //
    // A closed vocabulary because MusicXML needs a `<sign>` and a `<line>`, and
    // there is no way to derive those from an arbitrary Symbol. Checked where a
    // clef is set. See Note [Refuse at the constructor] in MusicPitch.sc. Left
    // open, LilyPond wrote `\clef bogus` and MusicXML wrote nothing at all, so
    // a wrong clef failed at one backend and a right one vanished from the
    // other.
    //
    // Octave-transposing clefs are a second fact, not a sixth clef: `treble_8`
    // is a treble clef plus a shift, which is how MusicXML spells it too
    // (`<clef-octave-change>`). They are absent until that fact has somewhere
    // to live, rather than being smuggled in as more names here.
    classvar <clefSigns;

    var <>name, <clef;

    *initClass {
        clefSigns = IdentityDictionary[
            \treble -> ["G", 2], \bass -> ["F", 4], \alto -> ["C", 3],
            \tenor -> ["C", 4], \percussion -> ["percussion", 2]
        ];
    }

    // >>> Staff.clefs   -> [ alto, bass, percussion, tenor, treble ]
    *clefs { ^clefSigns.keys.asArray.sort }

    // nil is admitted and means "say nothing", which is what a staff that wants
    // the backend's own default asks for.
    //
    // >>> Staff.checkedClef(nil)   -> nil
    *checkedClef { |value|
        if (value.isNil) { ^nil };
        if (clefSigns[value.asSymbol].isNil) {
            Error("Staff: % is not a clef this writes. The clefs are %."
                .format(value.asCompileString,
                    this.clefs.collect { |each| each.asString }.join(", "))).throw
        };
        ^value.asSymbol
    }

    *new { |children, name, clef = \treble|
        ^super.new(children).initStaff(name, clef)
    }

    initStaff { |argName, argClef|
        name = argName;
        clef = Staff.checkedClef(argClef);
        ^this
    }

    // Answers this, so it chains as the auto-generated setter did.
    clef_ { |value| clef = Staff.checkedClef(value); ^this }

    accept { |writer| ^writer.visitStaff(this) }
}


// A whole score: staves, and the title and composer a page is headed with.
//
// The only root ScoreJSON accepts. A bare `Measure` is a fragment there and is
// refused both ways, where `LilyWriter` will happily write one. The envelope
// is a fact about the document, not about the music.
MusicScore : ScoreContainer {
    var <>title, <>composer;

    *new { |children, title, composer|
        ^super.new(children).initMusicScore(title, composer)
    }

    initMusicScore { |argTitle, argComposer|
        title = argTitle;
        composer = argComposer;
        ^this
    }

    accept { |writer| ^writer.visitScore(this) }

    // No asLily / asMusicXML / asJSON convenience methods here on purpose: the
    // model names no output format. Go through a writer, or through Rastrum.
}
