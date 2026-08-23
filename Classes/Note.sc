// Leaves: a note, a rest and a chord.
//
// The names stay qualified because common SuperCollider setups already define
// `Note`, `Rest` or adjacent names.

// `tiesToNext` says this note continues into the next one. Ties are recorded on
// the earlier leaf; the model has no standalone stop.
//
// Writer-neutral: each backend spells ties for itself.

MusicNote : ScoreLeaf {
    var <>pitch, <>tiesToNext;

    // A lone String is one note written out, `MN("c#8.")`. With a separate
    // duration, a pitch String fills a pitch slot:
    // `MN("c4")` is a C quarter and `MN("c[4]", "8")` an eighth in octave 4.
    // `text` and `placement` say in values what `"c4:text{pizz.}"` says in a
    // token, by Note [Text has no bare suffix] in ScoreElement.sc.
    //
    // >>> MusicNote("c4").dur                  -> Duration(1/4)
    // >>> MusicNote("c[4]", "8").dur           -> Duration(1/8)
    // >>> MusicNote(\c, Duration.quarter).pitch -> MusicPitch("c[4]")
    *new { |pitch, dur, tiesToNext = false, text, placement = \above|
        var built;
        if (pitch.isKindOf(String) and: { dur.isNil or: { dur.isKindOf(Boolean) } }) {
            built = this.notation(pitch, if (dur.isNil) { tiesToNext } { dur })
        } {
            built = super.new.initScoreLeaf(dur).initNote(pitch, tiesToNext)
        };
        ^this.prTexted(built, text, placement)
    }

    // One token of the grammar `Measure.notation` reads a bar of, through the
    // same parser so the two can't drift. Rests and chords are refused: this
    // builds a note.
    //
    // >>> MN("c#8.").dur      -> Duration(3/16)
    // >>> MN("c'4").pitch == MusicPitch("c'")   -> true
    // >>> MN("c2", true).tiesToNext   -> true
    // >>> MN("c4:mf:staccato").markings.size   -> 2
    *notation { |token, tiesToNext = false|
        var built;
        if (token.isKindOf(String).not) {
            Error("MusicNote.notation: expected a note String such as \"c4\" or "
                "\"c#8.\", got a %.".format(token.class)).throw
        };
        token = token.stripWhiteSpace;
        // Count tokens; prose inside braces may contain spaces.
        if (ScoreNotation.prNotationTokens(token, "MusicNote.notation").size > 1) {
            Error("MusicNote.notation: \"%\" is more than one leaf. Use "
                "Measure.notation for a bar.".format(token)).throw
        };
        if (token.beginsWith("<")) {
            Error("MusicNote.notation: \"%\" is a chord. Use Chord.notation."
                .format(token)).throw
        };
        built = ScoreNotation.prNotationLeaf(token, token, "MusicNote.notation", false);
        // The token may already have tied it; the argument only adds a tie.
        if (tiesToNext ? false) { built.tiesToNext_(true) };
        ^built
    }

    initNote { |argPitch, argTiesToNext|
        pitch = MusicPitch.fromSpec(argPitch);
        tiesToNext = argTiesToNext ? false;
        ^this
    }

    accept { |writer| ^writer.visitNote(this) }

    printOn { |stream|
        stream << "MusicNote(" << pitch << ", " << dur;
        if (tiesToNext) { stream << ", tied" };
        stream << ")"
    }
}

// A shorthand for writing a note, and nothing more than that.
// `MusicNote` is long in dense musical material. This buys the
// characters back. One name in a flat namespace is the whole cost.
MN {
    // >>> MN("c4").class   -> MusicNote
    *new { |pitch, dur, tiesToNext = false, text, placement = \above|
        ^MusicNote(pitch, dur, tiesToNext, text, placement)
    }
}

// A leaf that takes time and sounds nothing. It carries markings,
// spanners and a grace group as any leaf does, but never a tie: two
// rests in a row are two rests.
MusicRest : ScoreLeaf {
    // A written duration may carry suffixes, with `r` implied. A
    // grace on a rest sounds before the silence.
    //
    // >>> MusicRest("4").dur                       -> Duration(1/4)
    // >>> MusicRest("4:textBelow{mute}").texts.first.placement   -> below
    // >>> MusicRest("4:grace{b8}").graces.size     -> 1
    *new { |dur, text, placement = \above|
        var marks = if (dur.isKindOf(String)) {
            ScoreNotation.prLeafSuffixes(dur, dur, "MusicRest")
        } {
            [dur, [], nil]
        };
        ^this.prTexted(
            ScoreNotation.prGraced(
                ScoreNotation.prMarked(
                    super.new.initScoreLeaf(this.prWrittenDuration(marks[0])),
                    marks[1]),
                marks[2]),
            text, placement)
    }

    // A written rest token is the run's, so its length is read the
    // run's way and `MusicRest("r3/8")` is refused exactly where
    // `"r3/8"` in a bar is. Without the `r` this is an ordinary
    // duration slot, where a slash needs no star.
    *prWrittenDuration { |dur|
        var text;
        if (dur.isKindOf(String).not) { ^dur };
        text = dur.stripWhiteSpace;
        if (text.beginsWith("r") or: { text.beginsWith("R") }) {
            ^ScoreNotation.prTailDuration(text.drop(1), dur, dur, "MusicRest")
        };
        if (text.beginsWith("*")) {
            ^ScoreNotation.prTailDuration(text, dur, dur, "MusicRest")
        };
        ^text
    }
    accept { |writer| ^writer.visitRest(this) }
    printOn { |stream| stream << "MusicRest(" << dur << ")" }
}

// A chord ties per pitch. `tiesToNext` accepts `false`, `true`, or
// one Boolean per pitch, and stores one flag per pitch. Three states
// are unrepresentable: duplicate pitches, whole and partial ties
// together, and per-pitch ties on a single note. `pitches` is
// read-only: the tie mask is length-coupled to it, and a setter would
// let the two drift apart.
Chord : ScoreLeaf {
    var <pitches, <tiesToNext;

    // `Chord("c e g", "4")` is the pitch run and the value.
    // `Chord("<c e g>4")` is the one token a written run holds, where
    // the value is inside it. The brackets are what tells them apart,
    // so neither reading is a guess.
    //
    // >>> Chord("c e g", "4").pitches.size   -> 3
    // >>> Chord("<c e g>4").dur              -> Duration(1/4)
    *new { |pitches, dur, tiesToNext = false, text, placement = \above|
        var built;
        if (pitches.isKindOf(String) and: { pitches.stripWhiteSpace.beginsWith("<") }
            and: { dur.isNil or: { dur.isKindOf(Boolean) }
                or: { dur.isSequenceableCollection and: { dur.isKindOf(String).not } } }) {
            built = this.notation(pitches, if (dur.isNil) { tiesToNext } { dur })
        } {
            built = super.new.initScoreLeaf(dur).initChord(pitches, tiesToNext)
        };
        ^this.prTexted(built, text, placement)
    }

    // One chord token of the grammar `Measure.notation` reads a bar
    // of: `<c e g>4`, the pitches between the brackets and the length
    // the whole chord lasts. Pitches use
    // `ScoreNotation.prNotationPitch`. The tail uses the same length
    // parser as note tokens. A repeated pitch is refused by
    // `initChord`.
    //
    // >>> Chord("<c e g>4").pitches.size   -> 3
    // >>> Chord("<c e g>2.").dur           -> Duration(3/4)
    // >>> Chord("<c e g>2:ff").markings.first.value   -> ff
    // >>> Chord("<c e g>4:grace{b8}").graces.size     -> 1
    *notation { |token, tiesToNext = false, label = "Chord.notation", where|
        var text, close, inside, tail, tie, tied, marks, built;
        var parts, split, written;
        if (token.isKindOf(String).not) {
            Error("%: expected a chord String such as \"<c e g>4\", got a %.".format(label, token.class)).throw
        };
        text = token.stripWhiteSpace;
        where = where ?? { "\"%\"".format(text) };
        // Read suffixes here too, so standalone chords and bar tokens agree.
        marks = ScoreNotation.prLeafSuffixes(text, text, label);
        text = marks[0];
        tie = ScoreNotation.prTieSuffix(text, text, label);
        text = tie[0];
        tied = tie[1];
        // `~` ties the whole chord, a mask ties selected pitches. Choose one.
        if (tied and: { tiesToNext.isKindOf(Boolean).not and: { tiesToNext.notNil } }) {
            Error("%: % already ties the whole chord. Use either `~` or a "
                "per-pitch mask, not both.".format(label, where)).throw
        };
        close = text.find(">");
        if (text.beginsWith("<").not or: { close.isNil }) {
            Error("%: % is not a chord. Use pitches in angle brackets plus a "
                "length, e.g. \"<c e g>4\".".format(label, where)).throw
        };
        inside = text.copyRange(1, close - 1).stripWhiteSpace;
        tail = text.copyToEnd(close + 1);
        if (inside.isEmpty) {
            Error("%: % has no chord pitches. Put at least one pitch inside the brackets.".format(label, where)).throw
        };
        if (tail.isEmpty) {
            Error("%: % has no chord length. Write one after the brackets.".format(label, where)).throw
        };
        if (inside.includes($<) or: { tail.includes($>) }) {
            Error("%: % nests its brackets. A chord is one leaf.".format(label, where)).throw
        };
        // Refuse rests here, before `r` reaches the pitch parser.
        if (inside.split($ ).any { |part|
            (part == "r") or: { part == "R" } }) {
            Error("%: % puts a rest inside a chord. Use pitches only, or a "
                "MusicRest leaf.".format(label, where)).throw
        };
        parts = inside.split($ ).collect { |part| part.stripWhiteSpace }
            .reject { |part| part.isEmpty };
        // A `~` on a pitch is that pitch's tie, by
        // Note [A tilde binds to what it follows] in ScoreNotation.sc.
        split = ScoreNotation.prChordTieMask(parts, where, label);
        written = split[1];
        if (written.notNil and: {
            tied or: { (tiesToNext ? false) != false } }) {
            Error("%: % writes per-pitch ties and another tie setting. Use one or the other.".format(label, where)).throw
        };
        built = this.new(
            split[0].collect { |part|
                ScoreNotation.prNotationPitch(part, where, label) },
            ScoreNotation.prTailDuration(tail, text, text, label),
            written ?? { if (tied) { true } { tiesToNext ? false } });
        ^ScoreNotation.prGraced(
            ScoreNotation.prMarked(built, marks[1]), marks[2])
    }

    // A plural pitch slot, so a String is the whole run: `Chord("c e
    // g", "4")`. The duplicate check below reads the pitches however
    // they were written.
    initChord { |argPitches, argTiesToNext|
        pitches = this.prCheckedPitches(MusicPitch.asPitches(argPitches));
        this.tiesToNext_(argTiesToNext);
        ^this
    }

    // One pitch may appear once; repeated pitches would merge tied runs.
    prCheckedPitches { |list|
        list.do { |pitch, index|
            var earlier = list.copyRange(0, index - 1);
            if (index > 0 and: { earlier.any { |other| other == pitch } }) {
                Error("Chord: % appears twice. Use separate voices for unison doublings.".format(pitch)).throw
            }
        };
        ^list
    }

    // true or false sets every pitch. One flag per pitch sets them apart.
    //
    // >>> Chord([60, 64, 67], Duration(1, 4)).tiesToNext
    // [ false, false, false ]
    // >>> Chord([60, 64, 67], Duration(1, 4), true).tiesAll   -> true
    tiesToNext_ { |spec| tiesToNext = this.prTieMask(spec); ^this }

    prTieMask { |spec|
        if (spec.isNil or: { spec == false }) { ^Array.fill(pitches.size, false) };
        if (spec == true) { ^Array.fill(pitches.size, true) };
        if (spec.isSequenceableCollection.not) {
            Error("Chord: tiesToNext must be true, false, or one Boolean per pitch, got a %.".format(spec.class)).throw
        };
        if (spec.size != pitches.size) {
            Error("Chord: % tie flags for % pitches. Use one flag per pitch.".format(spec.size, pitches.size)).throw
        };
        // Element by element, before normalizing. Coercing with `== true` would
        // quietly read a typo like \yes as "doesn't tie".
        spec.do { |flag, i|
            if (flag.isKindOf(Boolean).not) {
                Error("Chord: tie flag % must be Boolean, got % in %.".format(i, flag.class, spec)).throw
            }
        };
        if (spec.notEmpty and: { spec.every { |flag| flag.not } }) {
            Error("Chord: an all-false partial tie ties nothing. Pass false.").throw
        };
        ^spec.asArray.copy
    }

    // The pitches that continue into the next leaf, in chord order.
    //
    // >>> Chord([60, 64, 67], Duration(1, 4), [true, false, true])
    //     .tiedPitches.size   -> 2
    tiedPitches {
        var acc = List.new;
        pitches.do { |p, i| if (tiesToNext[i]) { acc.add(p) } };
        ^acc.asArray
    }

    // A partial tie ties something without tying everything.
    //
    // >>> Chord([60, 64, 67], Duration(1, 4)).tiesAnything   -> false
    // >>> Chord([60, 64, 67], Duration(1, 4), [true, false, true]).tiesAll
    // false
    tiesAnything { ^tiesToNext.any { |flag| flag } }
    tiesAll { ^pitches.notEmpty and: { tiesToNext.every { |flag| flag } } }

    accept { |writer| ^writer.visitChord(this) }

    printOn { |stream|
        stream << "Chord(" << pitches << ", " << dur;
        if (this.tiesAnything) {
            stream << ", tied " << if (this.tiesAll) { "all" } { tiesToNext }
        };
        stream << ")"
    }
}
