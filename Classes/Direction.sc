// Note [A tempo ramp is two directions]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `rit.` and `accel.` are tempo over a span. A direction has one
// bar-local offset, so a ramp is a pair of endpoints.
//
// `\tempoRamp` is a kind rather than a class. `edge` and `id` are
// kind-specific fields, as `unit` and `perMinute` already are.
//
// The start carries text and target. The stop only closes the id. A
// named arrival speed is a point tempo.
//
// `isTempo` stays `kind == \tempo`. `hasMetronome` stays point-tempo
// only. `PlaybackTempoMap` reads both.


// Direction
//
// A tempo, rehearsal mark or text instruction on a `Measure`.
//
// `offset` is local to the bar and defaults to zero.
//
// Negative offsets are refused here. Bar bounds and writer-placeable
// offsets need the surrounding `Measure`, so `Validator` checks them.
//
// An offset must land where a leaf begins in every voice.
// See Note [Where a direction may sit] in Writers/ScoreWriter.sc.
//
// Four closed kinds, refused at construction.
//
// A tempo may carry prose, a metronome mark, or both, never neither.
// Whole-number BPM and one notatable beat keep it portable.
//
// Backend losses are recorded in Writers/ScoreWriter.sc.
Direction {
    classvar <kinds, <edges;

    var <kind, <text, <offset, <unit, <perMinute, <edge, <id;

    *initClass {
        kinds = [\tempo, \rehearsalMark, \text, \tempoRamp];
        edges = [\start, \stop];
    }

    // Note [Two names for one beat]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `beat` and `bpm` are call-site aliases for `unit` and
    // `perMinute`. The model and ScoreJSON keep one name each.
    //
    // One fact said twice is refused rather than resolved.

    // >>> Direction.tempo("Allegro").kind   -> tempo
    *tempo { |text, offset = 0, unit, perMinute, beat, bpm|
        ^this.of(\tempo, text, offset, unit, perMinute, beat, bpm)
    }
    // >>> Direction.rehearsalMark("A").text   -> A
    *rehearsalMark { |text, offset = 0| ^this.of(\rehearsalMark, text, offset) }
    // >>> Direction.text("pizz.", "4").offset   -> Duration(1/4)
    *text { |text, offset = 0| ^this.of(\text, text, offset) }

    // A tempo with no words. The unit leads because the mark reads `4 = 120`.
    //
    // >>> Direction.metronome("4", 120).hasMetronome   -> true
    *metronome { |unit, perMinute, offset = 0, text, beat, bpm|
        ^this.of(\tempo, text, offset, unit, perMinute, beat, bpm)
    }

    // Note [A tempo ramp is two directions]. The start says the ramp
    // and target. The stop says where it ends and which id it closes.
    //
    // >>> Direction.tempoRampStart("rit.").isRampStart   -> true
    // >>> Direction.tempoRampStop.id                     -> 1
    *tempoRampStart { |text, offset = 0, id = 1, unit, perMinute, beat, bpm|
        ^this.of(\tempoRamp, text, offset, unit, perMinute, beat, bpm, \start, id)
    }
    *tempoRampStop { |offset = 0, id = 1|
        ^this.of(\tempoRamp, nil, offset, nil, nil, nil, nil, \stop, id)
    }

    // A whole ramp at once: start on the first bar, stop on the last,
    // id held here. It needs explicit offsets because a bar has no
    // attack position.
    //
    // Build both endpoints before attaching either.
    //
    // >>> Direction.tempoRamp([Measure("1/4", "c4"), Measure("1/4", "d4")],
    //     "rit.").last.directions.first.isRampStop
    // true
    *tempoRamp { |bars, text, startOffset = 0, stopOffset = 0, id = 1,
        unit, perMinute, beat, bpm|

        var group = (bars ? []).asArray;
        var start = this.tempoRampStart(text, startOffset, id, unit, perMinute,
            beat, bpm);
        var stop = this.tempoRampStop(stopOffset, id);

        if (group.isEmpty) {
            Error("Direction: a tempo ramp needs at least one Measure.").throw
        };
        group.do { |bar|
            if (bar.isKindOf(Measure).not) {
                Error("Direction: a tempo ramp expects Measures, got %.".format(bar)).throw
            }
        };
        if (group.size == 1 and: { start.offset >= stop.offset }) {
            Error("Direction: a one-bar tempo ramp starts at % and stops at %. "
                "The stop must be later.".format(start.offset, stop.offset)).throw
        };
        group.first.attach(start);
        group.last.attach(stop);
        ^group
    }

    *of { |kind, text, offset = 0, unit, perMinute, beat, bpm, edge, id|
        var checked = kind !? { |value| value.asSymbol };
        var checkedEdge, checkedId;
        unit = this.prOneOfTwoNames(unit, beat, "unit", "beat");
        perMinute = this.prOneOfTwoNames(perMinute, bpm, "perMinute", "bpm");
        if (kinds.includes(checked).not) {
            Error("Direction: \"%\" is not a direction. The kinds are %.".format(
                kind, kinds)).throw
        };
        checkedEdge = this.checkedEdge(checked, edge);
        checkedId = this.checkedId(checked, id);
        this.checkMetronome(checked, text, unit, perMinute, checkedEdge);
        // Prose uses `Marking` rules. Absence was checked just above.
        ^super.newCopyArgs(checked,
            if (text.isNil and: {
                unit.notNil or: { this.prIsRampStop(checked, checkedEdge) }
            }) { nil } { Marking.checkedText(text) },
            this.checkedOffset(offset),
            unit !? { this.checkedUnit(unit) },
            perMinute !? { this.checkedPerMinute(perMinute) },
            checkedEdge, checkedId)
    }

    // Required on a ramp, refused everywhere else.
    *checkedEdge { |kind, edge|
        if (kind != \tempoRamp) {
            if (edge.notNil) {
                Error("Direction: a % direction cannot carry edge %. Only "
                    "tempoRamp uses paired endpoints.".format(kind, edge)).throw
            };
            ^nil
        };
        if (edge.isNil) {
            Error("Direction: a tempo ramp endpoint needs edge start or stop. "
                "Use Direction.tempoRampStart or Direction.tempoRampStop.").throw
        };
        if (edges.includes(edge.asSymbol).not) {
            Error("Direction: \"%\" is not a tempo ramp edge. Use start or stop.".format(edge)).throw
        };
        ^edge.asSymbol
    }

    // Ids pair the ends.
    *checkedId { |kind, id|
        if (kind != \tempoRamp) {
            if (id.notNil) {
                Error("Direction: a % direction cannot carry id %. Only "
                    "tempoRamp uses endpoint ids.".format(kind, id)).throw
            };
            ^nil
        };
        if ((id ? 1).isKindOf(Integer).not or: { (id ? 1) < 1 }) {
            Error("Direction: a tempo ramp id must be a positive integer, got %.".format(id)).throw
        };
        ^id ? 1
    }

    // Note [Two names for one beat]. Either name, never both.
    *prOneOfTwoNames { |kept, alias, keptName, aliasName|
        if (kept.notNil and: { alias.notNil }) {
            Error("Direction: use % or %, not both.".format(keptName, aliasName)).throw
        };
        ^kept ?? { alias }
    }

    // Metronome checks before coercion: both halves, speed-bearing
    // kind, and at least one tempo fact. A ramp start takes the same
    // fields. `hasMetronome` stays point-tempo only.
    *checkMetronome { |kind, text, unit, perMinute, edge|
        if (unit.isNil != perMinute.isNil) {
            Error("Direction: a metronome mark needs both unit and perMinute. "
                "got % and %.".format(unit, perMinute)).throw
        };
        if (this.prIsRampStop(kind, edge)) {
            if (text.notNil) {
                Error("Direction: a tempo ramp stop cannot carry text, got \"%\"."
                    .format(text)).throw
            };
            if (unit.notNil) {
                Error("Direction: a tempo ramp stop cannot carry a metronome "
                    "mark. Put the target speed on the start.").throw
            };
            ^this
        };
        if (unit.notNil and: { this.prStatesASpeed(kind).not }) {
            Error("Direction: a % direction cannot carry a metronome mark. Use "
                "tempo, metronome or tempoRampStart.".format(kind)).throw
        };
        if (this.prStatesASpeed(kind) and: { text.isNil } and: { unit.isNil }) {
            Error("Direction: a % needs text, a metronome mark, or both.".format(kind)).throw
        }
    }

    // Kinds that may carry a metronome mark. Ramp stops are handled first.
    *prStatesASpeed { |kind|       ^(kind == \tempo) or: { kind == \tempoRamp } }
    *prIsRampStop   { |kind, edge| ^(kind == \tempoRamp) and: { edge == \stop } }

    // Coerced like other durations. The beat must be one notatable value.
    *checkedUnit { |unit|
        var exact = Duration.asDuration(unit);
        if (exact <= Duration(0, 1)) {
            Error("Direction: metronome beat % must be positive.".format(exact)).throw
        };
        if (exact.notation.isNil) {
            Error("Direction: metronome beat % is not writable as one note value.".format(exact)).throw
        };
        ^exact
    }

    // Whole BPM keeps the mark portable.
    *checkedPerMinute { |value|
        if (value.isKindOf(Integer).not) {
            Error("Direction: perMinute must be a positive integer, got %.".format(value.asCompileString)).throw
        };
        if (value <= 0) {
            Error("Direction: perMinute must be positive, got %.".format(value)).throw
        };
        ^value
    }

    // Coerced as everything is. Negative is refused: nothing is written before
    // the bar it is written in.
    *checkedOffset { |offset|
        var exact = Duration.asDuration(offset);
        if (exact < Duration(0, 1)) {
            Error("Direction: offset must be zero or later, got %.".format(
                exact)).throw
        };
        ^exact
    }

    // An exact kind test, and it must stay one.
    // `PlaybackTempoMap.prWalk` gates on it before its own predicate
    // runs, so a ramp endpoint answering true would reach the
    // point-tempo path and be compared to point changes as a
    // duplicate. Note [A tempo ramp is two directions].
    //
    // >>> Direction.tempo("Allegro").isTempo                -> true
    // >>> Direction.rehearsalMark("A").isRehearsalMark      -> true
    // >>> Direction.tempoRampStart("rit.").isTempo          -> false
    isTempo { ^kind == \tempo }
    isRehearsalMark { ^kind == \rehearsalMark }
    isText { ^kind == \text }

    // >>> Direction.tempoRampStart("rit.").isTempoRamp   -> true
    // >>> Direction.tempoRampStop.isRampStop             -> true
    isTempoRamp { ^kind == \tempoRamp }
    isRampStart { ^(kind == \tempoRamp) and: { edge == \start } }
    isRampStop { ^(kind == \tempoRamp) and: { edge == \stop } }

    // Point tempo only, for the same reason `isTempo` is exact:
    // `withScoreTempo` filters on this to find the marks that carry
    // their own number, and a ramp target isn't one of those.
    //
    // >>> Direction.metronome(Duration(1, 4), 120).hasMetronome           -> true
    // >>> Direction.text("Rit.").hasMetronome                             -> false
    // >>> Direction.tempoRampStart("rit.", bpm: 60, beat: "4").hasMetronome
    // false
    hasMetronome { ^(kind == \tempo) and: { unit.notNil } }

    // The other half of that split: where a ramp is heading, when it says.
    //
    // >>> Direction.tempoRampStart("rit.", bpm: 60, beat: "4").hasRampTarget
    // true
    // >>> Direction.tempoRampStart("rit.").hasRampTarget   -> false
    hasRampTarget { ^(kind == \tempoRamp) and: { unit.notNil } }
    hasText { ^text.notNil }

    // At the bar's start, which is where a direction sits unless it says
    // otherwise, and the one case a writer never has to place specially.
    //
    // >>> Direction.text("Rit.").atBarStart                   -> true
    // >>> Direction.text("Rit.", Duration(1, 2)).atBarStart   -> false
    atBarStart { ^offset == Duration(0, 1) }

    // The same mark counted in quarter notes, nil when there is no mark. Both
    // callers want it in those units, MusicXML's `<sound tempo=...>` and
    // `TempoClock` are both defined that way.
    //
    // >>> Direction.metronome(Duration(1, 4), 120).quarterPerMinute   -> 120.0
    // >>> Direction.metronome(Duration(3, 8), 60).quarterPerMinute    -> 90.0
    // >>> Direction.text("Rit.").quarterPerMinute                     -> nil
    quarterPerMinute {
        if (unit.isNil) { ^nil };
        ^Direction.quarterPerMinuteOf(unit, perMinute)
    }

    // The conversion on its own, over a pair that is already checked.
    // Arithmetic and nothing else: a caller that isn't a `Direction`
    // has its own rules about what a count may be, and
    // `PlaybackTempoMap` admits a fractional one where a written mark
    // doesn't. Here so there is one copy of it.
    //
    // A plain number rather than a `Duration`: the exactness rule is about
    // *time*, and 67.5 beats a minute is a rate, not a moment.
    //
    // >>> Direction.quarterPerMinuteOf(Duration(1, 8), 144)   -> 72.0
    // >>> Direction.quarterPerMinuteOf(Duration(1, 8), 45)    -> 22.5
    *quarterPerMinuteOf { |unit, perMinute|
        ^perMinute * unit.numerator * 4 / unit.denominator
    }

    // Offset, mark and endpoint are identity: two "Rit."s at
    // different points are two instructions, `4 = 60` isn't `4 =
    // 120`, and a start isn't its stop.
    //
    // >>> Direction.text("Rit.") == Direction.text("Rit.", "4")               -> false
    // >>> Direction.tempoRampStart("rit.").hash == Direction.tempoRampStart("rit.").hash
    // true
    == { |that| ^that.isKindOf(Direction) and: {
        (kind == that.kind) and: { text == that.text }
            and: { offset == that.offset } and: { unit == that.unit }
            and: { perMinute == that.perMinute } and: { edge == that.edge }
            and: { id == that.id } } }
    hash { ^(((kind.hash bitXor: text.hash) bitXor: offset.hash)
        bitXor: (unit.hash bitXor: perMinute.hash))
        bitXor: (edge.hash bitXor: id.hash) }

    // Each part only when there is one. The offset is labeled, since
    // two optional trailing values would otherwise read as each
    // other, and so is a non-default id, for the same reason.
    printOn { |stream|
        stream << "Direction(" << kind;
        edge !? { stream << ", " << edge };
        text !? { stream << ", " << text.asCompileString };
        unit !? {
            stream << ", " << unit.numerator << "/" << unit.denominator
                   << " = " << perMinute
        };
        if (id.notNil and: { id != 1 }) { stream << ", id " << id     };
        if (this.atBarStart.not)        { stream << ", at " << offset };
        stream << ")"
    }
}
