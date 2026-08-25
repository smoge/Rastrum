// Note [Refuse at the constructor]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `noteNames` and `accidentals` are closed and checked where a pitch
// is built, so an invalid one can't be carried. Writers can then
// branch only for their own narrower policies.

// MusicPitch: spelled pitch, as letter + accidental + octave + cents.
//
// Named MusicPitch because `Pitch` is already a core UGen.
//
// Not a midinote: spelling has to survive a round trip. `alter` is
// exact semitones. `cents` is residual deviation. Exact semitones use
// `Duration`, this file names them as pitch distance. No output
// syntax here. Writers spell these facts for their formats.
//
//   step:   0..6 for c d e f g a b
//   alter:  semitones, exact (1 = sharp, 1/2 = quarter sharp, -1 = flat)
//   octave: scientific pitch notation, 4 = the octave of middle C
//
// Alteration is bounded to the portable writer grid. Changing it also
// changes ScoreJSON.
//
// `accidentals` names that grid for input. Storage stays the four facts above.
MusicPitch {
    var <step, <alter, <octave, <cents;

    classvar <stepSemitones, <sharpMap, <flatMap, <noteNames, <accidentals;
    classvar compactAccidentals;

    // An alteration, in exact semitones. `semitones(1)` is a sharp;
    // `semitones(-3, 2)` is three quarter tones flat.
    //
    // >>> MusicPitch.semitones(-3, 2)   -> Duration(-3/2)
    *semitones { |count, per = 1|
        if (per == 1) { ^Duration.asExactValue(count, "an alteration") };
        ^Duration(count, per)
    }

    // Two quarter tones to a semitone.
    *quarterStepsPerSemitone { ^2 }

    // A double sharp, and the far end of the portable accidental grid.
    *maxAlteration { ^this.semitones(2) }

    *initClass {
        stepSemitones = [0, 2, 4, 5, 7, 9, 11];

        noteNames = [\c, \d, \e, \f, \g, \a, \b];

        // Each pitch class maps to [step, alteration in semitones].
        sharpMap = [
            [0,0], [0,1], [1,0], [1,1], [2,0], [3,0],
            [3,1], [4,0], [4,1], [5,0], [5,1], [6,0]
        ];
        flatMap = [
            [0,0], [1,-1], [1,0], [2,-1], [2,0], [3,0],
            [4,-1], [4,0], [5,-1], [5,0], [6,-1], [6,0]
        ];

        // The admitted grid: quarter-tone steps from double flat to double sharp.
        accidentals = (
            doubleFlat:        this.semitones(-2),
            threeQuarterFlat:  this.semitones(-3, 2),
            flat:              this.semitones(-1),
            quarterFlat:       this.semitones(-1, 2),
            natural:           this.semitones(0),
            quarterSharp:      this.semitones(1, 2),
            sharp:             this.semitones(1),
            threeQuarterSharp: this.semitones(3, 2),
            doubleSharp:       this.semitones(2)
        );

        // Compact spelling tokens, preferred forms first and aliases
        // after them. Brackets carry absolute octaves, so a
        // minus-sign accidental and a negative octave never compete
        // for the same character.
        //
        // >>> MusicPitch("c+").accidental       -> quarterSharp
        // >>> MusicPitch("d-").accidental       -> quarterFlat
        // >>> MusicPitch("eb-").accidental      -> threeQuarterFlat
        // >>> MusicPitch("f#+").accidental      -> threeQuarterSharp
        // >>> MusicPitch("Gtqf").accidental     -> threeQuarterFlat
        // >>> MusicPitch("c-'").octave          -> 5
        // >>> MusicPitch("eb-,").octave         -> 3
        // >>> MusicPitch("c-[6]").octave        -> 6
        // >>> ["cbb", "cb-", "cb", "c-", "c", "c+", "c#", "c#+", "c##", "ctqf", "cqf", "cqs", "ctqs"].collect { |text| MusicPitch(text).accidental } == [\doubleFlat, \threeQuarterFlat, \flat, \quarterFlat, \natural, \quarterSharp, \sharp, \threeQuarterSharp, \doubleSharp, \threeQuarterFlat, \quarterFlat, \quarterSharp, \threeQuarterSharp]
        // true
        compactAccidentals = [
            ["bb",  \doubleFlat],
            ["b-",  \threeQuarterFlat],
            ["b",   \flat],
            ["-",   \quarterFlat],
            ["",    \natural],
            ["+",   \quarterSharp],
            ["#",   \sharp],
            ["#+",  \threeQuarterSharp],
            ["##",  \doubleSharp],
            ["tqf", \threeQuarterFlat],
            ["qf",  \quarterFlat],
            ["qs",  \quarterSharp],
            ["tqs", \threeQuarterSharp]
        ];
    }

    // Omitted alteration defaults to `\natural`; nil means a bad expression.
    //
    // Two spellings of one key are two pitches, and that is the point
    // of the class. A midinote can't tell them apart, and a score
    // must:
    //
    // >>> MusicPitch(\c, \sharp) == MusicPitch(\d, \flat)   -> false
    // >>> MusicPitch(\c, \sharp).midinote == MusicPitch(\d, \flat).midinote
    // true
    //
    // A String is the one token a part writes, by
    // Note [A spelling is a String, a name is a Symbol] below.
    //
    // >>> MusicPitch("c#'") == MusicPitch(\c, \sharp, 5)   -> true
    *new { |step = 0, alter = \natural, octave = 4, cents = 0|
        if (step.isKindOf(MusicPitch)) { ^step };
        if (step.isKindOf(String)) {
            ^this.prFromCompact(step, alter, octave, cents)
        };
        if (step.isKindOf(Symbol)) { ^this.named(step, alter, octave, cents) };
        if (step.isKindOf(String).not and: { step.isSequenceableCollection }) {
            if (alter != \natural) {
                Error("MusicPitch: a pitch spec such as [\\c, \\sharp, 4] "
                    "already includes its accidental and octave.").throw
            };
            ^this.prFromNamedSpec(step)
        };
        ^super.newCopyArgs(
            this.checkedStep(step),
            this.checkedAlteration(alter),
            this.checkedOctave(octave),
            this.checkedCents(cents))
    }

    // The same pitch, named: `MusicPitch.named(\c, \sharp, 4)`.
    //
    // Both vocabularies are closed, so a misspelling is an error
    // where it was written. See Note [Refuse at the constructor]
    // above.
    *named { |noteName, accidental = \natural, octave = 4, cents = 0|
        var step, alteration;
        if (noteName.isKindOf(Symbol).not
            and: { noteName.isKindOf(String).not }) {
            Error("MusicPitch: \"%\" is not a note name. Use one of %, or a "
                "compact spelling such as \"c#'\".".format(noteName, noteNames)).throw
        };
        if (accidental.isNil) { this.checkedAlteration(nil) };   // says why nil
        if (accidental.isKindOf(Symbol).not and: { accidental.isKindOf(String).not }) {
            Error("MusicPitch: \"%\" is not an accidental. The accidentals are "
                "%.".format(accidental, this.accidentalNames)).throw
        };
        step = noteNames.indexOf(noteName.asSymbol);
        alteration = accidentals[accidental.asSymbol];
        if (step.isNil) {
            Error("MusicPitch: \"%\" is not a note name. Use one of %, or a "
                "compact spelling such as \"c#'\".".format(noteName, noteNames)).throw
        };
        if (alteration.isNil) {
            Error("MusicPitch: \"%\" is not an accidental. The accidentals are "
                "%.".format(accidental, this.accidentalNames)).throw
        };
        ^this.new(step, alteration, octave, cents)
    }

    // Note [A spelling is a String, a name is a Symbol]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `MusicPitch(\c)` is the house spelling. A String is the compact
    // token a part writes: `MusicPitch("c#'")` against
    // `MusicPitch(\c, \sharp, 5)`.
    //
    // Strict on purpose. The letter is case-insensitive; the
    // accidental must be one of `compactAccidentals` or absent.

    // [step, accidental or nil, octave or nil]. nil marks what the
    // spelling left open for constructor arguments.
    *prParseCompact { |string|
        // Stripped, as every other parser here strips.
        var text = string.asString.stripWhiteSpace;
        var step, body, bracketAt, accidental, octave, found;
        if (text.isEmpty) { ^this.prRefuseCompact(string) };
        step = noteNames.indexOf(text[0].toLower.asSymbol);
        if (step.isNil) { ^this.prRefuseCompact(string) };

        body = text.copyRange(1, text.size - 1);
        bracketAt = text.indexOf($[);
        if (bracketAt.notNil) {
            if (text[text.size - 1] != $]) { ^this.prRefuseCompact(string) };
            octave = this.prParseOctaveText(
                text.copyRange(bracketAt + 1, text.size - 2), string);
            body = text.copyRange(1, bracketAt - 1);
        } {
            if (text.includes($])) { ^this.prRefuseCompact(string) };
            octave = this.prParseRegisterMarks(body, string);
            if (octave.notNil) {
                body = body.copyRange(0, body.size - 1 - octave[1]);
                octave = octave[0];
            };
        };
        accidental = body;
        found = compactAccidentals.detect { |pair| pair[0] == accidental };
        if (found.isNil) { ^this.prRefuseCompact(string) };
        ^[step,
          if (accidental.isEmpty) { nil } { found[1] },
          octave]
    }

    *prParseOctaveText { |text, whole|
        var at = 0, sign = 1, value = 0;
        if (text.isEmpty) { ^this.prRefuseCompact(whole) };
        if (text[0] == $-) {
            sign = -1;
            at = 1;
            if (at >= text.size) { ^this.prRefuseCompact(whole) };
        };
        while { at < text.size } {
            if (text[at].isDecDigit.not) { ^this.prRefuseCompact(whole) };
            value = (value * 10) + (text[at].ascii - $0.ascii);
            at = at + 1;
        };
        ^sign * value
    }

    *prParseRegisterMarks { |body, whole|
        var at = body.size, count = 0, kind;
        while { at > 0 and: {
            (body[at - 1] == RastrumChar.singleQuote) or: { body[at - 1] == Char.comma }
        } } {
            kind = kind ? body[at - 1];
            if (body[at - 1] != kind) {
                Error("MusicPitch: \"%\" mixes octave-up and octave-down marks. "
                    "Use only one kind.".format(whole)).throw
            };
            count = count + 1;
            at = at - 1;
        };
        if (count == 0) { ^nil };
        if (kind == $,) { ^[4 - count, count] };
        ^[4 + count, count]
    }

    *prRefuseCompact { |string|
        Error("MusicPitch: \"%\" is not a note name or compact pitch. Use a "
            "letter, optional accidental from %, and optional octave, e.g. "
            "\"c\", \"c'\", \"d-\", \"f#+\" or \"c[-1]\".".format(
                string,
                compactAccidentals.collect { |pair| pair[0] }
                    .reject { |text| text.isEmpty })).throw
    }

    *prFromCompact { |string, alter, octave, cents|
        var parsed = this.prParseCompact(string);
        // A compact spelling may carry accidental or octave. A
        // separate argument there would be a second answer.
        if (parsed[1].notNil and: { alter != \natural }) {
            Error("MusicPitch: \"%\" already includes an accidental. Do not also "
                "pass %.".format(
                    string, alter)).throw
        };
        if (parsed[2].notNil and: { octave != 4 }) {
            Error("MusicPitch: \"%\" already includes an octave. Do not also "
                "pass %.".format(
                    string, octave)).throw
        };
        ^this.new(parsed[0], parsed[1] ? alter, parsed[2] ? octave, cents)
    }

    // A neutral pitch spec, accepted by notes, chords and RTM pitch
    // streams: a MusicPitch, a midinote number, a note-name Symbol, a
    // compact spelling such as "c#'", or [noteName, octave] /
    // [noteName, accidental, octave, cents].
    //
    // >>> MusicPitch.fromSpec([\c, \sharp, 4]) == MusicPitch(\c, \sharp)
    // true
    *fromSpec { |spec|
        if (spec.isKindOf(MusicPitch)) { ^spec };
        if (spec.isNumber) { ^this.fromMidinote(spec) };
        // Symbol names a note; String is a whole spelling.
        if (spec.isKindOf(String)) { ^this.new(spec) };
        if (spec.isKindOf(Symbol)) { ^this.named(spec) };
        if (spec.isKindOf(String).not and: { spec.isSequenceableCollection }) {
            ^this.prFromNamedSpec(spec)
        };
        Error("MusicPitch: % is not a pitch. Use a MusicPitch, midinote, "
            "note name like \\c, spelling like \"c#'\", or "
            "[noteName, octave] / [noteName, accidental, octave, cents]."
            .format(spec)).throw
    }

    // Many pitches, as `fromSpec` is one: an Array of specs, a space-separated
    // String run, or one scalar spec taken as a run of one.
    //
    // >>> MusicPitch.asPitches("c e g").size   -> 3
    *asPitches { |value|
        var tokens;
        if (value.isKindOf(String).not) {
            ^(value ? []).asArray.collect { |each| this.fromSpec(each) }
        };
        tokens = value.split($ ).reject { |token| token.stripWhiteSpace.isEmpty };
        if (tokens.isEmpty) {
            Error("MusicPitch.asPitches: \"%\" contains no pitch tokens. Use "
                "spaces, e.g. \"c e g\".".format(value)).throw
        };
        ^tokens.collect { |token| this.new(token.stripWhiteSpace) }
    }

    *prFromNamedSpec { |spec|
        var accidental = \natural, octave = 4, cents = 0;
        if (spec.size < 1 or: { spec.size > 4 }) {
            Error("MusicPitch: a named pitch spec is [noteName, octave] or "
                "[noteName, accidental, octave, cents], got %.".format(spec)).throw
        };
        if (spec.size == 2 and: { spec[1].isKindOf(Integer) }) {
            octave = spec[1]
        } {
            if (spec.size > 1) { accidental = spec[1] };
            if (spec.size > 2) { octave = spec[2] };
            if (spec.size > 3) { cents = spec[3] };
        };
        ^this.named(spec[0], accidental, octave, cents)
    }

    // In grid order, flattest first, so the list reads as the row it is.
    // >>> MusicPitch.accidentalNames.size   -> 9
    *accidentalNames {
        ^accidentals.keys.asArray.sort { |a, b| accidentals[a] < accidentals[b] }
    }

    // Compact accidental tokens, preferred forms first and aliases after them.
    //
    // >>> MusicPitch.compactAccidentals.collect { |pair| pair[0] } == ["bb", "b-", "b", "-", "", "+", "#", "#+", "##", "tqf", "qf", "qs", "tqs"]
    // true
    // >>> MusicPitch.compactAccidentals.collect { |pair| pair[1] } == [\doubleFlat, \threeQuarterFlat, \flat, \quarterFlat, \natural, \quarterSharp, \sharp, \threeQuarterSharp, \doubleSharp, \threeQuarterFlat, \quarterFlat, \quarterSharp, \threeQuarterSharp]
    // true
    // >>> MusicPitch.compactAccidentals.collect { |pair| MusicPitch("c" ++ pair[0]).accidental } == MusicPitch.compactAccidentals.collect { |pair| pair[1] }
    // true
    // >>> MusicPitch.compactAccidentals.collect { |pair| MusicPitch("c" ++ pair[0] ++ "'").octave }.every { |octave| octave == 5 }
    // true
    // >>> MusicPitch.compactAccidentals.collect { |pair| MusicPitch("c" ++ pair[0] ++ "[5]").octave }.every { |octave| octave == 5 }
    // true
    *compactAccidentals {
        ^compactAccidentals.collect { |pair| pair.copy }
    }

    // Answers the alteration, if it is one this project admits. Named
    // or counted: `\quarterSharp` and `semitones(1, 2)` are the same
    // alteration. Two questions of a counted one: is it on the
    // quarter-tone grid, and is it inside the double flat to double
    // sharp range. A third of a semitone fails the first, a triple
    // sharp the second, and for the same reason.
    *checkedAlteration { |value|
        var alteration, quarters;
        if (value.isNil) {
            Error("MusicPitch: nil is not an alteration. Omit the argument for natural.").throw
        };
        if (value.isKindOf(Symbol) or: { value.isKindOf(String) }) {
            ^accidentals[value.asSymbol] ?? {
                Error("MusicPitch: \"%\" is not an accidental. The accidentals "
                    "are %.".format(value, this.accidentalNames)).throw
            }
        };
        alteration = Duration.asDuration(value);
        quarters = alteration * MusicPitch.quarterStepsPerSemitone;
        if (quarters.denominator != 1) {
            Error("MusicPitch: alteration % is not on the quarter-tone grid. "
                "Use one of %.".format(alteration, this.accidentalNames)).throw
        };
        if (alteration.abs > MusicPitch.maxAlteration) {
            Error("MusicPitch: alteration % is outside the double-flat to "
                "double-sharp range.".format(alteration)).throw
        };
        ^alteration
    }

    *checkedStep { |value|
        if (value.isKindOf(Integer).not or: { value < 0 } or: { value > 6 }) {
            Error("MusicPitch: step % is not a diatonic degree. Use 0 to 6 for %.".format(value, noteNames)).throw
        };
        ^value
    }

    *checkedOctave { |value|
        if (value.isKindOf(Integer).not) {
            Error("MusicPitch: octave % must be an integer.".format(value)).throw
        };
        ^value
    }

    // Residual deviation, in cents, that no accidental notates.
    // Always a Float, so equal pitches serialize the same way however
    // they were built. Time stays exact; cents is a measurement.
    //
    // >>> MusicPitch(\c).cents   -> 0.0
    *checkedCents { |value|
        if (value.isNumber.not) {
            Error("MusicPitch: cents must be a number, got %.".format(value)).throw
        };
        if ((value.abs < inf).not) {
            Error("MusicPitch: cents must be finite, got %.".format(value)).throw
        };
        ^value.asFloat
    }

    // Answers a pitch for this midinote, spelled with sharps or with
    // flats. Neither spelling is more correct. A midinote carries no
    // key context. Sharps raise the letter below the pitch; flats
    // lower the letter above it. That keeps quarter-tone spellings
    // consistent too.
    //
    // `quantum` is the snap grid. `cents` carries what the snap or the
    // quarter-tone accidental grid cannot spell.
    //
    // >>> MusicPitch.fromMidinote(61).accidental            -> sharp
    // >>> MusicPitch.fromMidinote(61, \flats).accidental    -> flat
    // >>> MusicPitch.fromMidinote(61, \flats).letter        -> d
    // >>> MusicPitch.fromMidinote(60.5).accidental          -> quarterSharp
    // >>> MusicPitch.fromMidinote(60.25).cents              -> -25.0
    // >>> MusicPitch.fromMidinote(60.25, 0.25).accidental   -> quarterSharp
    // >>> MusicPitch.fromMidinote(60.25, 0.25).cents        -> -25.0
    *fromMidinote { |midinote, spelling = \sharps, quantum = 0.5|
        var snapped, quarters, semis, steps, spec, alter;

        // A numeric second argument is the quantum: `fromMidinote(60.5, 1)`.
        if (spelling.isNumber) { quantum = spelling; spelling = \sharps };

        snapped = (midinote / quantum).round * quantum;

        // Accidentals spell quarter-tone steps; cents carries the rest.
        quarters = (snapped * this.quarterStepsPerSemitone).round.asInteger;
        semis = quarters div: this.quarterStepsPerSemitone;
        steps = quarters % this.quarterStepsPerSemitone;

        // Flats spell downward from the note above.
        if (spelling == \flats and: { steps > 0 }) {
            semis = semis + 1;
            steps = steps - this.quarterStepsPerSemitone;
        };

        spec = this.spellingMap(spelling)[semis % 12];
        alter = MusicPitch.semitones(spec[1])
            + MusicPitch.semitones(steps, this.quarterStepsPerSemitone);
        ^this.new(spec[0], alter, (semis div: 12) - 1,
            (midinote - (quarters / this.quarterStepsPerSemitone)) * 100)
    }

    *spellingMap { |spelling|
        if (spelling == \sharps) { ^sharpMap };
        if (spelling == \flats) { ^flatMap };
        Error("MusicPitch: \"%\" is not a spelling. Use \\sharps or \\flats."
            .format(
                spelling)).throw
    }

    // A Float, and the one place the exactness is spent: it leaves
    // the model for a synth, where a key number is what is wanted.
    //
    // >>> MusicPitch(\c, \sharp).midinote          -> 61.0
    // >>> MusicPitch(\c, \quarterSharp).midinote   -> 60.5
    // >>> MusicPitch(\b, \natural, 3).midinote     -> 59.0
    midinote {
        ^((octave + 1) * 12) + stepSemitones[step] + alter.asFloat + (cents / 100)
    }

    // This pitch's letter, as a Char, and neutral between notations:
    // LilyPond lowercases it, MusicXML uppercases it.
    //
    // >>> MusicPitch(\e, \flat, 3).letter   -> e
    letter { ^"cdefgab"[step] }

    // Answers the name of this pitch's accidental, which every
    // admitted alteration has.
    //
    // >>> MusicPitch(\c, \threeQuarterFlat).accidental   -> threeQuarterFlat
    accidental {
        MusicPitch.accidentalNames.do { |name|
            if (MusicPitch.accidentals[name] == alter) { ^name }
        };
        Error("MusicPitch: internal error, no accidental name for %.".format(
            alter)).throw
    }

    // Alteration in quarter-tone units: -4 .. +4 for a double flat .. double
    // sharp. Exact, never rounded.
    //
    // >>> MusicPitch(\c).alterationSteps                 -> 0
    // >>> MusicPitch(\c, \quarterSharp).alterationSteps  -> 1
    // >>> MusicPitch(\c, \doubleSharp).alterationSteps   -> 4
    alterationSteps {
        var quarters = alter * MusicPitch.quarterStepsPerSemitone;
        if (quarters.denominator != 1) {
            Error("MusicPitch: alteration % is not a whole number of quarter "
                "tones.".format(
                alter)).throw
        };
        ^quarters.numerator
    }

    // This pitch moved by a spelled interval. The interval carries the target
    // letter, so the result is spelled rather than chosen: see
    // Note [A height offset is not an interval] in MusicInterval.sc.
    //
    // >>> MusicPitch(\c).transposeBy(MusicInterval.between(MusicPitch(\c), MusicPitch(\e))).letter
    // e
    transposeBy { |interval| ^interval.transpose(this) }

    // Affine arithmetic. A pitch minus a pitch is an interval; a pitch plus an
    // interval is a pitch. Two pitches cannot be added.
    //
    // >>> (MusicPitch(\e) - MusicPitch(\c)).signedName.qualityName   -> major
    // >>> (MusicPitch(\c) + MusicInterval.named(\perfect, 5)).letter   -> g
    + { |that|
        if (that.isKindOf(MusicInterval)) { ^that.transpose(this) };
        if (that.isKindOf(MusicPitch)) {
            Error("MusicPitch: cannot add two pitches. Subtract pitches for an "
                "interval, or add an interval to a pitch.").throw
        };
        Error("MusicPitch: cannot add % to a pitch. Use a MusicInterval.".format(that)).throw
    }

    // `b - a` is the interval from a to b, so the vector points the way it
    // reads. Subtracting an interval moves the other way instead.
    - { |that|
        if (that.isKindOf(MusicPitch)) { ^MusicInterval.between(that, this) };
        if (that.isKindOf(MusicInterval)) { ^that.negated.transpose(this) };
        Error("MusicPitch: cannot subtract % from a pitch. Use a MusicPitch or "
            "MusicInterval.".format(that)).throw
    }

    == { |that| ^that.isKindOf(MusicPitch) and: {
        (step == that.step) and: { (alter == that.alter) and: {
        (octave == that.octave) and: { cents == that.cents } } } } }
    hash { ^step.hash bitXor: alter.hash bitXor: octave.hash bitXor: cents.hash }

    // Canonical compact spelling: letter, accidental and bracketed
    // octave. `MusicPitch(p.spelling) == p`. The parser also accepts
    // aliases and register marks.
    //
    // Not `LilyWriter.pitchString`; LilyPond counts octaves differently.
    //
    // >>> MusicPitch(\c).spelling                    -> c[4]
    // >>> MusicPitch(\c, \sharp, 4).spelling         -> c#[4]
    // >>> MusicPitch(\c, \quarterFlat, 4).spelling   -> c-[4]
    spelling {
        var name = this.accidental;
        var token = compactAccidentals.detect { |pair| pair[1] == name };
        ^"" ++ this.letter ++ token[0] ++ "[" ++ octave ++ "]"
    }

    // Printed as the constructor that would build it. Cents are shown
    // only when present, so unequal pitches never print the same.
    //
    // >>> MusicPitch(\e, \flat, 3).asString  -> MusicPitch("eb[3]")
    // >>> MusicPitch.fromMidinote(60.25).asString
    // MusicPitch("c+[4]", cents: -25.0)
    printOn { |stream|
        stream << "MusicPitch(" << this.spelling.asCompileString;
        if (cents != 0) { stream << ", cents: " << cents };
        stream << ")"
    }
}
