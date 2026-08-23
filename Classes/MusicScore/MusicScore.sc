// MusicScore rather than Score, because `Score` is core (NRT).


// A whole score: staves, and the title and composer a page is headed with.
//
// The only root ScoreJSON accepts. A bare `Measure` is a fragment there. The
// envelope is a document fact, not music content.
MusicScore : ScoreContainer {
    var <>title, <>composer;

    *new { |children, title, composer|
        ^super.new(children).initMusicScore(title, composer)
    }

    // Short spelling for `MusicScore([Staff([bar])])`, one measure or many.
    //
    // >>> MusicScore.oneStaff(Measure(Meter(1, 4), [MN(60, Duration(1, 4))])).leaves.size
    // 1
    *oneStaff { |measures, name, clef = \treble, title, composer|
        ^this.new([Staff(this.prMeasuresOf(measures), name, clef)],
            title, composer)
    }

    // Written bars stand for bars. Treat one String as one bar, not characters.
    //
    // Mixed object/String lists stay mixed.
    *prMeasuresOf { |measures|
        if (measures.isKindOf(String)) { ^[Measure.notation(measures)] };
        ^measures.asArray.collect { |each|
            if (each.isKindOf(String)) { Measure.notation(each) } { each } }
    }

    // The same shortening for several staves: one Event each, written
    // `(name:, shortName:, clef:, measures:)`, where a bar may be
    // written out:
    //
    //   MusicScore.staves([
    //       (name: "Flute", clef: \treble, measures: [
    //           "4/4 c'4 d'4 e'4 f'4", "4/4 3:2[g'4 a'4 b'4] c''4 r4"]),
    //       (name: "Cello", clef: \bass, measures: [
    //           "4/4 c,4 r4 e,4 g,4", Measure.rest("4/4")])
    //   ], "Study")
    //
    // `staves`, not `parts`: grouping staves into parts is not modeled here.
    //
    // >>> MusicScore.staves([(measures: Measure("1/4", "c4"))]).children.first.clef
    // treble
    *staves { |specs, title, composer|
        ^this.new(specs.asArray.collect { |spec| this.prStaffSpec(spec) },
            title, composer)
    }

    // Every key is checked.
    *prStaffSpec { |spec|
        var unknown;
        if (spec.isKindOf(Event).not) {
            Error("MusicScore.staves: expected a staff spec Event, got a %."
                .format(spec.class)).throw
        };
        unknown = spec.keys.asArray.reject { |key|
            [\name, \shortName, \clef, \measures].includes(key) }.sort;
        if (unknown.notEmpty) {
            Error("MusicScore.staves: unknown staff spec key(s): %. Use name, "
                "shortName, clef and measures.".format(
                    unknown.collect { |key| key.asString }.join(", "))).throw
        };
        if (spec[\measures].isNil) {
            Error("MusicScore.staves: staff % has no measures.".format(
                    spec[\name].asCompileString)).throw
        };
        // `clef: nil` reads as the default in an Event.
        ^Staff(this.prMeasuresOf(spec[\measures]), spec[\name],
            spec[\clef] ? \treble, spec[\shortName])
    }

    initMusicScore { |argTitle, argComposer|
        title = argTitle;
        composer = argComposer;
        ^this
    }

    accept { |writer| ^writer.visitScore(this) }

    // No asLily / asMusicXML / asJSON convenience methods: the model
    // names no output format.
}
