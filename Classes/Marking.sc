// Marking: a dynamic, an articulation or text, sitting on one leaf.
//
// A kind and a value. Text also carries a placement. How any of them is spelled
// is the writers' business and appears only there.
//
// Point markings only. A slur or a hairpin pairs a start with a stop and needs
// the continuity checking ties do, so it is a `Spanner`.
//
// The two vocabularies are closed and checked at construction: see
// Note [Refuse at the constructor] in MusicPitch.sc.
//
// Text has no vocabulary, there being no vocabulary of things a player might be
// told, so what is checked instead is that it is prose: see `checkedText`. Its
// placement is a closed pair, which is the only thing about text a writer has
// to decide.
Marking {
    classvar <dynamics, <articulations, <placements;

    var <kind, <value, <placement;

    *initClass {
        dynamics = [\pppp, \ppp, \pp, \p, \mp, \mf, \f, \ff, \fff, \ffff];
        articulations = [\staccato, \staccatissimo, \tenuto, \accent, \marcato];
        placements = [\above, \below]; // Above or below the staff
    }

    *dynamic { |value|
        ^super.newCopyArgs(\dynamic, this.prCheck(value, dynamics, "dynamic"), nil)
    }

    *articulation { |value|
        ^super.newCopyArgs(\articulation,
            this.prCheck(value, articulations, "articulation"), nil)
    }

    // Free text: a technique instruction.
    //
    // >>> Marking.text("sul pont.").placement           -> above
    // >>> Marking.text("sul pont.", \below).placement   -> below
    *text { |value, placement = \above|
        ^super.newCopyArgs(\text, this.checkedText(value),
            this.checkedPlacement(placement))
    }

    // The vocabulary for a kind, or nil for text, which has none by its
    // nature, so a caller walking vocabularies has nothing to walk rather than
    // a gap.
    //
    // >>> Marking.vocabularyFor(\dynamic).size   -> 10
    // >>> Marking.vocabularyFor(\text)           -> nil
    *vocabularyFor { |kind|
        if (kind == \dynamic) { ^dynamics };
        if (kind == \articulation) { ^articulations };
        ^nil
    }

    *of { |kind, value, placement|
        if (kind == \dynamic) { ^this.dynamic(value) };
        if (kind == \articulation) { ^this.articulation(value) };
        if (kind == \text) { ^this.text(value, placement ? \above) };
        Error("Marking: there is no \"%\" marking. The kinds are dynamic, "
            "articulation and text.".format(kind)).throw
    }

    // The checks prose gets: something, a String, not only whitespace, and no
    // control character a document could not carry.
    //
    // Public because `Spanner` and `Direction` ask the same question, and three
    // copies would answer it differently before long.
    *checkedText { |value|
        if (value.isNil) {
            Error("Marking: a text marking needs something to say.").throw
        };
        if (value.isKindOf(String).not) {
            Error("Marking: text must be a String, got a %. A Symbol would be a "
                "vocabulary word, and text is prose.".format(value.class)).throw
        };
        if (value.stripWhiteSpace.isEmpty) {
            Error("Marking: text of \"%\" says nothing. Empty text draws nothing "
                "and reads as a mistake later.".format(value)).throw
        };
        // Tab, newline and return are the only control characters XML allows,
        // so the rest would make a document no reader will take.
        value.do { |char|
            var code = char.ascii;
            if (code >= 0 and: { code < 32 }
                and: { [$\n, $\r, $\t].includes(char).not }) {
                Error("Marking: text contains a control character (%). Only tab, "
                    "newline and return are writable; the rest cannot be put in a "
                    "MusicXML document at all.".format(code)).throw
            }
        };
        ^value.copy
    }

    *checkedPlacement { |placement|
        if (placement.isNil) {
            Error("Marking: text needs a placement; the placements are %.".format(
                placements)).throw
        };
        if (placements.includes(placement.asSymbol).not) {
            Error("Marking: \"%\" is not a placement. The placements are %.".format(
                placement, placements)).throw
        };
        ^placement.asSymbol
    }

    *prArticle { |word| ^if ("aeiou".includes(word.asString.first)) { "an" } { "a" } }

    *prCheck { |value, vocabulary, what|
        if (value.isNil) {
            Error("Marking: % % needs a value; the % are %.".format(
                this.prArticle(what), what, what ++ "s", vocabulary)).throw
        };
        if (vocabulary.includes(value.asSymbol).not) {
            Error("Marking: \"%\" is not % %. The % are %.".format(
                value, this.prArticle(what), what, what ++ "s", vocabulary)).throw
        };
        ^value.asSymbol
    }

    // >>> Marking.dynamic(\ff).isDynamic             -> true
    // >>> Marking.articulation(\staccato).value      -> staccato
    // >>> Marking.text("sul pont.").isText           -> true
    isDynamic { ^kind == \dynamic }
    isArticulation { ^kind == \articulation }
    isText { ^kind == \text }

    // Placement is part of what a marking says, not decoration on it: the same
    // words above and below the staff are two different instructions.
    == { |that| ^that.isKindOf(Marking) and: {
        (kind == that.kind)
            and: { value == that.value }
            and: { placement == that.placement } } }
    hash { ^(kind.hash bitXor: value.hash) bitXor: placement.hash }

    printOn { |stream|
        stream << "Marking(" << kind << ", " << value;
        if (placement.notNil) { stream << ", " << placement };
        stream << ")"
    }
}
