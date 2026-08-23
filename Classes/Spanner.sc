// Note [Kinds that cannot overlap]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Hairpins, text, beams and glissandi do not overlap. Slurs may.
// Backends that cannot spell a specific overlap refuse it. Each
// endpoint answers `permitsOverlap`, so the rule stays with the kind.


// Spanner: one endpoint of a marking that reaches across leaves.
//
// A spanner is a pair. The model stores endpoints; `Validator` checks
// that they meet. Slurs, hairpins, text spanners, beams and glissandi
// share this endpoint shape. Kind, edge and hairpin direction are
// closed sets.
//
// Starts carry the fact. Stops carry only the id. Hairpin starts
// carry a direction; text starts carry prose and placement; beam
// starts carry neither. `id` pairs the ends. Only slurs can overlap,
// so only `Spanner.slur` exposes it on the group helper.
Spanner {
    classvar <kinds, <edges, <directions, <nonOverlapping, <directionHeads;

    var <kind, <edge, <id, <direction, <text, <placement;

    *initClass {
        kinds = [\slur, \hairpin, \text, \beam, \glissando];
        edges = [\start, \stop];
        directions = [\crescendo, \diminuendo];
        // See Note [Kinds that cannot overlap] above.
        nonOverlapping = [\hairpin, \text, \beam, \glissando];
        // See Note [A head is a spelling, a direction is the fact] below.
        directionHeads = [
            ["crescendo",  \crescendo],
            ["cresc",      \crescendo],
            ["diminuendo", \diminuendo],
            ["dim",        \diminuendo]
        ];
    }


    // Note [A head is a spelling, a direction is the fact]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `directions` is the model vocabulary. `directionHeads` is
    // parser spelling: a written head paired with the direction it
    // names. Abbreviations, not synonyms. `cresc` and `dim` shorten
    // the model words; they do not add names.

    // The direction a written head names, or nil.
    //
    // >>> Spanner.directionNamed("dim")     -> diminuendo
    // >>> Spanner.directionNamed("cresc")   -> crescendo
    // >>> Spanner.directionNamed("c")       -> nil
    *directionNamed { |head|
        var text = head.asString;
        var found = directionHeads.detect { |pair| pair[0] == text };
        ^found !? { found[1] }
    }

    // Every written head, for a refusal that lists what this grammar knows.
    //
    // >>> Spanner.directionSpellings   -> [ crescendo, cresc, diminuendo, dim ]
    *directionSpellings { ^directionHeads.collect { |pair| pair[0] } }

    // Written heads for a glissando group.
    //
    // >>> Spanner.glissandoSpellings   -> [ gliss, glissando ]
    *glissandoSpellings { ^["gliss", "glissando"] }

    // Compared by value: `includes` tests Strings by identity.
    //
    // >>> Spanner.isGlissandoHead("gliss")   -> true
    // >>> Spanner.isGlissandoHead("gl")      -> false
    *isGlissandoHead { |head|
        var text = head.asString;
        ^this.glissandoSpellings.any { |each| each == text }
    }

    *slurStart { |id = 1| ^this.of(\slur, \start, id) }
    *slurStop { |id = 1| ^this.of(\slur, \stop, id) }

    // >>> Spanner.hairpinStart(\crescendo).direction   -> crescendo
    *hairpinStart { |direction, id = 1| ^this.of(\hairpin, \start, id, direction) }
    *hairpinStop { |id = 1| ^this.of(\hairpin, \stop, id) }

    // >>> Spanner.textStart("cresc.", 2, \below).placement   -> below
    *textStart { |text, id = 1, placement = \above|
        ^this.of(\text, \start, id, text: text, placement: placement)
    }
    *textStop { |id = 1| ^this.of(\text, \stop, id) }

    // Beam groups are authored. `AutoBeam` may derive them, but the model stores
    // explicit endpoints.
    *beamStart { |id = 1| ^this.of(\beam, \start, id) }
    *beamStop { |id = 1| ^this.of(\beam, \stop, id) }

    // A glissando joins neighboring attacks.
    // See Note [A glissando is a chain, not a span].
    *glissandoStart { |id = 1| ^this.of(\glissando, \start, id) }
    *glissandoStop { |id = 1| ^this.of(\glissando, \stop, id) }


    // Note [A group cannot dangle]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A group starts on the first child and stops on the last. Both
    // ends must be leaves; a bracket may stand between them. Endpoint
    // helpers still serve cross-barline spans. Only the slur group
    // helper exposes an id.


    // >>> { |a, b| Spanner.slur([a, b]);
    //     [a.spannerStarts.size, b.spannerStops.size] }
    //     .value(MusicNote(60, Duration(1, 4)), MusicNote(62, Duration(1, 4)))
    // [ 1, 1 ]
    *slur { |leaves, id = 1|
        ^this.prGroup(leaves, this.slurStart(id), this.slurStop(id), "Spanner.slur")
    }

    // No id: beams do not overlap.
    *beam { |leaves| ^this.prGroup(leaves, this.beamStart, this.beamStop, "Spanner.beam") }

    // Two named helpers because the direction is which span this is.
    //
    // >>> Spanner.crescendo("c4 d4").first.spannerStarts.first.direction   -> crescendo
    *crescendo { |leaves|
        ^this.prGroup(leaves, this.hairpinStart(\crescendo), this.hairpinStop,
            "Spanner.crescendo")
    }
    // >>> Spanner.diminuendo("c4 d4").first.spannerStarts.first.direction   -> diminuendo
    *diminuendo { |leaves|
        ^this.prGroup(leaves, this.hairpinStart(\diminuendo), this.hairpinStop,
            "Spanner.diminuendo")
    }

    // Prose and placement go on the start.
    //
    // >>> Spanner.text("c4 d4", "sul pont.").first.spannerStarts.first.text   -> sul pont.
    *text { |leaves, text, placement = \above|
        ^this.prGroup(leaves, this.textStart(text, 1, placement), this.textStop,
            "Spanner.text")
    }

    // Note [A glissando is a chain, not a span]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Every other kind spans a run. A glissando joins neighboring
    // attacks, so three leaves are two lines and the middle leaf
    // carries a stop and a start. The pairs are the model fact.
    // Syntax and writers do not remember which spelling built them.
    // Manual endpoints still serve pairs this helper will not build,
    // including one across a barline.

    // Pairwise over the leaves inside, brackets included. Answers the children
    // as written, so a tuplet stays a tuplet.
    //
    // >>> Spanner.glissando("c4 d4").first.spannerStarts.first.kind   -> glissando
    // >>> Spanner.glissando("c4 e4 d4")[1].spanners.collect { |e| e.edge }
    // [ stop, start ]
    *glissando { |leaves|
        var group = this.prLeafGroup(leaves, "Spanner.glissando", \glissando);
        var flat = group.inject([], { |all, child|
            all ++ if (child.isKindOf(ScoreLeaf)) { [child] } { child.leaves } });
        if (flat.size < 2) {
            Error("Spanner.glissando: a glissando needs at least two attacks, "
                "got %.".format(flat.size)).throw
        };
        // Keep helper and written syntax in agreement. Manual endpoints are
        // checked later by `Validator`.
        flat.do { |leaf|
            if (leaf.isKindOf(MusicRest)) {
                Error("Spanner.glissando: a glissando holds a rest. Close the "
                    "chain before it.").throw
            }
        };
        flat.doAdjacentPairs { |from, to|
            from.attach(this.glissandoStart);
            to.attach(this.glissandoStop);
        };
        ^group
    }

    // Takes built endpoints. Helpers decide what the start carries,
    // this decides where the endpoints go. Check the run before
    // attaching either end. Ends must be leaves. Brackets may stand
    // between them and stay in the answered group.
    //
    // Coverage rules stay in `Validator`.
    *prGroup { |leaves, start, stop, label|
        var group = this.prLeafGroup(leaves, label, start.kind);
        if (group.size < 2) {
            Error("Spanner: a % needs at least two leaves, got %.".format(
                    start.kind, group.size)).throw
        };
        [[group.first, "start"], [group.last, "stop"]].do { |end|
            if (end[0].isKindOf(ScoreLeaf).not) {
                Error("%: a % takes a leaf at its %, got %. A container may "
                    "stand between the ends but not at one.".format(
                        label, start.kind, end[1], end[0])).throw
            }
        };
        group.first.attach(start);
        group.last.attach(stop);
        ^group
    }

    // Read a helper body as leaves and tuplets. The caller decides where
    // endpoints land.
    *prLeafGroup { |leaves, label, kind = \spanner|
        var group;
        // Containers are not phrases. Use a selection to say which leaves.
        if (leaves.isKindOf(ScoreElement) and: { leaves.isLeaf.not }) {
            Error("%: a % is not a run of leaves. Use a ScoreSelection and a "
                "filter, e.g. ScoreSelection(bar).runs.first.".format(label, leaves.class)).throw
        };
        group = ScoreNotation.prChildrenOf(leaves ? [], label,
            containers: true, hairpinGroups: false).asArray;
        group.do { |child|
            if (child.isKindOf(ScoreLeaf).not and: {
                child.isKindOf(Tuplet).not
            }) {
                Error("%: a % group holds leaves and brackets, got %."
                    .format(label, kind, child)).throw
            }
        };
        ^group
    }

    *of { |kind, edge, id = 1, direction, text, placement|
        var checkedKind = this.prCheck(kind, kinds, "kind");
        var checkedEdge = this.prCheck(edge, edges, "edge");
        var prose = this.prCheckText(checkedKind, checkedEdge, text, placement);
        ^super.newCopyArgs(
            checkedKind, checkedEdge, this.prCheckId(id),
            this.prCheckDirection(checkedKind, checkedEdge, direction),
            prose[0], prose[1])
    }

    // Answers [text, placement]. Required on starts, refused on stops.
    *prCheckText { |kind, edge, text, placement|
        if (kind == \text and: { edge == \start }) {
            if (text.isNil) {
                Error("Spanner: a text spanner start needs text.").throw
            };
            ^[Marking.checkedText(text), Marking.checkedPlacement(placement)]
        };
        if (text.notNil) {
            Error("Spanner: a % % cannot carry text, got \"%\".".format(
                kind, edge, text)).throw
        };
        if (placement.notNil) {
            Error("Spanner: a % % cannot carry placement %.".format(
                    kind, edge, placement)).throw
        };
        ^[nil, nil]
    }

    // Required on a hairpin start, refused everywhere else: a stop
    // says nothing about which direction it closed, and a slur has
    // none. One place to write it means no pair can disagree.
    *prCheckDirection { |kind, edge, direction|
        if (kind == \hairpin and: { edge == \start }) {
            if (direction.isNil) {
                Error("Spanner: a hairpin start needs a direction. Use one of %."
                    .format(directions)).throw
            };
            ^this.prCheck(direction, directions, "direction")
        };
        if (direction.notNil) {
            Error("Spanner: a % % cannot carry direction %.".format(
                kind, edge, direction)).throw
        };
        ^nil
    }

    *prCheck { |value, vocabulary, what|
        if (value.isNil) {
            Error("Spanner: a spanner needs a %. Use one of %.".format(
                what, vocabulary)).throw
        };
        if (vocabulary.includes(value.asSymbol).not) {
            Error("Spanner: \"%\" is not a spanner %. Use one of %.".format(
                value, what, vocabulary)).throw
        };
        ^value.asSymbol
    }

    // Ids pair the ends, so they have to be countable and distinguishable.
    *prCheckId { |id|
        if (id.isKindOf(Integer).not or: { id < 1 }) {
            Error("Spanner: id must be a positive integer, got %.".format(id)).throw
        };
        ^id
    }

    // >>> Spanner.slurStart.isStart          -> true
    // >>> Spanner.slurStop.isStop            -> true
    // >>> Spanner.beamStart.isBeam           -> true
    // >>> Spanner.textStart("cresc.").isText  -> true
    // >>> Spanner.glissandoStart.isGlissando  -> true
    isStart   { ^edge == \start   }
    isStop    { ^edge == \stop    }
    isSlur    { ^kind == \slur    }
    isHairpin { ^kind == \hairpin }
    isText    { ^kind == \text    }
    isBeam    { ^kind == \beam    }
    isGlissando { ^kind == \glissando }

    // Whether a second one of this kind may be open while this one is.
    //
    // >>> Spanner.slurStart.permitsOverlap                  -> true
    // >>> Spanner.beamStart.permitsOverlap                  -> false
    // >>> Spanner.hairpinStart(\crescendo).permitsOverlap   -> false
    permitsOverlap { ^nonOverlapping.includes(kind).not }

    // >>> Spanner.slurStart(2) == Spanner.slurStart(2)                 -> true
    // >>> Spanner.slurStart(2) == Spanner.slurStart(3)                 -> false
    // >>> Spanner.textStart("x").hash == Spanner.textStart("x").hash   -> true
    == { |that| ^that.isKindOf(Spanner) and: {
        (kind == that.kind) and: { (edge == that.edge) and: {
        (id == that.id) and: { (direction == that.direction) and: {
        (text == that.text) and: { placement == that.placement } } } } } } }
    hash { ^kind.hash bitXor: edge.hash bitXor: id.hash bitXor: direction.hash
        bitXor: text.hash bitXor: placement.hash }

    printOn { |stream|
        stream << "Spanner(" << kind << ", " << edge << ", " << id;
        if (direction.notNil) { stream << ", " << direction };
        if (text.notNil) { stream << ", \"" << text << "\", " << placement };
        stream << ")"
    }
}
