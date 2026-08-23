// Note [A bracket is two facts]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `ratio` is the arithmetic multiplier and reduces. `actualNotes` and
// `normalNotes` are the printed bracket counts and do not. The two
// describe one bracket, so they must agree. A multiplier-only tuplet
// has no authored pair, so counts are reduced. All three fields are
// read-only; changing one alone would split the bracket.



// A bracket: children whose written time is scaled to fit a different
// span. Five in the time of four is `Tuplet.ratio(5, 4, notes)`.
//
// `multiplier` is the scaling and inverts the printed pair: 5:4 stores 4/5.
//
// `actualNotes` and `normalNotes` keep the authored pair.
// Note [A bracket is two facts] above says what follows from that.
Tuplet : ScoreContainer {
    var <ratio, <actualNotes, <normalNotes;

    // The children slot takes a written run, by
    // Note [A run of leaves is a run of leaves] in ScoreNotation.sc, so
    // `Tuplet.ratio("3:2", "c4 d4 e4")` is the three leaves spelled out.
    //
    // String ratio and String multiplier stay in separate constructors.
    *new { |ratio, children|
        ^super.new(ScoreNotation.prChildrenOf(children, "Tuplet"))
            .initTuplet(ratio, nil, nil)
    }

    // The printed bracket: `Tuplet.ratio(5, 4, ...)` or `"5:4"`.
    //
    // The stored multiplier is the inverse.
    //
    // Separate from `new` because String multipliers already exist there.
    //
    // A multiplier is exact but not a written length.
    //
    // >>> Tuplet.ratio("5:4").actualNotes   -> 5
    // >>> Tuplet.ratio(5, 4).multiplier     -> Duration(4/5)
    *ratio { |actual, normal, children|
        var counts;
        if (actual.isKindOf(String) or: { actual.isKindOf(Symbol) }) {
            // "5:4" means the second argument is the children. There is no
            // third argument.
            counts = this.prParseRatio(actual);
            ^this.prBuild(counts[0], counts[1], normal)
        };
        ^this.prBuild(this.prCheckCount(actual, "actual"),
            this.prCheckCount(normal, "normal"), children)
    }

    // Counts kept as authored. The multiplier they imply is stored beside them.
    *prBuild { |actual, normal, children|
        ^super.new(ScoreNotation.prChildrenOf(children, "Tuplet.ratio"))
            .initTuplet(Duration(normal, actual), actual, normal)
    }

    // Rebuilt from a tree that already has both bracket facts.
    *like { |tuplet, children|
        ^this.prBuild(tuplet.actualNotes, tuplet.normalNotes, children)
    }

    // The exact constructor under a name that says it takes a multiplier.
    *multiplier { |value, children|
        ^this.new(value, children)
    }

    // Parses "5:4" as [actual, normal]: a colon and two whole numbers.
    *prParseRatio { |string|
        var parts = string.asString.split($:).collect { |part| part.stripWhiteSpace };
        var wrong = parts.any { |part|
            part.isEmpty or: { part.every { |char| char.isDecDigit }.not }
        };
        if (parts.size != 2 or: { wrong }) {
            Error("Tuplet: \"%\" is not a tuplet ratio. Use actual:normal, e.g. "
                "\"5:4\".".format(string)).throw
        };
        ^[this.prCheckCount(parts[0].asInteger, "actual"),
          this.prCheckCount(parts[1].asInteger, "normal")]
    }

    // Both sides are counts of notes, so both are whole and positive.
    // A bracket over nothing, or over minus three notes, isn't a
    // rhythm.
    *prCheckCount { |value, what|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error("Tuplet: % count must be a positive integer, got %.".format(
                what, value)).throw
        };
        ^value
    }

    initTuplet { |argRatio, argActual, argNormal|
        // Refuse printed-ratio syntax at the multiplier door.
        if (argRatio.isKindOf(String) or: { argRatio.isKindOf(Symbol) }) {
            if (argRatio.asString.contains(":")) {
                Error("Tuplet: \"%\" is a printed ratio, not a multiplier. Use "
                    "Tuplet.ratio(\"5:4\", children).".format(
                        argRatio)).throw
            }
        };
        ratio = Duration.asExactValue(argRatio, "a tuplet multiplier");
        // Absent counts are the reduced ones.
        actualNotes = if (argActual.isNil) {
            ratio.denominator
        } {
            Tuplet.prCheckCount(argActual, "actual")
        };
        normalNotes = if (argNormal.isNil) {
            ratio.numerator
        } {
            Tuplet.prCheckCount(argNormal, "normal")
        };
        // Cross-multiply so invalid counts fail before division.
        if ((normalNotes * ratio.denominator) != (actualNotes * ratio.numerator)) {
            Error("Tuplet: printed ratio %:% does not match multiplier %. Use "
                "matching counts or Tuplet.multiplier.".format(
                    actualNotes, normalNotes, ratio)).throw
        };
        ^this
    }

    // >>> Tuplet.ratio(3, 2).isTrivial   -> false
    // >>> Tuplet.ratio(2, 2).isTrivial   -> true
    multiplier { ^ratio }
    isTrivial { ^ratio.numerator == ratio.denominator }

    // Whether printed counts are the multiplier's reduced terms.
    //
    // >>> Tuplet.ratio(3, 2).countsAreReduced   -> true
    // >>> Tuplet.ratio(6, 4).countsAreReduced   -> false
    // >>> Tuplet.ratio(6, 4).multiplier         -> Duration(2/3)
    countsAreReduced {
        ^(actualNotes == ratio.denominator) and: { normalNotes == ratio.numerator }
    }

    accept { |writer| ^writer.visitTuplet(this) }

    printOn { |stream|
        stream << "Tuplet(" << actualNotes << ":" << normalNotes
               << ", " << children.size << ")"
    }
}
