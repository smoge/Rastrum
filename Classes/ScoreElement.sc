// The score tree spine: three abstract classes, no output syntax.
//
// Writers visit by double dispatch through `accept`. This file knows
// no writer syntax.
ScoreElement {
    var <>parent;

    *new { ^super.new }

    // Written duration, before any enclosing tuplet multiplier.
    duration { ^this.subclassResponsibility(thisMethod) }

    // How this node scales the written time of its children. Only
    // tuplets return anything other than 1.
    //
    // >>> MusicNote(60, Duration(1, 4)).multiplier   -> Duration(1/1)
    // >>> Tuplet.ratio(3, 2).multiplier              -> Duration(2/3)
    multiplier { ^Duration(1, 1) }

    // Duration as own multiplier plus every ancestor's. A quarter
    // inside a triplet is written as one and lasts a sixth.
    //
    // >>> Tuplet.ratio(3, 2, [MusicNote(60, Duration(1, 4))])
    //     .leaves[0].duration   -> Duration(1/4)
    // >>> Tuplet.ratio(3, 2, [MusicNote(60, Duration(1, 4))])
    //     .leaves[0].prolatedDuration   -> Duration(1/6)
    prolatedDuration {
        var d = this.duration * this.multiplier, p = parent;
        while { p.notNil } { d = d * p.multiplier; p = p.parent };
        ^d
    }

    isLeaf { ^false }
    accept { |writer| ^this.subclassResponsibility(thisMethod) }
    traverse { |func| func.value(this) }

    // >>> Tuplet.ratio(3, 2, [MN("c4"), MN("d4"), MN("e4")]).leaves.size   -> 3
    leaves {
        var acc = List.new;
        this.traverse { |c| if (c.isLeaf) { acc.add(c) } };
        ^acc.asArray
    }
}


// Note [A grace group is not rhythm]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Grace notes are notation on the leaf that follows them. They hang
// off that leaf instead of standing in the tree as zero-length
// leaves. A grace leaf is a `MusicNote` or `Chord`. Its duration is
// display value.
//
// `style` is `\grace` or `\acciaccatura`.


// Anything that occupies time without containing anything.
//
// Markings sit here because all leaf kinds take them. Lists are
// replaced, not mutated, so copied leaves share nothing with their
// originals.
ScoreLeaf : ScoreElement {
    classvar <graceStyles;

    var <>dur, <markings, <spanners, <graces, <graceStyle;

    *initClass { graceStyles = [\grace, \acciaccatura] }

    initScoreLeaf { |argDur|
        dur = Duration.asDuration(argDur);
        markings = [];
        spanners = [];
        graces = [];
        graceStyle = \grace;
        ^this
    }

    duration { ^dur  }
    isLeaf   { ^true }

    // Answers this leaf, so attachments chain inline. Markings,
    // spanner endpoints and graces split differently, so they are
    // stored separately.
    //
    // >>> MusicNote(60, Duration(1, 4)).dynamic(\ff).dynamics.size   -> 1
    // >>> MusicNote(60, Duration(1, 4)).hasMarkings                  -> false
    attach { |thing|
        if (thing.isKindOf(Marking)) { markings = markings ++ [thing]; ^this };
        if (thing.isKindOf(Spanner)) { spanners = spanners ++ [thing]; ^this };
        Error("ScoreLeaf: % is neither a Marking nor a Spanner. Use `grace` or "
            "`acciaccatura` for grace leaves.".format(thing)).throw
    }

    // The grace group before this leaf. See Note [A grace group is not rhythm].
    //
    // >>> MusicNote(60, Duration(1, 4)).grace(MN(62, Duration(1, 8))).graces.size
    // 1
    // >>> MusicNote(60, Duration(1, 4)).acciaccatura(
    //     MN(62, Duration(1, 8))).graceStyle   -> acciaccatura
    // >>> MusicNote(60, Duration(1, 4)).hasGraces   -> false
    grace { |leaves| ^this.graces_(leaves, \grace) }
    acciaccatura { |leaves| ^this.graces_(leaves, \acciaccatura) }

    graces_ { |list, style = \grace|
        if (ScoreLeaf.graceStyles.includes(style).not) {
            Error("ScoreLeaf: % is not a grace style. Use one of %."
                .format(style, ScoreLeaf.graceStyles.join(", "))).throw
        };
        graces = ScoreLeaf.prGraceLeaves(list, style);
        graceStyle = style;
        ^this
    }

    // A written group is a leaf run without rests.
    // See Note [A run of leaves is a run of leaves] in ScoreNotation.sc.
    *prGraceLeaves { |list, style|
        ^ScoreNotation.prChildrenOf(list ? [], "ScoreLeaf.%".format(style),
            rests: false, containers: false).asArray.copy
    }

    hasGraces { ^graces.notEmpty }


    // Note [Fluent helpers are sugar]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Short forms over `attach`. `Marking` still owns the checks.
    //
    // Singular sets, plural reads: `text` beside `texts`, `dynamic`
    // beside `dynamics`.


    dynamic { |value| ^this.attach(Marking.dynamic(value)) }
    // See Note [A sforzando is an accent at a level] in Marking.sc.
    sforzando { |level| ^this.attach(Marking.sforzando(level)) }
    articulation { |value| ^this.attach(Marking.articulation(value)) }
    // See Note [A technical mark is not an articulation] in Marking.sc.
    technical { |value| ^this.attach(Marking.technical(value)) }
    text { |value, placement = \above|
        ^this.attach(Marking.text(value, placement))
    }

    // Note [Text has no bare suffix]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Written prose uses braces, bare suffixes are closed vocabulary
    // words. This is the value-form entry point for the same text
    // helper.

    *prTexted { |leaf, text, placement = \above|
        if (text.isNil) { ^leaf };
        ^leaf.text(text, placement)
    }

    // Endpoints say which end this leaf carries. Pairing is `Validator`'s job.
    slurStart { |id = 1| ^this.attach(Spanner.slurStart(id)) }
    slurStop { |id = 1| ^this.attach(Spanner.slurStop(id)) }

    hairpinStart { |direction, id = 1|
        ^this.attach(Spanner.hairpinStart(direction, id))
    }
    hairpinStop { |id = 1| ^this.attach(Spanner.hairpinStop(id)) }

    textStart { |text, id = 1, placement = \above|
        ^this.attach(Spanner.textStart(text, id, placement))
    }
    textStop { |id = 1| ^this.attach(Spanner.textStop(id)) }

    beamStart { |id = 1| ^this.attach(Spanner.beamStart(id)) }
    beamStop { |id = 1| ^this.attach(Spanner.beamStop(id)) }
    // Ends are adjacent attacks. See Note [A glissando is a chain, not a span].
    glissandoStart { |id = 1| ^this.attach(Spanner.glissandoStart(id)) }
    glissandoStop { |id = 1| ^this.attach(Spanner.glissandoStop(id)) }

    markings_ { |list| markings = (list ? []).asArray.copy; ^this }
    spanners_ { |list| spanners = (list ? []).asArray.copy; ^this }

    // >>> MusicNote(60, Duration(1, 4)).hasSpanners   -> false
    // >>> MusicNote(60, Duration(1, 4)).slurStart.spannerStarts.size    -> 1
    // >>> MusicNote(60, Duration(1, 4)).text("sul pont.").texts.size    -> 1
    // >>> MusicNote(60, Duration(1, 4)).dynamic(\ff)
    //     .markingsOfKind(\dynamic).size   -> 1
    hasSpanners { ^spanners.notEmpty }
    spannerStarts { ^spanners.select { |s| s.isStart } }
    spannerStops { ^spanners.select { |s| s.isStop } }

    hasMarkings { ^markings.notEmpty }
    markingsOfKind { |kind| ^markings.select { |m| m.kind == kind } }
    dynamics { ^this.markingsOfKind(\dynamic) }
    sforzandos { ^this.markingsOfKind(\sforzando) }
    articulations { ^this.markingsOfKind(\articulation) }
    technicals { ^this.markingsOfKind(\technical) }
    texts { ^this.markingsOfKind(\text) }
}


// Anything that holds other elements in time order.
// Note [Adding a child repoints its parent]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `add` sets `element.parent`, so an element belongs to the last
// container that took it. `ScorePrepare` copies before rebuilding.


// A node with children it owns.
ScoreContainer : ScoreElement {
    var <children;

    *new { |children| ^super.new.initScoreContainer(children) }

    initScoreContainer { |argChildren|
        children = List.new;
        (argChildren ? []).do { |c| this.add(c) };
        ^this
    }

    // >>> { var n = MN("c4"); var v = Voice([]).add(n); n.parent == v }.value   -> true
    add { |element|
        element.parent = this;
        children.add(element);
        ^this
    }

    addAll { |elements| elements.do { |c| this.add(c) }; ^this }

    // >>> Voice([MN("c4"), MN("d4")]).size   -> 2
    // >>> Voice([MN("c4"), MN("d4")]).at(1).pitch   -> MusicPitch("d[4]")
    at { |i| ^children[i] }
    size { ^children.size }
    do { |func| children.do(func) }

    // Written duration of the contents here: each child duration
    // times its own multiplier. This container's own multiplier
    // belongs to the parent. `prolatedDuration` walks up for sounding
    // span.
    //
    // >>> Tuplet.ratio(3, 2, [MusicNote(60, Duration(1, 4)),
    //     MusicNote(62, Duration(1, 4)), MusicNote(64, Duration(1, 4))])
    //     .duration   -> Duration(3/4)
    // >>> Tuplet.ratio(3, 2, [MusicNote(60, Duration(1, 4)),
    //     MusicNote(62, Duration(1, 4)), MusicNote(64, Duration(1, 4))])
    //     .prolatedDuration   -> Duration(1/2)
    duration {
        var sum = Duration(0, 1);
        children.do { |c| sum = sum + (c.duration * c.multiplier) };
        ^sum
    }

    traverse { |func|
        func.value(this);
        children.do { |c| c.traverse(func) };
    }

    accept { |writer| ^writer.visitContainer(this) }
}
