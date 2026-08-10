// The score tree spine: three abstract classes, no output syntax.
//
// Writers visit the tree by double dispatch through `accept`. Nothing in this
// file, or in Duration, MusicPitch, RhythmTree, knows anything about LilyPond
// or MusicXML.

ScoreElement {
    var <>parent;

    *new { ^super.new }

    // Written duration, before any enclosing tuplet multiplier.
    duration { ^this.subclassResponsibility(thisMethod) }

    // How this node scales the written time of its children.
    // Only tuplets return anything other than 1.
    //
    // >>> MusicNote(60, Duration(1, 4)).multiplier   -> Duration(1/1)
    // >>> Tuplet.ratio(3, 2).multiplier              -> Duration(2/3)
    multiplier { ^Duration(1, 1) }

    // Duration as own multiplier plus every ancestor's. A quarter inside a
    // triplet is written as one and lasts a sixth.
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

    leaves {
        var acc = List.new;
        this.traverse { |c| if (c.isLeaf) { acc.add(c) } };
        ^acc.asArray
    }
}


// Anything that occupies time without containing anything.
//
// Markings sit here rather than on `MusicNote` because all three leaves take
// them, a chord plainly and a rest at least for text like "mute", and because
// the leaf is what preparation copies. The lists are replaced rather than
// mutated, so a copied leaf shares nothing with its original: preparation
// promises the old tree is untouched, and a shared array would break that.
// Note [A grace group is not rhythm]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Grace notes are notation *on* the leaf that follows them, so they hang off
// that leaf rather than standing in the tree as leaves of their own. The whole
// of their arithmetic follows from where they are kept: `graces` is not
// `children`, nothing that sums written time ever reaches it, and a bar holding
// a graced note is exactly as full as one holding the note alone. There is no
// zero duration anywhere, which is the point. A zero-length leaf would have to
// be excluded by hand from `duration`, `prolatedDuration`, `isFull`, the
// divisions arithmetic and the metric hierarchy, and each of those is a place
// to forget.
//
// A grace leaf is an ordinary `MusicNote` or `Chord`, and its duration is a
// display value: what note head to draw, never a length. metasonic-score makes
// that distinction in the type system, with a `GraceDuration` separate from
// `Duration`. sclang cannot, so it is structural here instead, which is the
// same guarantee reached the other way round: a value nothing reads cannot be
// read wrongly.
//
// `style` is `\grace` or `\acciaccatura`, and the two differ only in how a
// writer spells them, `\grace` against `\acciaccatura` and `<grace/>` against
// `<grace slash="yes"/>`. Appoggiatura is deliberately absent: LilyPond infers
// the time it steals from the host, MusicXML wants it stated as a percentage in
// `steal-time-following`, and the admission rule asks for agreement rather than
// for two backends that can each say something.
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

    duration { ^dur }
    isLeaf { ^true }

    // Answers this leaf, so anything attaches inline inside an array literal.
    //
    // Three lists, because they behave differently under splitting: a marking
    // belongs to the attack, a spanner's two ends move to opposite pieces, and
    // a grace group goes where the marking goes, being an ornament of the
    // attack and not of the note's length. One word to attach a marking or a
    // spanner, because that difference is not the author's. A grace group has
    // its own word, being leaves rather than a decoration of one.
    //
    // >>> MusicNote(60, Duration(1, 4)).dynamic(\ff).dynamics.size   -> 1
    // >>> MusicNote(60, Duration(1, 4)).hasMarkings                  -> false
    attach { |thing|
        if (thing.isKindOf(Marking)) { markings = markings ++ [thing]; ^this };
        if (thing.isKindOf(Spanner)) { spanners = spanners ++ [thing]; ^this };
        Error("ScoreLeaf: % is neither a Marking nor a Spanner. Build one with "
            "Marking.dynamic, Marking.articulation, Marking.text, or "
            "Spanner.slurStart / Spanner.slurStop. A grace group is attached "
            "with `grace` or `acciaccatura` instead, being leaves rather than a "
            "decoration of one.".format(thing)).throw
    }

    // The grace group before this leaf. See Note [A grace group is not rhythm].
    // Singular sets and plural reads, as `text` sits beside `texts`.
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
            Error("ScoreLeaf: % is not a grace style. It is one of %."
                .format(style, ScoreLeaf.graceStyles.join(", "))).throw
        };
        graces = (list ? []).asArray.copy;
        graceStyle = style;
        ^this
    }

    hasGraces { ^graces.notEmpty }


    // Note [Fluent helpers are sugar]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The same act said shorter, and nothing else: each delegates to `attach`
    // and builds through `Marking`'s own factory, so what a value may be is
    // decided in one place. A helper that checked anything itself would be a
    // second behavior wearing a shorter name.
    //
    // Singular sets, plural reads: `text` beside `texts`, as `dynamic` sits
    // beside `dynamics`. The receiver and the argument say which is meant.


    dynamic { |value| ^this.attach(Marking.dynamic(value)) }
    articulation { |value| ^this.attach(Marking.articulation(value)) }
    text { |value, placement = \above|
        ^this.attach(Marking.text(value, placement))
    }

    // The endpoints, on the same terms. These say which end this leaf carries
    // and nothing more: whether the other end exists is `Validator`'s question,
    // and asking it here would answer it twice and differently.
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
    articulations { ^this.markingsOfKind(\articulation) }
    texts { ^this.markingsOfKind(\text) }
}


// Anything that holds other elements in time order.
// Note [Adding a child repoints its parent]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `add` sets `element.parent`, so an element belongs to the last container that
// took it. Two consequences, both load-bearing elsewhere.
//
// An element cannot be in two trees at once. `ScorePrepare` copies rather than
// reusing for exactly this reason. See Note [Copy, never adopt] there, and
// `RhythmTree.voice` builds its Voice directly rather than filling a Measure
// and lifting the children out, which would leave every one of them pointing at
// a bar that is not theirs.
//
// `Validator` checks the links, so getting it wrong is loud rather than a tree
// that walks strangely.


// A node with children, which it owns: see the Note above.
ScoreContainer : ScoreElement {
    var <children;

    *new { |children| ^super.new.initScoreContainer(children) }

    initScoreContainer { |argChildren|
        children = List.new;
        (argChildren ? []).do { |c| this.add(c) };
        ^this
    }

    add { |element|
        element.parent = this;
        children.add(element);
        ^this
    }

    addAll { |elements| elements.do { |c| this.add(c) }; ^this }

    at { |i| ^children[i] }
    size { ^children.size }
    do { |func| children.do(func) }

    // How much space the contents take up here. Each child contributes what it
    // occupies, which is its own written duration times its own multiplier: a
    // leaf contributes what it is written as, a 3:2 triplet of quarters is
    // written as 3/4 and occupies 1/2.
    //
    // This container's own multiplier is deliberately not applied. Applying it
    // is the parent's job when it asks the same question one level up, and that
    // is what keeps `duration` meaning the same thing, written rather than
    // sounding, at every level of the tree. `prolatedDuration` walks up.
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
