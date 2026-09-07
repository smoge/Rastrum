+ ScoreEdit {

    // Written tie edits.

    // Note [A tie is written, not derived]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `tieRuns` ties selected `pitchGroups`: touching heads of one pitch. Rests,
    // holes and pitch changes break the run. Meter is not read.
    //
    // `clearTies` clears a whole touched logical tie, like the detach
    // helpers take a whole touched span. Clearing only half would
    // leave one written sound on two pitches.

    // A copy of the tree with each selected run of one pitch tied into one
    // sounding note.
    //
    // A run of one head is skipped, so a selection holding no run
    // answers the tree it was given. Matching chords are one run and
    // take a whole tie mask.
    //
    // A tied run is refused. Clear first.
    //
    // >>> { var bar = Measure("4/4", "c4 c4 d4 d4");
    //     ScoreEdit.tieRuns(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.tiesToNext } }.value
    // [ true, false, true, false ]
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4"); ScoreEdit.tieRuns(bar, ScoreSelection(bar)) === bar }.value
    // true
    // ^ ScoreElement
    *tieRuns { |element, selection|
        var label = "tieRuns";
        var groups, inside, pairs = [];

        this.prCheckedSelectionOf(element, selection, label, "leaf to tie");
        groups = selection.pitched.pitchGroups
            .select { |group| group.size > 1 };
        // Sweep once for the whole tree rather than once per run.
        inside = this.prTieCoverage(element);
        groups.do { |group|
            this.prCheckedFreeOfTies(group.records, inside, label) };
        if (groups.isEmpty) { ^element };

        groups.do { |group|
            pairs = pairs ++ group.records.drop(-1).collect { |record|
                [record[\path], this.prWithTieToNext(record[\leaf])] }
        };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // A copy of the tree with every touched tie cleared.
    //
    // See Note [A tie is written, not derived].
    //
    // Select either head and the whole tie goes. A selected chord
    // clears its whole mask. Touching no tie answers the tree it was
    // given.
    //
    // >>> { var bar = Measure("4/4", [MN("c4~"), MN("c4"), MN("d2")]);
    //     ScoreEdit.clearTies(bar, ScoreSelection(bar).withinBar("1/4", "1/2"))
    //     .leaves.collect { |leaf| leaf.tiesToNext } }.value
    // [ false, false, false ]
    // ^ ScoreElement
    *clearTies { |element, selection|
        var label = "clearTies";
        var wanted, source, drops = Set.new, pairs;

        this.prCheckedSelectionOf(element, selection, label, "leaf to clear");
        wanted = selection.paths.as(Set);
        source = ScoreSelection(element);
        source.logicalTies.do { |run|
            var paths = run[\paths];
            if (paths.size > 1
                and: { paths.any { |path| wanted.includes(path) } }) {
                paths.drop(-1).do { |path| drops.add(path) }
            }
        };
        // A flag reaching nothing is no logical tie, so clearing a selected flag
        // repairs a half-built tree.
        selection.records.do { |record|
            if (this.prTiesAnything(record[\leaf])) {
                drops.add(record[\path])
            }
        };
        if (drops.isEmpty) { ^element };

        pairs = drops.asArray.collect { |path|
            [path, this.prWithoutTieToNext(source.leafAtPath(path))] };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // Leaves in written ties, as path -> tie path list. A run of one head is no
    // tie.
    // ^ Dictionary
    *prTieCoverage { |element|
        var covered = Dictionary.new;

        ScoreSelection(element).logicalTies.do { |run|
            if (run[\paths].size > 1) {
                run[\paths].do { |path| covered[path] = run[\paths] }
            }
        };
        ^covered
    }

    // Refuse tying over an existing tie. Extending one would guess at intent.
    *prCheckedFreeOfTies { |records, inside, label|
        records.do { |record|
            inside[record[\path]] !? { |paths|
                Error(
                    "ScoreEdit.%: % is already tied, written as %. Clear ties first."
                    .format(
                        label,
                        record[\path].asCompileString,
                        paths.asCompileString
                    )
                ).throw
            }
        };
        ^records
    }

    // A note reads as a chord of one, a rest as neither.
    // ^ Boolean
    *prTiesAnything { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiesAnything };
        if (leaf.isKindOf(MusicNote)) { ^leaf.tiesToNext == true };
        ^false
    }

    // ^ ScoreLeaf
    *prWithTieToNext { |leaf| ^ScorePrepare.copyOf(leaf).tiesToNext_(true) }

    // ^ ScoreLeaf
    *prWithoutTieToNext { |leaf| ^ScorePrepare.copyOf(leaf).tiesToNext_(false) }

    // Leaf markings

    // A copy of the tree with one marking on every selected leaf.
    //
    // Adds rather than sets: a leaf already carrying an articulation
    // keeps it and takes this one beside it. The loudness slot is the
    // exception, a second dynamic or sforzando is refused.
    //
    // See Note [One loudness slot on a leaf] in Marking.sc.
    //
    // What counts as a marking is `Marking`'s rule and
    // `ScoreLeaf.attach`'s, so neither is repeated here.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.addMarking(bar, ScoreSelection(bar), Marking.dynamic(\mf))
    //     .leaves.collect { |leaf| leaf.markings.size } }.value
    // [ 1, 1 ]
    // >>> { var bar = Measure("2/4", "c4:mf d4");
    //     ScoreEdit.addMarking(bar, ScoreSelection(bar).pitched,
    //     Marking.articulation(\staccato))
    //     .leaves.first.markings.collect { |mark| mark.value } }.value
    // [ mf, staccato ]
    // ^ ScoreElement
    *addMarking { |element, selection, marking|
        var label = "addMarking";

        this.prCheckedSelectionOf(element, selection, label, "leaf to mark");
        this.prCheckedMarking(marking, label);
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element,
            selection.records.collect { |record|
                [record[\path], this.prWithAttached(record[\leaf], marking)] })
    }

    // One hairpin over each selected run, as a copy. `direction` is
    // the difference between the two public names.
    //
    // Hairpin ids stay at 1. Only slurs overlap, so only slurs need distinct
    // ids. A run touching a hairpin already there is refused.
    //
    // See Note [A span each backend can draw].
    //
    // ^ ScoreElement
    *prHairpinRuns { |element, selection, direction, label|
        var runs, inside, pairs = [];

        this.prCheckedSelectionOf(element, selection, label, "run to shape");
        runs = selection.runs.select { |run| run.size > 1 };
        inside = this.prSpanCoverage(element, \hairpin);
        runs.do { |run|
            this.prCheckedOneTimeline(run, label);
            this.prCheckedWholeLogicalTies(element, run.records, label);
            this.prCheckedFreeOf(\hairpin, run.records, inside, label);
        };
        if (runs.isEmpty) { ^element };

        runs.do { |run|
            var records = run.records;
            pairs = pairs ++ [
                [records.first[\path], this.prWithAttached(records.first[\leaf],
                    Spanner.hairpinStart(direction))],
                [records.last[\path], this.prWithAttached(records.last[\leaf],
                    Spanner.hairpinStop)]
            ];
        };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // A crescendo over each selected run, as a copy.
    //
    // Rests split runs. Barlines do not. Written groups stop at a
    // barline. Endpoints do not.
    //
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4");
    //     ScoreEdit.crescendoRuns(bar, ScoreSelection(bar))
    //     .leaves.first.spannerStarts.first.direction }.value
    // crescendo
    //
    // ^ ScoreElement
    *crescendoRuns { |element, selection|
        ^this.prHairpinRuns(element, selection, \crescendo, "crescendoRuns")
    }

    // The same, the other way up.
    //
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4");
    //     ScoreEdit.diminuendoRuns(bar, ScoreSelection(bar))
    //     .leaves.first.spannerStarts.first.direction }.value
    // diminuendo
    //
    // ^ ScoreElement
    *diminuendoRuns { |element, selection|
        ^this.prHairpinRuns(element, selection, \diminuendo, "diminuendoRuns")
    }

    // A copy of the tree with every marking of `kind` gone from the selected
    // leaves. Other kinds, spans, graces and everything else stay.
    //
    // By kind rather than by value: the musical action is clearing
    // the dynamics off a phrase, not removing one particular `mp`.
    // Nothing of that kind on any selected leaf answers the tree
    // unchanged.
    //
    // >>> { var bar = Measure("2/4", "c4:mf:staccato d4:f");
    //     ScoreEdit.clearMarkings(bar, ScoreSelection(bar), \dynamic)
    //     .leaves.collect { |leaf| leaf.markings.size } }.value
    // [ 1, 0 ]
    //
    // ^ ScoreElement
    *clearMarkings { |element, selection, kind|
        var label = "clearMarkings";
        var pairs;

        this.prCheckedSelectionOf(element, selection, label, "leaf to clear");
        this.prCheckedMarkingKind(kind, label);
        pairs = selection.records
            .select { |record| record[\leaf].markings
                .any { |marking| marking.kind == kind } }
            .collect { |record|
                [record[\path], this.prWithoutKind(record[\leaf], kind)] };
        if (pairs.isEmpty) { ^element };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // A copy of the tree with this marking standing alone in its kind on every
    // selected leaf. One checked edit, so no reading goes stale between
    // clearing and adding.
    //
    // >>> { var bar = Measure("2/4", "c4:mp:staccato d4");
    //     ScoreEdit.setMarking(bar, ScoreSelection(bar), Marking.dynamic(\mf))
    //     .leaves.first.markings.collect { |mark| mark.value } }.value
    // [ staccato, mf ]
    //
    // ^ ScoreElement
    *setMarking { |element, selection, marking|
        var label = "setMarking";

        this.prCheckedSelectionOf(element, selection, label, "leaf to mark");
        this.prCheckedMarking(marking, label);
        ^this.replaceLeavesAt(element,
            selection.records.collect { |record|
                [record[\path], this.prWithSet(record[\leaf], marking)] })
    }

    // A leaf copy, with this marking alone in its kind. A sforzando goes in
    // front of a dynamic already there, that pair being the only order a leaf
    // holds.
    //
    // See Note [One loudness slot on a leaf] in Marking.sc.
    //
    // ^ ScoreLeaf
    *prWithSet { |leaf, marking|
        var rest = this.prWithoutKind(leaf, marking.kind);
        var at;
        if (marking.isSforzando.not) { ^rest.attach(marking) };
        at = rest.markings.detectIndex { |each| each.isDynamic };
        if (at.isNil) { ^rest.attach(marking) };
        ^rest.markings_(
            rest.markings.keep(at) ++ [marking] ++ rest.markings.drop(at))
    }

    // A leaf copy, with every marking of one kind left behind.
    // ^ ScoreLeaf
    *prWithoutKind { |leaf, kind|
        ^ScorePrepare.copyOf(leaf).markings_(
            leaf.markings.reject { |marking| marking.kind == kind })
    }

    // `Marking` owns the list, so this asks rather than repeating it.
    // ^ Symbol
    *prCheckedMarkingKind { |kind, label|
        if (Marking.kinds.includes(kind).not) {
            Error(
                "ScoreEdit.%: % is not a marking kind. Use %."
                .format(
                    label,
                    kind.asCompileString,
                    Marking.kinds.join(", ")
                )
            ).throw
        };
        ^kind
    }

    // ^ Marking
    *prCheckedMarking { |marking, label|
        if (marking.isKindOf(Marking).not) {
            Error(
                "ScoreEdit.%: expected a Marking, got %.%"
                .format(
                    label,
                    marking.class,
                    if (marking.isKindOf(Spanner)) {
                    " A span covers a run rather than standing on one leaf."
                    } { "" }
                )
            ).throw
        };
        ^marking
    }

    // Span detachment

    // Ids alone are not unique span names. Sequential spans reuse them, and
    // hairpins use 1. Pair endpoints by path before detaching.
    //
    // Every span of `kind`, as [id, startPath, stopPath, pathsCovered].
    *prSpanInstances { |element, kind|
        var instances = [], open = Dictionary.new;

        ScoreSelection(element).records.do { |record|
            var leaf = record[\leaf];
            var key = [record[\staffIndex], record[\voiceIndex]];
            var here = open[key];
            var starts, stops;

            if (here.isNil) { here = List.new; open[key] = here };
            starts = leaf.spannerStarts.select { |each| each.kind == kind };
            stops = leaf.spannerStops.select { |each| each.kind == kind };

            // Whatever is still open reaches this leaf, endpoint or not.
            here.do { |entry| entry[2] = entry[2].add(record[\path]) };

            stops.do { |endpoint|
                var at = here.detectIndex { |entry| entry[0] == endpoint.id };
                at !? {
                    var entry = here.removeAt(at);
                    instances = instances.add(
                        [endpoint.id, entry[1], record[\path], entry[2]])
                }
            };
            starts.do { |endpoint|
                here.add([endpoint.id, record[\path], [record[\path]]])
            };
        };
        ^instances
    }

    // A copy of the tree with every touched span of `kind` removed whole.
    // Interior leaves count. Outside endpoints go too.
    //
    // ^ ScoreElement
    *prDetachSpans { |element, selection, kind, label|
        var instances, touched, wanted, drops, source, pairs;

        this.prCheckedSelectionOf(element, selection, label,
            "leaf to detach from");
        wanted = selection.paths.as(Set);
        instances = this.prSpanInstances(element, kind);
        touched = instances.select { |instance|
            instance[3].any { |path| wanted.includes(path) } };
        if (touched.isEmpty) { ^element };

        // One leaf may stop one span and start the next.
        drops = Dictionary.new;
        touched.do { |instance|
            drops[instance[1]] = (drops[instance[1]] ? [])
                .add([\start, instance[0]]);
            drops[instance[2]] = (drops[instance[2]] ? [])
                .add([\stop, instance[0]]);
        };

        source = ScoreSelection(element);
        pairs = drops.keys.asArray.collect { |path|
            var leaf = source.leafAtPath(path);
            var going = drops[path];
            var kept = leaf.spanners.reject { |endpoint|
                endpoint.kind == kind and: {
                    going.any { |pair|
                        pair[0] == endpoint.edge and: { pair[1] == endpoint.id } } } };
            [path, ScorePrepare.copyOf(leaf).spanners_(kept)]
        };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // A copy of the tree with every touched slur removed. Select any leaf under
    // it; both ends go.
    //
    // >>> { var bar = Measure("4/4", Spanner.slur("c4 d4 e4 f4"));
    //     ScoreEdit.detachSlurs(bar, ScoreSelection(bar).withinBar("1/4", "1/2"))
    //     .leaves.collect { |leaf| leaf.spanners.size } }.value
    // [ 0, 0, 0, 0 ]
    //
    // ^ ScoreElement
    *detachSlurs { |element, selection|
        ^this.prDetachSpans(element, selection, \slur, "detachSlurs")
    }

    // The same for hairpins.
    //
    // >>> { var bar = Measure("4/4", Spanner.crescendo("c4 d4 e4 f4"));
    //     ScoreEdit.detachHairpins(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.spanners.size } }.value
    // [ 0, 0, 0, 0 ]
    //
    // ^ ScoreElement
    *detachHairpins { |element, selection|
        ^this.prDetachSpans(element, selection, \hairpin, "detachHairpins")
    }

    // The same for authored beams. `AutoBeam` is separate.
    //
    // >>> { var bar = Measure("2/4", Spanner.beam("c8 d8 e8 f8"));
    //     ScoreEdit.detachBeams(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.spanners.size } }.value
    // [ 0, 0, 0, 0 ]
    // ^ ScoreElement
    *detachBeams { |element, selection|
        ^this.prDetachSpans(element, selection, \beam, "detachBeams")
    }

    // Note [A phrase is a run, and a run crosses a barline]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `ScoreSelection#runs` splits pitched leaves on rests, not on
    // barlines, which is what a slur wants: a rest ends a phrase and
    // a barline doesn't. A derived *beam* wants the opposite, so
    // `AutoBeam` reads the meter and this grouping isn't the one to
    // reach for there.
    //
    // Each run is checked on its own, not against the selection it
    // came from. Two phrases either side of a rest are one useful
    // selection and two runs. `prCheckedRunShape` would refuse that
    // selection for being two.

    // span creation

    // A copy of the tree with a slur over each phrase in `selection`.
    //
    // A run of one attack has nothing to slur and is skipped, so a
    // selection holding no phrase answers the score unchanged rather
    // than an error. `ScoreHistory.edited` records nothing for it.
    //
    // Ids continue from the highest already in the tree, one per run, so each
    // slur is nameable by whatever later edit wants to undo or move it.
    //
    // A run touching a slur already there is refused rather than
    // nested, so this helper does not create a nested slur that GUIDO
    // would refuse.
    //
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4");
    //     ScoreEdit.slurRuns(bar, ScoreSelection(bar))
    //     .leaves.first.spannerStarts.size }.value
    // 1
    // >>> { var bar = Measure("4/4", "c4 r4 e4 f4");
    //     ScoreEdit.slurRuns(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.spannerStarts.size } }.value
    // [ 0, 0, 1, 0 ]
    //
    // ^ ScoreElement
    *slurRuns { |element, selection|
        var label = "slurRuns";
        var runs, next, inside, pairs = [];

        this.prCheckedSelectionOf(element, selection, label, "phrase to slur");
        runs = selection.runs.select { |run| run.size > 1 };
        // Sweep once for the whole tree rather than once per run.
        inside = this.prSpanCoverage(element, \slur);
        runs.do { |run|
            this.prCheckedOneTimeline(run, label);
            this.prCheckedWholeLogicalTies(element, run.records, label);
            this.prCheckedFreeOf(\slur, run.records, inside, label);
        };
        if (runs.isEmpty) { ^element };

        next = this.prNextSlurId(element);
        runs.do { |run, index|
            var records = run.records;
            pairs = pairs ++ [
                [records.first[\path], this.prWithAttached(records.first[\leaf],
                    Spanner.slurStart(next + index))],
                [records.last[\path], this.prWithAttached(records.last[\leaf],
                    Spanner.slurStop(next + index))]
            ];
        };
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, pairs)
    }

    // Note [A beam is a fact about heads]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A tie joins heads into one sounding note. Edits that rewrite a
    // leaf take the whole tie. A beam joins heads, not sounding
    // notes, so it may begin or end inside a tie. The selection is
    // the group. Meter grouping belongs to `AutoBeam`.
    //
    // See Note [A derived beam is not a score fact] in Rastrum.sc.

    // A copy of the tree with one beam over the selected run.
    //
    // The selection is the group. It may cross a barline. Every
    // selected leaf must be a note or chord written shorter than a
    // quarter.
    //
    // >>> { var bar = Measure("2/4", "c8 d8 e8 f8");
    //     ScoreEdit.beamRun(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.spanners.size } }.value
    // [ 1, 0, 0, 1 ]
    // ^ ScoreElement
    *beamRun { |element, selection|
        var label = "beamRun";
        var records = this.prCheckedContiguousRun(element, selection, label,
            "run to beam");

        if (records.size < 2) {
            Error(ScoreIssue.prOf(\beamNeedsTwoHeads,
                "ScoreEdit.%: a beam needs at least two note heads; the "
                "selection holds one.".format(label))[\message]).throw
        };
        records.do { |record| this.prCheckedBeamable(record, label) };
        // Ids stay at 1. Beams do not overlap.
        this.prCheckedFreeOf(\beam, records,
            this.prSpanCoverage(element, \beam), label);
        // `replaceLeavesAt` validates the result once.
        ^this.replaceLeavesAt(element, [
            [records.first[\path],
                this.prWithAttached(records.first[\leaf], Spanner.beamStart)],
            [records.last[\path],
                this.prWithAttached(records.last[\leaf], Spanner.beamStop)]])
    }

    // Check beamability at the selected path before rebuild. Written duration:
    // a tupleted eighth beams.
    *prCheckedBeamable { |record, label|
        var issue = this.prBeamableIssue(record, label);
        if (issue.notNil) { Error(issue[\message]).throw };
        ^record
    }

    // Source checks know tokens; edit checks know selection paths. A
    // diagnostic record may carry either. Neither is guaranteed. This
    // runs `beamRun` checks in order and answers records instead of
    // throwing the first one.

    // Every issue `beamRun` would raise, as records.
    //
    // >>> { var bar = Measure("2/4", "c8 r8 e8 f8");
    //     ScoreEdit.prBeamRunIssues(bar, ScoreSelection(bar))
    //     .collect { |issue| issue[\code] } }.value
    // [ beamRestSelected ]
    *prBeamRunIssues { |element, selection, label = "beamRun"|
        var records, failure, issues, inside;
        try {
            records = this.prCheckedContiguousRun(element, selection, label,
                "run to beam")
        } { |err| failure = err.what };
        if (failure.notNil) { ^[] };
        if (records.size < 2) {
            ^[ScoreIssue.prOf(\beamNeedsTwoHeads,
                "ScoreEdit.%: a beam needs at least two note heads; the "
                "selection holds one.".format(label))]
        };
        // beamability first, then spans, which is `beamRun`'s order
        issues = records.collect { |record|
            this.prBeamableIssue(record, label) }.reject { |each| each.isNil };
        if (issues.notEmpty) { ^issues };
        inside = this.prSpanCoverage(element, \beam);
        ^records.collect { |record|
            this.prSpanFreeIssue(\beam, record, inside, label)
        }.reject { |each| each.isNil }
    }

    // The beam refusal for one selected leaf, or nil.
    *prBeamableIssue { |record, label|
        var leaf = record[\leaf];
        var at = record[\path];

        if (leaf.isKindOf(MusicRest)) {
            ^ScoreIssue.prAtPath(ScoreIssue.prOf(\beamRestSelected,
                "ScoreEdit.%: % is a rest. A beam joins note heads."
                    .format(label, at.asCompileString)), at)
        };
        if (leaf.duration >= Duration.quarter) {
            ^ScoreIssue.prAtPath(ScoreIssue.prOf(\beamDurationTooLong,
                "ScoreEdit.%: % is written %; beamed notes are shorter than a quarter."
                .format(label, at.asCompileString,
                    leaf.duration)), at)
        };
        ^nil
    }

    // Note [A span each backend can draw]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `Validator` isn't the full writer boundary. It permits nested
    // slurs and same-attack hairpin handoffs. GUIDO refuses both.
    // These helpers refuse a run already touched or covered by the
    // same span kind.

    // A run free of spans of this kind. Endpoints and covered interior leaves
    // both count.
    *prCheckedFreeOf { |kind, records, inside, label|
        records.do { |record|
            var issue = this.prSpanFreeIssue(kind, record, inside, label);
            if (issue.notNil) { Error(issue[\message]).throw }
        };
        ^records
    }

    // the span refusal for one selected leaf or nil. Shared codes keep
    // `kind` and `id` as data.
    *prSpanFreeIssue { |kind, record, inside, label|
        var leaf = record[\leaf];
        var at = record[\path];
        var carried = (leaf.spannerStarts ++ leaf.spannerStops)
            .detect { |endpoint| endpoint.kind == kind };
        var issue;

        carried !? {
            issue = ScoreIssue.prOf(\spanEndpointSelected,
                "ScoreEdit.%: % already carries % id %. Detach that one first."
                .format(label, at.asCompileString, kind, carried.id));
            ^this.prSpanAt(issue, at, kind, carried.id)
        };
        inside[at] !? { |id|
            issue = ScoreIssue.prOf(\spanInteriorSelected,
                "ScoreEdit.%: % stands inside % id %. Detach that one first."
                .format(label, at.asCompileString, kind, id));
            ^this.prSpanAt(issue, at, kind, id)
        };
        ^nil
    }

    // Span data beside the path.
    *prSpanAt { |issue, at, kind, id|
        issue[\kind] = kind;
        issue[\id] = id;
        ^ScoreIssue.prAtPath(issue, at)
    }

    // Note [A covered leaf carries nothing]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A span marks endpoints. Interior leaves carry no endpoint, so
    // the sweep keeps open ids per timeline and records covered
    // paths.
    //
    // Leaves inside an open span of this kind, as path -> covering id.
    //
    // ^ Dictionary
    *prSpanCoverage { |element, kind|
        var covered = Dictionary.new;
        var open = Dictionary.new;

        ScoreSelection(element).records.do { |record|
            var leaf = record[\leaf];
            var key = [record[\staffIndex], record[\voiceIndex]];
            // Bound, not chained: `open[key] = Set.new` answers the
            // Dictionary rather than the Set.
            var here = open[key];
            var starts = leaf.spannerStarts
                .select { |endpoint| endpoint.kind == kind };
            var stops = leaf.spannerStops
                .select { |endpoint| endpoint.kind == kind };

            if (here.isNil) { here = Set.new; open[key] = here };
            if (here.notEmpty and: { starts.isEmpty and: { stops.isEmpty } }) {
                covered[record[\path]] = here.asArray.sort.first
            };
            stops.do { |endpoint| here.remove(endpoint.id) };
            starts.do { |endpoint| here.add(endpoint.id) };
        };
        ^covered
    }

    // One past the highest slur id in the tree, so a new slur
    // collides with none. Grace leaves are read too: they are not
    // `leaves` and a slur id hiding in one would collide unseen.
    //
    // >>> ScoreEdit.prNextSlurId(Measure("2/4", "c4 d4"))   -> 1
    //
    // ^ Integer
    *prNextSlurId { |element|
        var highest = 0;
        element.leaves.do { |leaf|
            ([leaf] ++ leaf.graces).do { |each|
                (each.spannerStarts ++ each.spannerStops).do { |endpoint|
                    if (endpoint.kind == \slur) {
                        highest = max(highest, endpoint.id)
                    }
                }
            }
        };
        ^highest + 1
    }

    // A leaf copy with one marking or span endpoint added. The tree handed in
    // keeps the leaf it had, which is what makes this an edit rather than a
    // mutation.
    //
    // ^ ScoreLeaf
    *prWithAttached { |leaf, attachment|
        var copy = ScorePrepare.copyOf(leaf);
        copy.attach(attachment);
        ^copy
    }

}
