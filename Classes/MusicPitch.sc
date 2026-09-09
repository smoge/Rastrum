// MusicPitch: spelled pitch as letter + accidental + octave + cents.
//
// Named MusicPitch because `Pitch` is already a core UGen.
//
// `noteNames` and `accidentals` are closed and checked where a pitch
// is built, so an invalid one can't be carried. Writers can then
// branch only for their own narrower policies.
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
    // step: Integer, alter: Duration, octave: Integer, cents: Float
    var <step, alter, <octave, <cents;

    // stepSemitones: [Integer], sharpMap: [[Integer]], flatMap: [[Integer]]
    // noteNames: [Symbol], accidentals: Event
    classvar <stepSemitones, <sharpMap, <flatMap, <noteNames, <accidentals;

    // compactAccidentals: [(String, Symbol)]
    classvar compactAccidentals;

    // An alteration in exact semitones. `semitones(1)` is a sharp.
    // `semitones(-3, 2)` is three quarter tones flat.
    //
    // >>> MusicPitch.semitones(-3, 2)   -> Duration(-3/2)
    //
    // ^ Duration
    *semitones { |count, per = 1|
        if (per == 1) { ^Duration.asExactValue(count, "an alteration") };
        ^Duration(count, per)
    }

    // Two quarter tones to a semitone.
    //
    // ^ Integer
    *quarterStepsPerSemitone { ^2 }

    // Note [A semitone is not a length]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Exact semitones are carried in a `Duration` because that is the
    // only exact-rational wrapper this quark owns. That's a carrier,
    // not a claim that an alteration is a length, so no alteration or
    // interval-chromatic accessor hands one out: `alter` here and
    // `chromatic` on `MusicInterval` both answer plain numbers. The
    // carrier stays behind `prAlter` and `prChromatic`.
    //
    // `height` is the exception. It stays a `Duration`, the ordering
    // key the quark sorts and compares pitches by. Its public view is
    // `number`, which answers the same exact value as a Rational.
    //
    // Whole semitones answer an Integer, not `n%/1`, because that's
    // what a reader writes.

    // The three exact forms and nothing else. `asExactValue` would
    // also read a ratio String and a Float. Both are wrong here for
    // the same reason: a String is a second spelling that hands the
    // carrier back through another door. A Float is not exact, which
    // is the one thing a semitone count has to be.
    //
    // See Note [A Float is not an exact duration] in Duration.sc.
    //
    // >>> MusicPitch.semitoneNumber(MusicPitch.semitones(1))      -> 1
    // >>> MusicPitch.semitoneNumber(MusicPitch.semitones(1, 2))   -> 1%/2
    // >>> MusicPitch.semitoneNumber(3)                            -> 3
    // value: Duration | Integer | Rational
    //
    // ^ Integer | Rational
    *semitoneNumber { |value|
        var exact;
        if (value.isKindOf(Duration).not and: { value.isKindOf(Integer).not }
            and: { value.isKindOf(Rational).not }) {
            Error(
                "MusicPitch: % is not a semitone count. Write an exact number, such as "
                "1 or 1%/2."
                .format(value.asCompileString)
            ).throw
        };
        exact = Duration.asExactValue(value, "a semitone value");
        if (exact.denominator == 1) { ^exact.numerator };
        ^Rational(exact.numerator, exact.denominator)
    }

    // A double sharp and the far end of the portable accidental grid.
    //
    // ^ Duration
    *maxAlteration { ^this.semitones(2) }

    // ^ Self
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
        // >>> MusicPitch("cbb").accidental      -> doubleFlat
        // >>> MusicPitch("ctqs").accidental     -> threeQuarterSharp
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

    // Omitted alteration defaults to `\natural`. Nil means a bad expression.
    //
    // Two spellings of one key are two pitches. That is the point of
    // the class. A midinote can't tell them apart. A score must:
    //
    // >>> MusicPitch(\c, \sharp) == MusicPitch(\d, \flat)   -> false
    // >>> MusicPitch(\c, \sharp).midinote == MusicPitch(\d, \flat).midinote
    // true
    //
    // A String is the one token a part writes.
    // See Note [A spelling is a String, a name is a Symbol].
    //
    // >>> MusicPitch("c#'") == MusicPitch(\c, \sharp, 5)   -> true
    //
    // step: MusicPitch | Integer | SequenceableCollection | String | Symbol
    // ^ Self
    *new { |step = 0, alter = \natural, octave = 4, cents = 0|
        if (step.isKindOf(MusicPitch)) { ^step };
        if (step.isKindOf(String)) {
            ^this.prFromCompact(step, alter, octave, cents)
        };
        if (step.isKindOf(Symbol)) { ^this.named(step, alter, octave, cents) };
        if (step.isKindOf(String).not and: { step.isSequenceableCollection }) {
            if (alter != \natural) {
                Error(
                    "MusicPitch: a pitch spec such as [\\c, \\sharp, 4] already "
                    "includes its accidental and octave."
                ).throw
            };
            ^this.prFromNamedSpec(step)
        };
        ^super.newCopyArgs(
            this.checkedStep(step),
            this.checkedAlteration(alter),
            this.checkedOctave(octave),
            this.checkedCents(cents))
    }

    // The same pitch, named: `MusicPitch.named(\c, \sharp, 4)`
    //
    // Both vocabularies are closed, so a misspelling is an error where it was
    // written.
    //
    // ^ Self
    *named { |noteName, accidental = \natural, octave = 4, cents = 0|
        var step, alteration;

        if (noteName.isKindOf(Symbol).not and: { noteName.isKindOf(String).not }) {
            Error(
                "MusicPitch: \"%\" is not a note name. Use one of %, or a compact "
                "spelling such as \"c#'\".".format(noteName, noteNames)
            ).throw
        };

        if (accidental.isNil) { this.checkedAlteration(nil) };   // says why nil

        if (accidental.isKindOf(Symbol).not and: { accidental.isKindOf(String).not }) {
            Error(
                "MusicPitch: \"%\" is not an accidental. The accidentals are %.".format(accidental, this.accidentalNames)
            ).throw
        };

        step = noteNames.indexOf(noteName.asSymbol);
        alteration = accidentals[accidental.asSymbol];

        if (step.isNil) {
            Error(
                "MusicPitch: \"%\" is not a note name. Use one of %, or a compact "
                "spelling such as \"c#'\".".format(noteName, noteNames)
            ).throw
        };

        if (alteration.isNil) {
            Error(
                "MusicPitch: \"%\" is not an accidental. The accidentals are %.".format(accidental, this.accidentalNames)
            ).throw
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
    // Strict on purpose. The letter is case-insensitive. The
    // accidental must be one of `compactAccidentals` or absent.

    // [step, accidental or nil, octave or nil]. `nil` marks what the
    // spelling left open for constructor arguments.
    //
    // ^ (Integer, Symbol | Nil, Integer | Nil)
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
            octave = this.prParseOctaveText(text.copyRange(bracketAt + 1, text.size - 2), string);
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

    // ^ Integer
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

    // ^ (Integer, Integer) | Nil
    *prParseRegisterMarks { |body, whole|
        var at = body.size, count = 0, kind;
        while { at > 0 and: {
            (body[at - 1] == RastrumChar.singleQuote) or: { body[at - 1] == Char.comma }
        } } {
            kind = kind ? body[at - 1];
            if (body[at - 1] != kind) {
                Error(
                    "MusicPitch: \"%\" mixes octave-up and octave-down marks. Use only one kind.".format(whole)
                ).throw
            };
            count = count + 1;
            at = at - 1;
        };
        if (count == 0) { ^nil };
        if (kind == $,) { ^[4 - count, count] };
        ^[4 + count, count]
    }

    // ^ Never
    *prRefuseCompact { |string|
        Error(
            "MusicPitch: \"%\" is not a note name or compact pitch. Use a letter, "
            "optional accidental from %, and optional octave, e.g. \"c\", \"c'\", "
            "\"d-\", \"f#+\" or \"c[-1]\"."
            .format(
                string,
                compactAccidentals.collect { |pair| pair[0] }
                .reject { |text| text.isEmpty }
            )
        ).throw
    }

    // ^ Self
    *prFromCompact { |string, alter, octave, cents|
        var parsed = this.prParseCompact(string);
        // A compact spelling may carry accidental or octave. A
        // separate argument there would be a second answer.
        if (parsed[1].notNil and: { alter != \natural }) {
            Error(
                "MusicPitch: \"%\" already includes an accidental. Do not also pass %.".format(string, alter)
            ).throw
        };
        if (parsed[2].notNil and: { octave != 4 }) {
            Error(
                "MusicPitch: \"%\" already includes an octave. Do not also pass %.".format(string, octave)
            ).throw
        };
        ^this.new(parsed[0], parsed[1] ? alter, parsed[2] ? octave, cents)
    }

    // A neutral pitch spec accepted by notes, chords and RTM pitch
    // streams: a MusicPitch, a midinote number, a note-name Symbol, a
    // compact spelling such as "c#'" or [noteName, octave] /
    // [noteName, accidental, octave, cents].
    //
    // >>> MusicPitch.fromSpec([\c, \sharp, 4]) == MusicPitch(\c, \sharp)
    // true
    //
    // ^ Self
    *fromSpec { |spec|
        if (spec.isKindOf(MusicPitch)) { ^spec };
        if (spec.isNumber) { ^this.fromMidinote(spec) };
        // Symbol names a note. String is a whole spelling.
        if (spec.isKindOf(String)) { ^this.new(spec) };
        if (spec.isKindOf(Symbol)) { ^this.named(spec) };
        if (spec.isKindOf(String).not and: { spec.isSequenceableCollection }) {
            ^this.prFromNamedSpec(spec)
        };
        Error(
            "MusicPitch: % is not a pitch. Use a MusicPitch, midinote, note name like "
            "\\c, spelling like \"c#'\", or [noteName, octave] / [noteName, accidental, "
            "octave, cents].".format(spec)
        ).throw
    }

    // Many pitches, as `fromSpec` is one: an Array of specs, a
    // space-separated String run or one scalar spec taken as a run of one
    //
    // >>> MusicPitch.asPitches("c e g").size   -> 3
    //
    // ^ [MusicPitch]
    *asPitches { |value|
        var tokens;

        if (value.isKindOf(String).not) {
            ^(value ? []).asArray.collect { |each| this.fromSpec(each) }
        };

        tokens = value.split($ ).reject { |token| token.stripWhiteSpace.isEmpty };

        if (tokens.isEmpty) {
            Error(
                "MusicPitch.asPitches: \"%\" contains no pitch tokens. Use spaces, e.g. "
                "\"c e g\"."
                .format(value)
            ).throw
        };

        ^tokens.collect { |token| this.new(token.stripWhiteSpace) }
    }

    // ^ Self
    *prFromNamedSpec { |spec|
        var accidental = \natural, octave = 4, cents = 0;
        if (spec.size < 1 or: { spec.size > 4 }) {
            Error(
                "MusicPitch: a named pitch spec is [noteName, octave] or [noteName, accidental, octave, cents], got %."
                .format(spec)
            ).throw
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
    //
    // ^ [Symbol]
    *accidentalNames {
        ^accidentals.keys.asArray.sort { |a, b| accidentals[a] < accidentals[b] }
    }

    // Compact accidental tokens, preferred forms first and aliases after them.
    //
    // >>> MusicPitch.compactAccidentals.size   -> 13
    // >>> MusicPitch.compactAccidentals.first  -> [ bb, doubleFlat ]
    // >>> MusicPitch.compactAccidentals.last   -> [ tqs, threeQuarterSharp ]
    // >>> MusicPitch.compactAccidentals.every { |pair| MusicPitch("c" ++ pair[0]).accidental == pair[1] }
    // true
    // >>> MusicPitch.compactAccidentals.every { |pair| MusicPitch("c" ++ pair[0] ++ "'").octave == 5 }
    // true
    // >>> MusicPitch.compactAccidentals.every { |pair| MusicPitch("c" ++ pair[0] ++ "[5]").octave == 5 }
    // true
    //
    // ^ [(String, Symbol)]
    *compactAccidentals {
        ^compactAccidentals.collect { |pair| pair.copy }
    }

    // Answers the alteration if it is one this project admits. Named
    // or counted: `\quarterSharp` and `semitones(1, 2)` are the same
    // alteration. Two questions of a counted one: is it on the
    // quarter-tone grid and is it inside the double flat to double
    // sharp range. A third of a semitone fails the first. A triple
    // sharp fails the second for the same reason.
    //
    // ^ Duration
    *checkedAlteration { |value|
        var alteration, quarters;
        if (value.isNil) {
            Error(
                "MusicPitch: nil is not an alteration. Omit the argument for natural."
            ).throw
        };
        if (value.isKindOf(Symbol) or: { value.isKindOf(String) }) {
            ^accidentals[value.asSymbol] ?? {
                Error(
                    "MusicPitch: \"%\" is not an accidental. The accidentals are %."
                    .format(value, this.accidentalNames)
                ).throw
            }
        };

        // The same exact forms `semitoneNumber` takes, for the same
        // reason: `asDuration` reads a Float as a convenience. That
        // would leave `MusicPitch(0, 0.5)` open while `fromNumber(60,
        // 0.5)` and `semitoneNumber(0.5)` refuse.
        //
        // See Note [A semitone is not a length].
        if (value.isKindOf(Duration).not and: { value.isKindOf(Integer).not }
            and: { value.isKindOf(Rational).not }) {
            Error(
                "MusicPitch: % is not an alteration. Write an accidental name, or an "
                "exact number such as 1, -1 or 1%/2.".format(value.asCompileString)
            ).throw
        };
        alteration = Duration.asDuration(value);
        quarters = alteration * MusicPitch.quarterStepsPerSemitone;

        // Refusals speak the public contract, so a caller reads back the
        // number they wrote rather than the carrier it became.
        if (quarters.denominator != 1) {
            Error(
                "MusicPitch: alteration % is not on the quarter-tone grid. Use one of %."
                .format(
                    this.semitoneNumber(alteration),
                    this.accidentalNames
                )
            ).throw
        };

        if (alteration.abs > MusicPitch.maxAlteration) {
            Error(
                "MusicPitch: alteration % is outside the double-flat to double-sharp range."
                .format(this.semitoneNumber(alteration))
            ).throw
        };
        ^alteration
    }

    // ^ Integer
    *checkedStep { |value|
        if (value.isKindOf(Integer).not or: { value < 0 } or: { value > 6 }) {
            Error(
                "MusicPitch: step % is not a diatonic degree. Use 0 to 6 for %."
                .format(value, noteNames)
            ).throw
        };
        ^value
    }

    // ^ Integer
    *checkedOctave { |value|
        if (value.isKindOf(Integer).not) {
            Error("MusicPitch: octave % must be an integer.".format(value)).throw
        };
        ^value
    }

    // Residual deviation, in cents, that no accidental notates.
    // Always a Float, so equal pitches serialize the same way however
    // they were built. Time stays exact. Cents is a measurement.
    //
    // >>> MusicPitch(\c).cents   -> 0.0
    //
    // ^ Float
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
    // key context. Sharps raise the letter below the pitch. Flats
    // lower the letter above it. That keeps quarter-tone spellings
    // consistent too.
    //
    // `quantum` is the snap grid. `cents` carries what the snap or
    // the quarter-tone accidental grid cannot spell.
    //
    // >>> MusicPitch.fromMidinote(61).accidental            -> sharp
    // >>> MusicPitch.fromMidinote(61, \flats).accidental    -> flat
    // >>> MusicPitch.fromMidinote(61, \flats).letter        -> d
    // >>> MusicPitch.fromMidinote(60.5).accidental          -> quarterSharp
    // >>> MusicPitch.fromMidinote(60.25).cents              -> -25.0
    // >>> MusicPitch.fromMidinote(60.25, 0.25).accidental   -> quarterSharp
    // >>> MusicPitch.fromMidinote(60.25, 0.25).cents        -> -25.0
    // >>> MusicPitch.fromMidinote(61.5, \minimal).spelling   -> d-[4]
    //
    // ^ Self
    *fromMidinote { |midinote, spelling = \sharps, quantum = 0.5|
        var snapped, quarters;

        // A numeric second argument is the quantum: `fromMidinote(60.5, 1)`.
        if (spelling.isNumber) { quantum = spelling; spelling = \sharps };

        snapped = (midinote / quantum).round * quantum;

        // Accidentals spell quarter-tone steps. Cents carries the rest.
        quarters = (snapped * this.quarterStepsPerSemitone).round.asInteger;
        ^this.prFromGridSteps(quarters, spelling,
            (midinote - (quarters / this.quarterStepsPerSemitone)) * 100)
    }

    // Exact spelling from a pitch number, 60 being middle C.
    //
    // Whole offsets, fractional base-plus-offset and off-grid numbers are
    // refused.
    //
    // >>> MusicPitch.fromNumber(61).accidental                  -> sharp
    // >>> MusicPitch.fromNumber(61, \flats).letter              -> d
    // >>> MusicPitch.fromNumber(60, 1%/2).accidental            -> quarterSharp
    // >>> MusicPitch.fromNumber(60, 1%/2, \flats).accidental    -> threeQuarterFlat
    // >>> MusicPitch.fromNumber(60, 1%/2, cents: 7).cents       -> 7.0
    // >>> MusicPitch.fromNumber(61, \minimal).spelling           -> c#[4]
    // >>> MusicPitch.fromNumber(61, 1%/2, \minimal).spelling     -> d-[4]
    // >>> MusicPitch.fromNumber(60, { |ctx| ctx[\minimal] }).spelling   -> c[4]
    // >>> MusicPitch.fromNumber(70, { |ctx| ctx[\candidates][1] }).spelling   -> bb[4]
    //
    // ^ Self
    *fromNumber { |base, offset = 0, spelling, cents = 0|
        // A spelling in the offset slot stays spelling.
        if (offset.isKindOf(Symbol) or: { this.prIsPolicy(offset) }) {
            if (spelling.notNil) {
                Error(
                    "MusicPitch: % in the offset slot sets spelling. Pass a fractional "
                    "offset first, or pass cents by keyword.".format(offset)
                ).throw
            };
            spelling = offset;
            offset = 0;
        };
        ^this.prFromGridSteps(
            this.prCheckedGridSteps(base, offset), spelling ? \sharps, cents)
    }

    // One pitch number, whole part plus fraction. The way to write a
    // quarter tone without `60 + (1%/2)`'s parentheses at every call.
    //
    // >>> MusicPitch.number(60, 1%/2)   -> 121%/2
    // >>> MusicPitch.number(60)         -> 60%/1
    //
    // ^ Rational
    *number { |base, offset = 0|
        var exact = this.prCheckedNumber(base, offset);
        ^Rational(exact.numerator, exact.denominator)
    }

    // Private passage entry. Built-ins need no passage facts.
    //
    // ^ Self
    *prFromNumberInPassage { |base, spelling, cents = 0, passage|
        // `\written` needs `currentPitch`.
        if (spelling == \written) {
            ^this.prFromGridSteps(this.prCheckedGridSteps(base, 0), spelling,
                cents, passage)
        };
        if (this.prIsPolicy(spelling).not) {
            ^this.fromNumber(base, 0, spelling, cents)
        };
        ^this.prPolicySpelling(
            this.prCheckedGridSteps(base, 0), spelling, cents, passage)
    }

    // Candidate spellings for one pitch number. Order is written
    // ladder order, not preference order.
    //
    // >>> MusicPitch.spellings(60).collect { |each| each.spelling } == ["b#[3]", "c[4]", "dbb[4]"]
    // true
    // >>> MusicPitch.spellings(61).collect { |each| each.spelling } == ["b##[3]", "c#[4]", "db[4]"]
    // true
    // >>> MusicPitch.spellings(60, 1%/2).collect { |each| each.spelling } == ["b#+[3]", "c+[4]", "db-[4]"]
    // true
    // >>> MusicPitch.spellings(60, 0, 7).every { |each| each.cents == 7.0 }   -> true
    //
    // ^ [MusicPitch]
    *spellings { |base, offset = 0, cents = 0|
        ^this.prSpellingsOf(this.prCheckedGridSteps(base, offset), cents)
    }

    // Candidates for a number already counted in grid steps.
    //
    // ^ [MusicPitch]
    *prSpellingsOf { |quarters, cents|
        var perOctave = 12 * this.quarterStepsPerSemitone;
        var found = [];

        // The bounded alteration leaves at most one octave per
        // letter.
        noteNames.size.do { |step|
            var natural = quarters - (stepSemitones[step] * this.quarterStepsPerSemitone);

            // Integer nearest octave. `/` would spend this path through Float.
            var octaves = (natural + (perOctave div: 2)) div: perOctave;
            var alter = this.semitones(natural - (octaves * perOctave), this.quarterStepsPerSemitone);

            if (alter.abs <= this.maxAlteration) {
                found = found.add([octaves - 1, step, alter])
            };
        };

        ^found.sort { |a, b| if (a[0] == b[0]) { a[1] < b[1] } { a[0] < b[0] } }
              .collect { |each| this.new(each[1], each[2], each[0], cents) }
    }

    // The pitch number all three take, counted in written grid steps.
    //
    // ^ Integer
    *prCheckedGridSteps { |base, offset|
        var number = this.prCheckedNumber(base, offset);
        var quarters = number * this.quarterStepsPerSemitone;
        if (quarters.denominator != 1) {
            Error(
                "MusicPitch: pitch number % is off the quarter-tone grid. Pass the "
                "remainder as cents.".format(this.prNumberText(number))
            ).throw
        };
        ^quarters.numerator
    }

    // Exact input only. Floats belong at `fromMidinote`.
    //
    // ^ Duration
    *prCheckedExact { |value, what|
        if (value.isKindOf(Float)) {
            Error(
                "MusicPitch: % is a Float; % must be exact. Use Integer, Rational or "
                "MusicPitch.number; use fromMidinote for Floats.".format(value, what)
            ).throw
        };
        ^Duration.asExactValue(value, what)
    }

    // Either one exact pitch number or whole base plus fractional
    // offset `Duration` stays the exact carrier. Callers see numbers.
    //
    // ^ Duration
    *prCheckedNumber { |base, offset|
        var exact = this.prCheckedExact(base, "a pitch number");
        var shift = this.prCheckedExact(offset, "an offset from a pitch number");
        var number = exact + shift;
        if (shift.isZero) { ^number };
        if (shift.abs >= 1) {
            Error(
                "MusicPitch: offset % is not fractional. Write pitch number %."
                .format(this.prNumberText(shift), this.prNumberText(number))
            ).throw
        };
        if (exact.denominator != 1) {
            Error(
                "MusicPitch: base % and offset % both carry fractions. Use one exact "
                "pitch number, or split at a whole semitone."
                .format(
                    this.prNumberText(exact),
                    this.prNumberText(shift)
                )
            ).throw
        };
        ^number
    }

    // Display pitch numbers as callers write them.
    //
    // ^ Integer | Rational
    *prNumberText { |number|
        if (number.denominator == 1) { ^number.numerator };
        ^Rational(number.numerator, number.denominator)
    }

    // Shared scalar spelling resolver. `quarters` is a number in grid steps.
    //
    // ^ Self
    *prFromGridSteps { |quarters, spelling, cents, passage|
        var semis, steps, spec, alter;

        if (this.prIsPolicy(spelling)) {
            ^this.prPolicySpelling(quarters, spelling, cents, passage)
        };
        if (spelling == \minimal) { ^this.prMinimalSpelling(quarters, cents) };
        if (spelling == \written) {
            ^this.prWrittenSpelling(quarters, cents, passage)
        };

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
        ^this.new(spec[0], alter, (semis div: 12) - 1, cents)
    }

    // Keep `currentPitch`. Fall back to `\minimal` where there is none.
    //
    // >>> MusicPitch("dbb[4]").spelled(\written).spelling   -> dbb[4]
    // >>> MusicPitch.fromNumber(70, \written).spelling      -> a#[4]
    //
    // ^ Self
    *prWrittenSpelling { |quarters, cents, passage|
        var written = (passage ?? { () })[\currentPitch];

        if (written.notNil) { ^written };
        ^this.prMinimalSpelling(quarters, cents)
    }

    // ^ Self
    *prMinimalSpelling { |quarters, cents|
        ^this.prLeastAltered(this.prSpellingsOf(quarters, cents), quarters, cents)
    }

    // Smallest exact `alter.abs`. `\sharps` breaks ties.
    //
    // ^ Self
    *prLeastAltered { |pitches, quarters, cents|
        var sharp = this.prFromGridSteps(quarters, \sharps, cents);
        var least, tied;

        pitches.do { |each|
            if (least.isNil or: { each.prAlter.abs < least }) { least = each.prAlter.abs }
        };
        tied = pitches.select { |each| each.prAlter.abs == least };
        ^tied.detect { |each| each == sharp } ?? { tied.first }
    }

    // Scalar context by default. Passage callers add neighbor facts.
    // ^ Self
    *prPolicySpelling { |quarters, policy, cents, passage|
        var context = this.prContextFor(quarters, cents, passage);
        var number = Duration(quarters, this.quarterStepsPerSemitone);

        ^this.prCheckedChoice(policy.value(context), context, number, policy)
    }

    // Policy context, built without choosing a spelling.
    //
    // ^ Event
    *prContextFor { |quarters, cents, passage|
        var pitches = this.prSpellingsOf(quarters, cents);
        var context = (passage ?? { () }).copy;

        context[\pitchNumber] = Rational(quarters, this.quarterStepsPerSemitone);
        context[\cents] = this.checkedCents(cents);
        context[\candidates] = pitches.collect { |each| this.prCandidateOf(each) };
        context[\minimal] = this.prLeastAltered(pitches, quarters, cents);
        ^context
    }

    // Policy context for an already spelled pitch.
    //
    // ^ Event
    *prContextForPitch { |pitch, passage|
        ^this.prContextFor(this.prCheckedGridSteps(pitch.height, 0),
            pitch.cents, passage)
    }

    // Derived view over one candidate pitch.
    //
    // ^ Event
    *prCandidateOf { |pitch|
        ^(noteName: noteNames[pitch.step], accidental: pitch.accidental,
            alter: pitch.alter, octave: pitch.octave, pitch: pitch)
    }

    // Function or SpellingPolicy. Not every object answering `value`.
    //
    // >>> MusicPitch.prIsPolicy(SpellingPolicy.minimal)   -> true
    // >>> MusicPitch.prIsPolicy(\minimal)                 -> false
    //
    // ^ Boolean
    *prIsPolicy { |value|
        ^value.isKindOf(Function) or: { value.isKindOf(SpellingPolicy) }
    }

    // Admitted spelling values. Checked without calling a policy.
    // Silent doors still reject bad spellings.
    //
    // >>> MusicPitch.checkedSpelling(\flats, "spellPitches")   -> flats
    // >>> try { MusicPitch.checkedSpelling(\tonal, "x") } { |e| e.what.contains("not a spelling") }
    // true
    //
    // ^ Symbol | Function | SpellingPolicy
    *checkedSpelling { |spelling, label|
        if (this.prIsPolicy(spelling)) { ^spelling };
        if (this.spellingNames.includes(spelling)) { ^spelling };
        Error(
            "%: % is not a spelling. Use \\sharps, \\flats, \\minimal, \\written, a "
            "Function or a SpellingPolicy.".format(label, spelling.asCompileString)
        ).throw
    }

    // Built-in spellings. `\written` reads `currentPitch`.
    //
    // >>> MusicPitch.spellingNames   -> [ sharps, flats, minimal, written ]
    //
    // ^ [Symbol]
    *spellingNames { ^[\sharps, \flats, \minimal, \written] }

    // Built-ins a pass-like rule can use when spelling neighbors.
    //
    // >>> MusicPitch.contextFreeSpellingNames   -> [ sharps, flats, minimal ]
    //
    // ^ [Symbol]
    *contextFreeSpellingNames { ^[\sharps, \flats, \minimal] }

    // A policy chooses spelling only. Refusals name what answered.
    //
    // ^ Self
    *prCheckedChoice { |answer, context, number, policy|
        var pitch = if (answer.isKindOf(Event)) { answer[\pitch] } { answer };
        var who = this.prPolicyLabel(policy);
        if (pitch.isKindOf(MusicPitch).not) {
            Error(
                "MusicPitch: % must answer a candidate Event or MusicPitch, got %."
                .format(who, answer.asCompileString)
            ).throw
        };
        if (pitch.height != number) {
            Error(
                "MusicPitch: % answered % at %, not pitch number %."
                .format(
                    who,
                    pitch.spelling,
                    this.prNumberText(pitch.height),
                    this.prNumberText(number)
                )
            ).throw
        };
        if (pitch.cents != context[\cents]) {
            Error(
                "MusicPitch: % answered % cents, not %."
                .format(who, pitch.cents, context[\cents])
            ).throw
        };
        if (context[\candidates].any { |each| each[\pitch] == pitch }.not) {
            Error(
                "MusicPitch: % answered %, which is not a candidate spelling of "
                "pitch number %."
                .format(who, pitch.spelling, this.prNumberText(number))
            ).throw
        };
        ^pitch
    }

    // Diagnostic label for a policy answer.
    //
    // >>> MusicPitch.prPolicyLabel(SpellingPolicy.flats)   -> spelling policy flats
    // >>> MusicPitch.prPolicyLabel({ |ctx| ctx })          -> spelling Function
    //
    // ^ String
    *prPolicyLabel { |policy|
        if (policy.isKindOf(SpellingPolicy)) {
            ^"spelling policy %".format(policy.name)
        };
        ^"spelling Function"
    }

    // ^ [(Integer, Integer)]
    *spellingMap { |spelling|
        if (spelling == \sharps) { ^sharpMap };
        if (spelling == \flats) { ^flatMap };
        if (spelling == \minimal) {
            Error(
                "MusicPitch: \\minimal ranks MusicPitch.spellings; it has no map."
            ).throw
        };
        if (spelling == \written) {
            Error(
                "MusicPitch: \\written keeps the pitch being respelled; it has no map."
            ).throw
        };
        Error(
            "MusicPitch: \"%\" is not a spelling. Use \\sharps, \\flats, \\minimal, "
            "\\written, a Function or a SpellingPolicy.".format(spelling)
        ).throw
    }

    // Exact spelled-ladder semitones. This is an ordering key, not a
    // duration. `MusicPitch.number` and policy `pitchNumber` are the
    // user-facing number. Residual `cents` stays separate.
    //
    // >>> MusicPitch(\c).height                      -> Duration(60/1)
    // >>> MusicPitch(\c, \sharp).height == MusicPitch(\d, \flat).height
    // true
    // >>> MusicPitch(\c, \quarterSharp).height      -> Duration(121/2)
    // >>> MusicPitch(\c, \natural, 4, 50).height    -> Duration(60/1)
    //
    // ^ Duration
    height {
        ^Duration(((octave + 1) * 12) + stepSemitones[step], 1) + alter
    }

    // User-facing pitch number. Same exact value as `height`, without
    // the duration carrier. Residual `cents` stays separate.
    //
    // >>> MusicPitch(\c).number                      -> 60%/1
    // >>> MusicPitch(\c, \quarterSharp).number      -> 121%/2
    // >>> MusicPitch(\c, \natural, 4, 50).number    -> 60%/1
    //
    // ^ Rational
    number {
        var value = this.height;
        ^Rational(value.numerator, value.denominator)
    }

    // The same pitch under another enharmonic spelling. It reads
    // `height`, not `midinote`, so `cents` survives exactly.
    //
    // >>> MusicPitch("c#[4]").spelled(\flats).spelling      -> db[4]
    // >>> MusicPitch("c[4]").spelled(\flats).accidental     -> natural
    // >>> MusicPitch("c#[4]", cents: 7).spelled(\flats).cents   -> 7.0
    // >>> MusicPitch("c#+[4]").spelled(\minimal).spelling   -> d-[4]
    //
    // `currentPitch` is only available through the passage path.
    //
    // >>> MusicPitch("c#[4]").spelled({ |ctx| ctx[\currentPitch] }).spelling   -> c#[4]
    //
    // ^ MusicPitch
    spelled { |spelling = \sharps|
        ^MusicPitch.prFromNumberInPassage(
            this.height, spelling, cents, (currentPitch: this))
    }

    // Playback projection. Folds `cents` into a Float. Ordering uses `height`.
    //
    // >>> MusicPitch(\c, \sharp).midinote          -> 61.0
    // >>> MusicPitch(\c, \quarterSharp).midinote   -> 60.5
    // >>> MusicPitch(\b, \natural, 3).midinote     -> 59.0
    //
    // ^ Float
    midinote {
        ^((octave + 1) * 12) + stepSemitones[step] + alter.asFloat + (cents / 100)
    }

    // This pitch's letter as a Char, neutral between notations:
    // LilyPond lowercases it, MusicXML uppercases it.
    //
    // >>> MusicPitch(\e, \flat, 3).letter   -> e
    //
    // ^ Char
    letter { ^"cdefgab"[step] }

    // Answers the name of this pitch's accidental, which every
    // admitted alteration has.
    //
    // >>> MusicPitch(\c, \threeQuarterFlat).accidental   -> threeQuarterFlat
    //
    // ^ Symbol
    accidental {
        MusicPitch.accidentalNames.do { |name|
            if (MusicPitch.accidentals[name] == alter) { ^name }
        };
        Error(
            "MusicPitch: internal error, no accidental name for %."
            .format(alter)
        ).throw
    }

    // Exact semitones of alteration: 1 is a sharp, -1 a flat, 1%/2 a
    // quarter sharp. A whole number answers an Integer and a
    // fractional one a Rational, so a reader never meets the
    // `Duration` this is carried in.
    //
    // See Note [A semitone is not a length].
    //
    // >>> MusicPitch(\c, \sharp).alter          -> 1
    // >>> MusicPitch(\c, \quarterSharp).alter   -> 1%/2
    // >>> MusicPitch(\c).alter                  -> 0
    //
    // ^ Integer | Rational
    alter { ^MusicPitch.semitoneNumber(alter) }

    // The carrier itself, for arithmetic inside the quark. Everything that
    // adds an alteration to a height, compares two pitches or writes one to a
    // document wants this rather than the projection.
    //
    // ^ Duration
    prAlter { ^alter }

    // Alteration in quarter-tone units: -4 .. +4 for a double flat .. double
    // sharp. Exact, never rounded.
    //
    // >>> MusicPitch(\c).alterationSteps                 -> 0
    // >>> MusicPitch(\c, \quarterSharp).alterationSteps  -> 1
    // >>> MusicPitch(\c, \doubleSharp).alterationSteps   -> 4
    //
    // ^ Integer
    alterationSteps {
        var quarters = alter * MusicPitch.quarterStepsPerSemitone;
        if (quarters.denominator != 1) {
            Error(
                "MusicPitch: alteration % is not a whole number of quarter tones.".format(alter)
            ).throw
        };
        ^quarters.numerator
    }

    // This pitch moved by a spelled interval. The interval carries
    // the target letter, so the result is spelled rather than chosen.
    // See Note [A height offset is not an interval] in MusicInterval.sc.
    //
    // >>> MusicPitch(\c).transposeBy(MusicInterval.between(MusicPitch(\c), MusicPitch(\e))).letter
    // e
    //
    // ^ MusicPitch
    transposeBy { |interval| ^interval.transpose(this) }

    // Affine arithmetic. A pitch minus a pitch is an interval. A
    // pitch plus an interval is a pitch. Two pitches cannot be added.
    //
    // >>> (MusicPitch(\e) - MusicPitch(\c)).signedName.qualityName    -> major
    // >>> (MusicPitch(\c) + MusicInterval.named(\perfect, 5)).letter  -> g
    //
    // ^ MusicPitch
    + { |that|
        if (that.isKindOf(MusicInterval)) { ^that.transpose(this) };
        if (that.isKindOf(MusicPitch)) {
            Error(
                "MusicPitch: cannot add two pitches. Subtract pitches for an interval, "
                "or add an interval to a pitch."
            ).throw
        };
        Error(
            "MusicPitch: cannot add % to a pitch. Use a MusicInterval.".format(that)
        ).throw
    }

    // `b - a` is the interval from a to b, so the vector points the
    // way it reads. Subtracting an interval moves the other way
    // instead.
    //
    // that: MusicPitch ^ MusicInterval
    // that: MusicInterval ^ MusicPitch
    - { |that|
        if (that.isKindOf(MusicPitch)) { ^MusicInterval.between(that, this) };
        if (that.isKindOf(MusicInterval)) { ^that.negated.transpose(this) };
        Error(
            "MusicPitch: cannot subtract % from a pitch. Use a MusicPitch or MusicInterval."
            .format(that)
        ).throw
    }

    // ^ Boolean
    == { |that| ^that.isKindOf(MusicPitch) and: {
        (step == that.step) and: { (alter == that.prAlter) and: {
            (octave == that.octave) and: { cents == that.cents } } } } }

    // ^ Integer
    hash { ^step.hash bitXor: alter.hash bitXor: octave.hash bitXor: cents.hash }

    // Canonical compact spelling: letter, accidental and bracketed
    // octave. `MusicPitch(p.spelling) == p`. The parser also accepts
    // aliases and register marks.
    //
    // Not `LilyWriter.pitchString`. LilyPond counts octaves differently.
    //
    // >>> MusicPitch(\c).spelling                    -> c[4]
    // >>> MusicPitch(\c, \sharp, 4).spelling         -> c#[4]
    // >>> MusicPitch(\c, \quarterFlat, 4).spelling   -> c-[4]
    //
    // ^ String
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
    //
    // ^ Self
    printOn { |stream|
        stream << "MusicPitch(" << this.spelling.asCompileString;
        if (cents != 0) { stream << ", cents: " << cents };
        stream << ")"
    }

    // The summary is valid source.
    //
    // >>> MusicPitch(\e, \flat, 3).asCompileString   -> MusicPitch("eb[3]")
    //
    // ^ Self
    storeOn { |stream| this.printOn(stream) }
}
