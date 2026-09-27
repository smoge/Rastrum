// MusicSieve: Xenakis residue formulas as data.
//
// Prints a formula, computes periods, compares fields and lowers to a
// `DiscreteField` predicate. Scores store only the resulting material.
//
// Points are integers. `predicate(origin, unit)` realizes them.
//
// `==` compares how two sieves are written, `sameFieldAs` what they admit.

MusicSieve {
    var kind, modulus, residue, parts;

    // Residue class `modulus@residue`, normalized below modulus.
    //
    // >>> MusicSieve(3, 4).label    -> 3@1
    // >>> MusicSieve(5, -1).label   -> 5@4
    *new { |modulus, residue|
        var label = "MusicSieve";
        var m = this.prCheckedInteger(modulus, "modulus", label);
        var r = this.prCheckedInteger(residue, "residue", label);
        if (m < 1) {
            Error("%: modulus % is not positive.".format(label, m)).throw
        };
        ^super.new.prInitResidue(m, r % m)
    }

    // Reads the notation `label` prints. Mixed operators need brackets.
    //
    // >>> MusicSieve.parse("(3@0 & 5@1) | -7@2").label
    // (3@0 & 5@1) | -7@2
    // >>> MusicSieve.parse("3@-1")   -> MusicSieve(3, 2)
    *parse { |string|
        var state, sieve;
        if (string.isKindOf(String).not) {
            Error("MusicSieve.parse: expected a String, got %.".format(
                string.asCompileString)).throw
        };
        state = IdentityDictionary[\text -> string,
            \tokens -> this.prTokens(string), \index -> 0];
        if (state[\tokens].isEmpty) {
            Error("MusicSieve.parse: the formula is empty.").throw
        };
        sieve = this.prParseFormula(state);
        if (this.prPeek(state).notNil) {
            this.prParseFail(state, "unmatched )")
        };
        ^sieve
    }

    // `Set` names and operators. Same-kind parts are flattened.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).label
    // 3@0 | 5@1
    // >>> ((MusicSieve(3, 0) | MusicSieve(5, 1)) & MusicSieve(2, 0)).label
    // (3@0 | 5@1) & 2@0
    union { |that| ^this.prJoin(\union, that, "MusicSieve.union") }
    | { |that| ^this.union(that) }
    sect { |that| ^this.prJoin(\sect, that, "MusicSieve.sect") }
    & { |that| ^this.sect(that) }

    // Difference, written as intersection with a complement.
    //
    // >>> (MusicSieve(3, 0) - MusicSieve(5, 1)).label
    // 3@0 & -5@1
    difference { |that|
        ^this.sect(this.prChecked(that, "MusicSieve.difference").complement)
    }
    - { |that| ^this.difference(that) }

    // Symmetric difference. The operator is `--`. Labels print `^`.
    //
    // >>> (MusicSieve(3, 0) -- MusicSieve(5, 1)).label
    // 3@0 ^ 5@1
    symmetricDifference { |that|
        ^this.prJoin(\symmetricDifference, that,
            "MusicSieve.symmetricDifference")
    }
    -- { |that| ^this.symmetricDifference(that) }

    // Complement. Two complements cancel.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).complement.label
    // -(3@0 | 5@1)
    // >>> MusicSieve(3, 0).complement.complement == MusicSieve(3, 0)
    // true
    complement {
        if (kind == \complement) { ^parts[0] };
        ^MusicSieve.prNode(\complement, [this])
    }

    // Moves every residue by whole points.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).shift(2).label
    // 3@2 | 5@3
    shift { |by|
        ^this.prShifted(MusicSieve.prCheckedInteger(by, "shift",
            "MusicSieve.shift",
            " Use origin of predicate for fractional moves."))
    }

    // Embeds the sieve in a lattice `by` times finer. A complement stays on
    // the old lattice.
    //
    // >>> MusicSieve.parse("3@0 | 5@1").refined(2).label   -> 6@0 | 10@2
    // >>> MusicSieve.parse("-3@0").refined(2).label        -> 2@0 & -6@0
    refined { |by|
        var label = "MusicSieve.refined";
        var k = MusicSieve.prCheckedInteger(by, "refinement", label);
        if (k < 1) {
            Error("%: refinement % is not positive.".format(label, k)).throw
        };
        if (k == 1) { ^this };
        ^this.prRefined(k, label)
    }

    // True when `point` is admitted.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).admits(6)   -> true
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).admits(7)   -> false
    admits { |point|
        ^this.prAdmits(MusicSieve.prCheckedInteger(point, "point",
            "MusicSieve.admits"))
    }

    // Function for `DiscreteField.membersBetween`.
    //
    // >>> DiscreteField.membersBetween(60, 66, 1, MusicSieve(3, 0).predicate)
    // [ 60%/1, 63%/1, 66%/1 ]
    // >>> MusicSieve(3, 0).predicate(1%/2).value(121%/2)   -> true
    // >>> MusicSieve(3, 0).predicate(1%/2).value(60)       -> false
    predicate { |origin = 0, unit = 1|
        var label = "MusicSieve.predicate";
        var start = MusicSieve.prCheckedExact(origin, "origin", label);
        var step = MusicSieve.prCheckedExact(unit, "unit", label);
        if (step <= 0) {
            Error("%: unit % is not positive.".format(label,
                MusicSieve.prText(step))).throw
        };
        ^{ |value|
            var exact = MusicSieve.prCheckedExact(value, "value", label);
            var point = (exact - start) / step;
            (point.denominator == 1)
                and: { this.prAdmits(point.numerator.asInteger) }
        }
    }

    // Formula label in sieve notation.
    //
    // >>> ((MusicSieve(3, 0) & MusicSieve(5, 1)) | MusicSieve(7, 2)).label
    // (3@0 & 5@1) | 7@2
    // >>> (MusicSieve(7, 2) | MusicSieve(3, 0).complement).label
    // 7@2 | -3@0
    label {
        ^switch(kind,
            \residue, { "%@%".format(modulus, residue) },
            \complement, { "-" ++ parts[0].prLabelIn(kind) },
            { parts.collect { |part| part.prLabelIn(kind) }
                .join(MusicSieve.prSymbols(kind)[0]) })
    }

    // LCM period, refused before Integer overflow.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).lcmPeriod   -> 15
    // >>> (MusicSieve(8, 0) | MusicSieve(8, 4)).lcmPeriod   -> 8
    lcmPeriod { ^this.prLcmPeriod("MusicSieve.lcmPeriod") }

    // Shortest period, reduced from the LCM.
    //
    // >>> (MusicSieve(8, 0) | MusicSieve(8, 4)).minimalPeriod   -> 4
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).minimalPeriod   -> 15
    minimalPeriod {
        var label = "MusicSieve.minimalPeriod";
        var whole = MusicSieve.prTableLength(this.prLcmPeriod(label), label);
        var table = Array.fill(whole, { |n| this.prAdmits(n) });
        var period = whole;
        MusicSieve.prPrimesOf(whole).do { |prime|
            while {
                ((period % prime) == 0)
                    and: { MusicSieve.prRepeats(table, period div: prime) }
            } { period = period div: prime }
        };
        ^period
    }

    // Same admitted field over their common period.
    //
    // >>> (MusicSieve(3, 0) & MusicSieve(5, 1)).sameFieldAs(MusicSieve(15, 6))
    // true
    sameFieldAs { |that|
        var label = "MusicSieve.sameFieldAs";
        var other = this.prChecked(that, label);
        var common = MusicSieve.prTableLength(
            MusicSieve.prLcm(this.prLcmPeriod(label), other.prLcmPeriod(label),
                label), label);
        var n = 0;
        while { (n < common) and: { this.prAdmits(n) == other.prAdmits(n) } } {
            n = n + 1
        };
        ^n == common
    }

    // Structural equality over the normalized tree.
    //
    // >>> (MusicSieve(3, 0) & MusicSieve(5, 1)) == MusicSieve(15, 6)
    // false
    == { |that|
        ^that.isKindOf(MusicSieve) and: { kind == that.prKind } and: {
            if (kind == \residue) {
                (modulus == that.prModulus) and: { residue == that.prResidue }
            } { parts == that.prParts }
        }
    }

    hash {
        if (kind == \residue) { ^[kind, modulus, residue].hash };
        ^[kind, parts].hash
    }

    // Source that rebuilds the sieve, also used when posting.
    //
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).asCompileString
    // MusicSieve(3, 0) | MusicSieve(5, 1)
    // >>> (MusicSieve(3, 0) | MusicSieve(5, 1)).complement.asCompileString
    // (MusicSieve(3, 0) | MusicSieve(5, 1)).complement
    storeOn { |stream| stream << this.prSource }
    printOn { |stream| this.storeOn(stream) }

    *prNode { |kind, parts| ^super.new.prInitNode(kind, parts) }
    prInitResidue { |m, r| kind = \residue; modulus = m; residue = r }
    prInitNode { |k, p| kind = k; parts = p }

    prKind { ^kind }
    prModulus { ^modulus }
    prResidue { ^residue }
    prParts { ^parts }

    prJoin { |joined, that, label|
        var other = this.prChecked(that, label);
        ^MusicSieve.prNode(joined,
            this.prPartsAs(joined) ++ other.prPartsAs(joined))
    }

    // A node's own parts for the same kind, else itself.
    prPartsAs { |joined| ^if (kind == joined) { parts } { [this] } }

    prShifted { |steps|
        if (kind == \residue) { ^MusicSieve(modulus, residue + steps) };
        ^MusicSieve.prNode(kind,
            parts.collect { |part| part.prShifted(steps) })
    }

    // Rebuilt through the operators, so a guard joins its parent's parts.
    // A `do` loop, since a refusal must not unwind through `collect`.
    prRefined { |k, label|
        var refined;
        if (kind == \residue) {
            if (modulus > (2147483647 div: k)) {
                Error("%: % refined by % passes 2147483647.".format(label,
                    this.label, k)).throw
            };
            ^MusicSieve(modulus * k, residue * k)
        };
        if (kind == \complement) {
            refined = parts[0].prRefined(k, label);
            ^MusicSieve(k, 0) - refined
        };
        parts.do { |part|
            var next = part.prRefined(k, label);
            refined = if (refined.isNil) { next } {
                refined.perform(kind, next) }
        };
        ^refined
    }

    prAdmits { |n|
        ^switch(kind,
            \residue, { (n % modulus) == residue },
            \union, { parts.any { |part| part.prAdmits(n) } },
            \sect, { parts.every { |part| part.prAdmits(n) } },
            \symmetricDifference, {
                parts.count { |part| part.prAdmits(n) }.odd },
            \complement, { parts[0].prAdmits(n).not })
    }

    prLcmPeriod { |label|
        ^switch(kind,
            \residue, { modulus },
            \complement, { parts[0].prLcmPeriod(label) },
            { parts.inject(1, { |whole, part|
                MusicSieve.prLcm(whole, part.prLcmPeriod(label), label) }) })
    }

    prSource {
        ^switch(kind,
            \residue, { "MusicSieve(%, %)".format(modulus, residue) },
            \complement, { parts[0].prSourceIn(kind) ++ ".complement" },
            { parts.collect { |part| part.prSourceIn(kind) }
                .join(MusicSieve.prSymbols(kind)[1]) })
    }

    prLabelIn { |parent|
        if (this.prIsBare(parent)) { ^this.label };
        ^"(%)".format(this.label)
    }

    prSourceIn { |parent|
        if (this.prIsBare(parent)) { ^this.prSource };
        ^"(%)".format(this.prSource)
    }

    // Bracket rule for labels and source.
    prIsBare { |parent|
        ^(kind == \residue)
            or: { (kind == \complement) and: { parent != \complement } }
    }

    prChecked { |that, label|
        if (that.isKindOf(MusicSieve)) { ^that };
        Error("%: % is not a MusicSieve.%".format(label, that.asCompileString,
            MusicSieve.prPointHint(that))).throw
    }

    // Label symbol, then source operator.
    *prSymbols { |kind|
        ^switch(kind,
            \union, { [" | ", " | "] },
            \sect, { [" & ", " & "] },
            \symmetricDifference, { [" ^ ", " -- "] })
    }

    // Integer, or whole Rational as Integer.
    *prCheckedInteger { |value, what, label, hint = ""|
        if (value.isKindOf(Integer)) { ^value };
        if (value.isKindOf(Rational) and: { value.denominator == 1 }) {
            if (value.numerator.abs > 2147483647) {
                Error("%: % % is past the largest Integer.".format(label, what,
                    value)).throw
            };
            ^value.numerator.asInteger
        };
        Error("%: % % is not an Integer.%%".format(label, what,
            value.asCompileString, this.prPointHint(value), hint)).throw
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

    // `3@0` is a Point in sclang.
    *prPointHint { |value|
        if (value.isKindOf(Point).not) { ^"" };
        ^" sclang reads %@% as a Point. Write MusicSieve(%, %).".format(
            value.x, value.y, value.x, value.y)
    }

    *prLcm { |a, b, label|
        var reduced = a div: (a gcd: b);
        if (reduced > (2147483647 div: b)) {
            Error("%: period exceeds 2147483647.".format(label)).throw
        };
        ^reduced * b
    }

    // One bounded table per period.
    *prTableLength { |length, label|
        if (length > 1048576) {
            Error("%: period % exceeds table limit 1048576.".format(label,
                length)).throw
        };
        ^length
    }

    *prPrimesOf { |number|
        var primes = List.new, rest = number, factor = 2;
        while { (factor * factor) <= rest } {
            if ((rest % factor) == 0) {
                primes.add(factor);
                while { (rest % factor) == 0 } { rest = rest div: factor }
            };
            factor = factor + 1
        };
        if (rest > 1) { primes.add(rest) };
        ^primes.asArray
    }

    // True when one period repeats after `shift`.
    *prRepeats { |table, shift|
        var size = table.size, n = 0;
        while { (n < size) and: { table[n] == table[(n + shift) % size] } } {
            n = n + 1
        };
        ^n == size
    }

    // Caller-facing exact number.
    *prText { |number|
        if (number.denominator == 1) { ^number.numerator.asInteger };
        ^number
    }

    // Tokens are [kind, value, index].
    *prTokens { |text|
        var tokens = List.new, index = 0, char, kind, start, number;
        while { index < text.size } {
            char = text[index];
            kind = this.prTokenKind(char);
            case
            { char.isSpace } { index = index + 1 }
            { char.isDecDigit } {
                start = index;
                while { (index < text.size) and: { text[index].isDecDigit } } {
                    index = index + 1
                };
                number = this.prParsedNumber(text.copyRange(start, index - 1),
                    text, start);
                tokens.add([\number, number, start])
            }
            { kind.notNil } {
                tokens.add([kind, char, index]);
                index = index + 1
            }
            {
                Error("MusicSieve.parse: unexpected % at character % of %.%"
                    .format(char, index + 1, text.asCompileString,
                        if (char.isAlpha) { " Use notation, not source." } {
                            "" }))
                    .throw
            }
        };
        ^tokens.asArray
    }

    *prTokenKind { |char|
        ^switch(char,
            $@, { \at }, $|, { \union }, $&, { \sect },
            $^, { \symmetricDifference }, $-, { \minus },
            $(, { \open }, $), { \close })
    }

    *prParsedNumber { |digits, text, index|
        if ((digits.size > 10) or: { digits.asFloat > 2147483647 }) {
            Error(("MusicSieve.parse: % is past the largest Integer at "
                ++ "character % of %.").format(digits, index + 1,
                text.asCompileString)).throw
        };
        ^digits.asInteger
    }

    // Formula joined by one operator.
    *prParseFormula { |state|
        var sieve = this.prParseOperand(state), joined, token, reason, operand;
        while {
            token = this.prPeek(state);
            token.notNil and: { token[0] != \close }
        } {
            if ([\union, \sect, \symmetricDifference].includes(token[0]).not) {
                reason = this.prNotAnOperator(state);
                this.prParseFail(state, reason[0], reason[1])
            };
            if (joined.notNil and: { joined[0] != token[0] }) {
                this.prParseFail(state, "% and % meet".format(joined[1],
                    token[1]),
                    " Bracket one side.")
            };
            joined = this.prTake(state);
            operand = this.prParseOperand(state);
            sieve = sieve.perform(joined[0], operand)
        };
        ^sieve
    }

    // Residue, complement or group.
    *prParseOperand { |state|
        var token = this.prPeek(state), inner;
        if (token.isNil) {
            this.prParseFail(state, "expected a residue class, - or (")
        };
        switch(token[0],
            \number, { ^this.prParseResidue(state) },
            \minus, {
                this.prTake(state);
                ^this.prParseOperand(state).complement
            },
            \open, {
                this.prTake(state);
                inner = this.prParseFormula(state);
                if (this.prPeek(state).isNil) {
                    this.prParseFail(state, "unmatched (", "", token)
                };
                this.prTake(state);
                ^inner
            });
        this.prParseFail(state, "expected a residue class, - or (")
    }

    // Signed `modulus@residue`.
    *prParseResidue { |state|
        var modulus = this.prTake(state), sign = 1, token, residue;
        if (this.prPeek(state).isNil or: { this.prPeek(state)[0] != \at }) {
            this.prParseFail(state, "expected @ after %".format(modulus[1]))
        };
        this.prTake(state);
        token = this.prPeek(state);
        if (token.notNil and: { token[0] == \minus }) {
            this.prTake(state);
            sign = -1
        };
        residue = this.prPeek(state);
        if (residue.isNil or: { residue[0] != \number }) {
            this.prParseFail(state, "expected a residue after %@"
                .format(modulus[1]))
        };
        this.prTake(state);
        if (modulus[1] < 1) {
            this.prParseFail(state, "modulus % is not positive"
                .format(modulus[1]), "", modulus)
        };
        ^MusicSieve(modulus[1], sign * residue[1])
    }

    // Join refusal and hint.
    *prNotAnOperator { |state|
        var token = this.prPeek(state);
        var after = state[\tokens][state[\index] + 1];
        if (token[0] != \minus) { ^["expected |, & or ^", ""] };
        if (after.notNil and: { after[0] == \minus }) {
            ^["unexpected --",
                " Write it as ^."]
        };
        ^["unexpected -", " Write a difference as a & -b."]
    }

    *prPeek { |state| ^state[\tokens][state[\index]] }

    *prTake { |state|
        var token = this.prPeek(state);
        state[\index] = state[\index] + 1;
        ^token
    }

    // Refusal with location.
    *prParseFail { |state, what, hint = "", token|
        var at = token ?? { this.prPeek(state) };
        var where = if (at.isNil) { "at the end of" } {
            "at character % of".format(at[2] + 1) };
        Error("MusicSieve.parse: % % %.%".format(what, where,
            state[\text].asCompileString, hint)).throw
    }
}
