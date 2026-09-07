+ ScoreSelection {
    // Grouping answers Arrays of selections, so every selection query also
    // applies to a group. Timeline order, not onset order. Adjacency is per
    // timeline.
    //
    // ^ [IdentityDictionary]
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
    //
    // ^ Boolean
    *prTouching { |a, b|
        if (a[\staffIndex] != b[\staffIndex]) { ^false };
        if (a[\voiceIndex] != b[\voiceIndex]) { ^false };
        ^(a[\offset] + a[\prolated]) == b[\offset]
    }

    // One timeline and one live Measure object, as `measures` reads it.
    //
    // ^ Boolean
    *prSameBar { |a, b|
        if (a[\staffIndex] != b[\staffIndex]) { ^false };
        if (a[\voiceIndex] != b[\voiceIndex]) { ^false };
        ^a[\measure] === b[\measure]
    }

    // Split wherever leaves stop touching. A filtered-out leaf still
    // took its time, so it leaves a hole.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 r4 e4")).pitched.contiguousGroups.size
    // 2
    //
    // ^ [ScoreSelection]
    contiguousGroups {
        ^this.prGroupedBy { |previous, record|
            ScoreSelection.prTouching(previous, record) }
    }

    // Pitched contiguous groups: rests and filtered-out leaves break
    // phrases.
    //
    // ^ [ScoreSelection]
    runs { ^this.pitched.contiguousGroups }

    // One group per bar per timeline. Staff and voice split before
    // the bar is considered, so a divided staff answers a group per
    // voice rather than one bucket per bar. A hole does not split a
    // group: this asks where a leaf stands, not whether leaves touch,
    // which is `contiguousGroups`.
    //
    // >>> ScoreSelection(MusicScore.oneStaff([Measure("4/4", "c4 r4 d4 e4"),
    //     Measure("4/4", "f4 g4 a4 b4")], "V")).pitched
    //     .groupByMeasure.collect { |group| group.size }
    // [ 3, 4 ]
    // >>> ScoreSelection(Measure("1/4", "c4")).rests.groupByMeasure
    // [ ]
    //
    // ^ [ScoreSelection]
    groupByMeasure {
        ^this.prGroupedBy { |previous, record|
            ScoreSelection.prSameBar(previous, record) }
    }

    // The predicate answers true while adjacent records stay in one group.
    // ^ [ScoreSelection]
    prGroupedBy { |predicate|
        var groups = List.new, current = List.new, previous;
        ScoreSelection.prByTimeline(records).do { |record|
            if (previous.notNil
                and: { predicate.value(previous, record).not }) {
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

    // Touching leaves with equal pitches. Rests group together, so
    // `runs` is the call where rests should split instead.
    //
    // ^ [ScoreSelection]
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
    // ^ ScoreSelection
    withNextLeaf { ^this.prWithNeighbor(1) }

    // ^ ScoreSelection
    withPreviousLeaf { ^this.prWithNeighbor(-1) }

    // ^ ScoreSelection
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

    // ^ Boolean
    *prSameTimeline { |a, b|
        if (a[\staffIndex] != b[\staffIndex]) { ^false };
        ^a[\voiceIndex] == b[\voiceIndex]
    }

    // Written durations, so a group inside a tuplet is measured as it
    // is spelled. Requested groups must fill exactly. Leaves after
    // the last requested duration become one final group.
    //
    // One timeline only. Holes stay admitted: this partitions a
    // selection, not a run.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).partitionByDurations(["2", "2"]).collect { |group| group.size }
    // [ 2, 2 ]
    //
    // ^ [ScoreSelection]
    partitionByDurations { |durations|
        var wanted = Duration.asDurations(durations);
        var groups = List.new, current = List.new;
        var running = Duration(0, 1), at = 0;

        this.prOneTimeline("partitionByDurations");
        ScoreSelection.prByTimeline(records).do { |record|
            current.add(record);
            running = running + record[\written];
            if (at < wanted.size) {
                if (running > wanted[at]) {
                    Error(
                        "ScoreSelection.partitionByDurations: group % wants %, but leaves reach %. Groups must fill exactly."
                        .format(at, wanted[at], running)
                    ).throw
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
            Error(
                "ScoreSelection.partitionByDurations: group % wants %, and the "
                "selection ends at %. Groups must fill exactly."
                .format(at, wanted[at], running)
            ).throw
        };
        if (current.notEmpty) { groups.add(current.asArray) };
        ^groups.collect { |list|
            ScoreSelection.fromRecords(list, source) }.asArray
    }

    // Partition by written leaf count, parallel to `partitionByDurations`.
    //
    // Leftover becomes one final group. Underfill is refused.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).partitionByCounts([2, 1]).collect { |group| group.size }
    // [ 2, 1, 1 ]
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).partitionByCounts([2, 2]).collect { |group| group.size }
    // [ 2, 2 ]
    //
    // ^ [ScoreSelection]
    partitionByCounts { |counts|
        var wanted = ScoreSelection.prCheckedCounts(counts, "partitionByCounts");
        var groups = List.new, current = List.new;
        var at = 0;

        this.prOneTimeline("partitionByCounts");
        ScoreSelection.prByTimeline(records).do { |record|
            current.add(record);
            if (at < wanted.size and: { current.size == wanted[at] }) {
                groups.add(current.asArray);
                current = List.new;
                at = at + 1
            }
        };
        // Leftover applies only after every requested group is filled.
        if (at < wanted.size) {
            Error(
                "ScoreSelection.partitionByCounts: group % wants % leaves, selection ends at %. Groups must fill exactly."
                .format(at, wanted[at], current.size)
            ).throw
        };
        if (current.notEmpty) { groups.add(current.asArray) };
        ^groups.collect { |list|
            ScoreSelection.fromRecords(list, source) }.asArray
    }

    // Counts are positive Integers.
    //
    // ^ [Integer]
    *prCheckedCounts { |counts, label|
        var list;

        if (counts.isSequenceableCollection.not
            or: { counts.isKindOf(String) }) {
            Error(
                "ScoreSelection.%: % is not a count list. Use positive Integers."
                .format(label, counts.asCompileString)
            ).throw
        };
        list = counts.asArray;
        if (list.isEmpty) {
            Error(
                "ScoreSelection.%: an empty count list partitions nothing."
                .format(label)
            ).throw
        };
        list.do { |each|
            if (each.isKindOf(Integer).not or: { each < 1 }) {
                Error(
                    "ScoreSelection.%: % is not a count. Use positive Integers."
                    .format(label, each.asCompileString)
                ).throw
            }
        };
        ^list
    }

    // Report a staff-spanning selection before a voice-spanning one.
    //
    // ^ Self
    prOneTimeline { |label|
        var staves = records.collect { |record| record[\staffIndex] }.as(Set);
        var voices = records.collect { |record| record[\voiceIndex] }.as(Set);

        if (staves.size > 1) {
            Error(
                "ScoreSelection.%: the selection covers % staves. Partition one "
                "timeline at a time. Narrow it with inStaff."
                .format(label, staves.size)
            ).throw
        };
        if (voices.size > 1) {
            Error(
                "ScoreSelection.%: the selection covers % voices. Partition one "
                "timeline at a time. Narrow it with inVoice."
                .format(label, voices.size)
            ).throw
        };
        ^this
    }

    // A logical tie is one sounding pitch in one timeline. Chords have one run
    // per tied pitch stream. Rests and graces have none. Runs are grouped from
    // this selection's records, so filters compose.

    // One record per sounding pitch run, in the order they begin.
    //
    // Ordered by onset. Chord pitches at one onset keep written order.
    //
    // >>> ScoreSelection(Measure(Meter(2, 4), [MusicNote(\c, "4", true), MusicNote(\c, "4")])).logicalTies.first[\duration]
    // Duration(1/2)
    //
    // ^ [IdentityDictionary]
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
    //
    // ^ [IdentityDictionary]
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

    // ^ IdentityDictionary
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

    // One flag per pitch, in the same order, so a chord's mask lines
    // up with its pitches and a note reads as a chord of one.
    //
    // ^ [Boolean]
    *prTieFlags { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesToNext.asArray };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.tiesToNext] };
        ^[]
    }
}
