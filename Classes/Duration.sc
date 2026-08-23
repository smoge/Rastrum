// Note [A Float is not an exact duration]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Floats are input conveniences only. In sclang, `1/4` is Float
// division. `Rational` recovers a nearby simple fraction, which is
// fine for basics and wrong for denser rhythms.
//
//   Duration(3/4)   -> 3/4    Duration(1/12)   -> 1/12
//   Duration(3/112) -> 1/37   Duration(13/240) -> 2/37
//   Duration(5/1024) -> 1/205
//
// `%/` is the compact exact form, and the docs prefer it. String
// input follows Note [Two string grammars, told apart by the slash].


// A duration, exact and measured in whole notes: Duration(1, 4) is a
// quarter note.
//
// Also the exact-rational carrier for tuplets, offsets and pitch alterations.
// That avoids another global arithmetic class.
Duration {
    var <rational;

    // Numeric input is arithmetic. String input is either a rational or a note
    // value, by Note [Two string grammars, told apart by the slash].
    //
    // >>> Duration(3, 8)    -> Duration(3/8)
    // >>> Duration("3/8")   -> Duration(3/8)
    // >>> Duration("8.")    -> Duration(3/16)
    // >>> Duration(8)       -> Duration(8/1)
    *new { |num = 0, den = 1| ^super.new.initDuration(this.coerce(num, den)) }

    // The single admission point. Refuse non-numbers here before
    // `Rational` reports a low-level `isNaN` error.
    *coerce { |num, den = 1|
        if (num.isKindOf(Duration)) { ^num.rational };
        if (num.isKindOf(Rational)) { ^num };
        // See Note [Two string grammars, told apart by the slash].
        if (num.isKindOf(String)) {
            // A String already carries the whole duration.
            if (den != 1) {
                Error("Duration: \"%\" already includes the denominator. Do not "
                    "also pass %. Use Duration(\"%\") or Duration(num, den)."
                    .format(num, den, num)).throw
            };
            if (num.contains("/")) { ^this.prRational(num) };
            ^this.lily(num).rational
        };
        this.prCheckNumeric(num, "a duration");
        this.prCheckNumeric(den, "a denominator");
        ^Rational(num, den)
    }

    // The error says what to write, not every value this method accepts.
    *prCheckNumeric { |value, what|
        if (value.isNumber and: { (value.abs < inf).not }) {
            Error("Duration: % must be finite, got %. Use a finite number, "
                "Rational, Duration or duration String.".format(what, value)).throw
        };
        if (value.isNumber or: { value.isKindOf(String) }) { ^value };
        Error("Duration: % is not %. Use a number, Rational, Duration or "
            "duration String.".format(value.asCompileString, what)).throw
    }


    // Same admission rule as `Duration(x)`, preserving an existing Duration.
    //
    // >>> Duration.asDuration(0.25)      -> Duration(1/4)
    // >>> Duration.asDuration("4")       -> Duration(1/4)
    // >>> Duration.asDuration(1%/3)      -> Duration(1/3)
    *asDuration { |x| ^if (x.isKindOf(Duration)) { x } { Duration(x) } }

    // Note [Two string grammars, told apart by the slash]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A String with a slash is an exact rational, "3/4". One without is a
    // LilyPond note value, "4" or "8.". The grammars can't overlap.
    //
    // A number stays arithmetic everywhere: `Duration(16)` is sixteen whole
    // notes, while `Duration("16")` is one sixteenth.
    //
    //     Duration(16)      16/1   sixteen whole notes
    //     Duration(16, 1)   16/1   sixteen whole notes
    //     Duration("16")    1/16   one sixteenth note
    //     Duration("3/4")   3/4    three quarter notes, so a dotted half note
    //
    // The first two count whole notes. The last two are note spellings.
    //
    // Nothing was taken away. A bare "16" was never a rational spelling.
    // `prRational` is stricter than `Rational`, which accepts partial parses.
    //
    // Exact non-length slots use `asExactValue`.
    //
    // `lily` names the same parser when the call site wants that grammar stated.

    // A LilyPond duration token: a note value from `1` to `64`, then any number
    // of dots.
    //
    // This is LilyPond's numeric row, not every notatable value. Breves and
    // longas use `Duration(2, 1)` or `Duration(4, 1)`.
    //
    // `128` would change the supported note-value row, not just this parser.
    //
    // >>> Duration.lily("4.")   -> Duration(3/8)
    // >>> Duration.lily("64")   -> Duration(1/64)
    *lily { |text|
        var string, digits, dots = 0, value;
        // This method is notation only. `Duration(4)` remains arithmetic.
        if (text.isKindOf(String).not) {
            Error("Duration.lily: expected a duration String such as \"4\" or "
                "\"8.\", got a %.".format(text.class)).throw
        };
        // Match the rational grammar and the other parsers in this file.
        string = text.stripWhiteSpace;
        digits = string;
        while { digits.notEmpty and: { digits.last == $. } } {
            dots = dots + 1;
            digits = digits.copyRange(0, digits.size - 2)
        };
        if (digits.isEmpty
            or: { digits.every { |char| char.isDecDigit }.not }) {
            ^this.prRefuseLily(text)
        };
        value = digits.asInteger;
        // A supported note value is a power of two in 1, 2, 4, ..., 64.
        if (value < 1 or: { value > 64 } or: { value.bitAnd(value - 1) != 0 }) {
            ^this.prRefuseLily(text)
        };
        ^Duration(1, value).dotted(dots)
    }

    // Many durations: an Array of inputs, or a space-separated String.
    //
    // Each token is one Duration in either grammar. No pitches, ties, beams or
    // bars.
    //
    // A String is the whole run because every caller is plural.
    //
    // >>> Duration.asDurations("4 4. 2").size   -> 3
    // >>> Duration.asDurations("4 -8 2")
    // [ Duration(1/4), Duration(-1/8), Duration(1/2) ]
    // >>> Duration.asDurations(["4", "3/8", Duration.eighth])
    // [ Duration(1/4), Duration(3/8), Duration(1/8) ]
    *asDurations { |value|
        var tokens;
        if (value.isKindOf(String).not) {
            ^(value ? []).asArray.collect { |each| Duration.asDuration(each) }
        };
        tokens = value.split($ ).reject { |token| token.stripWhiteSpace.isEmpty };
        if (tokens.isEmpty) {
            Error("Duration.asDurations: \"%\" contains no duration tokens. "
                "Use spaces, e.g. \"4 4 2\".".format(value)).throw
        };
        ^tokens.collect { |token| this.prRunToken(token.stripWhiteSpace) }
    }

    // Runs admit negative note values for rests. `lily` stays positive-only.
    *prRunToken { |token|
        if (token.first == $- and: { token.contains("/").not }) {
            ^Duration(0, 1) - this.lily(token.drop(1))
        };
        ^Duration(token)
    }

    // Exact non-length input. Tuplet multipliers and intervals accept rationals,
    // but not note values like "4".
    //
    // >>> Duration.asExactValue("4/5")   -> Duration(4/5)
    // >>> Duration.asExactValue(1%/5)    -> Duration(1/5)
    *asExactValue { |value, what = "this"|
        if (value.isKindOf(String) and: { value.contains("/").not }) {
            Error("Duration: \"%\" is a note value, but % needs an exact "
                "rational. Use \"4/5\" or Duration(4, 5).".format(
                    value, what)).throw
        };
        ^Duration.asDuration(value)
    }

    // Checked here because `Rational` accepts partial parses such as "3/4x".
    *prRational { |text|
        var parts = text.split($/).collect { |part| part.stripWhiteSpace };
        if (parts.size != 2) { ^this.prRefuseRational(text) };
        ^Rational(this.prSignedWhole(parts[0], text),
            this.prSignedWhole(parts[1], text))
    }

    // Signed for rests and downward pitch alterations.
    *prSignedWhole { |part, text|
        var digits = part;
        if (digits.notEmpty and: { digits.first == $- }) { digits = digits.drop(1) };
        if (digits.isEmpty or: { digits.every { |char| char.isDecDigit }.not }) {
            ^this.prRefuseRational(text)
        };
        ^part.asInteger
    }

    *prRefuseRational { |text|
        Error("Duration: \"%\" is not a rational. Use num/den, e.g. \"3/4\" "
            "or \"-1/2\".".format(text)).throw
    }

    *prRefuseLily { |text|
        Error("Duration.lily: \"%\" is not a LilyPond duration. Use 1, 2, 4, "
            "8, 16, 32 or 64 plus dots, e.g. \"4\", \"8.\" or \"2..\"."
            .format(text)).throw
    }


    initDuration { |rat| rational = rat; ^this }

    // Public integer access keeps exact values stable on the wire.
    //
    // >>> Duration(3, 4).numerator              -> 3
    // >>> Duration(3, 4).denominator            -> 4
    // >>> Duration(3, 8).asFloat                -> 0.375
    // >>> Duration(3, 4).asRational == (3%/4)   -> true
    // >>> Duration(3, 4).asPair                 -> [ 3, 4 ]
    // >>> Duration(3, 12)                       -> Duration(1/4)
    numerator   { ^rational.numerator.asInteger }
    denominator { ^rational.denominator.asInteger }
    asFloat     { ^rational.asFloat }
    asRational  { ^rational }
    asPair      { ^[this.numerator, this.denominator] }

    // Arithmetic stays exact and accepts the same inputs as construction.
    //
    // >>> Duration.quarter + Duration.eighth   -> Duration(3/8)
    // >>> Duration.half - "4"                  -> Duration(1/4)
    // >>> Duration.quarter * "2/3"             -> Duration(1/6)
    // >>> Duration.quarter / Duration.eighth   -> Duration(2/1)
    + { |that| ^Duration(rational + Duration.coerce(that)) }
    - { |that| ^Duration(rational - Duration.coerce(that)) }
    * { |that| ^Duration(rational * Duration.coerce(that)) }
    / { |that| ^Duration(rational / Duration.coerce(that)) }

    // Comparisons coerce the right-hand side. Equality stays class-specific.
    //
    // >>> Duration.quarter == Duration("4")          -> true
    // >>> Duration.quarter == (1%/4)                 -> false
    // >>> Duration.quarter < Duration.half           -> true
    // >>> Duration.quarter <= "4"                    -> true
    // >>> Duration.half > Duration.quarter           -> true
    // >>> Duration(2, 8).hash == Duration(1, 4).hash -> true
    == { |that| ^that.isKindOf(Duration) and: { rational == that.rational } }
    <  { |that| ^rational < Duration.coerce(that) }
    >  { |that| ^rational > Duration.coerce(that) }
    <= { |that| ^(this > that).not }
    >= { |that| ^(this < that).not }
    hash { ^rational.hash }

    // >>> Duration(0, 1).isZero    -> true
    // >>> Duration(-1, 4).abs      -> Duration(1/4)
    isZero { ^this.numerator == 0 }
    abs { ^if (this < Duration(0, 1)) { Duration(0, 1) - this } { this } }

    // Shared note-head ceiling. `\maxima` is outside the portable set.
    *maxNoteValue { ^Duration(4, 1) }

    *noteValueNames {
        ^[\longa, \breve, \whole, \half, \quarter, \eighth, \sixteenth,
          \thirtySecond, \sixtyFourth]
    }

    // >>> Duration.breve        -> Duration(2/1)
    // >>> Duration.quarter      -> Duration(1/4)
    // >>> Duration.sixtyFourth  -> Duration(1/64)
    *longa                { ^Duration(4, 1)   }
    *breve                { ^Duration(2, 1)   }
    *whole                { ^Duration(1, 1)   }
    *half                 { ^Duration(1, 2)   }
    *quarter              { ^Duration(1, 4)   }
    *eighth               { ^Duration(1, 8)   }
    *sixteenth            { ^Duration(1, 16)  }
    *thirtySecond         { ^Duration(1, 32)  }
    *sixtyFourth          { ^Duration(1, 64)  }

    // Adds `count` dots. `notation` reads the same arithmetic back.
    //
    // >>> Duration.quarter.dotted      -> Duration(3/8)
    // >>> Duration.quarter.dotted(2)   -> Duration(7/16)
    dotted { |count = 1|
        if (count.isKindOf(Integer).not or: { count < 0 }) {
            Error("Duration: % is not a dot count. Use a non-negative integer."
                .format(count)).throw
        };
        ^this * Duration(1.leftShift(count + 1) - 1, 1.leftShift(count))
    }

    // Answers [undotted note value, dot count], or nil. Dotted notes are solid
    // runs of 1 bits:
    //
    //    3/8   11     quarter + 1 dot        5/8   101    no, not a solid run
    //    7/16  111    quarter + 2 dots       1/4   1      quarter, no dots
    //    6/1   110    longa + 1 dot         12/1   1100   no, past a longa
    //
    // Trailing zeros belong to the note value: 110 becomes a dotted longa.
    //
    // >>> Duration.quarter.dotted.notation   -> [ Duration(1/4), 1 ]
    // >>> Duration(6, 1).notation            -> [ Duration(4/1), 1 ]
    // >>> Duration(5, 8).notation            -> nil
    notation {
        var n = this.numerator, d = this.denominator;
        var trailing = 0, dots, value;
        if (n <= 0) { ^nil };
        if (d.bitAnd(d - 1) != 0) { ^nil };
        while { n.bitAnd(1) == 0 } { n = n.rightShift(1); trailing = trailing + 1 };
        if ((n + 1).bitAnd(n) != 0) { ^nil };
        dots = (n + 1).log2.round.asInteger - 1;
        value = Duration(1.leftShift(dots + trailing), d);
        if (value > Duration.maxNoteValue) { ^nil };
        ^[value, dots]
    }

    // How many flags this note value carries, or nil.
    //
    // >>> Duration.half.flags      -> 0
    // >>> Duration(1, 32).flags   -> 3
    // >>> Duration(5, 8).flags    -> nil
    flags {
        var value = this.noteValue;
        if (value.isNil) { ^nil };
        if (value.numerator != 1) { ^0 };
        ^max(0, value.denominator.log2.round.asInteger - 2)
    }

    // Convenience views over `notation`.
    //
    // >>> Duration(7, 16).isNotatable   -> true
    // >>> Duration(7, 16).dots          -> 2
    // >>> Duration(7, 16).noteValue     -> Duration(1/4)
    // >>> Duration(5, 8).noteValue      -> nil
    isNotatable { ^this.notation.notNil }
    dots        { ^this.notation !? { |x| x[1] } }
    noteValue   { ^this.notation !? { |x| x[0] } }

    // Whether ties can spell this value. Only power-of-two denominators qualify.
    // 1/3 needs a tuplet.
    //
    // >>> Duration(5, 8).isTieSplittable   -> true
    // >>> Duration(1, 3).isTieSplittable   -> false
    isTieSplittable {
        var d = this.denominator;
        ^(this.numerator > 0) and: { d.bitAnd(d - 1) == 0 }
    }

    // The tied note values that spell this duration, or nil. Each run of 1 bits
    // is one note head:
    //
    //    5/8   101    1/2 + 1/8        7/16  111    7/16, one run and no tie
    //   11/16  1011   1/2 + 3/16       8/1   1000   4/1 + 4/1, capped at a longa
    //
    // `notation` is the one-run case. Runs wider than a longa are capped.
    // Metric split points belong to `ScorePrepare`.
    //
    // >>> Duration(5, 8).tieRuns   -> [ Duration(1/2), Duration(1/8) ]
    // >>> Duration(1, 3).tieRuns   -> nil
    // >>> Duration(13, 1).tieRuns
    // [ Duration(4/1), Duration(4/1), Duration(4/1), Duration(1/1) ]
    tieRuns {
        var n = this.numerator, d = this.denominator;
        var acc = List.new, bit = 0, hi;
        if (this.isTieSplittable.not) { ^nil };
        while { n.rightShift(bit) > 0 } { bit = bit + 1 };
        bit = bit - 1;
        while { bit >= 0 } {
            if (n.rightShift(bit).bitAnd(1) == 1) {
                hi = bit;
                while { (bit >= 0) and: { n.rightShift(bit).bitAnd(1) == 1 } } {
                    bit = bit - 1
                };
                this.prAddRun(acc, hi, bit + 1, d);
            } {
                bit = bit - 1
            }
        };
        ^acc.asArray
    }

    // >>> Duration(5, 8).tieRunCount   -> 2
    // >>> Duration.maxNoteValue        -> Duration(4/1)
    tieRunCount { ^this.tieRuns !? { |x| x.size } }

    // One run of 1 bits, from bit hi down to bit lo, over denominator d
    prAddRun { |acc, hi, lo, d|
        var longest = Duration.maxNoteValue;
        var value = Duration(1.leftShift(hi + 1) - 1.leftShift(lo), d);
        if (value.isNotatable) { acc.add(value); ^this };
        while { value > longest } { acc.add(longest); value = value - longest };
        if (value.isZero.not) {
            if (value.isNotatable) { acc.add(value) } { acc.addAll(value.tieRuns) }
        };
        ^this
    }

    printOn { |stream|
        stream << "Duration(" << this.numerator << "/" << this.denominator << ")"
    }
}
