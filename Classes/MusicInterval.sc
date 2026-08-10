// MusicInterval: the spelled distance between two MusicPitches.
//
// Pitches are points. Intervals are vectors in that affine space. Subtracting two
// pitches gives the interval between them. Adding it back gives the second pitch,
// spelling included. The class must keep letter motion rather than ask a spelling
// policy to invent it later.
//
//   generic    signed letter-step distance, so C4 -> C5 is 7
//   chromatic  signed exact semitone distance, a Duration
//   cents      signed residual, a Float, the one inexact coordinate
//
// Two coordinates are necessary: C4 -> F#4 and C4 -> Gb4 are both six semitones
// but are not the same interval.
//
// No output syntax: an interval is a computed relationship, not a score fact,
// so it reaches no writer and crosses no wire.


// Note [The number comes from the letters]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Quality is derived from letters first, accidentals second. The order matters:
// four semitones is a major third from C#4 to E#4 and a diminished fourth from
// C#4 to F4.
//
// Fold to ascending by `generic` alone. Where `generic` is zero there is no
// letter motion to fold, so C4 -> Cb4 remains a diminished interval.
// `soundingDirection` must not be reused here: it falls back to chromatic
// direction and would invert the quantity the quality is read from.


// Note [A height offset is not an interval]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `semitones` and `quarterSteps` project height out of an interval. Nothing
// constructs one from height alone: 12 quarter steps above C could be an
// augmented fourth, a diminished fifth or a doubly augmented third.
//
// Height-only transposition is a spelling policy, already handled by
// `MusicPitch.fromMidinote(m, \sharps)`. Keeping it there leaves this class
// total, so `zero`, `negated` and `+` need no case analysis.

MusicInterval {
    var <generic, <chromatic, <cents;

    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\e)).generic   -> 2
    *new { |generic = 0, chromatic, cents = 0.0|
        ^super.newCopyArgs(
            this.checkedGeneric(generic),
            Duration.asDuration(chromatic ?? { MusicPitch.semitones(0) }),
            this.checkedCents(cents))
    }

    // The unique vector from one pitch to the other. Both arguments go through
    // `MusicPitch.fromSpec`, so anything a note accepts is accepted here.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\f, \sharp)).signedName.qualityName
    // augmented
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\g, \flat)).signedName.qualityName
    // diminished
    *between { |from, to|
        var a = MusicPitch.fromSpec(from);
        var b = MusicPitch.fromSpec(to);
        ^this.new(
            ((b.octave - a.octave) * 7) + (b.step - a.step),
            this.prChromaticHeightOf(b) - this.prChromaticHeightOf(a),
            b.cents - a.cents)
    }

    *zero { ^this.new(0, MusicPitch.semitones(0), 0.0) }

    // An interval said rather than measured, mirroring `MusicPitch.named`.
    // Ascending, since a number and a quality carry no direction: negate for the
    // other way. The vocabulary is `MusicIntervalName`'s, so this and
    // `signedName` are inverses.
    //
    // >>> MusicInterval.named(\major, 3).transpose(MusicPitch(\d)).letter   -> f
    // >>> MusicInterval.named(\perfect, 5).semitones   -> Duration(7/1)
    *named { |quality, number|
        var generic, genericClass, steps, reference;
        generic = MusicIntervalName.checkedNumber(number) - 1;
        genericClass = MusicIntervalName.genericClassOf(number);
        steps = MusicIntervalName.quarterStepsFor(genericClass, quality);
        if (steps.isNil) {
            // Derived from the vocabulary rather than written out, so the two
            // cannot disagree.
            var takes = MusicIntervalName.commonQualityNames.select { |each|
                MusicIntervalName.quarterStepsFor(genericClass, each).notNil };
            Error("MusicInterval: % is not a quality that interval number % can take. "
                "It takes %, where augmented and diminished may also be "
                "doubled, tripled or given as exact (kind:, degree:) events.".format(
                    quality, number, takes)).throw
        };
        reference = MusicPitch.semitones(
            (12 * (generic div: 7)) + MusicPitch.stepSemitones[genericClass]);
        ^this.new(generic, reference + MusicPitch.semitones(steps, 2), 0.0)
    }

    // There is no unspelled interval, so this is an Integer and never nil. See
    // Note [A height offset is not an interval].
    *checkedGeneric { |value|
        if (value.isKindOf(Integer).not) {
            Error("MusicInterval: a generic distance of % is not a whole number "
                "of letter steps.".format(value)).throw
        };
        ^value
    }

    *checkedCents { |value|
        if (value.isNumber.not) {
            Error("MusicInterval: cents of % is not a number.".format(value)).throw
        };
        if ((value.abs < inf).not) {
            Error("MusicInterval: cents of % is not finite.".format(value)).throw
        };
        ^value.asFloat
    }

    // The `12` is deliberately not `midinote`'s `(octave + 1) * 12`. Either
    // convention works because the offset cancels in a subtraction, but every
    // call site has to agree or a result is an octave out.
    *prChromaticHeightOf { |p|
        ^p.alter + (p.octave * 12) + MusicPitch.stepSemitones[p.step]
    }

    // Floor division, not truncation toward zero, so descending motion keeps a
    // step inside 0..6 instead of answering a negative one.
    *prSplitGenericHeight { |height|
        var octave = (height / 7).floor.asInteger;
        ^[height - (octave * 7), octave]
    }

    // The notated coordinate, scaled. Residual cents do not enter either: they
    // are a measurement beside the grid rather than a part of it.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\g)).quarterSteps   -> 14
    semitones { ^chromatic }

    quarterSteps {
        var steps = chromatic * 2;
        if (steps.denominator != 1) { ^nil };
        ^steps.numerator
    }

    genericDirection { ^generic.sign }

    // Compares the exact coordinate first and consults the Float only when it
    // has to, so a residual is never promoted into a fake-exact rational.
    soundingDirection {
        var zero = MusicPitch.semitones(0);
        if (chromatic > zero) { ^1 };
        if (chromatic < zero) { ^-1 };
        if (cents > 0.0) { ^1 };
        if (cents < 0.0) { ^-1 };
        ^0
    }

    isZero {
        ^(generic == 0)
            and: { chromatic == MusicPitch.semitones(0) }
            and: { cents == 0.0 }
    }

    // The prime class, which is not the same question as `isZero`: C4 -> C#4 is
    // an augmented prime, so it is a generic unison and is not a perfect one.
    // There is deliberately no `isUnison`, because the bare word means the
    // perfect unison and would answer true here.
    isGenericUnison { ^generic == 0 }

    // Same sound, different spelling. `generic != 0` is what keeps a pitch from
    // being enharmonic with itself, which is `isZero`.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\b, \sharp, 3)).isEnharmonic
    // true
    isEnharmonic {
        ^(generic != 0)
            and: { chromatic == MusicPitch.semitones(0) }
            and: { cents == 0.0 }
    }

    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\c, \natural, 5)).number   -> 8
    number          { ^generic.abs + 1 }
    genericClass    { ^generic.abs % 7 }
    classNumber     { ^this.genericClass + 1 }
    compoundOctaves { ^generic.abs div: 7 }

    // An octave is a simple interval and its simple number is 8, not 1, which
    // is why this is not `classNumber`.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\c, \natural, 5)).simpleNumber
    // 8
    simpleNumber {
        var n = generic.abs;
        if (n == 0) { ^1 };
        if ((n % 7) == 0) { ^8 };
        ^((n % 7) + 1)
    }

    // Componentwise, and total because `generic` is always an Integer. `* -1`
    // rather than `neg`, which `Duration` does not answer.
    negated { ^MusicInterval(generic * -1, chromatic * -1, 0 - cents) }

    + { |that|
        var other = MusicInterval.prOperand(that, "added to");
        ^MusicInterval(
            generic + other.generic,
            chromatic + other.chromatic,
            cents + other.cents)
    }

    - { |that| ^this + MusicInterval.prOperand(that, "subtracted from").negated }

    // Intervals combine with intervals. A pitch is reached by `transpose`
    // instead, so saying that beats a doesNotUnderstand from inside the sum.
    *prOperand { |that, what|
        if (that.isKindOf(MusicInterval).not) {
            Error("MusicInterval: % cannot be % an interval. Intervals combine "
                "with intervals, and reach a pitch through `transpose`.".format(
                    that, what)).throw
        };
        ^that
    }

    // The interval carries the target letter, so there is no "choose sharps or
    // flats" step. `MusicPitch.new` is left to refuse a result past the
    // accidental grid, which is why this class needs no bound of its own.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\e)).transpose(MusicPitch(\g)).letter
    // b
    transpose { |pitch|
        var p, targetGeneric, split, step, octave, target, base;
        p = MusicPitch.fromSpec(pitch);
        targetGeneric = (p.octave * 7) + p.step + generic;
        split = MusicInterval.prSplitGenericHeight(targetGeneric);
        step = split[0];
        octave = split[1];
        target = MusicInterval.prChromaticHeightOf(p) + chromatic;
        base = MusicPitch.semitones((octave * 12) + MusicPitch.stepSemitones[step]);
        ^MusicPitch(step, target - base, octave, p.cents + cents)
    }

    // The reference the signed projection measures from, already folded into
    // that orientation.
    signedReferenceChromatic {
        var n = generic.abs;
        ^MusicPitch.semitones((12 * (n div: 7)) + MusicPitch.stepSemitones[n % 7])
    }

    signedQualityDelta { ^this.signedName.qualityDelta }

    // The same signed delta, projected to quarter steps. Nil if residual cents
    // or a non-quarter-tone rational leave the strict 24-EDO grid.
    signedQualityDeltaQuarterSteps { ^this.signedName.qualityDeltaQuarterSteps }

    // Folds by generic direction only. See Note [The number comes from the
    // letters] for why the sounding direction must not be used here.
    prNameFor { |gen0, chrom0, cents0, convention, direction|
        var descending, gen, chrom, residual, genericClass, octaves, reference, delta;
        descending = gen0 < 0;
        gen = gen0.abs;
        chrom = if (descending) { chrom0 * -1 } { chrom0 };
        residual = if (descending) { 0 - cents0 } { cents0 };
        genericClass = gen % 7;
        octaves = gen div: 7;
        reference = MusicPitch.semitones(
            (12 * octaves) + MusicPitch.stepSemitones[genericClass]);
        delta = (semitones: chrom - reference, cents: residual);
        ^MusicIntervalName(convention, direction, gen + 1, delta,
            MusicIntervalName.qualityFor(genericClass, delta))
    }

    // The ordered vector as given. For non-primes the fold makes this
    // direction neutral, so C4 -> E4 and E4 -> C4 are both major thirds. Primes
    // have no fold, so C4 -> C#4 and C4 -> Cb4 differ.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\c, \flat)).signedName.qualityName
    // diminished
    signedName { ^this.prNameFor(generic, chromatic, cents, \signed, \ordered) }

    // Named the way it is sung: the direction first, then the interval as it
    // would be read going that way.
    //
    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\c, \flat)).melodicName.qualityName
    // augmented
    melodicName {
        var d = this.soundingDirection;
        if (d < 0) {
            ^this.prNameFor(generic * -1, chromatic * -1, 0 - cents,
                \melodic, \descending)
        };
        if (d > 0) {
            ^this.prNameFor(generic, chromatic, cents, \melodic, \ascending)
        };
        ^this.prNameFor(generic, chromatic, cents, \melodic, \same)
    }

    // A dyad rather than a vector: read from the lower pitch up, so it forgets
    // which came first. When both sound the same it falls back to staff order,
    // which is what keeps an enharmonic pair from answering nothing.
    harmonicName {
        var d = this.soundingDirection;
        if (d < 0) {
            ^this.prNameFor(generic * -1, chromatic * -1, 0 - cents,
                \harmonic, \ascending)
        };
        if (d > 0) {
            ^this.prNameFor(generic, chromatic, cents, \harmonic, \ascending)
        };
        if (this.genericDirection < 0) {
            ^this.prNameFor(generic * -1, chromatic * -1, 0 - cents,
                \harmonic, \same)
        };
        ^this.prNameFor(generic, chromatic, cents, \harmonic, \same)
    }

    == { |that| ^that.isKindOf(MusicInterval) and: {
        (generic == that.generic) and: { (chromatic == that.chromatic) and: {
        cents == that.cents } } } }

    hash { ^generic.hash bitXor: chromatic.hash bitXor: cents.hash }

    // >>> MusicInterval.between(MusicPitch(\c), MusicPitch(\e)).asString
    // MusicInterval(2, Duration(4/1))
    printOn { |stream|
        stream << "MusicInterval(" << generic << ", " << chromatic;
        if (cents != 0) { stream << ", " << cents };
        stream << ")"
    }
}
