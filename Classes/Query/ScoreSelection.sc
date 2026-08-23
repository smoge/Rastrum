// Note [A record is not an event payload]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// EventWriter's `\rastrum` payload carries similar facts for
// playback. Here the model's written and sounded durations are kept
// separate. `staffIndex` and `voiceIndex` are identity for the same
// reason EventWriter targets playback by index: names are optional
// and may repeat. A plain IdentityDictionary avoids spending a global
// class name on a struct.


// ScoreSelection: reading a tree in the terms a musician would use, and
// rewriting a copy of it.
//
// `ScoreSelection(score).notes.inStaff(1).offsets` is the shape. One
// way in, filters answer another selection, and accessors answer
// plain arrays. `mapLeaves` and `transposeBy` are write-side helpers:
// they change a copy. A leaf alone doesn't say where it is, so a
// selection holds records: the leaf plus the position facts that make
// it addressable.
ScoreSelection {
    var <records, <source;

    // Every leaf under a score, a staff or a bar, in reading order.
    //
    // >>> ScoreSelection(RhythmTree.measure(Meter(4, 4), [1, -1, 1, 1])).size
    // 4
    *new { |element|
        var found = List.new;
        var inScore = element.isKindOf(MusicScore);
        this.prStavesOf(element).do { |staff, staffIndex|
            // A staff is a path step only when the source is the score above it.
            this.prCollectStaff(staff, staffIndex,
                if (inScore) { [staffIndex] } { [] }, element, found)
        };
        ^this.fromRecords(found.asArray, element)
    }

    // A selection over records read elsewhere. Source is inferred
    // when possible. Empty selections still need a source if they
    // will transform a copy.
    *fromRecords { |records, source|
        var list = records.asArray;
        ^super.newCopyArgs(list, this.prAgreedSource(list, source))
    }

    // Records and source must name one tree. Transforms copy that
    // tree by path.
    *prAgreedSource { |records, source|
        var found;
        records.do { |record|
            var read = record[\source];
            if (read.notNil) {
                found = found ? read;
                if (read !== found) {
                    Error("ScoreSelection.fromRecords: these records were read "
                        "from multiple trees. There is no single source to "
                        "rewrite.").throw
                }
            }
        };
        if (source.isNil) { ^found };
        if (found.notNil and: { found !== source }) {
            Error("ScoreSelection.fromRecords: the records were read from a "
                "different tree than the source given.").throw
        };
        ^source
    }

    // A score is its staves. A staff is one. A bare bar is treated as
    // one staff's bar list without wrapping it in a Staff.
    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        if (element.isKindOf(Staff)) { ^[element] };
        if (element.isKindOf(Measure)) { ^[[element]] };
        Error("ScoreSelection: expected MusicScore, Staff or Measure, got %."
            .format(element.class)).throw
    }

    // Offsets and multipliers come from `ScorePrepare`.
    *prCollectStaff { |staff, staffIndex, staffPath, source, found|
        var inStaff = staff.isKindOf(Staff);
        var staffName = if (inStaff) { staff.name } { nil };
        var bars = if (inStaff) { staff.children.asArray } { staff };
        var barStart = Duration(0, 1);

        bars.do { |bar, barIndex|
            var offsets = ScorePrepare.leafOffsetsIn(bar);
            var multipliers = ScorePrepare.leafMultipliersIn(bar);
            // A bare bar is the source itself, so its own index isn't a step.
            var barPath = if (bar === source) { [] } { staffPath ++ [barIndex] };
            bar.voices.do { |voice, voiceIndex|
                // A bar with no Voice children answers itself as its one
                // timeline, and then the timeline isn't a child of anything.
                var voicePath = if (bar.hasVoices) {
                    barPath ++ [voiceIndex]
                } {
                    barPath
                };
                this.prCollectLeaves(voice, voicePath, (
                    staffIndex: staffIndex, staffName: staffName,
                    measureIndex: barIndex, measure: bar,
                    voiceIndex: voiceIndex,
                    voiceName: if (voice.isKindOf(Voice)) { voice.name } { nil },
                    barStart: barStart, source: source,
                    offsets: offsets, multipliers: multipliers), found)
            };
            barStart = barStart + bar.barDuration;
        }
    }

    // Descends by child index, so a path reaches through nested
    // tuplets and can be followed again on a copy of the same shape.
    *prCollectLeaves { |element, path, where, found|
        element.children.do { |child, index|
            var childPath = path ++ [index];
            if (child.isLeaf) {
                found.add(this.prRecord(child, childPath, where))
            } {
                this.prCollectLeaves(child, childPath, where, found)
            }
        }
    }

    *prRecord { |leaf, path, where|
        // `leafOffsetsIn` counts from the bar's metric offset.
        var barOffset = where[\offsets][leaf] - where[\measure].metricOffset;
        var record = IdentityDictionary.new;
        record[\leaf] = leaf;
        record[\source] = where[\source];
        record[\path] = path;
        record[\staffIndex] = where[\staffIndex];
        record[\staffName] = where[\staffName];
        record[\measureIndex] = where[\measureIndex];
        record[\measure] = where[\measure];
        record[\voiceIndex] = where[\voiceIndex];
        record[\voiceName] = where[\voiceName];
        record[\barOffset] = barOffset;
        record[\offset] = where[\barStart] + barOffset;
        record[\written] = leaf.duration;
        record[\prolated] = leaf.duration * where[\multipliers][leaf];
        ^record
    }

    size { ^records.size }
    isEmpty { ^records.isEmpty }
    notEmpty { ^records.notEmpty }
    at { |index| ^records[index] }
    first { ^records.first }
    last { ^records.last }
    do { |function| ^records.do(function) }
    collect { |function| ^records.collect(function) }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).leaves.last.class   -> MusicRest
    leaves { ^records.collect { |record| record[\leaf] } }

    // The first selected leaf, without the `.leaves.first` step.
    //
    // nil for an empty selection.
    //
    // >>> ScoreSelection(Measure("2/4", "c4 r4")).notes.firstLeaf.class   -> MusicNote
    firstLeaf { ^records.first !? { |record| record[\leaf] } }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).lastLeaf.class   -> MusicRest
    lastLeaf { ^records.last !? { |record| record[\leaf] } }

    // >>> ScoreSelection(Measure("2/4", "c4 d4")).offsets
    // [ Duration(0/1), Duration(1/4) ]
    offsets { ^records.collect { |record| record[\offset] } }

    barOffsets { ^records.collect { |record| record[\barOffset] } }

    // >>> ScoreSelection(Measure("2/4", "c4 d4")).writtenDurations
    // [ Duration(1/4), Duration(1/4) ]
    writtenDurations { ^records.collect { |record| record[\written] } }
    prolatedDurations { ^records.collect { |record| record[\prolated] } }

    // Distinct bars, in reading order. Identity matters.
    measures {
        var found = List.new;
        records.do { |record|
            var bar = record[\measure];
            if (found.any { |each| each === bar }.not) { found.add(bar) }
        };
        ^found.asArray
    }

    // Every filter answers another selection, so they compose and
    // none of them is the last call in a chain.
    where {
        |function| ^ScoreSelection.fromRecords(records.select(function), source)
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).notes.size   -> 1
    notes { ^this.where { |record| record[\leaf].isKindOf(MusicNote) } }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).rests.size   -> 1
    rests { ^this.where { |record| record[\leaf].isKindOf(MusicRest) } }
    chords { ^this.where { |record| record[\leaf].isKindOf(Chord) } }
    pitched { ^this.where { |record| record[\leaf].isKindOf(MusicRest).not } }

    // By index, or by staff name.
    inStaff { |which|
        if (which.isNumber) {
            ^this.where { |record| record[\staffIndex] == which }
        };
        ^this.where { |record| record[\staffName] == which }
    }

    inMeasure { |index| ^this.where { |record| record[\measureIndex] == index } }

    inVoice { |which|
        if (which.isNumber) {
            ^this.where { |record| record[\voiceIndex] == which }
        };
        ^this.where { |record| record[\voiceName] == which }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).paths   -> [ [ 0 ], [ 1 ] ]
    paths { ^records.collect { |record| record[\path] } }

    // Note [A path is an address, not an index]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A path is a child-index address from `source`: `[0]` from a bar,
    // `[0, 0, 0]` from a score.
    //
    // `recordAtPath` and `leafAtPath` search this selection. `elementAtPath`
    // walks `source` and treats a miss as stale addressing.
    prPath { |path, label|
        var steps = if (path.isNumber) { [path] } { path };
        if (steps.isArray.not) {
            Error("ScoreSelection.%: path must be an Array of child indices or "
                "one Integer, got % (%).".format(
                    label, path.asCompileString, path.class)).throw
        };
        steps.do { |step|
            if (step.isKindOf(Integer).not or: { step < 0 }) {
                Error("ScoreSelection.%: path % has invalid step %. Use "
                    "non-negative Integers.".format(
                        label, steps.asCompileString, step.asCompileString)).throw
            }
        };
        ^steps
    }

    recordAtPath { |path|
        var steps = this.prPath(path, "recordAtPath");
        ^records.detect { |record| record[\path] == steps }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).leafAtPath([1]).class
    // MusicRest
    leafAtPath { |path|
        ^this.recordAtPath(path) !? { |record| record[\leaf] }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).elementAtPath([0]).pitch
    // MusicPitch("c[4]")
    elementAtPath { |path|
        var steps = this.prPath(path, "elementAtPath");
        var here = source;
        steps.do { |step|
            var children = if (here.respondsTo(\children)) { here.children } { nil };
            if (children.isNil or: { step >= children.size }) {
                Error("ScoreSelection.elementAtPath: % does not resolve. % has "
                    "% children.".format(steps.asCompileString, here.class,
                        children !? { |each| each.size } ?? { 0 })).throw
            };
            here = children[step]
        };
        ^here
    }

    // What a leaf carries, as filters, so a question about decoration composes
    // with the questions about place and time.
    withMarkings { ^this.where { |record| record[\leaf].markings.notEmpty } }
    withDynamics { ^this.where { |record| record[\leaf].dynamics.notEmpty } }
    withArticulations {
        ^this.where { |record| record[\leaf].articulations.notEmpty }
    }
    // Filters are by kind. New values need no new query.
    withTechnicals {
        ^this.where { |record| record[\leaf].technicals.notEmpty }
    }
    withTexts { ^this.where { |record| record[\leaf].texts.notEmpty } }
    withSpanners { ^this.where { |record| record[\leaf].hasSpanners } }
    withGraces { ^this.where { |record| record[\leaf].hasGraces } }

    // Notes tie by flag; chords tie when any pitch continues. Rests do not tie.
    withTies {
        ^this.where { |record|
            var leaf = record[\leaf];
            case
            { leaf.isKindOf(Chord) } { leaf.tiesAnything }
            { leaf.isKindOf(MusicNote) } { leaf.tiesToNext == true }
            { true } { false }
        }
    }

    // Exact windows over sounding time, from each staff start.
    startingAt { |offset|
        var at = Duration.asDuration(offset);
        ^this.where { |record| record[\offset] == at }
    }

    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).within(0, "2").size
    // 2
    within { |start, end|
        var window = this.prWindow(start, end, "within");
        ^this.where { |record|
            (record[\offset] >= window[0])
                and: { (record[\offset] + record[\prolated]) <= window[1] }
        }
    }

    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).overlapping("4", "2").size
    // 1
    overlapping { |start, end|
        var window = this.prWindow(start, end, "overlapping");
        ^this.where { |record|
            (record[\offset] < window[1])
                and: { (record[\offset] + record[\prolated]) > window[0] }
        }
    }

    // The same window rules, but in each bar's coordinate. Use
    // `inMeasure` to narrow to one bar.
    //
    // >>> ScoreSelection(MusicScore.oneStaff([Measure("2/4", "c4 d4"),
    //     Measure("2/4", "e4 f4")], "V")).withinBar(0, "1/4").size
    // 2
    withinBar { |start, end|
        var window = this.prWindow(start, end, "withinBar");
        ^this.where { |record|
            (record[\barOffset] >= window[0])
                and: { (record[\barOffset] + record[\prolated]) <= window[1] }
        }
    }

    // >>> ScoreSelection(Measure("4/4", "c2 d2")).overlappingBar(0, "1/4").size
    // 1
    overlappingBar { |start, end|
        var window = this.prWindow(start, end, "overlappingBar");
        ^this.where { |record|
            (record[\barOffset] < window[1])
                and: { (record[\barOffset] + record[\prolated]) > window[0] }
        }
    }

    // A zero-length window is a point question; use `startingAt`.
    prWindow { |start, end, label|
        var from = Duration.asDuration(start), to = Duration.asDuration(end);
        if (to > from) { ^[from, to] };
        Error("ScoreSelection.%: window % to % has no length. Use an end after "
            "the start, or startingAt for one point.".format(
                label, from, to)).throw
    }

    // Grouping answers Arrays of selections, so everything above
    // applies to a group. Timeline order, not onset order; adjacency
    // is per timeline.
    *prByTimeline { |list|
        ^list.copy.sort { |a, b|
            if (a[\staffIndex] != b[\staffIndex]) {
                a[\staffIndex] < b[\staffIndex]
            } {
                if (a[\voiceIndex] != b[\voiceIndex]) {
                    a[\voiceIndex] < b[\voiceIndex]
                } {
                    a[\offset] < b[\offset]
                }
            }
        }
    }

    // The second begins where the first ends, in one timeline.
    *prTouching { |a, b|
        if (a[\staffIndex] != b[\staffIndex]) { ^false };
        if (a[\voiceIndex] != b[\voiceIndex]) { ^false };
        ^(a[\offset] + a[\prolated]) == b[\offset]
    }

    // Split wherever leaves stop touching. A filtered-out leaf still
    // took its time, so it leaves a hole.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 r4 e4"))
    //     .pitched.contiguousGroups.size
    // 2
    contiguousGroups {
        var groups = List.new, current = List.new, previous;
        ScoreSelection.prByTimeline(records).do { |record|
            if (previous.notNil
                and: { ScoreSelection.prTouching(previous, record).not }) {
                groups.add(current.asArray);
                current = List.new
            };
            current.add(record);
            previous = record
        };
        if (current.notEmpty) { groups.add(current.asArray) };
        ^groups.collect { |list|
            ScoreSelection.fromRecords(list, source) }.asArray
    }

    // Pitched contiguous groups: rests and filtered-out leaves break phrases.
    runs { ^this.pitched.contiguousGroups }

    // Touching leaves with equal pitches. Rests group together, so
    // `runs` is the call where rests should split instead.
    pitchGroups {
        var out = List.new;
        this.contiguousGroups.do { |group|
            var current = List.new, previous;
            group.do { |record|
                var here = ScoreSelection.prPitchesOf(record[\leaf]);
                if (previous.notNil and: { (here == previous).not }) {
                    out.add(ScoreSelection.fromRecords(current.asArray, source));
                    current = List.new
                };
                current.add(record);
                previous = here
            };
            if (current.notEmpty) {
                out.add(ScoreSelection.fromRecords(current.asArray, source))
            }
        };
        ^out.asArray
    }

    // Widened by the neighboring leaf on one side.
    withNextLeaf { ^this.prWithNeighbor(1) }

    withPreviousLeaf { ^this.prWithNeighbor(-1) }

    prWithNeighbor { |step|
        var all, at = Dictionary.new, wanted = Set.new;
        if (source.isNil) {
            Error("ScoreSelection: cannot widen a selection with no source.")
                .throw
        };
        all = ScoreSelection.prByTimeline(ScoreSelection(source).records);
        all.do { |record, index| at[record[\path]] = index };
        records.do { |record|
            var here = at[record[\path]], next, neighbor;
            if (here.notNil) {
                wanted.add(here);
                next = here + step;
                neighbor = if (next >= 0) { all[next] } { nil };
                // Within one timeline only.
                if (neighbor.notNil
                    and: { ScoreSelection.prSameTimeline(record, neighbor) }) {
                    wanted.add(next)
                }
            }
        };
        ^ScoreSelection.fromRecords(
            wanted.asArray.sort.collect { |index| all[index] }, source)
    }

    *prSameTimeline { |a, b|
        if (a[\staffIndex] != b[\staffIndex]) { ^false };
        ^a[\voiceIndex] == b[\voiceIndex]
    }

    // Written durations, so a group inside a tuplet is measured as it is
    // spelled. Requested groups must fill exactly. Leaves after the last
    // requested duration become one final group.
    //
    // One timeline only. Holes stay admitted: this partitions a selection, not
    // a run.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4"))
    //     .partitionByDurations(["2", "2"]).collect { |group| group.size }
    // [ 2, 2 ]
    partitionByDurations { |durations|
        var wanted = Duration.asDurations(durations);
        var groups = List.new, current = List.new;
        var running = Duration(0, 1), at = 0;
        var staves = records.collect { |record| record[\staffIndex] }.as(Set);
        var voices = records.collect { |record| record[\voiceIndex] }.as(Set);

        // Staff before voice: report the coarser span.
        if (staves.size > 1) {
            Error("ScoreSelection.partitionByDurations: the selection covers % "
                "staves. Partition one timeline at a time. Narrow it with "
                "inStaff.".format(staves.size)).throw
        };
        if (voices.size > 1) {
            Error("ScoreSelection.partitionByDurations: the selection covers % "
                "voices. Partition one timeline at a time. Narrow it with "
                "inVoice.".format(voices.size)).throw
        };
        ScoreSelection.prByTimeline(records).do { |record|
            current.add(record);
            running = running + record[\written];
            if (at < wanted.size) {
                if (running > wanted[at]) {
                    Error("ScoreSelection.partitionByDurations: group % wants %, "
                        "but leaves reach %. Groups must fill exactly.".format(
                            at, wanted[at], running)).throw
                };
                if (running == wanted[at]) {
                    groups.add(current.asArray);
                    current = List.new;
                    running = Duration(0, 1);
                    at = at + 1
                }
            }
        };
        // Underfill is an exactness failure. Leftover applies only after every
        // requested group is filled.
        if (at < wanted.size) {
            Error("ScoreSelection.partitionByDurations: group % wants %, and "
                "the selection ends at %. Groups must fill exactly.".format(
                    at, wanted[at], running)).throw
        };
        if (current.notEmpty) { groups.add(current.asArray) };
        ^groups.collect { |list|
            ScoreSelection.fromRecords(list, source) }.asArray
    }

    // Note [A run is what sounds, not what is written]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A logical tie is one sounding pitch in one timeline. Chords
    // have one run per tied pitch stream. Rests and graces have none.
    //
    // Runs are grouped from this selection's records, so filters
    // compose.

    // One record per sounding pitch run, in the order they begin.
    //
    // Ordered by onset. Chord pitches at one onset keep written order.
    //
    // >>> ScoreSelection(Measure(Meter(2, 4),
    //     [MusicNote(\c, "4", true), MusicNote(\c, "4")]))
    //     .logicalTies.first[\duration]
    // Duration(1/2)
    logicalTies {
        var runs = List.new;
        var open = Dictionary.new;

        ScoreSelection.prByOnset(records).do { |record|
            var leaf = record[\leaf];
            var flags = ScoreSelection.prTieFlags(leaf);
            ScoreSelection.prPitchesOf(leaf).do { |pitch, index|
                var key = [record[\staffIndex], record[\voiceIndex], pitch];
                var run = open[key];
                if (run.notNil
                    and: { (run[\offset] + run[\duration]) == record[\offset] }) {
                    run[\duration] = run[\duration] + record[\prolated];
                    run[\records] = run[\records].add(record)
                } {
                    run = ScoreSelection.prRunFor(pitch, record);
                    runs.add(run)
                };
                if (flags[index] == true) { open[key] = run } { open.removeAt(key) }
            }
        };
        ^runs.collect { |run|
            run[\leaves] = run[\records].collect { |record| record[\leaf] };
            run[\paths] = run[\records].collect { |record| record[\path] };
            run
        }.asArray
    }

    // Onset, then staff, then timeline. Two leaves of one timeline
    // never share an onset, so the grouping below still sees each
    // timeline in order.
    *prByOnset { |list|
        ^list.copy.sort { |a, b|
            if (a[\offset] != b[\offset]) {
                a[\offset] < b[\offset]
            } {
                if (a[\staffIndex] != b[\staffIndex]) {
                    a[\staffIndex] < b[\staffIndex]
                } {
                    a[\voiceIndex] <= b[\voiceIndex]
                }
            }
        }
    }

    *prRunFor { |pitch, record|
        var run = IdentityDictionary.new;
        run[\pitch] = pitch;
        run[\records] = [record];
        run[\staffIndex] = record[\staffIndex];
        run[\staffName] = record[\staffName];
        run[\voiceIndex] = record[\voiceIndex];
        run[\voiceName] = record[\voiceName];
        run[\offset] = record[\offset];
        run[\duration] = record[\prolated];
        ^run
    }

    // A chord read as its pitches, a note as a chord of one, a rest as none.
    *prPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    // One flag per pitch, in the same order, so a chord's mask lines up with
    // its pitches and a note reads as a chord of one.
    *prTieFlags { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesToNext.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.tiesToNext] };
        ^[]
    }

    // Note [A transform copies, and copies everything]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // These transforms change a copy. `mapLeaves` gives the callback
    // a copied leaf and the record.
    //
    // Records and runs are live views. Edit only the copied leaf.

    // Every selected leaf replaced by what the function answers for
    // it, as a copy of the source. The function is given a copy of
    // the leaf and its record.
    mapLeaves { |function|
        var replacements = Dictionary.new;
        if (source.isNil) {
            Error("ScoreSelection.mapLeaves: selection has no source to copy.")
                .throw
        };
        records.do { |record|
            // nil would store no replacement and silently leave the
            // leaf. Copy the answer before installation; adding a
            // child repoints it.
            replacements[record[\path]] = ScorePrepare.copyOf(
                ScoreSelection.prCheckedLeaf(
                    function.value(ScorePrepare.copyOf(record[\leaf]), record),
                    record[\path]))
        };
        ^ScoreSelection.prRebuild(source, [], replacements)
    }

    // One call per logical tie, answering the pitch that run becomes.
    //
    // A result equal to `run[\pitch]` leaves it alone. Results are
    // coerced with `MusicPitch.fromSpec`. Graces are carried, not
    // rewritten; they are not logical ties.
    mapLogicalTies { |function|
        var edits = Dictionary.new;
        this.logicalTies.do { |run, index|
            var becomes = MusicPitch.fromSpec(function.value(run, index));
            run[\records].do { |record|
                // Which pitch stream this run owns. Use equality, not identity.
                var at = ScoreSelection.prPitchesOf(record[\leaf])
                    .detectIndex { |pitch| pitch == run[\pitch] };
                var perLeaf = edits[record[\path]] ?? { Dictionary.new };
                perLeaf[at] = becomes;
                edits[record[\path]] = perLeaf
            }
        };
        ^this.mapLeaves { |leaf, record|
            ScoreSelection.prRepitched(leaf, edits[record[\path]])
        }
    }

    // Every selected leaf moved by an interval. Rests stay; grace
    // groups move with their host leaf.
    //
    // >>> ScoreSelection(Measure("1/4", "c4"))
    //     .transposeBy(MusicInterval.named(\major, 3)).leaves.first.pitch.letter
    // e
    transposeBy { |interval|
        ^this.mapLeaves { |leaf| ScoreSelection.prTransposed(leaf, interval) }
    }

    // Pitch cycle over logical ties. Tied continuations take one
    // pitch, chord streams advance independently, and rests are
    // skipped.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4"))
    //     .assignPitches("g a").leaves.collect { |leaf| leaf.pitch.letter }
    // [ g, a, g, a ]
    assignPitches { |pitches|
        var cycle;
        // Pitch only. These leaves already carry their own markings.
        if (pitches.isKindOf(String) and: { pitches.includes($:) }) {
            Error("ScoreSelection.assignPitches: \"%\" carries a marking. This "
                "writes pitch onto leaves that already have their own, so "
                "attach one with `dynamic` or `articulation`, or write the bar "
                "out.".format(pitches)).throw
        };
        cycle = MusicPitch.asPitches(pitches);
        if (cycle.isEmpty) {
            Error("ScoreSelection.assignPitches: needs at least one pitch.")
                .throw
        };
        ^this.mapLogicalTies { |run, index| cycle.wrapAt(index) }
    }

    // Followed by child index, so a path lands on the same leaf in a
    // tree of the same shape.
    *prRebuild { |element, path, replacements|
        if (element.isLeaf) {
            ^replacements[path] ?? { ScorePrepare.copyOf(element) }
        };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, index|
                this.prRebuild(child, path ++ [index], replacements) })
    }

    *prCheckedLeaf { |leaf, path|
        if (leaf.isKindOf(ScoreLeaf).not) {
            Error("ScoreSelection.mapLeaves: function returned % for leaf at %. "
                "Return a ScoreLeaf.".format(
                    leaf.class, path)).throw
        };
        ^leaf
    }

    *prTransposed { |leaf, interval|
        var moved;
        // A rest has no pitch to move, and it may still carry a grace
        // group, which does.
        if (leaf.isKindOf(MusicRest)) { ^this.prMoveGraces(leaf, leaf, interval) };
        if (leaf.isKindOf(Chord)) {
            moved = this.prChordLike(leaf,
                leaf.pitches.collect { |pitch| interval.transpose(pitch) });
            ^this.prMoveGraces(moved, leaf, interval)
        };
        if (leaf.isKindOf(MusicNote)) {
            leaf.pitch = interval.transpose(leaf.pitch);
            ^this.prMoveGraces(leaf, leaf, interval)
        };
        ^leaf
    }

    // Substitutions are by position, so a leaf no run touched comes
    // back as it was and a chord keeps the pitches no run claimed.
    *prRepitched { |leaf, substitutions|
        if (substitutions.isNil) { ^leaf };
        if (leaf.isKindOf(Chord)) {
            ^this.prChordLike(leaf, leaf.pitches.collect { |pitch, index|
                substitutions[index] ? pitch })
        };
        if (leaf.isKindOf(MusicNote)) {
            leaf.pitch = substitutions[0] ? leaf.pitch;
            ^leaf
        };
        ^leaf
    }

    // A Chord pitches are read-only and it refuses an all-false mask,
    // so one that changes pitch is rebuilt and its attachments
    // carried across by hand.
    *prChordLike { |chord, pitches|
        var fresh = Chord(pitches, chord.dur, this.prTieMask(chord));
        fresh.markings_(chord.markings);
        fresh.spanners_(chord.spanners);
        ^fresh.graces_(chord.graces, chord.graceStyle)
    }

    *prTieMask { |chord|
        var mask = chord.tiesToNext;
        if (mask.isSequenceableCollection and: { mask.every { |flag| flag.not } }) {
            ^false
        };
        ^mask
    }

    *prMoveGraces { |target, from, interval|
        ^target.graces_(
            from.graces.collect { |grace| this.prTransposed(grace, interval) },
            from.graceStyle)
    }

    printOn { |stream| stream << "ScoreSelection(" << records.size << ")" }
}
