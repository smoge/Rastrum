// Marking: a point fact on one leaf.
//
// A marking has a kind and a value. Text also has placement.
//
// Writers own spelling.
//
// Slurs and hairpins are `Spanner`s. They pair endpoints and need
// continuity checks.
//
// Dynamic, sforzando, articulation and technical values are closed
// vocabularies.
//
// Text checks prose and placement only.
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
        // See Note [A technical mark is not an articulation].
        technicalMarks = [\upbow, \downbow, \stopped, \snapPizzicato,
            \openString, \harmonic];
        placements = [\above, \below]; // Above or below the staff
        // See Note [A spelling is not a vocabulary word].
        articulationShortNames = [["stac", \staccato]];
        // See Note [A sforzando is an accent at a level].
        sforzandoLevels = [\mp, \mf, \f, \ff, \fff, \ffff];
        sforzandoSpellings = [
            ["smpz",   \mp  ],
            ["smfz",   \mf  ],
            ["sfz",    \f   ],
            ["sffz",   \ff  ],
            ["sfffz",  \fff ],
            ["sffffz", \ffff]
        ];
    }

    // Note [A sforzando is an accent at a level]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A sforzando names a level plus a sharp attack. The model stores
    // one kind with a level parameter:
    //
    //   smpz -> mp    smfz  -> mf    sfz    -> f
    //   sffz -> ff    sfffz -> fff   sffffz -> ffff
    //
    // The spelling lives here because input and output use the same
    // word. The family spans mp to ffff, where its written words run
    // out at both ends: nothing is spelled below smpz and
    // fffff names no attack. rfz is hairpin-shaped reinforcement over
    // time. sf and fz don't name a level here.

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
            Error(
                "Marking: % is not a sforzando level. Use one of %."
                .format(level, sforzandoLevels)
            ).throw
        }
    }

    // All written sforzando spellings.
    //
    // >>> Marking.sforzandoSuffixes
    // [ smpz, smfz, sfz, sffz, sfffz, sffffz ]
    *sforzandoSuffixes { ^sforzandoSpellings.collect { |pair| pair[0] } }

    // Note [A spelling is not a vocabulary word]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `articulations` is the model and wire vocabulary.
    // `articulationShortNames` is parser spelling only, like compact
    // accidentals and hairpin heads. Short names are abbreviations,
    // not synonyms. `\stac` isn't an articulation, so
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

    // All written articulation suffixes.
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
    // the sound is produced. They stay separate so `PlaybackMap`
    // doesn't need neutral rows for marks with no playback policy.
    // The kind is broad. Bowed-string marks are only the first
    // values. Staff names are labels, not an instrument-family
    // validation layer.

    // >>> Marking.technical(\upbow).kind    -> technical
    // >>> Marking.technical(\downbow).value -> downbow
    *technical { |value|
        ^super.newCopyArgs(\technical,
            this.prCheck(value, technicalMarks, "technical mark"), nil)
    }

    // The technical mark a written suffix names, or nil; no short spellings.
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

    // Note [Coincident marks of one kind have no order]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Two articulations on one leaf are one fact however they were
    // built, so a leaf stores them in vocabulary order and the two
    // build orders reach `ScoreDiff` and every writer as one value.
    // Which order wins doesn't matter, only that one wins.
    //
    // Not every kind. Text is prose in authored order. The loudness
    // slot isn't sorted at all: it holds at most one sforzando and
    // at most one dynamic, in the order `sfz:pp` writes them.
    //
    // See Note [One loudness slot on a leaf].
    //
    // What holds is the sequence of kinds: a mark of another kind
    // never moves, though a sorted value may land on the far side of
    // one.

    // The kinds whose coincident marks are ordered for the caller.
    //
    // >>> Marking.canonicalKinds   -> [ articulation, technical ]
    *canonicalKinds { ^[\articulation, \technical] }

    // One list ordered by kind vocabulary, leaving every other slot
    // where it was. Duplicates stay the caller's business.
    //
    // >>> Marking.canonicalOrder([Marking.articulation(\accent), Marking.articulation(\staccato)]).first.value   -> staccato
    // >>> Marking.canonicalOrder([Marking.dynamic(\f), Marking.dynamic(\p)]).first.value   -> f
    *canonicalOrder { |list|
        var out = (list ? []).asArray.copy;
        this.canonicalKinds.do { |kind|
            var vocabulary = this.vocabularyFor(kind);
            var at = [];
            out.do { |each, i|
                if (each.isKindOf(Marking) and: { each.kind == kind }) {
                    at = at.add(i)
                }
            };
            if (at.size > 1) {
                var sorted = at.collect { |i| out[i] }.sort { |a, b|
                    vocabulary.indexOf(a.value) <= vocabulary.indexOf(b.value)
                };
                at.do { |i, n| out[i] = sorted[n] }
            }
        };
        ^out
    }

    // Note [One loudness slot on a leaf]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A page has one glyph there, so a leaf carries at most one
    // dynamic and at most one sforzando. The one pair a score prints
    // is the compound `sfz:pp`, an attack and the level it settles
    // onto, so the sforzando comes first. The other order is refused,
    // not sorted. Written order is the question the compound answers.

    // The list back, or a refusal naming what a leaf may hold.
    //
    // >>> Marking.checkedLoudness([Marking.sforzando(\f)]).size   -> 1
    // >>> try { Marking.checkedLoudness([Marking.dynamic(\p), Marking.dynamic(\mf)]) } { \refused }   -> refused
    *checkedLoudness { |list|
        var issues = this.prLoudnessIssues(list);
        if (issues.notEmpty) { Error(issues.first[\message]).throw };
        ^list
    }

    // A refusal still throws prose. This method answers the same rule
    // as records, so a tool can key on `code`.
    //
    // `ScoreIssue` owns the record shape. `Marking` owns this rule.
    //
    // Private while the diagnostic lane is experimental.

    // A record per loudness mistake. Location is added by callers
    // that have source text or a score path.
    //
    // >>> Marking.prLoudnessIssues([Marking.sforzando(\f)]).size   -> 0
    // >>> Marking.prLoudnessIssues([Marking.dynamic(\p), Marking.dynamic(\mf)]).first[\code]   -> loudnessDynamicRepeated
    *prLoudnessIssues { |list|
        var marks = (list ? []).asArray.select { |each|
            each.isKindOf(Marking) and: {
                each.isDynamic or: { each.isSforzando } } };
        var levels = marks.select { |each| each.isDynamic };
        var attacks = marks.select { |each| each.isSforzando };
        var issues = [];
        if (levels.size > 1) {
            issues = issues.add(ScoreIssue.prOf(\loudnessDynamicRepeated,
                "Marking: a leaf carries at most one dynamic; this one "
                "has % and %.".format(levels[0].value, levels[1].value)))
        };
        if (attacks.size > 1) {
            issues = issues.add(ScoreIssue.prOf(\loudnessSforzandoRepeated,
                "Marking: a leaf carries at most one sforzando; this one "
                "has % and %.".format(
                    this.sforzandoSpelling(attacks[0].value),
                    this.sforzandoSpelling(attacks[1].value))))
        };
        if (attacks.notEmpty and: { levels.notEmpty }
            and: { marks.first.isDynamic }) {
            issues = issues.add(ScoreIssue.prOf(\loudnessOrderReversed,
                "Marking: a sforzando comes before its dynamic. Write "
                "\"%:%\" rather than \"%:%\".".format(
                    this.sforzandoSpelling(attacks[0].value), levels[0].value,
                    levels[0].value,
                    this.sforzandoSpelling(attacks[0].value))))
        };
        ^issues
    }

    // The kinds a marking may be. `vocabularyFor` answers what each admits,
    // except `text`, which is prose rather than a closed list.
    //
    // >>> Marking.kinds.includes(\dynamic)   -> true
    *kinds { ^[\dynamic, \sforzando, \articulation, \technical, \text] }

    // >>> Marking.of(\dynamic, \mf).isDynamic              -> true
    // >>> Marking.of(\text, "sul pont.", \below).placement -> below
    *of { |kind, value, placement|
        if (kind == \dynamic)      { ^this.dynamic(value)      };
        if (kind == \articulation) { ^this.articulation(value) };
        if (kind == \sforzando)    { ^this.sforzando(value)    };
        if (kind == \technical)    { ^this.technical(value)    };
        if (kind == \text) { ^this.text(value, placement ? \above) };
        Error(
            "Marking: \"%\" is not a marking kind. Use %."
            .format(kind, this.kinds.join(", "))
        ).throw
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
            Error(
                "Marking: text must be a String, got a %."
                .format(value.class)
            ).throw
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
                Error(
                    "Marking: text contains control character %. Only tab, newline and "
                    "return are writable."
                    .format(code)
                ).throw
            }
        };
        ^value.copy
    }

    // >>> Marking.checkedPlacement(\above)   -> above
    *checkedPlacement { |placement|
        if (placement.isNil) {
            Error(
                "Marking: text needs a placement. Use one of %."
                .format(placements)
            ).throw
        };
        if (placements.includes(placement.asSymbol).not) {
            Error(
                "Marking: \"%\" is not a placement. Use one of %."
                .format(placement, placements)
            ).throw
        };
        ^placement.asSymbol
    }

    *prArticle { |word| ^if ("aeiou".includes(word.asString.first)) { "an" } { "a" } }

    *prCheck { |value, vocabulary, what|
        if (value.isNil) {
            Error(
                "Marking: % % needs a value. Use one of %."
                .format(this.prArticle(what), what, vocabulary)
            ).throw
        };
        if (vocabulary.includes(value.asSymbol).not) {
            Error(
                "Marking: \"%\" is not % %. Use one of %."
                .format(value, this.prArticle(what), what, vocabulary)
            ).throw
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

    // Placement participates in text equality.
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

    // Source uses the named helper for the kind.
    //
    // >>> Marking.dynamic(\mf).asCompileString   -> Marking.dynamic(\mf)
    // >>> Marking.text("ordinario", \below).asCompileString
    // Marking.text("ordinario", \below)
    storeOn { |stream|
        stream << "Marking." << kind << "(";
        if (kind == \text) {
            stream << value.asCompileString << ", \\" << placement
        } {
            stream << "\\" << value
        };
        stream << ")"
    }
}
