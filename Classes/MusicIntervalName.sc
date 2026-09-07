// MusicIntervalName: what a `MusicInterval` is called, under one convention.
//
// A name is a projection of the vector, not the vector itself.
// `signedName`, `melodicName` and `harmonicName` share this shape.
// Quality names come from the interval number, then from the
// quarter-step offset from its major or perfect reference.
// `commonQualityName` is the smaller display set.
//
// `quality` keeps the exact degree. `qualityName` displays it. If no
// quality exists, `quality` is nil and `qualityDelta` still reports
// the deviation.
MusicIntervalName {
    // convention: Symbol, direction: Symbol, number: Integer
    // qualityDelta: Event, quality: Symbol | Event | Nil
    var <convention, <direction, <number, qualityDelta, <quality;

    // conventions: [Symbol], directions: [Symbol], perfectClasses: [Integer]
    // degreeNames: Event, commonQualityNames: [Symbol]
    classvar <conventions, <directions, <perfectClasses, <degreeNames,
        <commonQualityNames;

    // ^ Self
    *initClass {
        conventions = [\signed, \melodic, \harmonic];

        // `\ordered` is what `signedName` uses: no melodic direction is claimed.
        directions = [\ordered, \ascending, \descending, \same];

        // Unison/octave, fourth and fifth, as generic classes.
        perfectClasses = [0, 3, 4];

        degreeNames = (
            augmented:  [\augmented, \doublyAugmented, \triplyAugmented],
            diminished: [\diminished, \doublyDiminished, \triplyDiminished]
        );

        commonQualityNames = [
            \diminished, \semiDiminished, \perfect, \semiAugmented, \augmented,
            \subminor, \minor, \neutral, \major, \supermajor
        ];
    }

    // ^ Self
    *new { |convention, direction, number, qualityDelta, quality|
        var checkedNumber = this.checkedNumber(number);
        var checkedDelta = this.checkedDelta(qualityDelta);
        ^super.newCopyArgs(
            this.checkedConvention(convention),
            this.checkedDirection(direction),
            checkedNumber,
            checkedDelta,
            this.checkedQuality(checkedNumber, checkedDelta, quality))
    }

    // The generic class an interval number belongs to. Octaves share
    // unison's class, so this is not `number - 1`.
    //
    // Checked because sclang `%` floors. Unchecked `0` would answer `6`.
    //
    // >>> [1, 3, 8, 9].collect { |n| MusicIntervalName.genericClassOf(n) }
    // [ 0, 2, 0, 1 ]
    //
    // ^ Integer
    *genericClassOf { |number| ^(this.checkedNumber(number) - 1) % 7 }

    // ^ Symbol
    *checkedConvention { |value|
        if (conventions.includes(value).not) {
            Error(
                "MusicIntervalName: \"%\" is not a convention. Use one of %."
                .format(value, conventions)
            ).throw
        };
        ^value
    }

    // ^ Symbol
    *checkedDirection { |value|
        if (directions.includes(value).not) {
            Error(
                "MusicIntervalName: \"%\" is not a direction. Use one of %."
                .format(value, directions)
            ).throw
        };
        ^value
    }

    // 1 is a prime. There is no zeroth interval.
    // ^ Integer
    *checkedNumber { |value|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error(
                "MusicIntervalName: interval number must be a positive integer, got %."
                .format(value)
            ).throw
        };
        ^value
    }

    // Both fields are type-checked, not merely present.
    // value: Event
    // ^ Event
    *checkedDelta { |value|
        var semitones, cents, normalized;
        if (value.isKindOf(Event).not) {
            Error(
                "MusicIntervalName: quality delta must be (semitones: anExactNumber, cents: aFloat), got %."
                .format(value)
            ).throw
        };
        semitones = this.prCheckedSemitones(value[\semitones]);
        cents = value[\cents];
        if (cents.isNumber.not or: { (cents.abs < inf).not }) {
            Error(
                "MusicIntervalName: a quality delta's cents must be a finite number, got %."
                .format(cents)
            ).throw
        };
        // Always Float, copied so callers can't mutate stored delta.
        normalized = value.copy;
        normalized[\semitones] = semitones;
        normalized[\cents] = cents.asFloat;
        ^normalized
    }

    // Accept the internal carrier too. Source prints numbers.
    // ^ Duration
    *prCheckedSemitones { |value|
        if (value.isKindOf(Duration)) { ^value };
        if (value.isKindOf(Integer) or: { value.isKindOf(Rational) }) {
            ^Duration.asExactValue(value, "a quality-delta semitone value")
        };
        Error(
            "MusicIntervalName: a quality delta's semitones must be an Integer or Rational, got %."
            .format(value.asCompileString)
        ).throw
    }

    // Derive quality from number and delta, then compare
    //
    // ^ Symbol | Event | Nil
    *checkedQuality { |number, qualityDelta, value|
        var derived = this.qualityFor(this.genericClassOf(number), qualityDelta);
        if (value != derived) {
            Error(
                "MusicIntervalName: delta % on interval number % names %, not %."
                .format(
                    this.prDeltaSource(qualityDelta),
                    number,
                    derived ?? { "no quality" },
                    value
                )
            ).throw
        };
        ^value
    }

    // The quality named by a generic class and exact delta or nil
    // Common names are a smaller display projection over the exact
    // interval vector.
    //
    // >>> MusicIntervalName.qualityFor(2, (semitones: 0, cents: 0.0))
    // major
    // >>> MusicIntervalName.qualityFor(4, (semitones: 0, cents: 0.0))
    // perfect
    // >>> MusicIntervalName.qualityFor(2, (semitones: -1%/2, cents: 0.0))
    // neutral
    // >>> MusicIntervalName.qualityFor(3, (semitones: 1%/2, cents: 0.0))
    // semiAugmented
    //
    // ^ Symbol | Event | Nil
    *qualityFor { |genericClass, qualityDelta|
        var perfect, quarterSteps, delta;
        perfect = this.prCheckedPerfect(genericClass);
        quarterSteps = this.qualityDeltaQuarterSteps(qualityDelta);
        if (quarterSteps.isNil) { ^nil };

        if (perfect) {
            if (quarterSteps == 0) { ^\perfect };
            if (quarterSteps == 1) { ^\semiAugmented };
            if (quarterSteps == -1) { ^\semiDiminished };
            if ((quarterSteps % 2) != 0) { ^nil };
            delta = quarterSteps div: 2;
            if (delta > 0) { ^(kind: \augmented, degree: delta) };
            ^(kind: \diminished, degree: delta.abs)
        };

        if (quarterSteps == 0)       { ^\major      };
        if (quarterSteps == 1)       { ^\supermajor };
        if (quarterSteps == -1)      { ^\neutral    };
        if (quarterSteps == -2)      { ^\minor      };
        if (quarterSteps == -3)      { ^\subminor   };
        if ((quarterSteps % 2) != 0) { ^nil         };

        delta = quarterSteps div: 2;
        if (delta > 0) { ^(kind: \augmented, degree: delta) };

        // diminished major-family intervals count down from minor
        ^(kind: \diminished, degree: (delta + 1).abs)
    }

    // The inverse of `qualityFor`: how far off the reference a named
    // quality sits in quarter steps or nil where the name doesn't
    // belong to this family. `\augmented` and `\diminished` are
    // accepted as the degree-1 shorthand that `commonQualityName`
    // answers.
    //
    // >>> MusicIntervalName.quarterStepsFor(2, \neutral)   -> -1
    // >>> MusicIntervalName.quarterStepsFor(4, \perfect)   -> 0
    //
    // ^ Integer | Nil
    *quarterStepsFor { |genericClass, quality|
        var perfect, kind, degree;
        perfect = this.prCheckedPerfect(genericClass);

        if (quality.isKindOf(Symbol)) {
            // Every symbol `qualityName` can answer, degrees included, so the
            // display spelling inverts as readily as the exact one.
            degree = degreeNames[\augmented].indexOf(quality);
            if (degree.notNil) { ^(degree + 1) * 2 };
            degree = degreeNames[\diminished].indexOf(quality);
            if (degree.notNil) {
                ^if (perfect) { (degree + 1) * -2 } { (degree + 2) * -2 }
            };
            if (perfect) {
                if (quality == \perfect)        { ^0 };
                if (quality == \semiAugmented)  { ^1 };
                if (quality == \semiDiminished) { ^-1 };
                ^nil
            };
            if (quality == \major)      { ^0 };
            if (quality == \supermajor) { ^1 };
            if (quality == \neutral)    { ^-1 };
            if (quality == \minor)      { ^-2 };
            if (quality == \subminor)   { ^-3 };
            ^nil
        };

        if (quality.isKindOf(Event).not) { ^nil };
        kind = quality[\kind];
        degree = quality[\degree];
        if (degree.isKindOf(Integer).not or: { degree < 1 }) { ^nil };
        if (kind == \augmented) { ^degree * 2 };
        // Minor is already a step below major, which is where a
        // major-family diminished counts from. See `qualityFor`.
        if (kind == \diminished) { ^if (perfect) { degree * -2 } { (degree + 1) * -2 } };
        ^nil
    }

    // The perfect-family answer for a checked generic class. Throws
    // rather than answering for anything outside 0 to 6.
    //
    // genericClass: Integer
    // ^ Boolean
    *prCheckedPerfect { |genericClass|
        if (genericClass.isKindOf(Integer).not
            or: { genericClass < 0 } or: { genericClass > 6 }) {
            Error(
                "MusicIntervalName: generic class % must be 0 to 6."
                .format(genericClass)
            ).throw
        };
        ^perfectClasses.includes(genericClass)
    }

    // ^ Symbol | Nil
    *commonQualityNameFor { |genericClass, qualityDelta|
        ^this.prCommonQualityName(this.qualityFor(genericClass, qualityDelta))
    }

    // Exact augmented and diminished degrees stay in `quality`. Common names
    // decline beyond the first degree.
    // ^ Symbol | Nil
    *prCommonQualityName { |quality|
        var name;
        if (quality.isNil) { ^nil };
        if (quality.isKindOf(Symbol)) {
            if (commonQualityNames.includes(quality)) { ^quality };
            ^nil
        };
        if (quality.isKindOf(Event)
            and: { [\augmented, \diminished].includes(quality[\kind]) }
            and: { quality[\degree] == 1 }) {
            name = quality[\kind];
            if (commonQualityNames.includes(name)) { ^name }
        };
        ^nil
    }

    // ^ Integer | Nil
    *qualityDeltaQuarterSteps { |qualityDelta|
        var checked = this.checkedDelta(qualityDelta);
        var steps;
        if (checked[\cents] != 0.0) { ^nil };
        steps = checked[\semitones] * MusicPitch.quarterStepsPerSemitone;
        if (steps.denominator != 1) { ^nil };
        ^steps.numerator
    }

    // One Symbol for display and nil past the degrees that have a settled
    // word. `quality` still carries the exact degree, so nothing is lost.
    //
    // >>> MusicIntervalName(\signed, \ordered, 3, (semitones: 0, cents: 0.0), \major).qualityName
    // major
    //
    // ^ Symbol | Nil
    qualityName {
        var names;
        if (quality.isNil) { ^nil };
        if (quality.isKindOf(Symbol)) { ^quality };
        names = degreeNames[quality[\kind]];
        ^names !? { names[quality[\degree] - 1] }
    }

    // ^ Symbol | Nil
    commonQualityName { ^MusicIntervalName.prCommonQualityName(quality) }

    // ^ Event
    qualityDelta { ^MusicIntervalName.prDeltaSource(qualityDelta).copy }

    // ^ Integer | Nil
    qualityDeltaQuarterSteps { ^MusicIntervalName.qualityDeltaQuarterSteps(qualityDelta) }

    // ^ Event
    prQualityDelta { ^qualityDelta }

    // ^ Boolean
    == { |that| ^that.isKindOf(MusicIntervalName) and: {
        (convention == that.convention) and: { (direction == that.direction) and: {
        (number == that.number) and: { (qualityDelta == that.prQualityDelta) and: {
        quality == that.quality } } } } } }

    // ^ Integer
    hash { ^convention.hash bitXor: direction.hash bitXor: number.hash
        bitXor: qualityDelta.hash bitXor: quality.hash }

    // >>> MusicIntervalName(\signed, \ordered, 3, (semitones: 0, cents: 0.0), \major).asString
    // MusicIntervalName(signed, ordered, 3, major)
    //
    // ^ Self
    printOn { |stream|
        stream << "MusicIntervalName(" << convention << ", " << direction << ", "
            << number << ", " << (this.qualityName ?? { quality }) << ")"
    }

    // The printed form drops the delta. Source storage keeps it, but prints
    // semitones as numbers rather than the internal exact carrier.
    //
    // >>> MusicInterval.named(\major, 3).signedName.asCompileString.interpret.quality   -> major
    // ^ (Symbol, Symbol, Integer, Event, Symbol | Event | Nil)
    storeArgs {
        ^[convention, direction, number, this.qualityDelta, quality]
    }

    // value: Event
    // ^ Event
    *prDeltaSource { |value|
        ^(semitones: MusicPitch.semitoneNumber(value[\semitones]),
            cents: value[\cents])
    }
}
