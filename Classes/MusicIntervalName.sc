// MusicIntervalName: what a `MusicInterval` is called, under one convention.
//
// A name is a projection of the vector, never the vector itself. `signedName`,
// `melodicName` and `harmonicName` all answer this shape, with different
// orientations.
//
// `quality` keeps the exact degree wherever a quality exists, and `qualityName`
// displays it. An augmented interval is `(kind: \augmented, degree: 1)` because
// a doubly augmented one is the same kind at degree 2. If no quality exists,
// `quality` and `qualityName` are nil and `qualityDelta` still carries the
// deviation.


// Note [Common names are a projection]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Quality names are read from the interval number first, then from the
// quarter-step offset from its major or perfect reference. The projection exists
// only when cents are zero and the exact semitone delta lands on the quarter-tone
// grid. `commonQualityName` is the smaller one-symbol display set.

MusicIntervalName {
    var <convention, <direction, <number, <qualityDelta, <quality;

    classvar <conventions, <directions, <perfectClasses, <degreeNames,
        <commonQualityNames;

    *initClass {
        conventions = [\signed, \melodic, \harmonic];

        // `\ordered` is what `signedName` uses: the name was computed from the
        // vector as given, so the field carries no claim about motion. See
        // MusicInterval#genericDirection for that.
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

    // The generic class an interval number belongs to, which is what
    // `qualityFor` wants. An octave is number 8 and shares the unison's class,
    // which is why this is not `number - 1`.
    //
    // Checked, because sclang's `%` floors: an unchecked `0` would answer `6`
    // and name a seventh's qualities for a nonexistent interval.
    //
    // >>> [1, 3, 8, 9].collect { |n| MusicIntervalName.genericClassOf(n) }
    // [ 0, 2, 0, 1 ]
    *genericClassOf { |number| ^(this.checkedNumber(number) - 1) % 7 }

    *checkedConvention { |value|
        if (conventions.includes(value).not) {
            Error("MusicIntervalName: \"%\" is not a convention. They are %.".format(
                value, conventions)).throw
        };
        ^value
    }

    *checkedDirection { |value|
        if (directions.includes(value).not) {
            Error("MusicIntervalName: \"%\" is not a direction. They are %.".format(
                value, directions)).throw
        };
        ^value
    }

    // 1 is a prime, and there is no zeroth interval.
    *checkedNumber { |value|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error("MusicIntervalName: a number of % is not an interval number. "
                "They count from 1, which is a prime.".format(value)).throw
        };
        ^value
    }

    // Both fields are type-checked, not merely present. A delta carrying a
    // Symbol where the semitones go would survive construction and fail much
    // later, inside whatever first did arithmetic with it.
    *checkedDelta { |value|
        var semitones, cents, normalized;
        if (value.isKindOf(Event).not) {
            Error("MusicIntervalName: a quality delta is "
                "(semitones: aDuration, cents: aFloat), got %.".format(value)).throw
        };
        semitones = value[\semitones];
        cents = value[\cents];
        if (semitones.isKindOf(Duration).not) {
            Error("MusicIntervalName: a quality delta's semitones must be a "
                "Duration, got %.".format(semitones)).throw
        };
        if (cents.isNumber.not or: { (cents.abs < inf).not }) {
            Error("MusicIntervalName: a quality delta's cents must be a finite "
                "number, got %.".format(cents)).throw
        };
        // Float always, as `MusicPitch` and `MusicInterval` both answer, so one
        // deviation stores and prints one way however it was built. A copy, so
        // a caller cannot reach into a name and change its delta afterwards.
        normalized = value.copy;
        normalized[\cents] = cents.asFloat;
        ^normalized
    }

    // A name whose quality is not the one its own delta names is two halves
    // disagreeing, so the quality is derived and compared rather than trusted.
    // The generic class follows from the number, so nothing extra is needed.
    *checkedQuality { |number, qualityDelta, value|
        var derived = this.qualityFor(this.genericClassOf(number), qualityDelta);
        if (value != derived) {
            Error("MusicIntervalName: a delta of % on interval number % names %, "
                "not %. The two halves of a name have to agree.".format(
                    qualityDelta, number, derived ?? { "no quality" }, value)).throw
        };
        ^value
    }

    // The quality a generic class and an exact delta name, or nil where no
    // settled word exists. See Note [Common names are a projection].
    //
    // >>> MusicIntervalName.qualityFor(2, (semitones: MusicPitch.semitones(0), cents: 0.0))
    // major
    // >>> MusicIntervalName.qualityFor(4, (semitones: MusicPitch.semitones(0), cents: 0.0))
    // perfect
    // >>> MusicIntervalName.qualityFor(2, (semitones: MusicPitch.semitones(-1, 2), cents: 0.0))
    // neutral
    // >>> MusicIntervalName.qualityFor(3, (semitones: MusicPitch.semitones(1, 2), cents: 0.0))
    // semiAugmented
    *qualityFor { |genericClass, qualityDelta|
        var perfect, quarterSteps, delta;
        if (genericClass.isKindOf(Integer).not
            or: { genericClass < 0 } or: { genericClass > 6 }) {
            Error("MusicIntervalName: a generic class of % is not a degree. They "
                "are 0 to 6, where 0 is the unison and octave class.".format(
                    genericClass)).throw
        };
        perfect = perfectClasses.includes(genericClass);
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

        if (quarterSteps == 0) { ^\major };
        if (quarterSteps == 1) { ^\supermajor };
        if (quarterSteps == -1) { ^\neutral };
        if (quarterSteps == -2) { ^\minor };
        if (quarterSteps == -3) { ^\subminor };
        if ((quarterSteps % 2) != 0) { ^nil };

        delta = quarterSteps div: 2;
        if (delta > 0) { ^(kind: \augmented, degree: delta) };

        // Minor is already one semitone down, so diminished major-family
        // intervals count from there rather than from major.
        ^(kind: \diminished, degree: (delta + 1).abs)
    }

    // The inverse of `qualityFor`: how far off the reference a named quality
    // sits, in quarter steps, or nil where the name does not belong to this
    // family. `\augmented` and `\diminished` are accepted as the degree-1
    // shorthand that `commonQualityName` answers.
    //
    // >>> MusicIntervalName.quarterStepsFor(2, \neutral)   -> -1
    // >>> MusicIntervalName.quarterStepsFor(4, \perfect)   -> 0
    *quarterStepsFor { |genericClass, quality|
        var perfect, kind, degree;
        if (genericClass.isKindOf(Integer).not
            or: { genericClass < 0 } or: { genericClass > 6 }) {
            Error("MusicIntervalName: a generic class of % is not a degree. They "
                "are 0 to 6, where 0 is the unison and octave class.".format(
                    genericClass)).throw
        };
        perfect = perfectClasses.includes(genericClass);

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
        // Minor is already a step below major, which is where a major-family
        // diminished counts from. See `qualityFor`.
        if (kind == \diminished) { ^if (perfect) { degree * -2 } { (degree + 1) * -2 } };
        ^nil
    }

    *commonQualityNameFor { |genericClass, qualityDelta|
        ^this.prCommonQualityName(this.qualityFor(genericClass, qualityDelta))
    }

    // Exact augmented and diminished degrees stay in `quality`. Common names
    // decline beyond the first degree.
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

    *qualityDeltaQuarterSteps { |qualityDelta|
        var checked = this.checkedDelta(qualityDelta);
        var steps;
        if (checked[\cents] != 0.0) { ^nil };
        steps = checked[\semitones] * MusicPitch.quarterStepsPerSemitone;
        if (steps.denominator != 1) { ^nil };
        ^steps.numerator
    }

    // One Symbol for display, and nil past the degrees that have a settled
    // word. `quality` still carries the exact degree, so nothing is lost.
    //
    // >>> MusicIntervalName(\signed, \ordered, 3, (semitones: MusicPitch.semitones(0), cents: 0.0), \major).qualityName
    // major
    qualityName {
        var names;
        if (quality.isNil) { ^nil };
        if (quality.isKindOf(Symbol)) { ^quality };
        names = degreeNames[quality[\kind]];
        ^names !? { names[quality[\degree] - 1] }
    }

    commonQualityName { ^MusicIntervalName.prCommonQualityName(quality) }

    qualityDeltaQuarterSteps { ^MusicIntervalName.qualityDeltaQuarterSteps(qualityDelta) }

    == { |that| ^that.isKindOf(MusicIntervalName) and: {
        (convention == that.convention) and: { (direction == that.direction) and: {
        (number == that.number) and: { (qualityDelta == that.qualityDelta) and: {
        quality == that.quality } } } } } }

    hash { ^convention.hash bitXor: direction.hash bitXor: number.hash
        bitXor: qualityDelta.hash bitXor: quality.hash }

    // >>> MusicIntervalName(\signed, \ordered, 3, (semitones: MusicPitch.semitones(0), cents: 0.0), \major).asString
    // MusicIntervalName(signed, ordered, 3, major)
    printOn { |stream|
        stream << "MusicIntervalName(" << convention << ", " << direction << ", "
            << number << ", " << (this.qualityName ?? { quality }) << ")"
    }
}
