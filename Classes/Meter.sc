// Note [Grouping is the second fact]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A meter is more than its duration. 3/4 and 6/8 last equally but
// differ as meters. So do 5/8 as 2+3 and as 3+2. Grouping is given,
// not guessed. Ungrouped means one group of the whole count.
// `Meter.grouped(5, 8, [5])` is `Meter(5, 8)`, including hash.

// Note [The metric hierarchy belongs to Meter]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Metric strength and legal cut points belong to the meter.
// `ScorePrepare` asks through this API. Level 0 is the barline, and
// lower is stronger. Groups divide first, then each group divides by
// its factors.
//
//   4/4        [0, 3, 2, 3, 1, 3, 2, 3]      one level per eighth
//   6/8        [0, 2, 2, 1, 2, 2]
//   5/8 [2,3]  [0, 2, 1, 2, 2]
//   5/8 [3,2]  [0, 2, 2, 1, 2]
//
// The last two show why grouping is a fact. One group is ungrouped
// and costs no level, so 4/4 puts its half-bar at 1. A position finer
// than anything counted can begin a rest, but cannot justify a
// crossing.

// Note [Barlow's indispensability]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Barlow's 1978 indispensability ranks pulses by metric importance.
// No two pulses tie.
//
//   3/4 as 3x2   [5, 0, 3, 1, 4, 2]
//   6/8 as 2x3   [5, 0, 2, 4, 1, 3]
//
// Same six pulses, different ranking. Grouping matters here too.A
// meter is prime, or an ordered product of primes. 12 can read as
// 3x2x2, 2x3x2, or 2x2x3. `stratification` makes that choice.
//
// Uppercase psi ranks a prime meter. Lowercase psi reads a pulse as a
// mixed radix position over the strata. It isn't lexicographic: in
// 3/4, group heads 0, 2, 4 rank 5, 3, 4, while offbeats 1, 3, 5 rank
// 0, 1, 2. It refines the same ordering as `levelOf`, but asks a
// different question: attack thinning, not notation splitting. No
// writer reads this.
//
// Additive meters are outside this implementation, so grouped meters refuse
// `stratification`.


// A time signature, its grouping, and the metric hierarchy they imply.
Meter {
    var <count, <unit, groups;

    // A printed time signature: `Meter("4/4")` or `Meter("5/8[2+3]")`.
    //
    // >>> Meter("4/4")                                      -> Meter(4/4)
    // >>> Meter("5/8[2+3]") == Meter.grouped(5, 8, [2, 3])   -> true
    // >>> Meter(5, 8) == Meter.grouped(5, 8, [5])             -> true
    *new { |count = 4, unit = 4|
        if (count.isKindOf(String) or: { count.isKindOf(Symbol) }) {
            // The default unit is harmless, but any other unit is a
            // second answer to one question.
            if (unit != 4) {
                Error("Meter: \"%\" already includes the unit, do not also pass "
                    "%.".format(count, unit)).throw
            };
            ^this.prParse(count)
        };
        ^this.grouped(count, unit, nil)
    }

    // A Meter or printed spelling. nil is left alone for callers with their own
    // default or error. Numbers are refused here so `Measure(4, ...)` doesn't
    // guess where `Measure("4", ...)` refuses.
    //
    // >>> Meter.asMeter("5/8[2+3]").groups   -> [ 2, 3 ]
    // >>> Meter.asMeter(Meter(3, 4))          -> Meter(3/4)
    // >>> Meter.asMeter(nil)                  -> nil
    *asMeter { |value|
        if (value.isNil or: { value.isKindOf(Meter) }) { ^value };
        if (value.isKindOf(String) or: { value.isKindOf(Symbol) }) {
            ^Meter(value)
        };
        Error("Meter: % is not a meter. Use a Meter or a time signature such as "
            "\"4/4\" or \"5/8[2+3]\".".format(value)).throw
    }

    // `groups` says how the bar divides. nil means one group of the count.
    //
    // >>> Meter.grouped(7, 8, [2, 2, 3]).groups   -> [ 2, 2, 3 ]
    // >>> Meter.grouped(7, 8, nil).groups         -> [ 7 ]
    *grouped { |count, unit, groups|
        var checkedCount = this.prWhole(count, "count");
        var checkedUnit = this.prWhole(unit, "unit");
        ^super.newCopyArgs(checkedCount, checkedUnit,
            this.prCheckGroups(groups, checkedCount))
    }

    // The pair is what was written. The duration is what it lasts.
    //
    // >>> Meter(3, 4).duration     -> Duration(3/4)
    // >>> Meter(6, 8).duration     -> Duration(3/4)
    // >>> Meter(6, 8).asPair       -> [ 6, 8 ]
    // >>> Meter(6, 8).unitDuration -> Duration(1/8)
    duration { ^Duration(count, unit) }
    asPair { ^[count, unit] }
    unitDuration { ^Duration(1, unit) }

    // Answers a copy. Grouping participates in equality, hashing and
    // metric questions.
    //
    // >>> { var m = Meter.grouped(5, 8, [2, 3]); var g = m.groups; g[0] = 99; m.groups }.value
    // [ 2, 3 ]
    groups { ^groups.copy }

    // Whether the bar was given its own division.
    //
    // >>> Meter.grouped(5, 8, [2, 3]).groups      -> [ 2, 3 ]
    // >>> Meter.grouped(5, 8, [2, 3]).isGrouped   -> true
    // >>> Meter(4, 4).isGrouped                   -> false
    isGrouped { ^groups.size > 1 }

    // See Note [The metric hierarchy belongs to Meter].
    //
    // >>> (0..7).collect { |i| Meter(4, 4).levelOf(Duration(i, 8)) }
    // [ 0, 3, 2, 3, 1, 3, 2, 3 ]
    // >>> (0..5).collect { |i| Meter(6, 8).levelOf(Duration(i, 8)) }
    // [ 0, 2, 2, 1, 2, 2 ]
    // >>> (0..5).collect { |i| Meter(3, 4).levelOf(Duration(i, 8)) }
    // [ 0, 2, 1, 2, 1, 2 ]
    // >>> Meter.grouped(5, 8, [2, 3]).levelOf(Duration(1, 4))   -> 1
    // >>> Meter.grouped(5, 8, [3, 2]).levelOf(Duration(1, 4))   -> 2
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

    // The strongest metric line strictly inside the span. Ties choose
    // the line nearest the middle, then the earlier one.
    //
    // >>> Meter(4, 4).strongestInteriorPoint(Duration(0, 1), Duration(1, 1))
    // Duration(1/2)
    // >>> Meter.grouped(5, 8, [2, 3]).strongestInteriorPoint(Duration(0, 1), Duration(5, 8))
    // Duration(1/4)
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

    // A span may cross a metric line only if it begins on one at
    // least as strong.
    //
    // >>> Meter(4, 4).isWellPlaced(Duration(0, 1), Duration(1, 1))   -> true
    // >>> Meter(4, 4).isWellPlaced(Duration(1, 4), Duration(1, 1))   -> false
    // >>> Meter(4, 4).isWellPlaced(Duration(1, 2), Duration(1, 2))   -> true
    isWellPlaced { |offset, dur|
        var point;
        if (dur.isNotatable.not) { ^false };
        point = this.strongestInteriorPoint(offset, dur);
        if (point.isNil) { ^true };
        ^this.levelOf(offset) <= this.levelOf(point)
    }

    // The weakest line the meter counts: where one beat parts from the next.
    //
    // >>> Meter(4, 4).unitLevel        -> 2
    // >>> Meter("5/8[2+3]").unitLevel  -> 2
    unitLevel {
        ^(0 .. count - 1).inject(0, { |weakest, i|
            max(weakest, this.levelOf(this.unitDuration * Duration(i, 1))) })
    }

    // Note [A rest carries the beat, a note carries the line]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A note may tie across a metric line. A rest cannot, and may not
    // hide a counted beat line. Inside one beat, a dotted rest may
    // stand whole.

    // Whether a rest may stand whole where it begins: well placed by
    // the rule above, or crossing nothing stronger than the lines
    // inside one beat.
    //
    // >>> Meter(4, 4).admitsRest(Duration(13, 16), Duration(3, 16))   -> true
    // >>> Meter(4, 4).admitsRest(Duration(5, 8), Duration(3, 8))      -> false
    // >>> Meter(4, 4).admitsRest(Duration(1, 4), Duration(1, 2))      -> false
    admitsRest { |offset, dur|
        var point;
        if (dur.isNotatable.not) { ^false };
        if (this.isWellPlaced(offset, dur)) { ^true };
        point = this.strongestInteriorPoint(offset, dur);
        if (point.isNil) { ^true };
        ^this.levelOf(point) > this.unitLevel
    }

    // Indispensability values in pulse order. See
    // Note [Barlow's indispensability].
    //
    // >>> Meter(3, 4).indispensability(Duration(1, 8)) -> [ 5, 0, 3, 1, 4, 2 ]
    // >>> Meter(6, 8).indispensability(Duration(1, 8)) -> [ 5, 0, 2, 4, 1, 3 ]
    // >>> Meter(7, 8).indispensability   -> [ 6, 0, 4, 2, 5, 1, 3 ]
    // >>> Meter(5, 8).indispensability   -> [ 4, 0, 3, 1, 2 ]
    indispensability { |pulse|
        var strata = this.stratification(pulse);
        var total = strata.inject(1, { |acc, each| acc * each });
        ^(1..total).collect { |n| Meter.prIndispensability(n, strata) }
    }

    // Ordered prime factors for Barlow's formula: bar count, then
    // each unit's subdivision into `pulse`.
    //
    // Twelve eighths twice over, in different orders:
    //
    // >>> Meter(3, 4).stratification(Duration(1, 8))    -> [ 3, 2 ]
    // >>> Meter(6, 8).stratification(Duration(1, 8))    -> [ 2, 3 ]
    // >>> Meter(12, 8).stratification(Duration(1, 8))   -> [ 2, 2, 3 ]
    // >>> Meter(6, 4).stratification(Duration(1, 8))    -> [ 2, 3, 2 ]
    // >>> Meter(5, 8).stratification                    -> [ 5 ]
    stratification { |pulse|
        var each = pulse ?? { this.unitDuration };
        var perUnit;
        if (this.isGrouped) {
            Error("Meter: indispensability is not defined for grouped meter %."
                .format(this)).throw
        };
        perUnit = this.unitDuration / each;
        if (perUnit.denominator != 1 or: { perUnit.numerator < 1 }) {
            Error("Meter: pulse % must be the unit of % or a division of it."
                .format(each, this)).throw
        };
        ^Meter.prPrimeFactors(count) ++ Meter.prPrimeFactors(perUnit.numerator)
    }

    // The printed pair and grouping are identity.
    //
    // >>> Meter(6, 8) == Meter(3, 4)                         -> false
    // >>> Meter.grouped(5, 8, [2, 3]) == Meter(5, 8)          -> false
    // >>> Meter.grouped(5, 8, [5]).hash == Meter(5, 8).hash   -> true
    == { |that| ^that.isKindOf(Meter) and: {
        (count == that.count) and: { (unit == that.unit) and: {
        groups == that.groups } } } }
    hash { ^count.hash bitXor: unit.hash bitXor: groups.hash }

    printOn { |stream|
        stream << "Meter(" << count << "/" << unit;
        if (this.isGrouped) { stream << ", " << groups };
        stream << ")"
    }

    // Digits, one slash, and optional groups joined by `+`. Strict so
    // `asInteger` cannot accept prefixes.
    *prParse { |text|
        var string = text.asString;
        var body = string;
        var groups, opens, parts;

        opens = string.find("[");
        if (opens.notNil) {
            if (string.last != $]) { ^this.prRefuseSpelling(text) };
            body = string.copyRange(0, opens - 1);
            groups = string.copyRange(opens + 1, string.size - 2)
                .split($+).collect { |part| this.prWholeIn(part, text) }
        };
        parts = body.split($/);
        if (parts.size != 2) { ^this.prRefuseSpelling(text) };
        ^this.grouped(this.prWholeIn(parts[0], text),
            this.prWholeIn(parts[1], text), groups)
    }

    // Refuse here so bad syntax reports the original spelling.
    *prWholeIn { |part, text|
        var trimmed = part.stripWhiteSpace;
        if (trimmed.isEmpty
            or: { trimmed.every { |char| char.isDecDigit }.not }) {
            ^this.prRefuseSpelling(text)
        };
        ^trimmed.asInteger
    }

    *prRefuseSpelling { |text|
        Error("Meter: \"%\" is not a time signature. Use count/unit, optionally with groups such as \"5/8[2+3]\".".format(text)).throw
    }

    *prWhole { |value, what|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error("Meter: % must be a positive integer, got %.".format(what, value)).throw
        };
        ^value
    }

    *prCheckGroups { |groups, count|
        var sum;
        if (groups.isNil) { ^[count] };
        if (groups.isKindOf(String) or: { groups.isSequenceableCollection.not }) {
            Error("Meter: grouping must be a list of whole units, got %.".format(groups)).throw
        };
        if (groups.isEmpty) {
            Error("Meter: grouping cannot be empty. Omit it for an undivided bar.").throw
        };
        groups.do { |group| this.prWhole(group, "group") };
        sum = groups.sum;
        if (sum != count) {
            Error("Meter: grouping % adds to %, but the meter has % units.".format(groups, sum, count)).throw
        };
        ^groups.asArray.copy
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

    // Lowercase psi. Pulses are numbered from 1.
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

    // Uppercase psi.
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
    // Generate a superset, then filter by `levelOf`, so the level
    // rule has one definition.
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

    // The step after dividing a span of `units` by `depth` levels.
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

    // A practical ceiling for open-ended binary subdivision.
    *prMaxLevel { ^20 }

    // Divide by the factors of n, twos first, then keep halving.
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
}
