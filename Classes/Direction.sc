// Direction
//
// A tempo, a rehearsal mark, an instruction over the staff system. Not
// markings: a marking sits on a leaf and belongs to that attack, while a
// direction attaches to a Measure.
//
// A direction carries an `offset` into its bar, zero by default, so "Allegro"
// over the barline and "Rit." over the third beat are one kind of fact said at
// two places. The offset is *local to the bar*: where the bar sits inside its
// meter is the bar's business.
//
// Negative is refused here, needing no context to know and this constructor
// being the one door every path runs through: the facade, a raw writer, the
// JSON reader, a Direction built by hand. Whether an offset is *inside* its
// bar, and whether it lands where a writer can place it, are questions about a
// Measure this object doesn't see. `Validator` asks that.
//
// An offset must land where a leaf begins, in every voice of the bar, which is
// the narrower of what the two backends can place. See
// Note [Where a direction may sit] in ScoreWriter.sc.
//
// Three kinds, on the usual rule. See Note [Refuse at the constructor] in
// MusicPitch.sc
//
// A tempo may also carry a metronome mark: prose, a mark, or both, never
// neither. "Allegro" is not 120, but `4 = 120` on the page is. The count is
// whole because LilyPond's `\\tempo 4 = 120` takes an integer, and the beat
// must be one note head: `Duration(5, 8)` is refused here, not at a writer.
//
// Both backends lose something here, in opposite directions. See
// Note [What each backend cannot say] in ScoreWriter.sc.
Direction {
    classvar <kinds;

    var <kind, <text, <offset, <unit, <perMinute;

    *initClass {
        kinds = [\tempo, \rehearsalMark, \text];
    }

    *tempo { |text, offset = 0, unit, perMinute|
        ^this.of(\tempo, text, offset, unit, perMinute)
    }
    *rehearsalMark { |text, offset = 0| ^this.of(\rehearsalMark, text, offset) }
    *text { |text, offset = 0| ^this.of(\text, text, offset) }

    // A mark with no words: the most common tempo of all, and the one `tempo`
    // spells awkwardly, its first argument being prose. The unit leads because
    // that is the order it is written and said in: `4 = 120`.
    *metronome { |unit, perMinute, offset = 0, text|
        ^this.of(\tempo, text, offset, unit, perMinute)
    }

    *of { |kind, text, offset = 0, unit, perMinute|
        var checked = kind !? { |value| value.asSymbol };
        if (kinds.includes(checked).not) {
            Error("Direction: \"%\" is not a direction. The kinds are %.".format(
                kind, kinds)).throw
        };
        this.checkMetronome(checked, text, unit, perMinute);
        // Prose, on `Marking`'s rules. Absent only where `checkMetronome` has
        // just allowed it: a tempo that is a number.
        ^super.newCopyArgs(checked,
            if (text.isNil and: { unit.notNil }) { nil } {
                Marking.checkedText(text)
            },
            this.checkedOffset(offset),
            unit !? { this.checkedUnit(unit) },
            perMinute !? { this.checkedPerMinute(perMinute) })
    }

    // The three things a metronome mark has to be, before any coercion: both
    // halves, on a tempo, and not the only thing left unsaid.
    *checkMetronome { |kind, text, unit, perMinute|
        if (unit.isNil != perMinute.isNil) {
            Error("Direction: a metronome mark is a beat and a count - % "
                "and %. One without the other is half a mark, and no writer "
                "could spell it.".format(unit, perMinute)).throw
        };
        if (unit.notNil and: { kind != \tempo }) {
            Error("Direction: a % direction cannot carry a metronome mark. "
                "A speed said over a passage is a tempo; that is what the "
                "kind is for.".format(kind)).throw
        };
        if (kind == \tempo and: { text.isNil } and: { unit.isNil }) {
            Error("Direction: a tempo has to say something - prose, a "
                "metronome mark, or both. Direction.tempo(\"Allegro\") or "
                "Direction.metronome(Duration.quarter, 120).").throw
        }
    }

    // Coerced as everything is, so `metronome(1%/4, 120)` means what it looks
    // like. Dotted is ordinary, compound time is counted in dotted beats, but
    // a value no note head can draw is not a beat.
    *checkedUnit { |unit|
        var exact = Duration.asDuration(unit);
        if (exact <= Duration(0, 1)) {
            Error("Direction: a beat of % is not a beat. A metronome mark "
                "counts something that lasts.".format(exact)).throw
        };
        if (exact.notation.isNil) {
            Error("Direction: % is not writable as one note value, so it "
                "cannot be the beat of a metronome mark - both writers spell "
                "the unit as a note head. Use the value it is counted in."
                .format(exact)).throw
        };
        ^exact
    }

    // Whole, per the header: a decimal would be a mark only MusicXML could
    // write.
    *checkedPerMinute { |value|
        if (value.isKindOf(Integer).not) {
            Error("Direction: a metronome count is a whole number of beats "
                "a minute, not %. LilyPond's \\tempo will not take a decimal, "
                "so a mark this quark can write is one both backends can "
                "spell.".format(value.asCompileString)).throw
        };
        if (value <= 0) {
            Error("Direction: a metronome count of % is not a speed. A beat "
                "has to come round.".format(value)).throw
        };
        ^value
    }

    // Coerced as everything is. Negative is refused: nothing is written before
    // the bar it is written in.
    *checkedOffset { |offset|
        var exact = Duration.asDuration(offset);
        if (exact < Duration(0, 1)) {
            Error("Direction: an offset into a bar cannot be negative, and % "
                "is. A direction sits at its bar's start by default; an offset "
                "says how far into that bar it stands.".format(exact)).throw
        };
        ^exact
    }

    // >>> Direction.tempo("Allegro").isTempo             -> true
    // >>> Direction.rehearsalMark("A").isRehearsalMark   -> true
    isTempo { ^kind == \tempo }
    isRehearsalMark { ^kind == \rehearsalMark }
    isText { ^kind == \text }

    // >>> Direction.metronome(Duration(1, 4), 120).hasMetronome   -> true
    // >>> Direction.text("Rit.").hasMetronome                     -> false
    hasMetronome { ^unit.notNil }
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
    // A plain number rather than a `Duration`: the exactness rule is about
    // *time*, and 67.5 beats a minute is a rate, not a moment.
    //
    // >>> Direction.metronome(Duration(1, 4), 120).quarterPerMinute   -> 120.0
    // >>> Direction.metronome(Duration(3, 8), 60).quarterPerMinute    -> 90.0
    // >>> Direction.text("Rit.").quarterPerMinute                     -> nil
    quarterPerMinute {
        if (unit.isNil) { ^nil };
        ^perMinute * unit.numerator * 4 / unit.denominator
    }

    // Offset and mark are part of what a direction *is*: two "Rit."s at
    // different points are two instructions, and `4 = 60` is not `4 = 120`. A
    // `==` that could not tell them apart would be believed by the JSON round
    // trip and by anything that dedupes.
    == { |that| ^that.isKindOf(Direction) and: {
        (kind == that.kind) and: { text == that.text }
            and: { offset == that.offset } and: { unit == that.unit }
            and: { perMinute == that.perMinute } } }
    hash { ^((kind.hash bitXor: text.hash) bitXor: offset.hash)
        bitXor: (unit.hash bitXor: perMinute.hash) }

    // Each part only when there is one. The offset is labeled, since two
    // optional trailing values would otherwise read as each other.
    printOn { |stream|
        stream << "Direction(" << kind;
        text !? { stream << ", " << text.asCompileString };
        unit !? {
            stream << ", " << unit.numerator << "/" << unit.denominator
                   << " = " << perMinute
        };
        if (this.atBarStart.not) { stream << ", at " << offset };
        stream << ")"
    }
}
