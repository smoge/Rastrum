// Note [Reading order]
// ~~~~~~~~~~~~~~~~~~~~
//
//   Time and pitch    Duration.sc  MusicPitch.sc  MusicInterval.sc
//                     MusicIntervalName.sc
//   Structure         Marking.sc  ScoreElement.sc  Spanner.sc  Direction.sc
//                     Note.sc  MusicScore.sc
//   Rhythm            RhythmCell.sc  RhythmTree.sc  AutoBeam.sc
//   Correctness       ScorePrepare.sc  Validator.sc
//   Output            ScoreWriter.sc  LilyWriter.sc  MusicXMLWriter.sc
//                     ScoreJSON.sc
//   Events            EventWriter.sc  PatternWriter.sc  PatternPlayback.sc
//   Interpretation    PlaybackTempoMap.sc  PlaybackMap.sc
//   Facade            Rastrum.sc
//
// Three pairs are cycles, and read as one thing each: ScoreElement/Spanner,
// Note/MusicScore, and MusicPitch/MusicInterval. The facade is last to read but
// is called from the layers above it, LilyWriter asks `Rastrum.lilypondVersion`,
// and the interpretation layer asks `Rastrum.prepared`, because shared settings
// belong at the front door.
//
// The tour starts here, and this file carries no output syntax: `notation`
// answers the undotted note value and a dot count, and each writer spells that
// its own way, "4." against <type>quarter</type><dot/>. `Meter` is here too: a
// printed time signature, plus the grouping and metric hierarchy that the pair
// on its own cannot carry.

// Note [A Float is not an exact duration]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A Float can be used as an input convenience for simple cases, but should not
// be used internally. `1/4` in sclang is Float division, and `Rational`
// recovers a fraction from the Float rather than storing one: a search for the
// simplest rational nearby. Right for anything written by hand, and silently
// wrong past that.
//
//   Duration(3/4)   -> 3/4    Duration(1/12)   -> 1/12
//   Duration(3/112) -> 1/37   Duration(13/240) -> 2/37
//   Duration(5/1024) -> 1/205
//
// 3/112 is a dotted 32nd inside a septuplet (an ordinary rhythm a composer
// could write), and 5/1024 is a plain binary value both writers spell. The
// Float is admitted anyway, because `Duration(0.25)` is a reasonable thing to
// have written. Exact costs one character, `3%/112` or `Duration(3, 112)`, and
// the docs use `%/`. A String is admitted because `Rational` parses "3/4",
// which is the one non-numeric spelling Duration accepts


// A duration, exact and measured in whole notes: Duration(1, 4) is a quarter.
//
// Also the project's only wrapper over the Rational quark, so it carries exact
// rationals that are not lengths: a tuplet multiplier, a metric offset, the
// semitone count of a MusicPitch alteration. That last one looks wrong, but is
// not. sclang has one flat namespace, so a second exact-rational class would
// spend a global name on arithmetic this one already does. A carrier, not a
// claim that an accidental has a length. MusicPitch.sc explains that in full.
Duration {
    var <rational;

    *new { |num = 0, den = 1| ^super.new.initDuration(this.coerce(num, den)) }

    // Every duration value in Rastrum arrives through here: a leaf's `dur`, a
    // direction's offset, a metronome mark's beat, so it is the one place a
    // non-number can be refused. Worth doing: `Rational` reaches for `isNaN`,
    // so `\quarter` (easy to write, since `Duration.quarter` exists) otherwise
    // surfaces as `Message 'isNaN' not understood` three classes away.
    *coerce { |num, den = 1|
        if (num.isKindOf(Duration)) { ^num.rational };
        if (num.isKindOf(Rational)) { ^num };
        this.prCheckNumeric(num, "a duration");
        this.prCheckNumeric(den, "a denominator");
        ^Rational(num, den)
    }

    // A String is admitted too (`Rational` parses "3/4") and is left out of the
    // message, which says what to write rather than everything taken.
    *prCheckNumeric { |value, what|
        if (value.isNumber or: { value.isKindOf(String) }) { ^value };
        Error("Duration: % is not %. Write a number, a Rational or a Duration - "
            "Duration.quarter rather than \\quarter.".format(
                value.asCompileString, what)).throw
    }


    // It admits nothing `Duration(x)` would not: `coerce` unwraps a Duration
    // and refuses a non-number either way. What differs is identity.
    // `Duration(d)` builds a second wrapper around d's own Rational, and
    // `ScoreLeaf` runs this for every leaf ever built, so that second wrapper
    // would be one per leaf.
    //
    // Having one name for the rule is the other half of it: `RhythmCell` and
    // `Tuplet` point here rather than restating what they accept.
    //
    // >>> Duration.asDuration(0.25)   -> Duration(1/4)
    *asDuration { |x| ^if (x.isKindOf(Duration)) { x } { Duration(x) } }

    initDuration { |rat| rational = rat; ^this }

    // The `.asInteger` is Invariant 2 in one method: Rational's own terms are
    // Floats by design, and the wire format needs `[3, 4]`, not `[3.0, 4.0]`.
    //
    // >>> Duration(3, 4).asPair       -> [ 3, 4 ]
    // >>> Duration(3, 12)             -> Duration(1/4)
    numerator   { ^rational.numerator.asInteger }
    denominator { ^rational.denominator.asInteger }
    asFloat     { ^rational.asFloat }
    asRational  { ^rational }
    asPair      { ^[this.numerator, this.denominator] }

    + { |that| ^Duration(rational + Duration.coerce(that)) }
    - { |that| ^Duration(rational - Duration.coerce(that)) }
    * { |that| ^Duration(rational * Duration.coerce(that)) }
    / { |that| ^Duration(rational / Duration.coerce(that)) }

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

    // Not a format limit: writers reach a maxima, twice a longa. The
    // disagreement is in the glyph: LilyPond parses `\maxima` but its default
    // font has no maxima note head and warns rather than drawing one, where
    // `\breve` and `\longa` are clean. So a maxima would come out of one
    // backend and not the other. May change in the future as long as all
    // renderers support it.
    *maxNoteValue { ^Duration(4, 1) }

    *noteValueNames {
        ^[\longa, \breve, \whole, \half, \quarter, \eighth, \sixteenth,
          \thirtySecond, \sixtyFourth]
    }

    *longa                { ^Duration(4, 1)   }
    *breve                { ^Duration(2, 1)   }
    *whole                { ^Duration(1, 1)   }
    *half                 { ^Duration(1, 2)   }
    *quarter              { ^Duration(1, 4)   }
    *eighth               { ^Duration(1, 8)   }
    *sixteenth            { ^Duration(1, 16)  }
    *thirtySecond         { ^Duration(1, 32)  }
    *sixtyFourth          { ^Duration(1, 64)  }

    // Returns this value with `count` dots. A dot adds half of what precedes
    // it, so one dot is three halves of the value and two is seven quarters.
    // That is the same arithmetic `notation` reads back the other way, which is
    // the property worth holding onto. See the example on `notation`.
    //
    // >>> Duration.quarter.dotted      -> Duration(3/8)
    // >>> Duration.quarter.dotted(2)   -> Duration(7/16)
    dotted { |count = 1|
        if (count.isKindOf(Integer).not or: { count < 0 }) {
            Error("Duration: % dots is not a number of dots. A dot adds half of "
                "what precedes it, so there can be none, one, or more.".format(
                    count)).throw
        };
        ^this * Duration(1.leftShift(count + 1) - 1, 1.leftShift(count))
    }

    // Returns [undotted-note-value-as-Duration, number-of-dots], or nil when
    // the duration cannot be written as a single note. One note head means a
    // note value plus dots. A dot adds half of what precedes it, so a k-dotted
    // value is `value * (2^(k+1) - 1) / 2^k` which makes the denominator a
    // power-of-two and the numerator a solid run of 1 bits. Both are then read
    // straight off those bits. See:
    //
    //    3/8   11     quarter + 1 dot        5/8   101    no, not a solid run
    //    7/16  111    quarter + 2 dots       1/4   1      quarter, no dots
    //    6/1   110    longa + 1 dot         12/1   1100   no, past a longa
    //
    // Trailing zeros belong to the value rather than to the dots, 110 strips
    // to 11, which is what makes 6/1 a dotted longa. They turn up only when the
    // denominator is 1, because a reduced fraction has no even numerator over
    // an even denominator and 1 is the only odd power of two.
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

    // How many flags the note value carries. A notation fact rather than a
    // backend one: LilyPond infers the count from the value and MusicXML
    // numbers each beam explicitly, but both ask the same question about the
    // same note. nil for a value no single note head can spell.
    //
    // >>> Duration(1, 32).flags   -> 3
    flags {
        var value = this.noteValue;
        if (value.isNil) { ^nil };
        if (value.numerator != 1) { ^0 };
        ^max(0, value.denominator.log2.round.asInteger - 2)
    }

    isNotatable { ^this.notation.notNil }
    dots        { ^this.notation !? { |x| x[1] } }
    noteValue   { ^this.notation !? { |x| x[0] } }

    // Can this become one or more notatable leaves joined by ties? Only the
    // denominator matters: every notatable value is a power of two over one, so
    // any sum of them has a power-of-two denominator, and nothing else does.
    // 1/3 stays unsplittable and must be reached through a tuplet.
    //
    // >>> Duration(5, 8).isTieSplittable   -> true
    // >>> Duration(1, 3).isTieSplittable   -> false
    isTieSplittable {
        var d = this.denominator;
        ^(this.numerator > 0) and: { d.bitAnd(d - 1) == 0 }
    }

    // The notatable leaves this splits into, in order, or nil when no chain of
    // ties reaches it. A tie joins note heads and a note head is one run of 1
    // bits (see `notation`), so each maximal run is a leaf and the gaps between
    // runs are where the ties fall:
    //
    //    5/8   101    1/2 + 1/8        7/16  111    7/16, one run and no tie
    //   11/16  1011   1/2 + 3/16       8/1   1000   4/1 + 4/1, capped at a longa
    //
    // So `notation` is this method's one-run case. A run wider than a longa is
    // capped into longas rather than left as a leaf nothing can draw, 13/1 is
    // 4/1 + 4/1 + 4/1 + 1/1. And nil means no tie reaches it at all: 1/3 wants
    // a tuplet, since only a power-of-two denominator can be a sum of note
    // heads.
    //
    // Where a split falls *in a bar* is not decided here. That is metric
    // rewriting and belongs to the preparation pass. This is only the
    // arithmetic of what can be written at all.
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


// Note [Grouping is the second fact]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A meter is more than the pair a writer prints. 3/4 and 6/8 last equally but
// are not the same meter, so the pair is kept rather than reduced. The pair
// still does not settle it: 5/8 as 2+3 and 5/8 as 3+2 print alike and divide
// differently. Grouping is that second fact, given rather than guessed.
//
// An ungrouped meter answers one group of its whole count, which is the truth
// and not a placeholder. A bar nobody has grouped is undivided until `levelOf`
// factors its beat count. `Meter.grouped(5, 8, [5])` is `Meter(5, 8)`, hash
// included.


// Note [The metric hierarchy belongs to Meter]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Metric strength and legal cut points belong to the meter. `levelOf`,
// `strongestInteriorPoint`, and `isWellPlaced` live here, and `ScorePrepare`
// asks.
//
// Level 0 is the barline and lower is stronger: group lines first, then each
// group divided by the factors of its own unit count, twos first, then binarily
// for as long as anyone writes.
//
//   4/4        [0, 3, 2, 3, 1, 3, 2, 3]      one level per eighth
//   6/8        [0, 2, 2, 1, 2, 2]
//   5/8 [2,3]  [0, 2, 1, 2, 2]
//   5/8 [3,2]  [0, 2, 2, 1, 2]
//
// The last two are why grouping is a fact and not a guess. One group is the
// ungrouped case and costs no level, which is why 4/4 puts its half-bar at 1
// and not 2.
//
// A position finer than anything counted is weaker than all of it. Such a
// position can still begin a rest, but cannot justify crossing anything.


// Note [Barlow's indispensability]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Barlow's 1978 indispensability ranks each pulse by how much the meter needs
// it. No two pulses tie. Removing attacks in increasing order preserves the
// meter as long as possible.
//
//   3/4 as 3x2   [5, 0, 3, 1, 4, 2]
//   6/8 as 2x3   [5, 0, 2, 4, 1, 3]
//
// Same six pulses, same printed pair, different ranking. This is the argument
// for keeping `Meter#groups`.
//
// A meter is prime, or an ordered product of primes. 12 reads 3x2x2, 2x3x2, or
// 2x2x3 and ranks differently each way, with the leftmost factor outermost.
// Barlow leaves that choice to the composer. `stratification` makes it.
//
// Two functions call each other. Upper case psi ranks a prime meter with
// `(p + i - 2) mod p` up to 3, then uses the multiplicative case on the reversed
// factors of `p - 1`. Lower case psi reads a pulse as a mixed radix position
// over the strata, with each digit weighted by its stratum's prime
// indispensability.
//
// This is not a lexicographic combination. In 3/4, group heads 0, 2, 4 rank
// 5, 3, 4, and off beats 1, 3, 5 rank 0, 1, 2. Values climb toward the barline.
// The `(pulse - 2) mod total` term wraps the numbering to allow it.
//
// It refines the same ordering as `levelOf`: read 6/8's levels as {0} < {3} <
// {1,2,4,5}. It is not a replacement. Barlow asks which attacks survive
// thinning. Notation asks where a bar may be cut. No writer reads this.
//
// Additive meters are outside the theory, so `stratification` refuses a grouped
// one rather than reading 5/8 [2,3] as the prime meter 5. Härpfer's 2015
// extension covers them.


// A time signature, its grouping, and the metric hierarchy those imply.
//
// Kept beside `Duration` because a meter is a length with structure: the pair
// prints, the grouping divides, and `levelOf` ranks every position under both.
// See Note [Grouping is the second fact] and
// Note [The metric hierarchy belongs to Meter] above.
Meter {
    var <count, <unit, groups;

    *new { |count = 4, unit = 4| ^this.grouped(count, unit, nil) }

    *grouped { |count, unit, groups|
        var checkedCount = this.prWhole(count, "count");
        var checkedUnit = this.prWhole(unit, "unit");
        ^super.newCopyArgs(checkedCount, checkedUnit,
            this.prCheckGroups(groups, checkedCount))
    }

    *prWhole { |value, what|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error("Meter: a % must be a whole number of at least one, got %. A bar "
                "of no beats and a beat of no length are both unwritable.".format(
                    what, value)).throw
        };
        ^value
    }

    *prCheckGroups { |groups, count|
        var sum;
        if (groups.isNil) { ^[count] };
        if (groups.isKindOf(String) or: { groups.isSequenceableCollection.not }) {
            Error("Meter: a grouping must be a list of whole units, got %."
                .format(groups)).throw
        };
        if (groups.isEmpty) {
            Error("Meter: an empty grouping divides nothing. Leave the grouping "
                "out to say the bar is undivided.").throw
        };
        groups.do { |group| this.prWhole(group, "group") };
        sum = groups.sum;
        if (sum != count) {
            Error("Meter: a grouping of % accounts for % units, but the meter has "
                "%. The groups are how the count is divided, so they add up to "
                "it.".format(groups, sum, count)).throw
        };
        ^groups.asArray.copy
    }

    // The pair is what was written. The duration is what it lasts. 3/4 and 6/8
    // agree on the second and differ on the first, which is the whole of what a
    // meter adds to a length.
    //
    // >>> Meter(3, 4).duration     -> Duration(3/4)
    // >>> Meter(6, 8).duration     -> Duration(3/4)
    // >>> Meter(6, 8).asPair       -> [ 6, 8 ]
    // >>> Meter(6, 8).unitDuration -> Duration(1/8)
    duration { ^Duration(count, unit) }
    asPair { ^[count, unit] }
    unitDuration { ^Duration(1, unit) }

    // Returns a copy of the grouping. Grouping is part of `==`, `hash`, and each
    // metric question below, so callers must not mutate it through the accessor.
    // `RhythmCell` follows the same rule for proportions.
    groups { ^groups.copy }

    // Whether the bar was given a division of its own. One group is what an
    // ungrouped bar answers, so it does not count as one.
    //
    // >>> Meter.grouped(5, 8, [2, 3]).groups      -> [ 2, 3 ]
    // >>> Meter.grouped(5, 8, [2, 3]).isGrouped   -> true
    // >>> Meter(4, 4).isGrouped                   -> false
    isGrouped { ^groups.size > 1 }

    // See Note [The metric hierarchy belongs to Meter]. Lower is stronger, and
    // the two six-eighth bars part company at the third pulse.
    //
    // >>> (0..7).collect { |i| Meter(4, 4).levelOf(Duration(i, 8)) }
    // [ 0, 3, 2, 3, 1, 3, 2, 3 ]
    // >>> (0..5).collect { |i| Meter(6, 8).levelOf(Duration(i, 8)) }
    // [ 0, 2, 2, 1, 2, 2 ]
    // >>> (0..5).collect { |i| Meter(3, 4).levelOf(Duration(i, 8)) }
    // [ 0, 2, 1, 2, 1, 2 ]
    levelOf { |position|
        var inBar = position - (this.duration * Duration(this.prBarIndex(position), 1));
        var extra = if (this.isGrouped) { 1 } { 0 };
        var start = Duration(0, 1);
        if (inBar.isZero) { ^0 };
        groups.do { |units|
            var span = this.unitDuration * Duration(units, 1);
            var local = inBar - start;
            if (local < span) {
                if (local.isZero) { ^1 };
                ^extra + this.prDepthWithin(local, span, units)
            };
            start = start + span;
        };
        ^Meter.prMaxLevel
    }

    // Returns the strongest metric line strictly inside the span, or nil if it
    // crosses none. Among equally strong lines the one nearest the middle, and
    // the earlier one when two are equally near.
    //
    // >>> Meter(4, 4).strongestInteriorPoint(Duration(0, 1), Duration(1, 1))
    // Duration(1/2)
    strongestInteriorPoint { |offset, dur|
        var end = offset + dur, level = 0, found, middle;
        while { level <= Meter.prMaxLevel } {
            found = this.prPositionsAtLevel(level, offset, end);
            if (found.notEmpty) {
                middle = offset + (dur / Duration(2, 1));
                ^found.reduce { |a, b|
                    if ((a - middle).abs <= (b - middle).abs) { a } { b }
                }
            };
            level = level + 1;
        };
        ^nil
    }

    // Whether a span can be read where it sits: it may cross a metric line only
    // if it begins on one at least as strong. That is the whole convention in
    // one sentence. A whole rest crosses the half-bar of 4/4 and is fine,
    // because it begins at the barline, which outranks it. The same span
    // beginning a quarter later is not, because a beat does not outrank the
    // half-bar it would hide.
    //
    // >>> Meter(4, 4).isWellPlaced(Duration(0, 1), Duration(1, 1))   -> true
    // >>> Meter(4, 4).isWellPlaced(Duration(1, 4), Duration(1, 1))   -> false
    isWellPlaced { |offset, dur|
        var point;
        if (dur.isNotatable.not) { ^false };
        point = this.strongestInteriorPoint(offset, dur);
        if (point.isNil) { ^true };
        ^this.levelOf(offset) <= this.levelOf(point)
    }

    // The indispensability of each pulse, in pulse order. Six eighths either
    // way, and a different order, which is Barlow's own argument, in values.
    // See Note [Barlow's indispensability].
    //
    // >>> Meter(3, 4).indispensability(Duration(1, 8)) -> [ 5, 0, 3, 1, 4, 2 ]
    // >>> Meter(6, 8).indispensability(Duration(1, 8)) -> [ 5, 0, 2, 4, 1, 3 ]
    // >>> Meter(7, 8).indispensability   -> [ 6, 0, 4, 2, 5, 1, 3 ]
    indispensability { |pulse|
        var strata = this.stratification(pulse);
        var total = strata.inject(1, { |acc, each| acc * each });
        ^(1..total).collect { |n| Meter.prIndispensability(n, strata) }
    }

    // The ordered prime factorisation the formula reads: this bar's count, then
    // the subdivision of each unit into `pulse`, which defaults to the unit
    // itself. Its own method because the choice is Barlow's composer's and this
    // makes it instead. See Note [Barlow's indispensability], STRATIFICATION.
    //
    // Twelve eighths twice over, in a different order, is why it is a choice:
    //
    // >>> Meter(3, 4).stratification(Duration(1, 8))    -> [ 3, 2 ]
    // >>> Meter(6, 8).stratification(Duration(1, 8))    -> [ 2, 3 ]
    // >>> Meter(12, 8).stratification(Duration(1, 8))   -> [ 2, 2, 3 ]
    // >>> Meter(6, 4).stratification(Duration(1, 8))    -> [ 2, 3, 2 ]
    stratification { |pulse|
        var each = pulse ?? { this.unitDuration };
        var perUnit;
        if (this.isGrouped) {
            Error("Meter: indispensability reads a product of primes, and % is "
                "additive.".format(this)).throw
        };
        perUnit = this.unitDuration / each;
        if (perUnit.denominator != 1 or: { perUnit.numerator < 1 }) {
            Error("Meter: a pulse of % is not the unit of % or a division of it."
                .format(each, this)).throw
        };
        ^Meter.prPrimeFactors(count) ++ Meter.prPrimeFactors(perUnit.numerator)
    }

    *prPrimeFactors { |n|
        var out = [], factor = 2, rest = n;
        while { factor * factor <= rest } {
            if (rest % factor == 0) {
                rest = rest div: factor;
                out = out.add(factor)
            } {
                factor = factor + 1
            }
        };
        if (rest > 1) { out = out.add(rest) };
        ^out
    }

    // Lower-case psi. Pulses are numbered from 1.
    *prIndispensability { |pulse, strata|
        var primes = [1] ++ strata ++ [1];
        var depth = strata.size;
        var total = strata.inject(1, { |acc, each| acc * each });
        var sum = 0;
        depth.do { |r|
            var inner = 1, outer = 1, modulo = primes[depth - r], digit;
            (r + 1).do { |k| inner = inner * primes[depth + 1 - k] };
            (depth - r).do { |i| outer = outer * primes[i] };
            digit = 1 + (1 + (((pulse - 2) % total) div: inner) % modulo);
            sum = sum + (outer * Meter.prBasicIndispensability(digit, modulo))
        };
        ^sum
    }

    // Upper-case psi.
    *prBasicIndispensability { |pulse, prime|
        var lifted, q, quarter, beyond;
        if (prime <= 3) { ^(prime + pulse - 2) % prime };
        lifted = pulse - 1 + Meter.prW(prime - pulse);
        q = Meter.prIndispensability(lifted, Meter.prPrimeFactors(prime - 1).reverse);
        quarter = prime div: 4;
        beyond = Meter.prW(prime - pulse - 1);
        ^((q + Meter.prW(q div: quarter)) * beyond) + (quarter * (1 - beyond))
    }

    *prW { |x| ^if (x == 0) { 0 } { 1 } }

    // Every position of exactly this level strictly inside (a, b).
    //
    // Generated as a superset and then filtered by `levelOf`, so the two
    // answers cannot disagree about what a level is, there is one definition,
    // and this only has to reach every candidate.
    prPositionsAtLevel { |level, a, b|
        var acc = List.new, barDur = this.duration;
        var extra = if (this.isGrouped) { 1 } { 0 };
        var depth = level - extra;
        (this.prBarIndex(a) .. this.prBarIndex(b)).do { |index|
            var base = barDur * Duration(index, 1);
            var start = base;
            if (level == 0) {
                acc.add(base);
                acc.add(base + barDur);
            } {
                groups.do { |units|
                    var span = this.unitDuration * Duration(units, 1);
                    if (level == 1) { acc.add(start) };
                    if (depth >= 1) {
                        acc.addAll(Meter.prMultiplesBetween(
                            this.prStepWithin(span, units, depth), start, start + span))
                    };
                    start = start + span;
                }
            }
        };
        ^acc.asArray.select { |position|
            (position > a) and: { (position < b) and: {
                this.levelOf(position) == level } }
        }.sort
    }

    // The step a span of `units` is divided into at `depth` levels down
    prStepWithin { |span, units, depth|
        var step = span, levels = Meter.prLevelsFor(units);
        depth.min(levels.size).do { |i| step = step / Duration(levels[i], 1) };
        ^step
    }

    prDepthWithin { |local, span, units|
        var step = span, levels = Meter.prLevelsFor(units);
        levels.do { |factor, i|
            step = step / Duration(factor, 1);
            if ((local / step).denominator == 1) { ^(i + 1) }
        };
        ^levels.size + 1
    }

    prBarIndex { |position|
        var quotient = position / this.duration;
        ^quotient.numerator div: quotient.denominator
    }

    // Deep enough that nothing anyone writes runs out of hierarchy, and shallow
    // enough that a span crossing nothing gives up rather than spiraling
    // forever.
    *prMaxLevel { ^20 }

    // A span of n units divides by the factors of n, twos first, then binarily.
    // 4 divides 2, 2, so the half-bar outranks the beats, while 3 divides by
    // 3 and 6 by 2 then 3.
    *prLevelsFor { |units| ^this.prFactors(units) ++ Array.fill(16, { 2 }) }

    // Prime factors, twos first, so a span halves before it thirds.
    *prFactors { |n|
        var acc = List.new, remaining = n, factor = 2;
        while { factor <= remaining } {
            if ((remaining % factor) == 0) {
                acc.add(factor);
                remaining = remaining div: factor
            } {
                factor = factor + 1
            }
        };
        ^acc.asArray
    }

    // Every multiple of `step` strictly between a and b.
    *prMultiplesBetween { |step, a, b|
        var quotient = a / step;
        var k = (quotient.numerator div: quotient.denominator) + 1;
        var acc = List.new, point = step * Duration(k, 1);
        while { point < b } {
            if (point > a) { acc.add(point) };
            k = k + 1;
            point = step * Duration(k, 1);
        };
        ^acc.asArray
    }

    == { |that| ^that.isKindOf(Meter) and: {
        (count == that.count) and: { (unit == that.unit) and: {
        groups == that.groups } } } }
    hash { ^count.hash bitXor: unit.hash bitXor: groups.hash }

    printOn { |stream|
        stream << "Meter(" << count << "/" << unit;
        if (this.isGrouped) { stream << ", " << groups };
        stream << ")"
    }
}
