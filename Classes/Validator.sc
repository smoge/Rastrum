// Note [What a beam may hold]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A beam joins note heads: notes and chords shorter than a quarter.
// Mixed flag counts are allowed; `AutoBeam` derives secondary rows
// and hooks. A beam may cross a barline inside one timeline, but not
// a voice or staff. Preparation may split a beamed leaf; endpoints
// follow the fragments.


// Validator: structural checks on a score tree, before anything writes it.
//
// Tree mistakes the model can hold and writers would catch unevenly.
// Reads and answers the tree it was given. No repair, no writer syntax.
// Some rules are repeated in writers when a writer depends on them.
// See Note [A writer refuses what it depends on] in Writers/ScoreWriter.sc.
Validator {

    // Answers the element unchanged, or throws. `requirePrepared` adds the
    // post-`ScorePrepare` single-notehead checks.
    //
    // >>> Validator.validate(RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]))
    //     .isKindOf(Measure)   -> true
    // >>> try { Validator.validate(Measure(Meter(4, 4),
    //     [MusicNote(60, Duration(1, 2))])) } { \refused }   -> refused
    *validate { |element, requirePrepared = false|
        this.prCheckStructure(element, requirePrepared);
        this.prCheckTies(element);
        this.prCheckBeams(element);
        this.prCheckGlissandi(element);
        this.prCheckSpanners(element);
        this.prCheckDirectionSpans(element);
        ^element
    }

    // A tempo ramp is two bar-local directions. Pair it per staff.
    // Note [A tempo ramp is two directions] in Direction.sc.
    //
    // The group helper makes dangling impossible. This is the
    // backstop for endpoint helpers and the JSON reader.
    *prCheckDirectionSpans { |element|
        var staves = List.new, loose = List.new;
        element.traverse { |node|
            if (node.isKindOf(Staff)) { staves.add(node) };
            if (node.isKindOf(Measure) and: { this.prStaffAbove(node).isNil }) {
                loose.add(node)
            };
        };
        staves.do { |staff|
            this.prCheckDirectionSpanRun(
                staff.children.select { |bar| bar.isKindOf(Measure) },
                "the staff ends")
        };
        loose.do { |measure|
            this.prCheckDirectionSpanRun([measure], "the bar ends")
        };
        ^this
    }

    // Offset order within each bar. Stops sort before starts only at
    // the same offset, so one ramp may end where the next begins.
    //
    // Refuse any second open ramp. The model says nothing about combining two.
    *prCheckDirectionSpanRun { |measures, ending|
        var open = Dictionary.new;
        measures.do { |measure|
            var ramps = measure.directions.select { |each| each.isTempoRamp };
            this.inSpanOrder(ramps).do { |endpoint|
                if (endpoint.isRampStop) {
                    if (open[endpoint.id].isNil) {
                        Error("Validator: tempo ramp stop id % has no open "
                            "start in this staff.".format(endpoint.id)).throw
                    };
                    open.removeAt(endpoint.id);
                } {
                    if (open.notEmpty) {
                        Error("Validator: % starts while another tempo ramp is "
                            "open. Close the first before starting another."
                            .format(this.prNameOf(endpoint))).throw
                    };
                    open[endpoint.id] = endpoint;
                }
            };
        };
        if (open.notEmpty) {
            Error("Validator: tempo ramp id % is still open when %. Add a stop "
                "in the same staff.".format(open.keys.asArray.sort.join(", "),ending)).throw
        };
        ^this
    }

    // A bar is reached before its leaves, so whole-bar rests are known first.
    *prCheckStructure { |element, requirePrepared|
        var barRests = IdentitySet.new;
        element.traverse { |node|
            case
                { node.isKindOf(Tuplet)  } { this.prCheckTuplet(node) }
                { node.isKindOf(Voice)   } { this.prCheckVoicePlacement(node) }
                { node.isKindOf(Measure) } {
                    this.prCheckMeasure(node);
                    node.wholeBarRests.do { |rest| barRests.add(rest) };
                }
                { node.isKindOf(Chord) } { this.prCheckChord(node) }
                { true } { };
            if (node.isKindOf(ScoreContainer)) {
                this.prCheckParents(node);
                this.prCheckContents(node);
            };
            if (node.isLeaf) { this.prCheckGraces(node) };
            if (requirePrepared and: { node.isLeaf }) {
                this.prCheckNotatable(node, barRests.includes(node))
            };
        };
        ^this
    }

    *prCheckTuplet { |tuplet|
        var ratio = tuplet.ratio;
        if (ratio.numerator <= 0 or: { ratio.denominator <= 0 }) {
            Error("Validator: % has a non-positive tuplet multiplier. The "
                "ratio must be greater than zero.".format(
                    tuplet)).throw
        };
        // Counts are whole positive note counts. Check before
        // comparing pairs: 0:0 and 1.5:1.5 can still match a
        // multiplier.
        if (tuplet.actualNotes.isKindOf(Integer).not
            or: { tuplet.actualNotes < 1 }
            or: { tuplet.normalNotes.isKindOf(Integer).not }
            or: { tuplet.normalNotes < 1 }) {
            Error("Validator: % prints invalid tuplet counts %:%. Counts must "
                "be positive integers.".format(
                    tuplet, tuplet.actualNotes, tuplet.normalNotes)).throw
        };
        // See Note [A bracket is two facts] in MusicScore/Tuplet.sc.
        if ((tuplet.normalNotes * ratio.denominator)
            != (tuplet.actualNotes * ratio.numerator)) {
            Error("Validator: % prints %:% but scales time by %. The printed "
                "ratio and multiplier disagree.".format(
                    tuplet, tuplet.actualNotes, tuplet.normalNotes, ratio)).throw
        };
        if (tuplet.children.isEmpty and: { tuplet.isTrivial.not }) {
            Error("Validator: % brackets no leaves.".format(tuplet)).throw
        };
        ^this
    }

    // A Voice is a timeline inside a bar. Loose leaves have no barline.
    *prCheckVoicePlacement { |voice|
        if (voice.parent.isKindOf(Measure).not) {
            Error("Validator: a Voice held by % is outside any Measure.".format(
                voice.parent ?? { "nothing" })).throw
        };
        ^this
    }

    // The spine's contents: a score holds staves; a staff holds bars.
    // Placement checks ask what holds an element. This asks what it
    // holds.
    *prCheckContents { |container|
        if (container.isKindOf(MusicScore)) {
            container.children.do { |child|
                if (child.isKindOf(Staff).not) {
                    Error("Validator: a score may hold only staves, got a %."
                        .format(child.class)).throw
                }
            };
            ^this
        };
        if (container.isKindOf(Staff)) {
            container.children.do { |child|
                if (child.isKindOf(Measure).not) {
                    Error("Validator: a staff may hold only measures, got a %."
                        .format(child.class)).throw
                }
            };
            ^this
        };

        // Below a staff, structural containers have no place.
        container.children.do { |child|
            if (child.isKindOf(MusicScore) or: { child.isKindOf(Staff) }
                or: { child.isKindOf(Measure) }) {
                Error("Validator: a % cannot hold a nested %.".format(
                    container.class, child.class)).throw
            }
        };
        ^this
    }

    // A bar belongs to a staff, or stands alone as the thing being checked.
    *prCheckMeasurePlacement { |measure|
        var holder = measure.parent;
        if (holder.notNil and: { holder.isKindOf(Staff).not }) {
            Error("Validator: a % bar must stand alone or belong to a Staff, got "
                "%.".format(
                    measure.meter, holder.class)).throw
        };
        ^this
    }

    *prCheckMeasure { |measure|
        this.prCheckMeasurePlacement(measure);
        if (measure.mixesVoicesWithElements) {
            Error("Validator: a % bar mixes voices with loose elements. Use one "
                "timeline or voices, not both.".format(
                    measure.meter)).throw
        };
        // A full bar matches its meter. A partial bar matches its declared span.
        measure.voices.do { |voice|
            if ((voice.duration == measure.barDuration).not) {
                Error("Validator: a % bar declares % but a voice holds %. Use "
                    "Measure.pickup or Measure.partial for a short bar.".format(
                        measure.meter, measure.barDuration, voice.duration)).throw
            }
        };
        this.prCheckDirectionOffsets(measure);
        ^this
    }

    // A direction offset is local to its bar. It must be inside the bar and on
    // a boundary every voice shares.
    // See Note [Where a direction may sit] in Writers/ScoreWriter.sc.
    *prCheckDirectionOffsets { |measure|
        var boundaries;
        if (measure.directions.every { |direction| direction.atBarStart }) { ^this };
        boundaries = this.prLocalBoundariesIn(measure);
        measure.directions.do { |direction|
            if (direction.offset >= measure.barDuration) {
                Error("Validator: % is written % into a % bar that lasts %. "
                    "Move an offset at the span to the next bar.".format(
                        this.prNameOf(direction),
                        direction.offset, measure.meter,
                        measure.barDuration)).throw
            };
            if (boundaries.any { |at| at == direction.offset }.not) {
                Error("Validator: % is written % into a % bar, but no leaf begins "
                    "there in every voice. Valid offsets are %.".format(
                        this.prNameOf(direction), direction.offset,
                        measure.meter,
                        boundaries.collect { |at| at.asString }.join(", "))).throw
            };
        };
        ^this
    }

    // Endpoints in musical order: by offset, with stops before starts at one
    // offset. Public because `MusicXMLWriter` needs the same order.
    // See Note [A writer refuses what it depends on] in Writers/ScoreWriter.sc.
    *inSpanOrder { |endpoints|
        var rank = { |each| if (each.isRampStop) { 0 } { 1 } };
        ^endpoints.asArray.sort { |a, b|
            if (a.offset == b.offset) { rank.(a) <= rank.(b) } { a.offset < b.offset }
        }
    }

    // What to call a direction in an error.
    *prNameOf { |direction|
        ^direction.text !? { |words| "\"" ++ words ++ "\"" }
            ?? { "a % %".format(direction.kind, direction.edge ? "direction") }
    }

    // Local offsets shared by every timeline in the bar.
    // Normalize by `metricOffset`, so pickups compare in bar-local terms.
    *prLocalBoundariesIn { |measure|
        var perVoice = measure.voices.collect { |voice|
            voice.leaves.collect { |leaf|
                ScorePrepare.leafOffsetsIn(measure)[leaf] - measure.metricOffset
            }
        };
        ^perVoice.reduce { |a, b|
            a.select { |at| b.any { |other| other == at } }
        } ? []
    }

    *prCheckChord { |chord|
        if (chord.pitches.isEmpty) {
            Error("Validator: a chord needs at least one pitch. Use a rest for "
                "silence.").throw
        };
        // One pitch, once. Repeats make ties ambiguous.
        chord.pitches.do { |pitch, index|
            if (index > 0 and: {
                chord.pitches.copyRange(0, index - 1).any { |other| other == pitch }
            }) {
                Error("Validator: % appears twice in %. Use separate voices for "
                    "unison doublings.".format(pitch, chord)).throw
            }
        };
        ^this
    }

    // A whole-bar rest is bar silence, not a notehead value. It is the one
    // prepared leaf allowed to be non-notatable.
    // Note [What a grace group may hold]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Notes and chords, each spellable as one note head, and nothing else.
    //
    // A rest is silent, not ornamental. Grace leaves also carry no ties,
    // markings or spanners in this slice.
    *prCheckGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        leaf.graces.do { |grace|
            var what = case
                { grace.isKindOf(MusicNote) or: { grace.isKindOf(Chord) } } { nil }
                { grace.isKindOf(MusicRest) } { "a rest, and an ornament is a sound" }
                { true } { "a %, and a group holds notes and chords"
                    .format(grace.class) };

            if (what.notNil) {
                Error("Validator: a grace group on % holds %."
                    .format(leaf.class, what)).throw
            };
            if (grace.dur.isNotatable.not) {
                Error("Validator: grace duration % must be writable as one note "
                    "head.".format(grace.dur)).throw
            };
            if (grace.hasGraces) {
                Error("Validator: a grace note cannot carry its own grace group.")
                    .throw
            };
            // `tiesToNext` is a flag on notes and a mask on chords.
            if (this.prTiedPitchesOf(grace).notEmpty) {
                Error("Validator: a grace note cannot tie onward.").throw
            };
            if (grace.hasMarkings or: { grace.hasSpanners }) {
                Error("Validator: a grace note cannot carry markings or spanners.")
                    .throw
            };
        };
        ^this
    }

    *prCheckNotatable { |leaf, wholeBarRest = false|
        if (wholeBarRest) { ^this };
        if (leaf.dur.isNotatable.not) {
            Error("Validator: % needs tie splitting before writing. Run "
                "ScorePrepare.run first.".format(leaf.dur)).throw
        };
        ^this
    }

    *prCheckParents { |container|
        container.children.do { |child|
            if (child.parent !== container) {
                Error("Validator: % is held by % but has parent %.".format(
                        child, container, child.parent)).throw
            }
        };
        ^this
    }

    // Ties resolve within one voice. Measures are checked voice by voice; a
    // fragment with no measures is checked end to end.
    *prCheckTies { |element|
        var staves = List.new, measures = List.new;
        element.traverse { |node|
            if (node.isKindOf(Staff)) { staves.add(node) };
            if (node.isKindOf(Measure)) { measures.add(node) };
        };
        staves.do { |staff| this.prCheckStaffTies(staff) };

        // Also check lone measures, which have no next bar to tie into.
        measures.do { |measure|
            if (this.prStaffAbove(measure).isNil) {
                measure.voices.do { |voice|
                    this.prCheckTieRun(voice.leaves, "the bar ends with nothing after it")
                }
            }
        };

        if (staves.isEmpty and: { measures.isEmpty }) {
            this.prCheckTieRun(element.leaves, "the music ends")
        };
        ^this
    }

    // Spanners pair over the same voice runs as ties.
    *prCheckSpanners { |element|
        var staves = List.new, measures = List.new;
        element.traverse { |node|
            if (node.isKindOf(Staff)) { staves.add(node) };
            if (node.isKindOf(Measure)) { measures.add(node) };
        };
        staves.do { |staff|
            var streams = Dictionary.new;
            staff.children.do { |bar|
                if (bar.isKindOf(Measure)) {
                    bar.voices.do { |voice, index|
                        streams[index] =
                            (streams[index] ?? { List.new }).addAll(voice.leaves)
                    }
                }
            };
            streams.keys.asArray.sort.do { |index|
                this.prCheckSpannerRun(streams[index].asArray, "the staff ends")
            }
        };
        measures.do { |measure|
            if (this.prStaffAbove(measure).isNil) {
                measure.voices.do { |voice|
                    this.prCheckSpannerRun(voice.leaves, "the bar ends")
                }
            }
        };
        if (staves.isEmpty and: { measures.isEmpty }) {
            this.prCheckSpannerRun(element.leaves, "the music ends")
        };
        ^this
    }

    *prCheckSpannerRun { |leaves, ending|
        var open = Dictionary.new, openAt = Dictionary.new;
        leaves.do { |leaf, at|
            // Stops before starts on one leaf: a slur may end and another begin
            // on the same note, and that isn't the same id reopening.
            leaf.spannerStops.do { |endpoint|
                var key = this.prSpannerKey(endpoint);
                if (open[key].isNil) {
                    Error("Validator: % stop id % on % has no open start."
                        .format(
                            endpoint.kind, endpoint.id, leaf)).throw
                };
                if (endpoint.isGlissando) {
                    this.prCheckGlissandoPair(open[key], leaf, openAt[key], at)
                };
                open.removeAt(key);
                openAt.removeAt(key);
            };
            leaf.spannerStarts.do { |endpoint|
                var key = this.prSpannerKey(endpoint);
                // Some kinds do not overlap, whatever their ids.
                // See Note [Kinds that cannot overlap] in Spanner.sc.
                if (endpoint.permitsOverlap.not and: {
                    open.keys.asArray.any { |k|
                        k.beginsWith(endpoint.kind.asString ++ "#") }
                }) {
                    Error("Validator: % starts a % while one is already open. "
                        "Close the first before starting another.".format(
                            leaf, endpoint.kind)).throw
                };
                if (open[key].notNil) {
                    Error("Validator: % id % is already open when % starts "
                        "another. Use different ids for overlapping spanners."
                        .format(
                            endpoint.kind, endpoint.id, leaf)).throw
                };
                open[key] = leaf;
                openAt[key] = at;
            };
        };
        if (open.notEmpty) {
            Error("Validator: % left open when %. Add the stop in the same voice."
                .format(
                    open.keys.asArray, ending)).throw
        };
        ^this
    }

    // Note [A glissando reaches one attack]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Other spanners cover a run. A glissando joins the next attack, so a leaf
    // between the ends is a mismatch.
    //
    // Chords pair by position. A note is one notehead for this check. Unequal
    // widths need a mapping the model does not store.

    // Both ends of one glissando, once the stop is reached.
    *prCheckGlissandoPair { |from, to, fromAt, toAt|
        var width = this.prGlissandoWidth(from);
        if (toAt != (fromAt + 1)) {
            var between = toAt - fromAt - 1;
            Error("Validator: a glissando runs from % to %, with % between "
                "them. It must join adjacent attacks."
                .format(from, to,
                    if (between == 1) { "1 leaf" } { between ++ " leaves" })).throw
        };
        if (width != this.prGlissandoWidth(to)) {
            Error("Validator: a glissando runs from % noteheads to %. Both ends "
                "need the same count. Writers pair by position."
                .format(width, this.prGlissandoWidth(to))).throw
        };
        ^this
    }

    // How many noteheads an end offers to pair.
    *prGlissandoWidth { |leaf|
        ^if (leaf.isKindOf(Chord)) { leaf.pitches.size } { 1 }
    }

    // A rest cannot hold a pitch line. Run before generic spanner pairing.
    *prCheckGlissandi { |element|
        element.traverse { |node|
            if (node.isLeaf) {
                node.spanners.do { |endpoint|
                    if (endpoint.isGlissando and: { node.isKindOf(MusicRest) }) {
                        Error("Validator: % carries a glissando %, but a rest "
                            "has no pitch.".format(
                                node, endpoint.edge)).throw
                    }
                }
            }
        };
        ^this
    }

    // See Note [What a beam may hold].
    //
    // Beams are checked over `AutoBeam.timelinesIn`, the same
    // partition writers use. Run this before generic spanners for
    // beam-specific errors.
    *prCheckBeams { |element|
        element.traverse { |node|
            if (node.isLeaf) { this.prCheckBeamEndpoints(node) }
        };
        AutoBeam.timelinesIn(element).do { |leaves|
            this.prCheckBeamRun(leaves, "the timeline ends")
        };
        ^this
    }

    // Endpoint-local beam checks.
    *prCheckBeamEndpoints { |leaf|
        var beams = leaf.spanners.select { |endpoint| endpoint.isBeam };
        if (beams.isEmpty) { ^this };
        if (leaf.isKindOf(MusicRest)) {
            Error("Validator: % carries a beam endpoint, but rests cannot be "
                "beamed.".format(leaf)).throw
        };
        // A note cannot close one beam and open another.
        if (beams.any { |endpoint| endpoint.isStart }
            and: { beams.any { |endpoint| endpoint.isStop } }) {
            Error("Validator: % both stops and starts a beam. A note belongs to "
                "one beam group.".format(leaf)).throw
        };
        ^this
    }

    // One beam is open at a time, so one open id is enough.
    *prCheckBeamRun { |leaves, ending|
        var open;
        leaves.do { |leaf|
            var stops = leaf.spannerStops.select { |endpoint| endpoint.isBeam };
            var starts = leaf.spannerStarts.select { |endpoint| endpoint.isBeam };
            if (open.notNil) {
                this.prCheckBeamable(leaf);
            };
            stops.do { |endpoint|
                if (open.isNil) {
                    Error("Validator: beam stop id % on % has no open start in "
                        "this timeline.".format(endpoint.id, leaf)).throw
                };
                if (open != endpoint.id) {
                    Error("Validator: beam stop id % on % closes open beam id %."
                        .format(endpoint.id, leaf, open)).throw
                };
                open = nil;
            };
            starts.do { |endpoint|
                if (open.notNil) {
                    Error("Validator: % starts a beam while another is open. "
                        "Close the first before starting another.".format(leaf)).throw
                };
                open = endpoint.id;
                this.prCheckBeamable(leaf);
            };
        };
        if (open.notNil) {
            Error("Validator: beam id % is still open when %. Add the stop in "
                "the same timeline.".format(open, ending)).throw
        };
        ^this
    }

    // Beamability follows written duration, not sounding duration.
    *prCheckBeamable { |leaf|
        if (leaf.isKindOf(MusicRest)) {
            Error("Validator: a beam runs through %, which is a rest. End the "
                "beam before it.".format(leaf)).throw
        };
        if (leaf.dur < Duration.quarter) { ^this };
        Error("Validator: a beam runs through %, which is written as %. Beamed "
            "notes must be shorter than a quarter.".format(leaf, leaf.dur)).throw
    }

    *prSpannerKey { |endpoint| ^(endpoint.kind.asString ++ "#" ++ endpoint.id) }

    *prStaffAbove { |node|
        var above = node.parent;
        while { above.notNil } {
            if (above.isKindOf(Staff)) { ^above };
            above = above.parent;
        };
        ^nil
    }

    // Within a staff, each voice position is one tie run across barlines.
    *prCheckStaffTies { |staff|
        var streams = Dictionary.new;
        staff.children.do { |bar|
            if (bar.isKindOf(Measure)) {
                bar.voices.do { |voice, index|
                    streams[index] = (streams[index] ?? { List.new }).addAll(voice.leaves)
                }
            }
        };
        streams.keys.asArray.sort.do { |index|
            this.prCheckTieRun(streams[index].asArray, "the staff ends")
        };
        ^this
    }

    *prCheckTieRun { |leaves, ending|
        var pending = [];
        leaves.do { |leaf|
            var arriving = this.prPitchesOf(leaf);
            pending.do { |pitch|
                if (arriving.any { |p| p == pitch }.not) {
                    Error("Validator: % ties to the next leaf, but % does not "
                        "carry that pitch.".format(pitch, leaf)).throw
                }
            };
            pending = this.prTiedPitchesOf(leaf);
        };
        if (pending.notEmpty) {
            Error("Validator: % ties onward, but %. Add the tied note in the "
                "same voice.".format(pending, ending)).throw
        };
        ^this
    }

    *prPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    *prTiedPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiedPitches };
        if (leaf.isKindOf(MusicNote)) { ^if (leaf.tiesToNext) { [leaf.pitch] } { [] } };
        ^[]
    }
}
