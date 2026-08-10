// MusicPitch: spelled pitch, as letter + accidental + octave + cents.
//
// Named MusicPitch because `Pitch` is already a core UGen.
//
// Deliberately not a midinote. Spelling has to survive a round trip. Alteration
// is an exact count of semitones, so a quarter tone is an ordinary value.
// `cents` carries residual deviation that is not notated.
//
// That count is carried in a `Duration`, the only exact rational wrapper this
// quark owns. An alteration is not a length. This file calls the value
// `semitones` so the tables read as pitch spelling rather than time.
//
// `semitones(-3, 2)` is three quarter tones flat.
//
// No output syntax here: writers spell "cis'" or
// <step>C</step><alter>1</alter><octave>4</octave> from these four facts.
//
//   step:   0..6 for c d e f g a b
//   alter:  semitones, exact (1 = sharp, 1/2 = quarter sharp, -1 = flat)
//   octave: scientific pitch notation, 4 = the octave of middle C
//
// The alteration is exact but bounded, by Note [The admission rule] in
// ScoreWriter.sc. MusicXML and LilyPond can each spell more alone. This grid is
// where they agree. Moving it would be a ScoreJSON version too, since the wire
// narrowed `alter` to quarter tones.
//
// `accidentals` is that grid by name, so a pitch can be written the way it is
// said. Nothing about the storage changes: a name is a way in, not another
// fact.


// Note [Refuse at the constructor]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `noteNames` and `accidentals` are closed and checked where a pitch is built,
// so an invalid one cannot be carried. sclang has no type checker, so the
// constructor is the executable contract.
//
// What each closed set admits is Note [The admission rule] in ScoreWriter.sc.
// what this Note adds is that it is checked at construction rather than
// trusted.
//
// What it buys is downstream. Because nothing in the model can hold a value a
// writer cannot spell, no writer carries a branch for one. The check happens
// once, early.


MusicPitch {
    var <step, <alter, <octave, <cents;

    classvar <stepSemitones, <sharpMap, <flatMap, <noteNames, <accidentals;

    // An alteration, in semitones, exact. `semitones(1)` is a sharp,
    // `semitones(-3, 2)` three quarter tones flat. The name is the point: a
    // table of bare `Duration`s reads as a table of lengths, which an
    // accidental is not.
    //
    // >>> MusicPitch.semitones(-3, 2)   -> Duration(-3/2)
    *semitones { |count, per = 1| ^Duration(count, per) }

    // Two quarter tones to a semitone. Written as a plain number rather than an
    // alteration, because it is a ratio between two units and not a pitch
    // distance.
    *quarterStepsPerSemitone { ^2 }

    // A double sharp, and the far end of what both formats spell.
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

        // The whole admitted grid, and the only alterations there are: the
        // quarter-tone steps from double flat to double sharp. LilyPond has a
        // name for each and MusicXML an <alter> for each.
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
    }

    // An omitted alteration defaults to `\natural` rather than to nil, which is
    // what lets `checkedAlteration` refuse a nil outright: one reaching here
    // came from an expression that answered nil, not from an alteration left
    // out.
    //
    // Two spellings of one key are two pitches, and that is the point of the
    // class. A midinote cannot tell them apart, and a score must:
    //
    // >>> MusicPitch(\c, \sharp) == MusicPitch(\d, \flat)   -> false
    // >>> MusicPitch(\c, \sharp).midinote == MusicPitch(\d, \flat).midinote
    // true
    *new { |step = 0, alter = \natural, octave = 4, cents = 0|
        if (step.isKindOf(MusicPitch)) { ^step };
        if (step.isKindOf(Symbol) or: { step.isKindOf(String) }) {
            ^this.named(step, alter, octave, cents)
        };
        if (step.isKindOf(String).not and: { step.isSequenceableCollection }) {
            if (alter != \natural) {
                Error("MusicPitch: a pitch spec such as [\\c, \\sharp, 4] "
                    "carries its accidental and octave inside the array.").throw
            };
            ^this.prFromNamedSpec(step)
        };
        ^super.newCopyArgs(
            this.checkedStep(step),
            this.checkedAlteration(alter),
            this.checkedOctave(octave),
            this.checkedCents(cents))
    }

    // The same pitch, said rather than counted: `MusicPitch.named(\c, \sharp,
    // 4)`.
    //
    // Both vocabularies are closed, so a misspelling is an error where it was
    // written. See Note [Refuse at the constructor] above.
    *named { |noteName, accidental = \natural, octave = 4, cents = 0|
        var step, alteration;
        if (noteName.isKindOf(Symbol).not
            and: { noteName.isKindOf(String).not }) {
            Error("MusicPitch: \"%\" is not a note name. The names are %.".format(
                noteName, noteNames)).throw
        };
        if (accidental.isNil) { this.checkedAlteration(nil) };   // says why nil
        if (accidental.isKindOf(Symbol).not and: { accidental.isKindOf(String).not }) {
            Error("MusicPitch: \"%\" is not an accidental. The accidentals are "
                "%.".format(accidental, this.accidentalNames)).throw
        };
        step = noteNames.indexOf(noteName.asSymbol);
        alteration = accidentals[accidental.asSymbol];
        if (step.isNil) {
            Error("MusicPitch: \"%\" is not a note name. The names are %.".format(
                noteName, noteNames)).throw
        };
        if (alteration.isNil) {
            Error("MusicPitch: \"%\" is not an accidental. The accidentals are "
                "%.".format(accidental, this.accidentalNames)).throw
        };
        ^this.new(step, alteration, octave, cents)
    }

    // A neutral pitch spec, accepted by notes, chords and RTM pitch streams: a
    // MusicPitch, a midinote number, a note-name Symbol, or [noteName, octave]
    // / [noteName, accidental, octave, cents].
    //
    // >>> MusicPitch.fromSpec([\c, \sharp, 4]) == MusicPitch(\c, \sharp)
    // true
    *fromSpec { |spec|
        if (spec.isKindOf(MusicPitch)) { ^spec };
        if (spec.isNumber) { ^this.fromMidinote(spec) };
        if (spec.isKindOf(Symbol) or: { spec.isKindOf(String) }) {
            ^this.named(spec)
        };
        if (spec.isKindOf(String).not and: { spec.isSequenceableCollection }) {
            ^this.prFromNamedSpec(spec)
        };
        Error("MusicPitch: % is not a pitch. Use a MusicPitch, a midinote, "
            "a note name like \\c, or [noteName, octave] / "
            "[noteName, accidental, octave, cents].".format(
                spec)).throw
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

    // Returns the alteration, if it is one this project admits. Named or
    // counted: `\quarterSharp` and `semitones(1, 2)` are the same alteration.
    //
    // Two questions of a counted one: is it on the quarter-tone grid, and is it
    // inside the double flat to double sharp range. A third of a semitone fails
    // the first, a triple sharp the second, and for the same reason.
    *checkedAlteration { |value|
        var alteration, quarters;
        if (value.isNil) {
            Error("MusicPitch: nil is not an alteration. Omit it for a natural "
                "- a nil here is usually an expression that failed, such as a "
                "fraction whose denominator is zero.").throw
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
            Error("MusicPitch: an alteration of % is not on the quarter-tone "
                "grid, so LilyPond would round it and MusicXML would not. The "
                "accidentals are %.".format(
                    alteration, this.accidentalNames)).throw
        };
        if (alteration.abs > MusicPitch.maxAlteration) {
            Error("MusicPitch: an alteration of % is past a double sharp, "
                "which is where the backends stop agreeing. MusicXML would "
                "write it and LilyPond needs an extension to spell it, so it "
                "is refused here rather than at whichever writer reaches it "
                "first.".format(alteration)).throw
        };
        ^alteration
    }

    *checkedStep { |value|
        if (value.isKindOf(Integer).not or: { value < 0 } or: { value > 6 }) {
            Error("MusicPitch: a step of % is not a diatonic degree. Steps are 0 "
                "to 6, for %.".format(value, noteNames)).throw
        };
        ^value
    }

    *checkedOctave { |value|
        if (value.isKindOf(Integer).not) {
            Error("MusicPitch: an octave of % is not a whole number. Octaves are "
                "scientific pitch notation, where 4 is the octave of middle "
                "C.".format(value)).throw
        };
        ^value
    }

    // Residual deviation, in cents, that no accidental notates. A measurement
    // rather than a spelling. No range is imposed: a limit here would be an
    // opinion about tuning, and this is only the place to keep what the
    // accidentals could not say.
    //
    // Always a Float, so the same pitch has the same cents however it was
    // built. `fromMidinote` computes one and a named pitch defaulted to the
    // integer 0, which reached the wire as `0` against `0.0` and made the
    // writer disagree with its own output after a round trip. It is the one
    // field this format allows a float, so making it one throughout costs
    // nothing. See Note [A Float is not an exact duration] for why time is the
    // opposite case.
    //
    // >>> MusicPitch(\c).cents   -> 0.0
    *checkedCents { |value|
        if (value.isNumber.not) {
            Error("MusicPitch: cents of % is not a number. It carries the "
                "deviation an accidental does not.".format(value)).throw
        };
        if ((value.abs < inf).not) {
            Error("MusicPitch: cents of % is not a finite number. JSON has no "
                "spelling for one, so it would be written into a document that "
                "no strict reader could take back.".format(value)).throw
        };
        ^value.asFloat
    }

    // Returns a pitch for this midinote, spelled with sharps or with flats.
    //
    // Neither answer is more correct: 61 is C sharp in one context and D flat
    // in another, and nothing in a midinote says which. The default stays
    // sharps.
    //
    // The two spellings are mirror images, which is what makes them consistent
    // at quarter tones as well as semitones: sharps raise the letter *below*
    // the pitch, flats lower the letter *above* it. So 60.5 is C quarter-sharp
    // with sharps and D three-quarter-flat with flats, never a sharp
    // accidental under a flat spelling, which is what asking for flats meant.
    //
    // `quantum` is the grid to snap to: 0.5 keeps quarter tones, 1 rounds to
    // the chromatic scale. Whatever is left over goes to `cents` rather than
    // nowhere.
    //
    // >>> MusicPitch.fromMidinote(61).accidental          -> sharp
    // >>> MusicPitch.fromMidinote(61, \flats).accidental  -> flat
    // >>> MusicPitch.fromMidinote(61, \flats).letter      -> d
    // >>> MusicPitch.fromMidinote(60.5).accidental        -> quarterSharp
    // >>> MusicPitch.fromMidinote(60.25).cents            -> -25.0
    *fromMidinote { |midinote, spelling = \sharps, quantum = 0.5|
        var snapped, semis, frac, spec, alter;

        // A number here can only be the quantum, since a spelling is a Symbol,
        // so `fromMidinote(60.5, 1)` is read that way rather than refused. The
        // two arguments cannot be confused for each other, which is what makes
        // this a convenience rather than a guess.
        if (spelling.isNumber) { quantum = spelling; spelling = \sharps };

        snapped = (midinote / quantum).round * quantum;
        semis = snapped.floor.asInteger;
        frac = snapped - semis;

        // Flats spell downward from the note above, so anything between two
        // semitones is named from the one above it rather than the one below.
        if (spelling == \flats and: { frac > 0 }) {
            semis = semis + 1;
            frac = frac - 1;
        };

        spec = this.spellingMap(spelling)[semis % 12];
        alter = MusicPitch.semitones(spec[1])
            + MusicPitch.semitones((frac * 2).round.asInteger, 2);
        ^this.new(spec[0], alter, (semis div: 12) - 1, (midinote - snapped) * 100)
    }

    *spellingMap { |spelling|
        if (spelling == \sharps) { ^sharpMap };
        if (spelling == \flats) { ^flatMap };
        Error("MusicPitch: \"%\" is not a spelling. A midinote can be spelled "
            "with sharps or with flats; nothing in the number says which.".format(
                spelling)).throw
    }

    // A Float, and the one place the exactness is spent: it leaves the model
    // for a synth, where a key number is what is wanted.
    //
    // >>> MusicPitch(\c, \sharp).midinote          -> 61.0
    // >>> MusicPitch(\c, \quarterSharp).midinote   -> 60.5
    // >>> MusicPitch(\b, \natural, 3).midinote     -> 59.0
    midinote {
        ^((octave + 1) * 12) + stepSemitones[step] + alter.asFloat + (cents / 100)
    }

    // This pitch's letter, as a Char, and neutral between notations: LilyPond
    // lowercases it, MusicXML uppercases it.
    //
    // >>> MusicPitch(\e, \flat, 3).letter   -> e
    letter { ^"cdefgab"[step] }

    // Returns the name of this pitch's accidental, which every admitted
    // alteration has.
    //
    // >>> MusicPitch(\c, \threeQuarterFlat).accidental   -> threeQuarterFlat
    accidental {
        MusicPitch.accidentalNames.do { |name|
            if (MusicPitch.accidentals[name] == alter) { ^name }
        };
        Error("MusicPitch: % has no accidental name, which construction should "
            "have made impossible".format(alter)).throw
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
            Error("MusicPitch: % is not a whole number of quarter tones".format(
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

    // Affine arithmetic, which is what `MusicInterval` makes available. A pitch
    // minus a pitch is the interval between them, and a pitch plus an interval
    // is a pitch. Two pitches cannot be added: an affine space has no origin, so
    // "C plus E" names nothing, and saying so beats a doesNotUnderstand.
    //
    // >>> (MusicPitch(\e) - MusicPitch(\c)).signedName.qualityName   -> major
    // >>> (MusicPitch(\c) + MusicInterval.named(\perfect, 5)).letter   -> g
    + { |that|
        if (that.isKindOf(MusicInterval)) { ^that.transpose(this) };
        if (that.isKindOf(MusicPitch)) {
            Error("MusicPitch: two pitches cannot be added. Pitches are points "
                "and intervals are the vectors between them, so subtract two "
                "pitches for an interval and add an interval to reach a "
                "pitch.").throw
        };
        Error("MusicPitch: % is not a MusicInterval, so there is nothing to add "
            "to a pitch.".format(that)).throw
    }

    // `b - a` is the interval from a to b, so the vector points the way it
    // reads. Subtracting an interval moves the other way instead.
    - { |that|
        if (that.isKindOf(MusicPitch)) { ^MusicInterval.between(that, this) };
        if (that.isKindOf(MusicInterval)) { ^that.negated.transpose(this) };
        Error("MusicPitch: % is neither a pitch nor an interval, so there is "
            "nothing to subtract from a pitch.".format(that)).throw
    }

    == { |that| ^that.isKindOf(MusicPitch) and: {
        (step == that.step) and: { (alter == that.alter) and: {
        (octave == that.octave) and: { cents == that.cents } } } } }
    hash { ^step.hash bitXor: alter.hash bitXor: octave.hash bitXor: cents.hash }

    // Cents are shown only when there are any, so ordinary output stays short
    // while two pitches `==` calls different never print the same. They did:
    // cents is the one field the comparison reads and this used to omit, which
    // made a quarter-tone measurement invisible at the very prompt where it is
    // most worth seeing.
    //
    // >>> MusicPitch(\e, \flat, 3).asString  -> MusicPitch(e, Duration(-1/1), 3)
    // >>> MusicPitch.fromMidinote(60.25).asString
    // MusicPitch(c, Duration(1/2), 4, -25.0)
    printOn { |stream|
        stream << "MusicPitch(" << this.letter << ", " << alter << ", " << octave;
        if (cents != 0) { stream << ", " << cents };
        stream << ")"
    }
}
