// Note [What a first policy admits]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A beam group here stays inside one bar, one voice and one parent, holds notes
// and chords of the same flag count, and is broken by a rest. So, no partial
// beams, no beam across a barline, none through a rest, and none reaching into
// or out of a bracket. Every one of those is a real thing in engraving and none
// is in this slice. Only the last can then be said by hand: `Validator` refuses
// a mixed group, a beam across a barline and a beam through a rest outright, so
// declining to derive those three takes nothing away.
//
// Authored beams win. A voice already carrying one is left exactly as written,
// rather than having this pass add groups around it: a person who beamed one bar
// by hand meant that bar, and guessing which of the two sources owns the rest of
// it is a decision nobody asked for.


// Note [Where a group ends]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// One group to a beat, and `Meter` is asked what a beat is rather than told.
//
// A grouped meter has already said: 5/8 as [2, 3] beams 2 then 3, and as [3, 2]
// the other way, which is the whole reason the grouping is on the model.
//
// An ungrouped one is read from its unit. A bar counted in eighths whose count
// divides by three beams in threes, so 6/8 is 3+3 rather than the 2+2+2 its bare
// pair suggests, and 3/8 is one group rather than 2+1. Otherwise a bar counted
// in eighths or shorter is felt in pairs, so 4/8 is 2+2. Anything else beams to
// its own unit, so 4/4 and 3/4 beam per quarter.
//
// Sixteenths follow from the same spans without a second rule: a 4/4 beat is a
// quarter either way, so four of them beam together where two eighths do.


// AutoBeam: beam groups derived from the meter, as model data.
//
// Separate from `ScorePrepare`, and run in fewer places. Preparation is about
// what a note head can spell, so every facade path runs it. Beaming is a
// rendering decision, so the notation paths run it and the rest do not:
// `render`, `preview` and `writeMusicXML` beam by default, `writeJSON` does not,
// and nothing on the playback side does. See
// Note [A derived beam is not a score fact] in Rastrum.sc for why the wire is
// left out. Calling this directly is always a choice.
//
// No output syntax, and nothing backend-specific: it attaches the same
// `Spanner.beam` endpoints a person would author, so LilyPond, MusicXML and the
// wire all see one decision. A writer that inferred its own would put a different
// score in front of each backend, which is what
// `\set Staff.autoBeaming = ##f` prevents.
AutoBeam {

    // Beams `element` in place and answers it, so a run reads as one line:
    // `Rastrum.render(AutoBeam.run(score), "study")`.
    //
    // In place, unlike `ScorePrepare.run`, because attaching an endpoint is what
    // `Spanner.beam` already does to the leaves it is given. This is that
    // operation chosen by the meter rather than by hand, so it behaves the same
    // way. Copy first if the original matters.
    //
    // >>> AutoBeam.run(RhythmTree.measure(Meter(4, 8), [1, 1, 1, 1]))
    //     .leaves.count { |leaf| leaf.hasSpanners }   -> 4
    *run { |element|
        if (element.isKindOf(Measure)) { ^this.prBeamMeasure(element) };
        if (element.isKindOf(ScoreContainer)) {
            element.children.do { |child| this.run(child) };
            ^element
        };
        Error("AutoBeam.run: expected a MusicScore, Staff or Measure, got a %. A "
            "leaf has no meter to be beamed against.".format(element.class)).throw
    }

    // The spans a beam may not cross, as durations from the bar's own start. See
    // Note [Where a group ends].
    //
    // >>> AutoBeam.groupSpans(Meter(6, 8))
    // [ Duration(3/8), Duration(3/8) ]
    // >>> AutoBeam.groupSpans(Meter.grouped(5, 8, [2, 3]))
    // [ Duration(1/4), Duration(3/8) ]
    *groupSpans { |meter|
        var unit = meter.unitDuration;
        var per;
        if (meter.isGrouped) {
            ^meter.groups.collect { |units| unit * Duration(units, 1) }
        };
        per = case
            { this.beamsInThrees(meter) } { 3 }
            { meter.unit >= 8 }           { 2 }
            { true }                      { 1 };
        ^Array.fill(meter.count div: per, { unit * Duration(per, 1) })
            ++ if ((meter.count % per) > 0) {
                [unit * Duration(meter.count % per, 1)]
            } {
                []
            }
    }

    // Whether a bar counted in eighths or shorter beams in threes. That is the
    // compound meters, 6/8 and 9/8 and 12/8, and 3/8 with them: three eighths
    // are beamed as one group, which is the same span by a different argument.
    //
    // Asked as its own question rather than through "is this compound", because
    // 3/8 is not a compound meter (three beats each dividing in two) and
    // beams in threes anyway. Grouping and metric type are two facts, and
    // conflating them beamed 3/8 as 2+1.
    //
    // >>> AutoBeam.beamsInThrees(Meter(6, 8))   -> true
    // >>> AutoBeam.beamsInThrees(Meter(3, 8))   -> true
    // >>> AutoBeam.beamsInThrees(Meter(4, 8))   -> false
    // >>> AutoBeam.beamsInThrees(Meter(3, 4))   -> false
    *beamsInThrees { |meter|
        ^(meter.unit >= 8) and: { (meter.count % 3) == 0 }
    }

    // A note head a beam can be drawn on: shorter than a quarter, so it carries
    // at least one flag to join.
    //
    // >>> AutoBeam.isBeamable(MusicNote(60, Duration(1, 8)))   -> true
    // >>> AutoBeam.isBeamable(MusicNote(60, Duration(1, 4)))   -> false
    // >>> AutoBeam.isBeamable(MusicRest(Duration(1, 8)))       -> false
    *isBeamable { |leaf|
        if (leaf.isKindOf(MusicNote).not and: { leaf.isKindOf(Chord).not }) { ^false };
        ^(leaf.dur.flags ? 0) > 0
    }

    *prBeamMeasure { |measure|
        var spans = this.groupSpans(measure.meter);
        var offsets = ScorePrepare.leafOffsetsIn(measure);
        measure.voices.do { |voice| this.prBeamRun(voice.leaves, spans, offsets) };
        ^measure
    }

    // One voice, in order. A run is broken by anything that ends a group, and
    // what is left is beamed if two or more note heads survived.
    //
    // The scan starts at the notional bar's own zero, not at wherever a partial
    // bar begins. `leafOffsetsIn` measures from that same zero (a quarter-note
    // pickup to 5/8 has its first leaf at 1/4), so a pickup simply enters the
    // loop below and advances past the groups it begins after. Starting at the
    // metric offset instead lined span one up against group two, and cut a
    // three-eighth pickup where nothing divides it.
    *prBeamRun { |leaves, spans, offsets|
        var run = [], flags = nil, parent = nil, edge = Duration(0, 1), index = 0;
        var flush = { if (run.size > 1) { Spanner.beam(run) }; run = []; flags = nil };

        // Note [What a first policy admits]: a hand-beamed voice is left alone.
        if (leaves.any { |leaf| leaf.spanners.any { |each| each.isBeam } }) { ^this };

        leaves.do { |leaf|
            var at = offsets[leaf];
            // A bracket divides its own span, so the bar's grid does not cut a
            // run inside one. A 3:2 nested in a 5:4 puts its eighths at 1/5,
            // 4/15 and 1/3, and the bar's quarter line falls between the first
            // two: grouping those by the grid would beam two of the three and
            // leave the other bare, which is not how a triplet is written.
            //
            // The boundary is still crossed, so what follows the bracket is
            // grouped from the right place. Only the flush is held back.
            var withinBracket = parent.notNil
                and: { parent.isKindOf(Tuplet) }
                and: { leaf.parent === parent };

            while { (index < spans.size) and: { (edge + spans[index]) <= at } } {
                edge = edge + spans[index];
                index = index + 1;
                if (withinBracket.not) { flush.value };
            };
            case
            { this.isBeamable(leaf).not } { flush.value }
            { flags.notNil and: { leaf.dur.flags != flags } } {
                flush.value;
                run = [leaf]; flags = leaf.dur.flags; parent = leaf.parent;
            }
            { parent.notNil and: { leaf.parent !== parent } } {
                flush.value;
                run = [leaf]; flags = leaf.dur.flags; parent = leaf.parent;
            }
            { true } {
                run = run.add(leaf);
                flags = leaf.dur.flags;
                parent = leaf.parent;
            };
        };
        flush.value;
        ^this
    }
}
