// Spanner: one endpoint of a marking that reaches across leaves.
//
// A point marking sits on a leaf and is finished (see Marking). A spanner is a
// pair and only means something as one, so the model stores endpoints and
// `Validator` checks that they meet: half a pair is an error, not half a slur.
//
// One generic class, because a slur and a hairpin are the same shape. An
// endpoint is a kind, an edge and an id, plus whatever the start of its kind
// says. Kind, edge and a hairpin's direction are closed sets: see
// Note [Refuse at the constructor] in MusicPitch.sc.
//
// The start says what the pair says, the stop says nothing but its id, so two
// ends have nowhere to disagree. A hairpin start is a crescendo or a
// diminuendo. A text start carries prose and which side of the staff it sits
// on, held to `Marking`'s rule for prose. A beam start carries neither, since
// what a beam says is entirely which notes it joins.
//
// `id` pairs the ends explicitly. Matching by proximity would guess wrong once
// spans overlap. Only slurs can overlap, so `Spanner.slur` is the only group
// helper that exposes an id.

// Note [Kinds that cannot overlap]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Hairpins and text may not be open twice at once, whatever their ids, because
// LilyPond spells one per voice and cannot say which of two a closing mark
// closes. A second open one is a shape only MusicXML could express, which
// Note [The admission rule] in ScoreWriter.sc keeps out. Beams because a note
// belongs to one beam group or to none. Slurs are absent: both backends number
// them.
//
// Each endpoint answers `permitsOverlap` for itself, so a new kind declares its
// own rule where it is defined rather than in `Validator`.
Spanner {
    classvar <kinds, <edges, <directions, <nonOverlapping;

    var <kind, <edge, <id, <direction, <text, <placement;

    *initClass {
        kinds = [\slur, \hairpin, \text, \beam];
        edges = [\start, \stop];
        directions = [\crescendo, \diminuendo];
        // See Note [Kinds that cannot overlap] above.
        nonOverlapping = [\hairpin, \text, \beam];
    }

    *slurStart { |id = 1| ^this.of(\slur, \start, id) }
    *slurStop { |id = 1| ^this.of(\slur, \stop, id) }

    *hairpinStart { |direction, id = 1| ^this.of(\hairpin, \start, id, direction) }
    *hairpinStop { |id = 1| ^this.of(\hairpin, \stop, id) }

    *textStart { |text, id = 1, placement = \above|
        ^this.of(\text, \start, id, text: text, placement: placement)
    }
    *textStop { |id = 1| ^this.of(\text, \stop, id) }

    // Beam groups are authored, not inferred: the same eighth notes group
    // differently in 6/8 and 3/4, and a phrase overrides either. Automatic
    // beaming can be built on an explicit one. The reverse cannot.
    *beamStart { |id = 1| ^this.of(\beam, \start, id) }
    *beamStop { |id = 1| ^this.of(\beam, \stop, id) }


    // Note [A group cannot dangle]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A whole spanner at once: start on the first leaf, stop on the last, id
    // held here. Answers the leaves, so a group goes inline where they would:
    //
    // Measure(Meter(4, 8), Spanner.beam([...]))
    //
    // This shape cannot dangle. Nest by giving the inner span its own slice and
    // id. Endpoint helpers still serve inline leaves, such as a slur across a
    // barline.
    //
    // Only the slur group helper exposes an id, and it defaults. The validator
    // refuses a second default slur while the first is open.


    // >>> { |a, b| Spanner.slur([a, b]);
    //     [a.spannerStarts.size, b.spannerStops.size] }
    //     .value(MusicNote(60, Duration(1, 4)), MusicNote(62, Duration(1, 4)))
    // [ 1, 1 ]
    *slur { |leaves, id = 1| ^this.prGroup(leaves, this.slurStart(id), this.slurStop(id)) }

    // No id: by Note [Kinds that cannot overlap] there is never a second open
    // beam to tell this one from.
    *beam { |leaves| ^this.prGroup(leaves, this.beamStart, this.beamStop) }

    // Two named helpers rather than one `hairpin` taking a direction: the
    // direction is which thing this is, not a setting on it. `hairpinStart`
    // still takes it, being the form a program builds.
    *crescendo { |leaves|
        ^this.prGroup(leaves, this.hairpinStart(\crescendo), this.hairpinStop)
    }
    *diminuendo { |leaves|
        ^this.prGroup(leaves, this.hairpinStart(\diminuendo), this.hairpinStop)
    }

    // Prose and placement go on the start, per the header: an instruction that
    // lasts until canceled is written once, where it begins.
    *text { |leaves, text, placement = \above|
        ^this.prGroup(leaves, this.textStart(text, 1, placement), this.textStop)
    }

    // Takes built endpoints, so each helper above decides what its start
    // carries and this decides only where the two ends go. Every leaf is
    // checked before anything is attached: a group that threw part way would
    // leave a start with nothing closing it.
    *prGroup { |leaves, start, stop|
        var group = (leaves ? []).asArray;
        if (group.size < 2) {
            Error("Spanner: a % joins at least two leaves, and was given %. A "
                "spanner over one leaf would open and close on the same attack, "
                "which is a point marking rather than a span.".format(
                    start.kind, group.size)).throw
        };
        group.do { |leaf|
            if (leaf.isKindOf(ScoreLeaf).not) {
                Error("Spanner: a % group takes leaves - notes, rests and chords - "
                    "and was given %. A container has no attack of its own to carry "
                    "an end, so pass its leaves.".format(start.kind, leaf)).throw
            }
        };
        group.first.attach(start);
        group.last.attach(stop);
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

    // Returns [text, placement], on the same terms as direction: required on
    // the start, refused on the stop, which cancels rather than restates.
    *prCheckText { |kind, edge, text, placement|
        if (kind == \text and: { edge == \start }) {
            if (text.isNil) {
                Error("Spanner: a text spanner start needs something to say.").throw
            };
            ^[Marking.checkedText(text), Marking.checkedPlacement(placement)]
        };
        if (text.notNil) {
            Error("Spanner: a % % carries no text, but was given \"%\". Only a "
                "text spanner start says anything; the end that closes one "
                "repeats nothing.".format(kind, edge, text)).throw
        };
        if (placement.notNil) {
            Error("Spanner: a % % carries no placement, but was given \"%\". Only "
                "a text spanner start sits on a side of the staff.".format(
                    kind, edge, placement)).throw
        };
        ^[nil, nil]
    }

    // Required on a hairpin start, refused everywhere else: a stop says nothing
    // about which direction it closed, and a slur has none. One place to write
    // it means no pair can disagree.
    *prCheckDirection { |kind, edge, direction|
        if (kind == \hairpin and: { edge == \start }) {
            if (direction.isNil) {
                Error("Spanner: a hairpin start needs a direction; the choices are "
                    "%.".format(directions)).throw
            };
            ^this.prCheck(direction, directions, "direction")
        };
        if (direction.notNil) {
            Error("Spanner: a % % carries no direction, but was given \"%\". Only "
                "a hairpin start has one.".format(kind, edge, direction)).throw
        };
        ^nil
    }

    *prCheck { |value, vocabulary, what|
        if (value.isNil) {
            Error("Spanner: a spanner needs a %; the choices are %.".format(
                what, vocabulary)).throw
        };
        if (vocabulary.includes(value.asSymbol).not) {
            Error("Spanner: \"%\" is not a spanner %. The choices are %.".format(
                value, what, vocabulary)).throw
        };
        ^value.asSymbol
    }

    // Ids pair the ends, so they have to be countable and distinguishable.
    *prCheckId { |id|
        if (id.isKindOf(Integer).not or: { id < 1 }) {
            Error("Spanner: an id must be a positive integer, got %. Ids pair a "
                "start with its stop, so overlapping spanners can be told "
                "apart.".format(id)).throw
        };
        ^id
    }

    // >>> Spanner.slurStart.isStart          -> true
    // >>> Spanner.slurStop.isStop            -> true
    // >>> Spanner.beamStart.isBeam           -> true
    // >>> Spanner.textStart("cresc.").isText  -> true
    isStart   { ^edge == \start   }
    isStop    { ^edge == \stop    }
    isSlur    { ^kind == \slur    }
    isHairpin { ^kind == \hairpin }
    isText    { ^kind == \text    }
    isBeam    { ^kind == \beam    }

    // Whether a second one of this kind may be open while this one is. Asked of
    // the endpoint so the rule has one home rather than a list in the
    // validator.
    //
    // >>> Spanner.slurStart.permitsOverlap                  -> true
    // >>> Spanner.beamStart.permitsOverlap                  -> false
    // >>> Spanner.hairpinStart(\crescendo).permitsOverlap   -> false
    permitsOverlap { ^nonOverlapping.includes(kind).not }

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
