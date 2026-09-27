// Exact numbers on a grid.
//
// The numbers carry no unit. Pitch callers can use them as row atoms. Rhythm
// callers can use them as offsets. Pitch spellability is checked when spelled.
//
// Not a sieve: the predicate is opaque. `MusicSieve` keeps residue classes as
// data and lowers them to a predicate for this.

DiscreteField {

    // Exact numbers from `low` through `high`, `step` apart, filtered by
    // `predicate`. A nil predicate keeps all.
    //
    // Counted in Rationals. Float inputs are refused.
    //
    // >>> DiscreteField.membersBetween(0, 1, 1%/3)
    // [ 0%/1, 1%/3, 2%/3, 1%/1 ]
    // >>> DiscreteField.membersBetween(60, 62, 1%/2)
    // [ 60%/1, 121%/2, 61%/1, 123%/2, 62%/1 ]
    // >>> DiscreteField.membersBetween(55, 70, 1, { |n| (n % 5) == 0 })
    // [ 55%/1, 60%/1, 65%/1, 70%/1 ]
    //
    // ^ [Rational]
    *membersBetween { |low, high, step = 1, predicate|
        var label = "DiscreteField.membersBetween";
        var first = this.prCheckedExact(low, "low", label);
        var last = this.prCheckedExact(high, "high", label);
        var by = this.prCheckedExact(step, "step", label);
        var found = List.new, number = first;

        if (by <= 0) {
            Error("%: step % is not positive.".format(label, this.prText(by)))
                .throw
        };
        if (last < first) {
            Error("%: high % is below low %.".format(label, this.prText(last),
                this.prText(first))).throw
        };
        if (predicate.notNil and: { predicate.isKindOf(Function).not }) {
            Error("%: expected a Function or nil, got %.".format(label,
                predicate.asCompileString)).throw
        };

        while { number <= last } {
            if (this.prAdmits(predicate, number, label)) { found.add(number) };
            number = number + by
        };
        ^found.asArray
    }

    // Exact in, Rational out.
    *prCheckedExact { |value, what, label|
        if (value.isKindOf(Float)) {
            Error("%: % % is a Float. Use an Integer or Rational.".format(
                label, what, value)).throw
        };
        if (value.isKindOf(Integer)) { ^Rational(value, 1) };
        if (value.isKindOf(Rational)) { ^value };
        Error("%: % is %, not an Integer or a Rational.".format(label, what,
            value.asCompileString)).throw
    }

    // Refuse a predicate result that `if` cannot read.
    *prAdmits { |predicate, number, label|
        var admitted;
        if (predicate.isNil) { ^true };
        admitted = predicate.value(number);
        if (admitted.isKindOf(Boolean).not) {
            Error("%: predicate answered % for %. Expected true or false."
                .format(label, admitted.asCompileString, this.prText(number)))
                .throw
        };
        ^admitted
    }

    // A number as a caller writes it: 60, or 1%/3.
    *prText { |number|
        if (number.denominator == 1) { ^number.numerator.asInteger };
        ^number
    }
}
