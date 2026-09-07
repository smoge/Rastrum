+ ScoreEdit {

    // Run replacement and reshaping.

    // Note [A run is a selection, not a path list]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `replaceRun` edits a root through a selection read from that root. The
    // rule is leaf for leaf: one timeline, touching records, whole tuplets,
    // whole ties, no span loss and the same written duration at each position.
    //
    // A path carries no source, so it can't reject a prepared-copy address. Read
    // edit paths from the tree being edited.
    // See Note [A prepared address is not an authored one] in ScoreLocator.sc.
    //
    // Written replacements can spell `c4:grace{b8}` and `<d~ f a~>4`. Invalid
    // source grace groups are the remaining written-refusal case.
    // See Note [A written run cannot restate every fact].

    // A copy of the tree with one selected *run* replaced.
    //
    // `selection` must have been read from `element` itself. `leaves` is an
    // Array of leaves or a written run: one per selected leaf, each taking over
    // that leaf's written duration.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.replaceRun(bar, ScoreSelection(bar), "r4 e4")
    //     .children.first.class }.value
    // MusicRest
    //
    // ^ ScoreElement
    *replaceRun { |element, selection, leaves|
        var label = "replaceRun";
        var records = this.prCheckedRun(element, selection, label);
        var list = this.prCheckedRunLeaves(leaves, records, label);

        this.prCheckedSpans(records, list, label);

        ^this.replaceLeavesAt(element,
            records.collect { |record, index| [record[\path], list[index]] })
    }

    // Note [A reshaped run rebuilds one container]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `reshapeRun` changes one immediate parent's child run. The selected leaves
    // must be one consecutive slice and the replacement must occupy the same
    // space. Count changes make later addresses stale.

    // A copy of the tree with one selected run *reshaped*.
    //
    // The same span in a different shape. `children` fills the occupied space.
    // Brackets, hairpin groups and glissando groups may stand there.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.reshapeRun(bar, ScoreSelection(bar), "c8 d8 e8 f8")
    //     .children.size }.value
    // 4
    // >>> { var bar = Measure("1/4", "c4");
    //     ScoreEdit.reshapeRun(bar, ScoreSelection(bar), "3:2[c8 d8 e8]")
    //     .children.first.class }.value
    // Tuplet
    //
    // ^ ScoreElement
    *reshapeRun { |element, selection, children|
        var label = "reshapeRun";
        var records = this.prCheckedRunShape(element, selection, label);
        var slice = this.prCheckedSlice(records, label);
        var list;

        list = this.prCheckedReshapeChildren(children, records, label);
        this.prCheckedSpans(records, list, label);
        ^Validator.validate(this.prResliced(element, [], slice, list))
    }

    // A copy of the tree with one selected run *filled* from an RTM shape.
    //
    // A thin route over `reshapeRun`: lower the occupied span from
    // `proportions` and `pitches`, then use reshape's replacement rules.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.fillRun(bar, ScoreSelection(bar), [1, 1, 1], "e f g")
    //     .children.first.class }.value
    // Tuplet
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.fillRun(bar, ScoreSelection(bar), [1, -1, 1, 1], "e f g")
    //     .leaves.collect { |leaf| leaf.isKindOf(MusicRest) } }.value
    // [ false, true, false, false ]
    //
    // ^ ScoreElement
    *fillRun { |element, selection, proportions, pitches|
        var label = "fillRun";
        var records = this.prCheckedRunShape(element, selection, label);
        // Label the stream conversion so a foreign stream names this method.
        var stream = RhythmTree.pitchStream(pitches, "ScoreEdit.fillRun");
        // Refuse a bad cell before drawing from the row.
        var cell = RhythmCell.checkedProportions(proportions);
        var span = this.prCheckedFillSpanOf(records, label);

        // Dry run: catch validation refusals before spending the row.
        this.prFilled(element, selection, span, cell, nil);
        ^this.prFilled(element, selection, span, cell, stream)
    }

    // The written span a checked run fills, asking the shared refusals under
    // `label`. Generated leaves carry no span endpoints, so an empty
    // replacement is equivalent here.
    //
    // ^ Duration
    *prCheckedFillSpanOf { |records, label|
        this.prCheckedSlice(records, label);
        this.prCheckedSpans(records, [], label);
        ^this.prTotalOf(records.collect { |record| record[\written] })
    }

    // A copy of the tree with every selected *measure voice* filled from one
    // cell.
    //
    // Units are canonicalized by lane, then bar. Direct material restarts per
    // lane; a caller-held `PitchStream` threads through all lanes.
    // >>> { var score = MusicScore.oneStaff([Measure("2/4", "c4 d4"),
    //     Measure("2/4", "e4 f4")], "V");
    //     ScoreEdit.fillMeasures(score, ScoreSelection(score), [1, 1], "c d e")
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ c, d, e, c ]
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.fillMeasures(bar, ScoreSelection(bar), [1, 1, 1], "e f g")
    //     .children.first.class }.value
    // Tuplet
    //
    // ^ ScoreElement
    *fillMeasures { |element, selection, proportions, pitches|
        var label = "fillMeasures";
        var units = this.prCheckedUnits(element, selection, label,
            "bar to fill");
        // Refuse a bad cell before lowering anything.
        var cell = RhythmCell.checkedProportions(proportions);
        var lanes = this.prLanesOf(units);
        var current = element;

        // Name cross-selected-bar ties before a per-unit check sees one half.
        this.prCheckedTiesWithinUnits(element, units, label);

        // Reject foreign streams before structural dry-run errors can mask them.
        RhythmTree.pitchStream(pitches, "ScoreEdit.fillMeasures");

        // Dry-run every unit before spending a caller-visible stream.
        units.inject(element, { |tree, unit|
            this.prFilledUnit(tree, unit[\path], cell, nil, label) });

        lanes.do { |lane|
            var stream = if (pitches.isKindOf(PitchStream)) { pitches } {
                RhythmTree.pitchStream(pitches, "ScoreEdit.fillMeasures") };
            units.select { |unit| unit[\lane] == lane }.do { |unit|
                current = this.prFilledUnit(current, unit[\path], cell, stream,
                    label)
            }
        };
        ^current
    }

    // Refuse ties wholly selected but split across filled units.
    *prCheckedTiesWithinUnits { |element, units, label|
        var unitOf = Dictionary.new;

        units.do { |unit, index|
            this.prSelectionForUnit(element, unit[\path]).paths.do { |path|
                unitOf[path] = index }
        };
        ScoreSelection(element).logicalTies.do { |run|
            var found = run[\paths].collect { |path| unitOf[path] };

            if (found.every { |each| each.notNil }
                and: { found.any { |each| each != found.first } }) {
                Error(
                    "ScoreEdit.%: the tie written as % crosses between two selected bars, and each bar is filled on its own, so no fill can keep it. Clear it with clearTies first."
                    .format(label, run[\paths].asCompileString)
                ).throw
            }
        }
    }

    // Fill one current-tree unit. `fillMeasures` owns the dry pass.
    //
    // ^ ScoreElement
    *prFilledUnit { |element, path, cell, stream, label|
        var selection = this.prSelectionForUnit(element, path);
        var records = this.prCheckedRunShape(element, selection, label);

        ^this.prFilled(element, selection, this.prCheckedFillSpanOf(records, label), cell, stream)
    }

    // Leaf splitting.

    // A copy of the tree with one selected leaf split by proportional shares.
    //
    // Unlike `fillRun`, the generated fragments come from the source leaf:
    // notes keep their pitch, chords keep their pitches and rests stay rests.
    //
    // >>> { var bar = Measure("1/4", "c4");
    //     ScoreEdit.splitLeafByProportions(bar, ScoreSelection(bar), [1, 1])
    //     .leaves.collect { |leaf| leaf.duration } }.value
    // [ Duration(1/8), Duration(1/8) ]
    // >>> { var bar = Measure("1/4", "c4");
    //     ScoreEdit.splitLeafByProportions(bar, ScoreSelection(bar), [1, 1, 1])
    //     .children.first.class }.value
    // Tuplet
    //
    // ^ ScoreElement
    *splitLeafByProportions { |element, selection, proportions|
        var label = "splitLeafByProportions";
        var record = this.prCheckedLeafToSplit(element, selection, label);

        ^this.prSplitLeafInto(element, record,
            this.prCheckedSplitCell(proportions, label), label)
    }

    // The explicit-duration sibling. The durations must fill the selected leaf
    // exactly and become the weights the shared lowering reads, so three equal
    // thirds of a quarter get the bracket that spells them.
    //
    // >>> { var bar = Measure("1/4", "c4");
    //     ScoreEdit.splitLeafByDurations(bar, ScoreSelection(bar), "8 8")
    //     .leaves.collect { |leaf| leaf.duration } }.value
    // [ Duration(1/8), Duration(1/8) ]
    // >>> { var bar = Measure("1/4", "c4");
    //     ScoreEdit.splitLeafByDurations(bar, ScoreSelection(bar),
    //     ["1/12", "1/12", "1/12"]).children.first.class }.value
    // Tuplet
    //
    // ^ ScoreElement
    *splitLeafByDurations { |element, selection, durations|
        var label = "splitLeafByDurations";
        var record = this.prCheckedLeafToSplit(element, selection, label);
        var weights = this.prCheckedSplitWeights(durations, record[\written],
            label);

        ^this.prSplitLeafInto(element, record,
            this.prCheckedSplitCell(weights, label), label)
    }

    // Replace one leaf's slice with the fragments a cell lowers to. Fact
    // placement is ScorePrepare's split policy.
    //
    // ^ ScoreElement
    *prSplitLeafInto { |element, record, cell, label|
        var slice = this.prCheckedSlice([record], label);
        var children;

        children = this.prSplitLeafByCell(record[\leaf], record[\written],
            cell);

        children = this.prCheckedReshapeChildren(children, [record], label);
        this.prCheckedSpans([record], children, label);
        ^Validator.validate(this.prResliced(element, [], slice, children))
    }

    // Exact durations as integer weights. `Duration` owns what a duration is,
    // so a malformed one is refused in its name before any of this runs.
    //
    // ^ [Integer]
    *prCheckedSplitWeights { |durations, written, label|
        var exact = Duration.asDurations(durations);
        var total, common;

        if (exact.size < 2) {
            Error(
                "ScoreEdit.%: a split needs at least two durations, got %."
                .format(label, exact.size)
            ).throw
        };
        exact.do { |each|
            if (each <= Duration(0, 1)) {
                Error(
                    "ScoreEdit.%: every duration must be positive, got %. A leaf split does not create rests. Use fillRun when the rhythm itself creates them."
                    .format(label, each)
                ).throw
            }
        };
        total = exact.reduce('+');
        if (total != written) {
            Error(
                "ScoreEdit.%: the durations total %, and the leaf is written %. They must fill it exactly."
                .format(
                    label,
                    total,
                    written
                )
            ).throw
        };
        common = exact.collect { |each| each.denominator }
            .reduce { |a, b| a lcm: b };
        ^exact.collect { |each| each.numerator * (common div: each.denominator) }
    }

    // One fill, lowered and reshaped. A nil stream means neutral material.
    *prFilled { |element, selection, span, cell, stream|
        ^this.reshapeRun(element, selection,
            RhythmTree.voice(span, cell, stream).children)
    }

    // ^ IdentityDictionary
    *prCheckedLeafToSplit { |element, selection, label|
        var checked = this.prCheckedSelectionOf(element, selection, label,
            "leaf to split");

        if (checked.records.size != 1) {
            Error(
                "ScoreEdit.%: the selection holds % leaves. Split one leaf at a time."
                .format(label, checked.records.size)
            ).throw
        };
        ^checked.records.first
    }

    // ^ RhythmCell
    *prCheckedSplitCell { |proportions, label|
        var cell = RhythmCell(RhythmCell.checkedProportions(proportions));

        if (cell.shareCount < 2) {
            Error(
                "ScoreEdit.%: a split needs at least two terminal shares, got %."
                .format(label, cell.shareCount)
            ).throw
        };
        if (cell.attackCount != cell.shareCount) {
            Error(
                "ScoreEdit.%: a leaf split cannot contain silent shares. Use fillRun when the rhythm itself creates rests."
                .format(label)
            ).throw
        };
        ^cell
    }

    // Silent-share refusal makes each terminal share call leafFor once. `count`
    // is also the last-fragment tie index.
    //
    // ^ [ScoreElement]
    *prSplitLeafByCell { |leaf, span, cell|
        var count = cell.attackCount;
        var at = 0;

        ^RhythmTree.prFill(ScoreContainer([]), span, cell.proportions,
            { |written|
                var index = at;
                at = at + 1;
                ScorePrepare.prCopyLeaf(leaf, written,
                    if (index == (count - 1)) { leaf } { nil },
                    index == 0)
            }).children
    }

    // Simplification and joining.

    // Note [Simplification is preparation, run backwards]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `simplifyRun` reverses preparation: rests through `prRestRun`, tied leaves
    // through `prCopyLeaf`, trivial brackets by flattening.
    //
    // A chord tie fuses only when every pitch continues. Partial masks stay split.
    //
    // Outside brackets, use `Meter#isWellPlaced` so fusion doesn't hide a metric
    // line. Inside brackets, use written `isNotatable`.
    //
    // Fusion may carry only first-leaf markings, graces and starts,
    // last-leaf ordinary stops and first-leaf stops that name the
    // attack. Anything else is refused by name.

    // A copy of the tree with every selected *bar* simplified.
    // Whole-unit checks match `repeatMeasures`. Rewrites stay `simplifyRun`'s.
    //
    // >>> { var score = MusicScore.oneStaff([Measure("4/4", "c4~ c4 r4 r4"),
    //     Measure("4/4", "d4~ d4 e4 f4")], "V");
    //     ScoreEdit.simplifyMeasures(score, ScoreSelection(score))
    //     .leaves.size }.value
    // 5
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4"); ScoreEdit.simplifyMeasures(bar, ScoreSelection(bar)) === bar }.value
    // true
    //
    // ^ ScoreElement
    *simplifyMeasures { |element, selection|
        var label = "simplifyMeasures";
        var units = this.prCheckedUnits(element, selection, label,
            "bar to simplify");
        var current = element;

        units.do { |unit|
            current = this.simplifyRun(current,
                this.prSelectionForUnit(current, unit[\path]))
        };
        ^current
    }

    // Re-read one unit from the current tree. [] is a bare bar.
    *prSelectionForUnit { |element, path|
        var depth = path.size;
        ^ScoreSelection(element).where { |record|
            path.isEmpty or: { record[\path].keep(depth) == path } }
    }

    // A copy of the tree with one selected run *simplified*.
    // See Note [Simplification is preparation, run backwards].
    //
    // Plain rests fuse, full-tie runs fuse and trivial brackets flatten.
    // Nothing sounds different. A no-op answers the tree it was given.
    //
    // >>> { var bar = Measure("4/4", "c4~ c4 r4 r4"); ScoreEdit.simplifyRun(bar, ScoreSelection(bar)).children.size }.value
    // 2
    // >>> { var bar = Measure("4/4", "c4 r4 r4 d4"); ScoreEdit.simplifyRun(bar, ScoreSelection(bar)) === bar }.value
    // true
    // >>> { var bar = Measure("1/4", [Tuplet.ratio(1, 1, "c8 d8")]);
    //     ScoreEdit.simplifyRun(bar, ScoreSelection(bar)).children.size }.value
    // 2
    //
    // ^ ScoreElement
    *simplifyRun { |element, selection|
        var label = "simplifyRun";
        var records = this.prCheckedRun(element, selection, label,
            "run to simplify");
        var owner, slice, measure, kept, simplified, collapsed;

        this.prCheckedOneMeasure(records, label);
        owner = this.prOwnerOf(element, records);
        slice = this.prCheckedOwnedSlice(element, records, owner, label);
        measure = records.first[\measure];
        kept = ScoreSelection(element).elementAtPath(owner).children.asArray
            .copyRange(slice[1], slice[2]);
        simplified = this.prSimplified(kept, measure,
            records.first[\barOffset] + measure.metricOffset,
            this.prOwnerMultiplier(element, owner), label);

        // Collapse before the no-op test. An already-degenerate wrapper fuses
        // nothing.
        collapsed = if (simplified.size == 1) {
            this.prCollapsedWrappers(element, slice, simplified.first)
        };
        if (collapsed.notNil and: { collapsed[0][0].size < slice[0].size }) {
            ^Validator.validate(
                this.prResliced(element, [], collapsed[0], [collapsed[1]]))
        };
        if (this.prSameChildren(kept, simplified)) { ^element };
        ^Validator.validate(this.prResliced(element, [], slice, simplified))
    }

    // A copy of the tree with one selected run joined into a single leaf. Rests
    // join by adjacency, notes and chords by tie.
    // See Note [Split leaf adornments] in ScorePrepare.sc.
    //
    // >>> { var bar = Measure("1/2", [MusicNote("c4", true), MusicNote("c4")]);
    //     ScoreEdit.joinLeaves(bar, ScoreSelection(bar))
    //     .leaves.collect { |leaf| leaf.duration } }.value
    // [ Duration(1/2) ]
    //
    // ^ ScoreElement
    *joinLeaves { |element, selection|
        var label = "joinLeaves";
        // Joins may work inside a longer tie or bracket. Ownership checks below
        // draw the structural boundary.
        var records = this.prCheckedContiguousRun(element, selection, label,
            "run to join");
        var owner, slice, run, written, joined, collapsed;

        // Refuse before reslicing so the edit names the barline boundary.
        this.prCheckedOneMeasure(records, label,
            "A joined leaf stands in one bar.");
        // A join admits partial brackets. Collapse only the wrappers it empties.
        owner = this.prLeafOwnerOf(records);
        slice = this.prCheckedOwnedSlice(element, records, owner, label);
        run = records.collect { |record| record[\leaf] };
        this.prCheckedJoinable(run, label);
        this.prCheckedFusable(run, label, "selected run");
        // Sum owner children, not leaves: a selected bracket contributes
        // occupied time.
        written = this.prTotalOf(
            ScoreSelection(element).elementAtPath(owner).children.asArray
                .copyRange(slice[1], slice[2])
                .collect { |child| this.prOccupies(child) });
        joined = ScorePrepare.prCopyLeaf(run.first, written, run.last);
        joined = joined.spanners_(joined.spanners ++ run.last.spannerStops);
        collapsed = this.prCollapsedWrappers(element, slice, joined);
        ^Validator.validate(
            this.prResliced(element, [], collapsed[0], [collapsed[1]]))
    }

    // Collapse one-leaf tuplet wrappers when the scaled duration is notatable.
    // Copy the leaf: `simplifyRun` may pass one from the source tree.
    *prCollapsedWrappers { |element, slice, leaf|
        var path = slice[0], first = slice[1], last = slice[2];
        var out = leaf, node, scaled;

        while {
            path.notEmpty and: {
                node = ScoreSelection(element).elementAtPath(path);
                node.isKindOf(Tuplet)
                    and: { first == 0 }
                    and: { last == (node.children.size - 1) }
                    and: {
                        scaled = out.duration * node.multiplier;
                        scaled.isNotatable
                    }
            }
        } {
            out = ScorePrepare.prCopyLeaf(out, scaled, out, true);
            first = path.last;
            last = first;
            path = path.drop(-1)
        };
        ^[[path, first, last], out]
    }

    // Rests join by adjacency. Sounding leaves must tie into each next leaf.
    *prCheckedJoinable { |run, label|
        if (run.size < 2) {
            Error(
                "ScoreEdit.%: a join needs at least two leaves, got %."
                .format(label, run.size)
            ).throw
        };
        if (run.every { |leaf| leaf.isKindOf(MusicRest) }) { ^run };
        run.do { |leaf, index|
            if (index < (run.size - 1)
                and: { this.prTiesInto(leaf, run[index + 1]).not }) {
                Error(
                    "ScoreEdit.%: leaf % does not tie into leaf %, so joining them would remove an attack. A run joins when every leaf ties into the next, or when all of them are rests."
                    .format(label, index + 1, index + 2)
                ).throw
            }
        };
        ^run
    }

    // One bar, with caller-specific refusal text.
    *prCheckedOneMeasure { |records, label,
        saying = "Simplify one bar at a time."|
        var bars = records.collect { |record| record[\measureIndex] }.as(Set);

        if (bars.size > 1) {
            Error(
                "ScoreEdit.%: the selection covers % bars. % Narrow it with inMeasure."
                .format(label, bars.size, saying)
            ).throw
        };
        ^records
    }

    // Run ownership

    // Note [A run is owned, not parented]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `prCheckedSlice` reads each selected leaf's parent. Simplification needs the
    // common owner instead, because a whole trivial bracket can flatten with its
    // neighbor.
    //
    // `prOwnerOf` may walk up because `prCheckedRun` has already refused partial
    // brackets. Callers that admit partial brackets use `prLeafOwnerOf`.

    // The node whose children this run rewrites.
    // Exact common owner for callers that may select part of a bracket.
    //
    // ^ [Integer]
    *prLeafOwnerOf { |records|
        ^this.prCommonPrefix(records.collect { |record| record[\path] })
    }

    // ^ [Integer]
    *prOwnerOf { |element, records|
        var selection = ScoreSelection(element);
        var owner = this.prCommonPrefix(
            records.collect { |record| record[\path] });
        var node;

        // Flatten through trivial brackets only. Kept brackets own their
        // simplified contents.
        while {
            owner.notEmpty and: {
                node = selection.elementAtPath(owner);
                node.isKindOf(Tuplet) and: { node.isTrivial } }
        } {
            owner = owner.copyRange(0, owner.size - 2)
        };
        ^owner
    }

    // Longest shared prefix that still leaves each path a child index.
    //
    // >>> ScoreEdit.prCommonPrefix([[0, 0, 1, 0], [0, 0, 1, 1], [0, 0, 2]])
    // [ 0, 0 ]
    //
    // ^ [Integer]
    *prCommonPrefix { |paths|
        var first = paths.first;
        var size = paths.collect { |path| path.size - 1 }.minItem;

        paths.do { |path|
            var shared = 0;
            while { shared < size and: { path[shared] == first[shared] } } {
                shared = shared + 1
            };
            size = shared
        };
        ^first.copyRange(0, size - 1)
    }

    // The owner's touched children, as `prResliced` reads a slice. Each selected
    // container must be taken whole.
    //
    // ^ ([Integer], Integer, Integer)
    *prCheckedOwnedSlice { |element, records, owner, label|
        var node = ScoreSelection(element).elementAtPath(owner);
        var chosen = Dictionary.new;
        var indexes;

        records.do { |record| chosen[record[\path]] = true };
        indexes = records.collect { |record| record[\path][owner.size] }
            .as(Set).asArray.sort;
        if (indexes.size != (indexes.last - indexes.first + 1)) {
            Error(
                "ScoreEdit.%: the selected leaves are not consecutive children of %."
                .format(label, owner.asCompileString)
            ).throw
        };
        indexes.do { |index|
            var path = owner ++ [index];
            this.prLeafPathsUnder(node.children[index], path).do { |each|
                if (chosen.includesKey(each).not) {
                    Error(
                        "ScoreEdit.%: the element at % is only partly selected. Take it whole or leave it out."
                        .format(label, path.asCompileString)
                    ).throw
                }
            }
        };
        ^[owner, indexes.first, indexes.last]
    }

    // How written time under the owner is scaled, which is every enclosing
    // bracket multiplied together.
    //
    // ^ Duration
    *prOwnerMultiplier { |element, owner|
        var here = element, scale = Duration(1, 1);

        owner.do { |step|
            scale = scale * here.multiplier;
            here = here.children[step]
        };
        ^scale * here.multiplier
    }

    // simplification helpers

    // The slice rewritten. Brackets that scale nothing go first, so the leaves
    // under one are fused with the leaves beside it.
    *prSimplified { |children, measure, offset, multiplier, label|
        var flat = this.prFlattened(children);
        var offsets = ScorePrepare.leafOffsetsIn(measure);
        var multipliers = ScorePrepare.leafMultipliersIn(measure);
        var out = List.new;
        var cursor = offset;
        var index = 0;

        while { index < flat.size } {
            var step = this.prSimplifiedAt(flat, index, measure, cursor,
                multiplier, offsets, multipliers, label);
            var taken = flat.copyRange(index, index + step[0] - 1);
            out.addAll(step[1]);
            cursor = cursor + (this.prTotalOf(
                taken.collect { |child| this.prOccupies(child) }) * multiplier);
            index = index + step[0]
        };
        ^out.asArray
    }

    // Every bracket that scales nothing replaced by its children, as deep as
    // they nest. `Tuplet#isTrivial` owns what counts as one.
    //
    // ^ [ScoreElement]
    *prFlattened { |children|
        ^children.collect { |child|
            if (child.isKindOf(Tuplet) and: { child.isTrivial }) {
                this.prFlattened(child.children.asArray)
            } {
                [child]
            }
        }.flatten(1)
    }

    // one step: how many children were read and what stands in their place
    *prSimplifiedAt { |flat, index, measure, offset, multiplier, offsets,
        multipliers, label|
        var here = flat[index];
        var run;

        if (ScorePrepare.prIsPlainRest(here)) {
            run = this.prRunOf(flat, index,
                { |child| ScorePrepare.prIsPlainRest(child) });
            ^[run.size, this.prRespelledRests(run, measure, offsets, multipliers)]
        };
        run = this.prTieRunAt(flat, index);
        if (run.size > 1) {
            ^[run.size,
                this.prFusedTie(run, measure, offset, multiplier, label)]
        };
        ^[1, [here]]
    }

    // The longest run from `index` every member of which answers the test.
    //
    // ^ [ScoreElement]
    *prRunOf { |list, index, test|
        var out = List.new;
        var at = index;

        while { at < list.size and: { test.value(list[at]) } } {
            out.add(list[at]);
            at = at + 1
        };
        ^out.asArray
    }

    // the fewest readable rests, preserving identity when the spelling is the
    // same.
    *prRespelledRests { |run, measure, offsets, multipliers|
        var pieces;

        if (run.size < 2) { ^run };
        pieces = ScorePrepare.prRestRun(run, measure, offsets, multipliers);
        if (pieces.collect { |rest| rest.duration }
            == run.collect { |rest| rest.duration }) { ^run };
        ^pieces
    }

    // The longest run from `index` written as one sounding pitch.
    //
    // ^ [ScoreElement]
    *prTieRunAt { |list, index|
        var out = [list[index]];
        var at = index;

        while { (at + 1) < list.size
            and: { this.prTiesInto(list[at], list[at + 1]) } } {
            at = at + 1;
            out = out.add(list[at])
        };
        ^out
    }

    // Whether the second leaf continues the first. Every pitch has to carry
    // over, so a partial chord mask is two sounding pitches and not one.
    //
    // ^ Boolean
    *prTiesInto { |a, b|
        if (a.isLeaf.not or: { b.isLeaf.not }) { ^false };
        if (a.class !== b.class) { ^false };
        if (a.isKindOf(Chord)) {
            if (a.tiesAll.not) { ^false }
        } {
            if (a.isKindOf(MusicNote).not) { ^false };
            if (a.tiesToNext != true) { ^false }
        };
        ^ScoreSelection.prPitchesOf(a) == ScoreSelection.prPitchesOf(b)
    }

    // one fused leaf or the original run when the meter refuses it
    *prFusedTie { |run, measure, offset, multiplier, label|
        var written = this.prTotalOf(run.collect { |leaf| leaf.duration });
        var first = run.first, last = run.last;
        var fits, fused;

        fits = if (multiplier == Duration(1, 1)) {
            measure.meter.isWellPlaced(offset, written)
        } {
            written.isNotatable
        };
        if (fits.not) { ^run };
        // Refuse attachment loss only when the fusion would actually occur.
        this.prCheckedFusable(run, label);
        fused = ScorePrepare.prCopyLeaf(first, written, last);
        ^[fused.spanners_(fused.spanners ++ last.spannerStops)]
    }

    // Attachment loss shared by `simplifyRun` and `joinLeaves`.
    // See Note [Simplification is preparation, run backwards].
    *prCheckedFusable { |run, label, saying = "tied run"|
        var last = run.size - 1;
        var opened, enclosed;

        // One leaf cannot both open and close a span. Compare endpoint keys by
        // value.
        opened = run.first.spannerStarts
            .collect { |each| "%/%".format(each.kind, each.id) };
        enclosed = run.last.spannerStops
            .collect { |each| "%/%".format(each.kind, each.id) }
            .detect { |key| opened.any { |each| each == key } };
        enclosed !? {
            Error(
                "ScoreEdit.%: a % begins and ends inside the %. Detach it first."
                .format(
                    label,
                    enclosed.split($/).first,
                    saying
                )
            ).throw
        };

        run.do { |leaf, index|
            var said = this.prFusionLoss(leaf, index, last);
            said !? {
                Error(
                    "ScoreEdit.%: leaf % of the % holds %. Detach it, or move it to the % leaf of the run."
                    .format(label, index + 1, saying, said[0], said[1])
                ).throw
            }
        };
        ^run
    }

    // Loss to refuse plus the end where the caller could keep it. A first-leaf
    // attack stop is split output; later stops are loss.
    *prFusionLoss { |leaf, index, last|
        if (index > 0) {
            if (leaf.hasMarkings) { ^["a marking", "first"] };
            if (leaf.hasGraces) { ^["a grace group", "first"] };
            if (leaf.spannerStarts.notEmpty) { ^["a span start", "first"] }
        };
        if (index < last and: { leaf.spannerStops.notEmpty }) {
            if (index == 0 and: { leaf.spannerStops.every { |endpoint|
                ScorePrepare.prNamesTheAttack(leaf, endpoint) } }) { ^nil };
            ^["a span stop", "last"]
        };
        ^nil
    }

    // Whether the rewrite is the same live children it was handed, so an edit
    // with nothing to do can answer the tree rather than a copy of it.
    //
    // ^ Boolean
    *prSameChildren { |before, after|
        if (before.size != after.size) { ^false };
        ^before.every { |child, index| child === after[index] }
    }

    // Run validation.

    // Note [A run edit loses no span]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A span is a two-leaf fact. Replacement must restate each endpoint by kind,
    // edge and id or the edit would validate with the span gone. Nothing is
    // carried automatically.
    //
    // Endpoint matching uses kind, edge and id. Direction, text and placement
    // are properties. Order is `Validator`'s.

    // Note [A run edit does not own directions]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Directions live on the bar, not leaves. Run edits carry none and move none.
    // Equal occupied space keeps later boundaries unchanged.
    //
    // If a reshape removes the boundary a direction uses, `Validator` refuses
    // the edit. Direction edits are `Measure`-owned through `replaceElementAt`.

    // Everything a run owes about where. Spans are checked after the replacement
    // is known.
    *prCheckedRun { |element, selection, label, saying = "run to replace"|
        var records = this.prCheckedRunShape(element, selection, label, saying);

        this.prCheckedWholeTuplets(element, records, label);
        ^records
    }

    // Contiguity is not enough: a selected run can still cut through a tuplet.
    *prCheckedWholeTuplets { |element, records, label|
        var selection = ScoreSelection(element);
        var chosen = Dictionary.new;
        var seen = Set.new;

        // Use a Dictionary rather than a list: `Array#includes` would only find
        // the same Array object, not an equal path.
        records.do { |record| chosen[record[\path]] = true };

        records.do { |record|
            var path = record[\path];
            (1 .. path.size - 1).do { |depth|
                var prefix = path.copyRange(0, depth - 1);
                var node;
                if (seen.includes(prefix).not) {
                    seen.add(prefix);
                    node = selection.elementAtPath(prefix);
                    if (node.isKindOf(Tuplet)) {
                        this.prLeafPathsUnder(node, prefix).do { |each|
                            if (chosen.includesKey(each).not) {
                                Error(
                                    "ScoreEdit.%: the tuplet at % is only partly selected. Take it whole or leave it out."
                                    .format(
                                        label,
                                        prefix.asCompileString
                                    )
                                ).throw
                            }
                        }
                    }
                }
            }
        };
        ^records
    }

    // Note [A written run cannot restate every fact]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Built leaves can keep or drop one-leaf facts deliberately. A written run keeps
    // only what the grammar spells. Invalid source grace groups are refused here so
    // the edit names them before `Validator` would.
    //
    // For `replaceRun`, the written form is leaves only, so it can't restate a
    // span endpoint.

    // An Array of leaves or a written leaves-only run.
    *prRunLeaves { |leaves, label|
        if (leaves.isKindOf(String)) {
            ^ScoreNotation.prNotationLeaves(leaves,
                "ScoreEdit.%".format(label)).asArray
        };
        ^this.prRunArray(leaves, "an Array of ScoreLeaf", label)
    }

    // Replacement leaves, checked leaf for leaf by written duration.
    *prCheckedRunLeaves { |leaves, records, label|
        var list;

        this.prCheckedSpellable(records, leaves, label);
        list = this.prRunLeaves(leaves, label);
        if (list.size != records.size) {
            Error(
                "ScoreEdit.%: % replacements for % selected leaves; a run is replaced leaf-for-leaf."
                .format(
                    label,
                    list.size,
                    records.size
                )
            ).throw
        };
        list.do { |leaf, index|
            var record = records[index];
            this.prCheckedLeaf(leaf, record[\path], label);
            if (leaf.duration != record[\written]) {
                Error(
                    "ScoreEdit.%: replacement % is written %, but leaf % is written %. A run is replaced leaf-for-leaf."
                    .format(
                        label,
                        index,
                        leaf.duration,
                        record[\path].asCompileString,
                        record[\written]
                    )
                ).throw
            }
        };
        ^list
    }

}
