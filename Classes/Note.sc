// Leaves.
//
// MusicRest rather than Rest: `Rest` is a core class used by the pattern
// library. MusicNote exists because common quark setups already define `Note`.
// Same reason MusicScore and MusicPitch carry a qualifier.

// `tiesToNext` says this note continues into the next one. A tie is a relation
// between adjacent leaves, so recording it forward on the earlier leaf is the
// smallest representation that can hold it. A symmetric start/stop pair could
// also express a stop with no start, which is a state the model has no use for.
//
// Neutral, like everything else here: LilyPond spells it `~` on the first note,
// MusicXML wants a sounding <tie> and a visual <tied> on both notes, and
// neither spelling appears in this file.

MusicNote : ScoreLeaf {
    var <>pitch, <>tiesToNext;

    *new { |pitch, dur, tiesToNext = false|
        ^super.new.initScoreLeaf(dur).initNote(pitch, tiesToNext)
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


// A shorthand for writing a note, and nothing more than that. `MusicNote` is
// nine characters and the examples write one on nearly every line, so this buys
// seven of them back where music is being written.
//
// One name in a flat namespace is the whole cost.
MN {
    *new { |pitch, dur, tiesToNext = false| ^MusicNote(pitch, dur, tiesToNext) }
}


// A leaf that takes time and sounds nothing. It carries markings, spanners and
// a grace group as any leaf does, but never a tie: two rests in a row are two
// rests.
MusicRest : ScoreLeaf {
    *new { |dur| ^super.new.initScoreLeaf(dur) }

    accept { |writer| ^writer.visitRest(this) }

    printOn { |stream| stream << "MusicRest(" << dur << ")" }
}


// A chord ties per pitch, because common-tone continuity is the ordinary case:
// some voices sustain while others re-attack. `tiesToNext` accepts `false` for
// none, `true` for all, or one Boolean per pitch, and is always stored
// normalized as one flag per pitch.
//
// Three states are unrepresentable by construction rather than by validation: a
// pitch appearing twice in one chord, a whole tie coexisting with a partial one
// (all-true *is* the whole tie), and a per-pitch tie on a single note
// (MusicNote carries a plain Boolean). A design that instead lists which
// pitches continue has to reject those tie-shape cases explicitly, the
// single-note case because the backends do not agree on what it would mean.
//
// `pitches` is read-only: the tie mask is length-coupled to it, and a setter
// would let the two drift apart.
Chord : ScoreLeaf {
    var <pitches, <tiesToNext;

    *new { |pitches, dur, tiesToNext = false|
        ^super.new.initScoreLeaf(dur).initChord(pitches, tiesToNext)
    }

    initChord { |argPitches, argTiesToNext|
        pitches = this.prCheckedPitches(
            argPitches.collect { |p| MusicPitch.fromSpec(p) });
        this.tiesToNext_(argTiesToNext);
        ^this
    }

    // One pitch may appear once. The error below says why, and what it costs
    // unchecked is that the two runs merge and lengthen, so the chord sounds
    // longer than it was written.
    prCheckedPitches { |list|
        list.do { |pitch, index|
            var earlier = list.copyRange(0, index - 1);
            if (index > 0 and: { earlier.any { |other| other == pitch } }) {
                Error("Chord: % appears twice. A chord ties per pitch, so a "
                    "repeated one has two tie flags and no way to tell which a "
                    "continuation belongs to. For doubling at the unison, give "
                    "each its own Voice.".format(pitch)).throw
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
            Error("Chord: tiesToNext must be true, false, or one Boolean per "
                "pitch; got a %".format(spec.class)).throw
        };
        if (spec.size != pitches.size) {
            Error("Chord: % tie flags for % pitches - a partial chord tie needs "
                "one flag per pitch".format(spec.size, pitches.size)).throw
        };
        // Element by element, before normalizing. Coercing with `== true` would
        // quietly read a typo like \yes as "does not tie".
        spec.do { |flag, i|
            if (flag.isKindOf(Boolean).not) {
                Error("Chord: tie flag % is a %, not a Boolean; got %".format(
                    i, flag.class, spec)).throw
            }
        };
        if (spec.notEmpty and: { spec.every { |flag| flag.not } }) {
            Error("Chord: a partial tie that ties nothing. Pass false rather "
                "than all-false flags.").throw
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
