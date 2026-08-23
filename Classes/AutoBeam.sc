// Note [What a first policy admits]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A derived beam stays inside one bar, one voice and one beaming
// context, and stops at rests. Cross-barline and top-level-bracket
// beams stay authorial.
//
// Mixed flag counts are allowed. Note [A row per level] derives the
// secondary beams and hooks.
//
// Authored beams win. A voice already carrying one is left exactly as
// written.


// Note [One beaming context]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A context is the outermost bracket around a leaf, or its timeline.
// Inner brackets don't split a run inside the same outer bracket.


// Note [Where a group ends]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// One group to a beat. Grouped meters state beats directly. Ungrouped
// eighth-note meters beam in threes when divisible by three,
// otherwise in pairs.


// Note [A row per level]
// ~~~~~~~~~~~~~~~~~~~~~~
//
// A beam endpoint pair names the group. Rows are derived from the
// grouped leaves: level 1 spans the group, and higher levels span
// only notes carrying enough flags.
//
// Rows are writer data, not model data or ScoreJSON.
//
//
// Note [Which way a hook points]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A one-note run above level 1 is a hook. Its side is chosen in order:
//
//   first note of the group    forward, there is nothing to its left
//   last note                  backward, nothing to its right
//   otherwise                  toward the neighbor with more flags
//   equal counts either side   forward
//
// This first policy uses neighboring flag counts, not metric position.


// AutoBeam: derived beam endpoints.
//
// Notation paths run this after preparation. The pass attaches
// ordinary `Spanner.beam` endpoints.
AutoBeam {

    // Beams `element` in place and answers it.
    //
    // >>> AutoBeam.run(RhythmTree.measure(Meter(4, 8), [1, 1, 1, 1]))
    //     .leaves.count { |leaf| leaf.hasSpanners }   -> 4
    *run { |element|
        if (element.isKindOf(Measure)) { ^this.prBeamMeasure(element) };
        if (element.isKindOf(ScoreContainer)) {
            element.children.do { |child| this.run(child) };
            ^element
        };
        Error("AutoBeam.run: expected a MusicScore, Staff or Measure, got a %."
            .format(element.class)).throw
    }

    // The spans a beam may not cross, as durations from the bar's own
    // start.
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

    // Where each group begins, downbeat included.
    //
    // >>> AutoBeam.groupOffsets(Meter(6, 8))
    // [ Duration(0/1), Duration(3/8) ]
    // >>> AutoBeam.groupOffsets(Meter.grouped(5, 8, [2, 3]))
    // [ Duration(0/1), Duration(1/4) ]
    *groupOffsets { |meter|
        var at = Duration(0, 1);
        ^this.groupSpans(meter).collect { |span|
            var start = at;
            at = at + span;
            start
        }
    }

    // Whether a bar counted in eighths or shorter beams in threes.
    //
    // >>> AutoBeam.beamsInThrees(Meter(6, 8))   -> true
    // >>> AutoBeam.beamsInThrees(Meter(3, 8))   -> true
    // >>> AutoBeam.beamsInThrees(Meter(4, 8))   -> false
    // >>> AutoBeam.beamsInThrees(Meter(3, 4))   -> false
    *beamsInThrees { |meter|
        ^(meter.unit >= 8) and: { (meter.count % 3) == 0 }
    }

    // A note head a beam can be drawn on: shorter than a quarter.
    //
    // >>> AutoBeam.isBeamable(MusicNote(60, Duration(1, 8)))   -> true
    // >>> AutoBeam.isBeamable(MusicNote(60, Duration(1, 4)))   -> false
    // >>> AutoBeam.isBeamable(MusicRest(Duration(1, 8)))       -> false
    *isBeamable { |leaf|
        if (leaf.isKindOf(MusicNote).not and: { leaf.isKindOf(Chord).not }) { ^false };
        ^(leaf.dur.flags ? 0) > 0
    }

    // The beams of one group, one `[level, state]` list per leaf.
	// See Note [A row per level].
    //
    // Fewer than two leaves answers no rows. Raw writers may call this.
    //
    // >>> AutoBeam.rowsFor([MN(60, Duration(1, 8)), MN(60, Duration(1, 16)), MN(60, Duration(1, 8))])
    // [[[1, begin]], [[1, continue], [2, forwardHook]], [[1, end]]]
    *rowsFor { |leaves|
        var counts = leaves.collect { |leaf| leaf.dur.flags ? 0 };
        var rows = Array.fill(leaves.size, { [] });
        var top = counts.maxItem ? 0;
        if (leaves.size < 2) { ^rows };
        // Level 1 is the group itself. Higher levels follow flag counts.
        this.prMarkRun(rows, (0 .. leaves.size - 1).asArray, 1);
        // Guard `(2..top)`: sclang counts down when the end is smaller.
        (2 .. top).do { |level|
            if (level >= 2) {
                this.prRunsAtLevel(counts, level).do { |run|
                    if (run.size == 1) {
                        rows[run[0]] = rows[run[0]].add(
                            [level, this.prHookAt(counts, run[0])])
                    } {
                        this.prMarkRun(rows, run, level)
                    }
                }
            }
        };
        ^rows
    }

    // Every beamed leaf in `element`, mapped to its rows. Beam levels
    // are a group fact, not a leaf fact.
    //
    // >>> AutoBeam.rowsIn(AutoBeam.run(RhythmTree.measure(Meter(4, 8), [1, 1, 1, 1]))).size
    // 4
    *rowsIn { |element|
        var out = IdentityDictionary.new;
        this.groupsIn(element).do { |group|
            this.rowsFor(group).do { |rows, i| out[group[i]] = rows }
        };
        ^out
    }

    // The leaves of each beam group, in order.
    //
    // Note [Pairing refuses what it cannot read]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Every endpoint must pair in one timeline.
    //
    // Pairing is needed before this method can answer. Writers call
    // it from `prepare`, so the writer-side refusal rule applies too.
    *groupsIn { |element|
        ^this.timelinesIn(element).inject([], { |all, leaves|
            all ++ this.prGroupsAlong(leaves) })
    }

    // Leaf timelines, in tree order. Staves use `Staff#timelines`;
    // bare bars partition by voice. Fragments with no bar are one
    // line.
    //
    // Note [A partition that loses a leaf is refused]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Every leaf must land in exactly one timeline.
    //
    // >>> AutoBeam.timelinesIn(Measure("1/4", "c8 d8")).size   -> 1
    *timelinesIn { |element|
        var staves = [], bars = [], out;
        element.traverse { |node|
            if (node.isKindOf(Staff))   { staves = staves.add(node) };
            if (node.isKindOf(Measure)) { bars = bars.add(node)     };
        };
        out = case
            { staves.notEmpty } {
                staves.inject([], { |all, staff| all ++ staff.timelines })
            }
            { bars.notEmpty } { Staff.timelinesOf(bars) }
            { true }          { [element.leaves] };
        this.prRequireEveryLeaf(element, out);
        ^out
    }

    // Note [A partition that loses a leaf is refused].
    *prRequireEveryLeaf { |element, timelines|
        var partitioned = timelines.inject(0, { |sum, each| sum + each.size });
        var total = element.leaves.size;
        if (partitioned != total) {
            Error("AutoBeam: % of this tree's % leaves are outside any timeline."
                .format(total - partitioned, total)).throw
        };
        ^this
    }

    *prGroupsAlong { |leaves|
        var out = [], run = nil, openId = nil;
        leaves.do { |leaf|
            var starts = leaf.spannerStarts.select { |endpoint| endpoint.isBeam };
            var stops = leaf.spannerStops.select { |endpoint| endpoint.isBeam };
            this.prRefuseUnpairable(leaf, starts, stops, run, openId);
            case
            { stops.notEmpty } {
                out = out.add(run.add(leaf));
                run = nil; openId = nil;
            }
            { starts.notEmpty } { run = [leaf]; openId = starts.first.id }
            { run.notNil }      { run = run.add(leaf) }
            { true }            { };
        };
        if (run.notNil) {
            Error("AutoBeam: beam beginning on % never ends.".format(run.first)).throw
        };
        ^out
    }

    // Note [Pairing refuses what it cannot read].
    *prRefuseUnpairable { |leaf, starts, stops, run, openId|
        if (starts.notEmpty and: { stops.notEmpty }) {
            Error("AutoBeam: % both stops and starts a beam. A note belongs to "
                "one beam group.".format(leaf)).throw
        };
        if (starts.size > 1 or: { stops.size > 1 }) {
            Error("AutoBeam: % carries % beam endpoints of one kind. Use at most "
                "one.".format(leaf, max(starts.size, stops.size))).throw
        };
        if (stops.notEmpty and: { run.isNil }) {
            Error("AutoBeam: beam ending on % has no open start.".format(leaf)).throw
        };
        if (stops.notEmpty and: { stops.first.id != openId }) {
            Error("AutoBeam: beam ending on % has id %, but open beam id is %."
                .format(leaf, stops.first.id, openId)).throw
        };
        if (starts.notEmpty and: { run.notNil }) {
            Error("AutoBeam: beam begins on % while another is open.".format(leaf)).throw
        };
        ^this
    }

    // Maximal runs of adjacent positions carrying at least `level` flags.
    *prRunsAtLevel { |counts, level|
        var runs = [], current = [];
        counts.do { |count, i|
            if (count >= level) {
                current = current.add(i)
            } {
                if (current.notEmpty) { runs = runs.add(current); current = [] }
            }
        };
        if (current.notEmpty) { runs = runs.add(current) };
        ^runs
    }

    *prMarkRun { |rows, run, level|
        run.do { |i, k|
            var state = case
                { k == 0 }               { \begin    }
                { k == (run.size - 1) }  { \end      }
                { true }                 { \continue };
            rows[i] = rows[i].add([level, state]);
        };
        ^rows
    }

    // Note [Which way a hook points].
    *prHookAt { |counts, index|
        if (index == 0)                            { ^\forwardHook  };
        if (index == (counts.size - 1))            { ^\backwardHook };
        if (counts[index - 1] > counts[index + 1]) { ^\backwardHook };
        ^\forwardHook
    }

    *prBeamMeasure { |measure|
        var spans = this.groupSpans(measure.meter);
        var offsets = ScorePrepare.leafOffsetsIn(measure);
        measure.voices.do { |voice| this.prBeamRun(voice.leaves, spans, offsets) };
        ^measure
    }

    // One voice, in order. Anything outside policy flushes the run.
    //
    // The scan starts at the bar's own zero, matching
    // `leafOffsetsIn`. Flag changes stay in one group. Rows carry
    // secondary beams.
    *prBeamRun { |leaves, spans, offsets|
        var run = [], context = nil, edge = Duration(0, 1), index = 0;
        var flush = { if (run.size > 1) { Spanner.beam(run) }; run = [] };

        // Note [What a first policy admits]: a hand-beamed voice is left alone.
        if (leaves.any { |leaf| leaf.spanners.any { |each| each.isBeam } }) { ^this };

        leaves.do { |leaf|
            var at = offsets[leaf];
            var here = this.prBeamContextOf(leaf);
            // The bar grid advances inside a bracket; context changes flush.
            var withinBracket = context.notNil
                and: { context.isKindOf(Tuplet) }
                and: { here === context         };

            while { (index < spans.size) and: { (edge + spans[index]) <= at } } {
                edge = edge + spans[index];
                index = index + 1;
                if (withinBracket.not) { flush.value };
            };
            case
            { this.isBeamable(leaf).not } { flush.value }
            { context.notNil and: { here !== context } } {
                flush.value;
                run = [leaf]; context = here;
            }
            { true } {
                run = run.add(leaf);
                context = here;
            };
        };
        flush.value;
        ^this
    }

    // Note [One beaming context].
    *prBeamContextOf { |leaf|
        var node = leaf.parent, context = leaf.parent;
        while { node.isKindOf(Tuplet) } {
            context = node;
            node = node.parent;
        };
        ^context
    }
}
