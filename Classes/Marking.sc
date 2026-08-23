// Marking: a point fact on one leaf.
//
// A marking has a kind and a value. Text also has placement. Writers
// own spelling. Slurs and hairpins are `Spanner`s. They pair
// endpoints and need continuity checks. Dynamic, sforzando,
// articulation and technical values are closed vocabularies. Text
// checks prose and placement only.
Marking {
    classvar <dynamics, <articulations, <placements, <articulationShortNames,
        <sforzandoLevels, <sforzandoSpellings, <technicalMarks;

    var <kind, <value, <placement;

    *initClass {
        dynamics = [\ppppp, \pppp, \ppp, \pp, \p, \mp, \mf, \f, \ff,
            \fff, \ffff, \fffff];
        // `breath` and `caesura` mark silence after an attack.
        // LilyPond and MusicXML place them with articulations.
        articulations = [\staccato, \staccatissimo, \tenuto, \accent, \marcato,
            \portato, \fermata, \breath, \caesura];
        // See Note [A technical mark is not an articulation] below.
        technicalMarks = [\upbow, \downbow, \stopped, \snapPizzicato,
            \openString, \harmonic];
        placements = [\above, \below]; // Above or below the staff
        // See Note [A spelling is not a vocabulary word] below.
        articulationShortNames = [["stac", \staccato]];
        // See Note [A sforzando is an accent at a level] below.
        sforzandoLevels = [\mp, \mf, \f, \ff];
        sforzandoSpellings = [
            ["smpz", \mp],
            ["smfz", \mf],
            ["sfz",  \f],
            ["sffz", \ff]
        ];
    }

    // Note [A sforzando is an accent at a level]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A sforzando names a level plus a sharp attack. The model stores
    // one kind with a level parameter:
    //
    //   smpz -> mp     smfz -> mf     sfz -> f     sffz -> ff
    //
    // The spelling lives here because input and output use the same
    // word. `rfz` is hairpin-shaped reinforcement over time. `sf` and
    // `fz` do not name a level here.

    // >>> Marking.sforzando(\f).kind    -> sforzando
    // >>> Marking.sforzando(\ff).value  -> ff
    *sforzando { |level|
        ^super.newCopyArgs(\sforzando,
            this.prCheck(level, sforzandoLevels, "sforzando level"), nil)
    }

    // The level a written spelling names, or nil where it names none.
    //
    // >>> Marking.sforzandoNamed("sfz")    -> f
    // >>> Marking.sforzandoNamed("smpz")   -> mp
    // >>> Marking.sforzandoNamed("sf")     -> nil
    *sforzandoNamed { |name|
        var text = name.asString;
        var found = sforzandoSpellings.detect { |pair| pair[0] == text };
        ^found !? { found[1] }
    }

    // What a score prints for a sforzando level.
    //
    // >>> Marking.sforzandoSpelling(\ff)   -> sffz
    *sforzandoSpelling { |level|
        var found = sforzandoSpellings.detect { |pair| pair[1] == level.asSymbol };
        ^found !? { found[0] } ?? {
            Error("Marking: % is not a sforzando level. Use one of %.".format(level, sforzandoLevels)).throw
        }
    }

    // Every written sforzando spelling.
    //
    // >>> Marking.sforzandoSuffixes   -> [ smpz, smfz, sfz, sffz ]
    *sforzandoSuffixes { ^sforzandoSpellings.collect { |pair| pair[0] } }

    // Note [A spelling is not a vocabulary word]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `articulations` is the model and wire vocabulary.
    // `articulationShortNames` is parser spelling only, like compact
    // accidentals and hairpin heads. Short names are abbreviations,
    // not synonyms. `\stac` is not an articulation, so
    // `articulation(\stac)` stays refused.

    // The articulation a written suffix names, or nil.
    //
    // >>> Marking.articulationNamed("stac")     -> staccato
    // >>> Marking.articulationNamed("tenuto")   -> tenuto
    // >>> Marking.articulationNamed("nope")     -> nil
    *articulationNamed { |name|
        var text = name.asString, found;
        if (articulations.includes(text.asSymbol)) { ^text.asSymbol };
        found = articulationShortNames.detect { |pair| pair[0] == text };
        ^found !? { found[1] }
    }

    // Every written articulation suffix.
    //
    // >>> Marking.articulationSuffixes.last   -> stac
    *articulationSuffixes {
        ^articulations.collect { |each| each.asString }
            ++ articulationShortNames.collect { |pair| pair[0] }
    }

    *dynamic { |value|
        ^super.newCopyArgs(\dynamic, this.prCheck(value, dynamics, "dynamic"), nil)
    }

    // >>> Marking.articulation(\tenuto).kind   -> articulation
    *articulation { |value|
        ^super.newCopyArgs(\articulation,
            this.prCheck(value, articulations, "articulation"), nil)
    }

    // Note [A technical mark is not an articulation]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // An articulation shapes the attack. A technical mark says how
    // the sound is produced. They stay separate so `PlaybackMap` does
    // not need neutral rows for marks with no playback policy. The
    // kind is broad. Bowed-string marks are only the first values.
    // Staff names are labels, not an instrument-family validation
    // layer.

    // >>> Marking.technical(\upbow).kind    -> technical
    // >>> Marking.technical(\downbow).value -> downbow
    *technical { |value|
        ^super.newCopyArgs(\technical,
            this.prCheck(value, technicalMarks, "technical mark"), nil)
    }

    // The technical mark a written suffix names, or nil. No short spellings.
    //
    // >>> Marking.technicalNamed("upbow")   -> upbow
    // >>> Marking.technicalNamed("nope")    -> nil
    *technicalNamed { |name|
        var word = name.asString.asSymbol;
        ^if (technicalMarks.includes(word)) { word } { nil }
    }

    // >>> Marking.technicalSuffixes
    // [ upbow, downbow, stopped, snapPizzicato, openString, harmonic ]
    *technicalSuffixes { ^technicalMarks.collect { |each| each.asString } }

    // Free text: a technique instruction.
    //
    // >>> Marking.text("sul pont.").placement           -> above
    // >>> Marking.text("sul pont.", \below).placement   -> below
    *text { |value, placement = \above|
        ^super.newCopyArgs(\text, this.checkedText(value),
            this.checkedPlacement(placement))
    }

    // The vocabulary for a kind, or nil for text.
    //
    // >>> Marking.vocabularyFor(\dynamic).size   -> 12
    // >>> Marking.vocabularyFor(\text)           -> nil
    *vocabularyFor { |kind|
        if (kind == \dynamic) { ^dynamics };
        if (kind == \articulation) { ^articulations };
        if (kind == \sforzando) { ^sforzandoLevels };
        if (kind == \technical) { ^technicalMarks };
        ^nil
    }

    // >>> Marking.of(\dynamic, \mf).isDynamic              -> true
    // >>> Marking.of(\text, "sul pont.", \below).placement -> below
    *of { |kind, value, placement|
        if (kind == \dynamic)      { ^this.dynamic(value)      };
        if (kind == \articulation) { ^this.articulation(value) };
        if (kind == \sforzando)    { ^this.sforzando(value)    };
        if (kind == \technical)    { ^this.technical(value)    };
        if (kind == \text) { ^this.text(value, placement ? \above) };
        Error("Marking: \"%\" is not a marking kind. Use dynamic, sforzando, "
            "articulation, technical or text.".format(kind)).throw
    }

    // Prose must be non-empty String content a document can carry.
    // Public because `Spanner` and `Direction` ask the same question.
    //
    // >>> Marking.checkedText("sul pont.")   -> sul pont.
    *checkedText { |value|
        if (value.isNil) {
            Error("Marking: text needs a String.").throw
        };
        if (value.isKindOf(String).not) {
            Error("Marking: text must be a String, got a %.".format(
                value.class)).throw
        };
        if (value.stripWhiteSpace.isEmpty) {
            Error("Marking: text cannot be empty or only whitespace.").throw
        };
        // Tab, newline and return are the only control characters XML allows,
        // so the rest would make a document no reader will take.
        value.do { |char|
            var code = char.ascii;
            if (code >= 0 and: { code < 32 }
                and: { [Char.nl, Char.ret, Char.tab].includes(char).not }) {
                Error("Marking: text contains control character %. Only tab, "
                    "newline and return are writable.".format(code)).throw
            }
        };
        ^value.copy
    }

    // >>> Marking.checkedPlacement(\above)   -> above
    *checkedPlacement { |placement|
        if (placement.isNil) {
            Error("Marking: text needs a placement. Use one of %.".format(
                placements)).throw
        };
        if (placements.includes(placement.asSymbol).not) {
            Error("Marking: \"%\" is not a placement. Use one of %.".format(
                placement, placements)).throw
        };
        ^placement.asSymbol
    }

    *prArticle { |word| ^if ("aeiou".includes(word.asString.first)) { "an" } { "a" } }

    *prCheck { |value, vocabulary, what|
        if (value.isNil) {
            Error("Marking: % % needs a value. Use one of %.".format(
                this.prArticle(what), what, vocabulary)).throw
        };
        if (vocabulary.includes(value.asSymbol).not) {
            Error("Marking: \"%\" is not % %. Use one of %.".format(
                value, this.prArticle(what), what, vocabulary)).throw
        };
        ^value.asSymbol
    }

    // >>> Marking.dynamic(\ff).isDynamic             -> true
    // >>> Marking.articulation(\staccato).value      -> staccato
    // >>> Marking.text("sul pont.").isText           -> true
    isDynamic { ^kind == \dynamic }
    isSforzando { ^kind == \sforzando }
    isArticulation { ^kind == \articulation }
    isTechnical { ^kind == \technical }
    isText { ^kind == \text }

    // Placement is part of text identity.
    //
    // >>> Marking.text("sul pont.") == Marking.text("sul pont.", \below)   -> false
    // >>> Marking.dynamic(\mf).hash == Marking.dynamic(\mf).hash           -> true
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
