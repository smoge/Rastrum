// The write-side counterpart to the query layer: check one requested edit,
// rebuild the tree, and answer a validated copy.
//
// Leaf methods give leaf-specific errors. Element and shape methods work on
// addressed nodes or slots. Run methods take a selection because a run may span
// several paths.
ScoreEdit {

    // Note [One read, one checked copy]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // ScoreEdit: addressed replacement, removal, insertion, reorder and move
    //
    // Every edit reads the original tree, checks its shape, then rebuilds once.
    // Paths are the same child-index addresses `ScoreSelection`, `ScoreDiff` and
    // `ScoreLocator` use. The answer is a validated copy.

    // A copy of the tree with one leaf replaced. The original is untouched.
    //
    // `element` is what `ScoreSelection` takes: a MusicScore, Staff or Measure,
    // and `path` is relative to it.
    //
    // >>> ScoreEdit.replaceLeafAt(Measure("2/4", "c4 d4"), [1], MusicRest(Duration.quarter)).children.last.class
    // MusicRest
    //
    // ^ ScoreElement
    *replaceLeafAt { |element, path, leaf|
        ^this.prReplaced(element, [[path, leaf]], "replaceLeafAt")
    }

    // Note [Several leaves are one edit]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `replaceLeavesAt` reads every pair before it rebuilds, then validates
    // once. The pairs are a set, so no path may repeat and none of them is
    // sequenced. Shape edits stay separate calls because each one changes later
    // addresses.

    // Replace several leaves at once as `[path, leaf]` pairs.
    //
    // >>> ScoreEdit.replaceLeavesAt(Measure("2/4", "c4 d4"),
    //     [[[0], MusicNote("d4", true)], [[1], MusicNote("d4")]])
    //     .children.first.tiesToNext
    // true
    //
    // ^ ScoreElement
    *replaceLeavesAt { |element, replacements|
        ^this.prReplaced(element,
            this.prCheckedPairs(replacements, "replaceLeavesAt"),
            "replaceLeavesAt")
    }

    // Note [Address edits name their shape]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `replaceElementAt`, `removeElementAt`, `insertElementAt`,
    // `reorderChildrenAt` and `moveElementAt` all use child-index paths, but
    // each method defines what its path means: node, slot, permutation, or node
    // plus slot.
    //
    // Survivors are copied, not spliced.
    // See Note [Copy, never adopt] in ScorePrepare.sc.

    // The tree with one *element* replaced, leaf or container alike. A bar, a
    // voice or a tuplet is addressed exactly as a leaf is.
    //
    // >>> ScoreEdit.replaceElementAt(MusicScore.oneStaff(
    //     Measure("2/4", "c4 d4"), "Violin"), [0, 0],
    //     Measure("2/4", "g4 a4")).children.first.children.first.children.first
    //     .pitch.midinote
    // 67.0
    //
    // ^ ScoreElement
    *replaceElementAt { |element, path, replacement|
        var label = "replaceElementAt";
        var selection = ScoreSelection(element);
        var steps = this.prCheckedPath(selection, path, label);

        this.prCheckedElement(replacement, steps, label);
        if (steps.isEmpty) { this.prRefuseRoot(label) };
        // catch a stale address before rebuilding
        this.prResolved(selection, steps, label);
        ^Validator.validate(this.prRebuiltWith(element, [], steps,
            ScorePrepare.copyOf(replacement)))
    }

    // A copy of the tree with one element *removed*. Unlike leaf replacement,
    // this changes later sibling addresses.
    // See Note [Address edits name their shape].
    //
    // Removing a note is usually `replaceLeafAt(path,
    // MusicRest(...))` rather than this: a bar declares its length,
    // so taking a leaf out of a full one is refused by arithmetic.
    //
    // >>> ScoreEdit.removeElementAt(MusicScore.oneStaff([
    //     Measure("2/4", "c4 d4"), Measure("2/4", "e4 f4")], "Violin"), [0, 1])
    //     .children.first.children.size
    // 1
    //
    // ^ ScoreElement
    *removeElementAt { |element, path|
        var label = "removeElementAt";
        var selection = ScoreSelection(element);
        var steps = this.prCheckedPath(selection, path, label);
        var found;

        if (steps.isEmpty) { this.prRefuseRoot(label) };
        found = this.prResolved(selection, steps, label);
        this.prCheckedRemoval(element, steps, found, label);
        ^Validator.validate(this.prRemovedWith(element, [], steps))
    }

    // the tree with one element *inserted* into a slot as a copy
    // See Note [Address edits name their shape].
    //
    // `path` is a slot: all but the last index reach the parent and
    // the last is where the new child goes. `children.size` there is
    // an append.
    //
    // >>> ScoreEdit.insertElementAt(MusicScore.oneStaff(
    //     Measure("2/4", "c4 d4"), "Violin"), [0, 1], Measure("2/4", "e4 f4"))
    //     .children.first.children.size
    // 2
    //
    // ^ ScoreElement
    *insertElementAt { |element, path, insertion|
        var label = "insertElementAt";
        var slot = this.prCheckedSlot(element, path, label);

        this.prCheckedElement(insertion, slot[0] ++ [slot[1]], label);
        ^Validator.validate(this.prInsertedWith(element, [], slot[0], slot[1],
            ScorePrepare.copyOf(insertion)))
    }

    // the tree with one container's children *reordered* as a copy
    // See Note [Address edits name their shape].
    //
    // `order` is a full permutation of the container's child indexes, read as
    // the new order by old index. It says where everything lands without naming
    // a destination.
    //
    // >>> ScoreEdit.reorderChildrenAt(MusicScore.oneStaff(
    //     Measure("2/4", "c4 d4"), "Violin"), [0, 0], [1, 0])
    //     .children.first.children.first.children.first.pitch.midinote
    // 62.0
    //
    // ^ ScoreElement
    *reorderChildrenAt { |element, parentPath, order|
        var label = "reorderChildrenAt";
        var selection = ScoreSelection(element);
        var steps = this.prCheckedPath(selection, parentPath, label);
        var holder = if (steps.isEmpty) { element } {
            this.prResolved(selection, steps, label) };
        var checked;

        if (holder.isLeaf) { this.prRefuseLeafParent(steps, label) };
        checked = this.prCheckedPermutation(order, holder.children.size, label);
        ^Validator.validate(
            this.prReorderedWith(element, [], steps, checked))
    }

    // the tree with one element carried into *another* container as
    // a copy.
    //
    // See Note [Address edits name their shape].
    //
    // `fromPath` names a node and `toSlotPath` a slot, both read against the
    // tree as handed in. A move inside one container is refused; use
    // `reorderChildrenAt` for that.
    //
    // >>> ScoreEdit.moveElementAt(MusicScore([
    //     Staff([Measure("2/4", "c4 d4"), Measure("2/4", "e4 f4")], "A"),
    //     Staff([Measure("2/4", "g4 a4")], "B")]), [0, 1], [1, 1])
    //     .children.collect { |staff| staff.children.size }
    // [ 1, 2 ]
    //
    // ^ ScoreElement
    *moveElementAt { |element, fromPath, toSlotPath|
        var label = "moveElementAt";
        var selection = ScoreSelection(element);
        var from = this.prCheckedPath(selection, fromPath, label);
        var moved, slot;

        if (from.isEmpty) { this.prRefuseRoot(label) };
        // resolve both addresses before rebuilding
        moved = this.prResolved(selection, from, label);
        slot = this.prCheckedSlot(element, toSlotPath, label);
        this.prCheckedMove(element, from, slot[0], slot[1], label);
        ^Validator.validate(this.prMovedWith(element, [], from, slot[0],
            slot[1], ScorePrepare.copyOf(moved)))
    }

    // A selection this tree can be edited through: the right kind, read from
    // this exact tree, and not empty. Shared by every selection edit method.
    // `slurRuns` checks the runs it derives rather than the plural selection
    // they came from.
    //
    // ^ ScoreSelection
    *prCheckedSelectionOf { |element, selection, label, saying = "run to replace"|
        if (selection.isKindOf(ScoreSelection).not) {
            Error(
                "ScoreEdit.%: selection must be a ScoreSelection, got %."
                .format(
                    label,
                    if (selection.isNil) { "nil" }
                    { selection.class.name }
                )
            ).throw
        };
        if (selection.source !== element) {
            Error(
                "ScoreEdit.%: the selection was read from another tree, so its paths do not address this one. Read with ScoreSelection(score) or ScoreLocator(score, false)."
                .format(label)
            ).throw
        };
        if (selection.isEmpty) {
            Error(
                "ScoreEdit.%: the selection is empty, so there is no %."
                .format(label, saying)
            ).throw
        };
        ^selection
    }

    // one timeline, one contiguous group. `prCheckedRunShape` adds the
    // whole-tie rule for leaf rewrites.
    *prCheckedContiguousRun { |element, selection, label,
        saying = "run to replace"|
        var groups;

        this.prCheckedSelectionOf(element, selection, label, saying);
        this.prCheckedOneTimeline(selection, label);
        groups = selection.contiguousGroups;
        if (groups.size != 1) {
            // neutral wording: five public methods share this refusal and only
            // one of them replaces anything.
            Error(
                "ScoreEdit.%: the selection is % runs with a gap between them. Take one contiguous run at a time."
                .format(label, groups.size)
            ).throw
        };
        ^groups.first.records
    }

    *prCheckedRunShape { |element, selection, label, saying = "run to replace"|
        var records = this.prCheckedContiguousRun(element, selection, label,
            saying);

        this.prCheckedWholeLogicalTies(element, records, label);
        ^records
    }

    // A logical tie is one sounding pitch written as several leaves. A
    // selection edit takes all of it or none of it. Ties are read from the tree,
    // not the filtered selection.
    //
    // Chords need no separate rule. `logicalTies` splits partial masks
    // per pitch.

    // Refuse partial logical ties.
    *prCheckedWholeLogicalTies { |element, records, label|
        var chosen = Dictionary.new;

        records.do { |record| chosen[record[\path]] = true };
        ScoreSelection(element).logicalTies.do { |run|
            var paths = run[\paths];
            var inside = paths.count { |path| chosen.includesKey(path) };
            if (inside > 0 and: { inside < paths.size }) {
                Error(
                    "ScoreEdit.%: the selection covers part of a tie. It is written as %, and the rest of it is not selected. Select the tie whole, or edit it with replaceLeavesAt."
                    .format(label, paths.asCompileString)
                ).throw
            }
        };
        ^records
    }

    // Report a staff-spanning run before a voice-spanning one.
    *prCheckedOneTimeline { |selection, label|
        var staves = selection.records
            .collect { |record| record[\staffIndex] }.as(Set);
        var voices = selection.records
            .collect { |record| record[\voiceIndex] }.as(Set);

        if (staves.size > 1) {
            Error(
                "ScoreEdit.%: the selection covers % staves. A run is one timeline. Narrow it with inStaff."
                .format(label, staves.size)
            ).throw
        };
        if (voices.size > 1) {
            Error(
                "ScoreEdit.%: the selection covers % voices. A run is one timeline. Narrow it with inVoice."
                .format(label, voices.size)
            ).throw
        };
        ^selection
    }

    // Every leaf path under a node, with `prefix` already naming that node.
    *prLeafPathsUnder { |node, prefix|
        if (node.isLeaf) { ^[prefix] };
        ^node.children.asArray
            .collect { |child, index|
                this.prLeafPathsUnder(child, prefix ++ [index]) }
            .flatten(1)
    }

    // See Note [A run edit loses no span] in ScoreEditRuns.sc.
    // Check after parsing: a written hairpin group can restate endpoints.
    *prCheckedSpans { |records, replacement, label|
        var carried = this.prEndpointTally(
            records.collect { |record| record[\leaf] });
        var restated, short;

        if (carried.isEmpty) { ^records };
        restated = this.prEndpointTally(
            replacement.asArray.collect { |each| each.leaves }.flatten(1));
        short = carried.keys.asArray.sort.detect { |key|
            (restated[key] ? 0) < carried[key] };
        short !? {
            Error(
                "ScoreEdit.%: the run carries % and the replacement does not, so the span would be lost. Restate it on a replacement leaf, or detach the span first."
                .format(label, this.prSpellEndpoint(short))
            ).throw
        };
        ^records
    }

    // Endpoints under these leaves, counted by kind, edge and id.
    *prEndpointTally { |leaves|
        var tally = Dictionary.new;
        leaves.do { |leaf|
            (leaf.spannerStarts ++ leaf.spannerStops).do { |each|
                var key = "%/%/%".format(each.kind, each.edge, each.id);
                tally[key] = (tally[key] ? 0) + 1 } };
        ^tally
    }

    // Turn "slur/stop/1" into prose for a refusal.
    *prSpellEndpoint { |key|
        var parts = key.split($/);
        ^"a % % with id %".format(parts[0], parts[1], parts[2])
    }

    // Facts a written replacement cannot restate from source notation alone.
    // See Note [A written run cannot restate every fact] in ScoreEditRuns.sc.
    *prCheckedSpellable { |records, given, label|
        if (given.isKindOf(String).not) { ^records };
        records.do { |record|
            var said = this.prUnspellableGrace(record[\leaf]);
            said !? {
                Error(
                    "ScoreEdit.%: the grace group at % holds %. Validator refuses that. Repair the source or pass built leaves."
                    .format(label, record[\path].asCompileString, said)
                ).throw
            }
        };
        ^records
    }

    // First grace member `Validator` would refuse, named at the edit site.
    // See Note [What a grace group may hold] in Validator.sc.
    *prUnspellableGrace { |leaf|
        leaf.graces.do { |grace|
            if (grace.isKindOf(MusicNote).not
                and: { grace.isKindOf(Chord).not }) {
                ^"a %, where a group holds notes and chords".format(grace.class)
            };
            if (grace.dur.isNotatable.not) {
                ^"%, which no note head spells".format(grace.dur)
            };
            if (grace.hasGraces) { ^"a grace group of its own" };
            if (Validator.prTiedPitchesOf(grace).notEmpty) {
                ^"a leaf tying onward"
            };
            if (grace.hasMarkings or: { grace.hasSpanners }) {
                ^"a leaf carrying a marking or a spanner"
            };
        };
        ^nil
    }

    // one immediate parent and a consecutive slice of its children
    // See Note [A reshaped run rebuilds one container] in ScoreEditRuns.sc.
    //
    // answers `[parentPath, from, to]`
    //
    // ^ ([Integer], Integer, Integer)
    *prCheckedSlice { |records, label|
        var first = records.first[\path];
        var parent = first.copyRange(0, first.size - 2);
        var indexes;

        records.do { |record|
            var path = record[\path];
            if (path.copyRange(0, path.size - 2) != parent) {
                Error(
                    "ScoreEdit.%: % and % are in different containers, so there is no one container to rebuild. Reshape a run inside one bar, voice or bracket at a time."
                    .format(
                        label,
                        first.asCompileString,
                        path.asCompileString
                    )
                ).throw
            }
        };
        indexes = records.collect { |record| record[\path].last }.sort;
        // a guard on a destructive rebuild rather than a rule of its
        // own: the slice runs first to last, so an unselected child
        // between two selected ones would be deleted without a word.
        // Contiguity already makes that unreachable, since children
        // are sequential and none is zero long.
        if (indexes.size != (indexes.last - indexes.first + 1)) {
            Error(
                "ScoreEdit.%: the selected leaves are not consecutive children of %."
                .format(label, parent.asCompileString)
            ).throw
        };
        ^[parent, indexes.first, indexes.last]
    }

    // Replacement children for a run reshape, held to the space the run
    // occupies rather than paired with it.
    // See Note [A reshaped run rebuilds one container] in ScoreEditRuns.sc.
    //
    // Children rather than leaves, so a bracket, hairpin group or glissando
    // group may stand here.
    *prCheckedReshapeChildren { |children, records, label|
        // A record's `written` is already an occupied space, with a leaf
        // multiplier of 1. A replacement child must answer the same question
        // through its own multiplier.
        var wanted = this.prTotalOf(records.collect { |record| record[\written] });
        var list, found;

        this.prCheckedSpellable(records, children, label);
        list = this.prRunChildren(children, label);

        if (list.isEmpty) {
            Error(
                "ScoreEdit.%: the replacement is empty. Taking a run out leaves a short bar. Replace it with rests instead."
                .format(label)
            ).throw
        };
        list.do { |child, index| this.prCheckedElement(child, index, label) };
        found = this.prTotalOf(list.collect { |child| this.prOccupies(child) });
        if (found != wanted) {
            Error(
                "ScoreEdit.%: the replacement occupies % where the run occupies %. A reshaped run fills exactly the space it was given."
                .format(label, found, wanted)
            ).throw
        };
        ^list
    }

    // The same, admitting containers, hairpin groups and glissando
    // groups, so `"3:2[c8 d8 e8]"`, `"crescendo[g4 a4]"` and
    // `"gliss[g4 a4]"` parse.
    *prRunChildren { |children, label|
        if (children.isKindOf(String)) {
            ^ScoreNotation.prNotationChildren(children,
                "ScoreEdit.%".format(label)).asArray
        };
        ^this.prRunArray(children, "an Array of ScoreElement", label)
    }

    *prRunArray { |given, wanted, label|
        if (given.isSequenceableCollection.not) {
            Error(
                "ScoreEdit.%: the replacement must be % or a written run such as \"e4 f4\", got %."
                .format(label, wanted, given.asCompileString)
            ).throw
        };
        ^given.asArray
    }

    // What a child takes up in its parent. Tuplets occupy prolated duration.
    //
    // >>> ScoreEdit.prOccupies(ScoreNotation.leafRun("3:2[c8 d8 e8]").first)
    // Duration(1/4)
    //
    // ^ Duration
    *prOccupies { |child| ^child.duration * child.multiplier }

    // ^ Duration
    *prTotalOf { |durations|
        ^durations.inject(Duration(0, 1), { |sum, each| sum + each })
    }

    // Replace one child slice. Every survivor is copied.
    *prResliced { |element, path, slice, leaves|
        var parent = slice[0], from = slice[1], to = slice[2];
        var kept;

        if (path == parent) {
            kept = element.children.asArray
                .collect { |child| ScorePrepare.copyOf(child) };
            ^ScorePrepare.rebuilt(element,
                kept.copyRange(0, from - 1)
                    ++ leaves.collect { |leaf| ScorePrepare.copyOf(leaf) }
                    ++ kept.copyRange(to + 1, kept.size - 1))
        };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, at|
                this.prResliced(child, path ++ [at], slice, leaves) })
    }

    // Check all replacements first, rebuild once, validate once.
    *prReplaced { |element, pairs, label|
        var selection = ScoreSelection(element);
        var wanted = Dictionary.new;

        pairs.do { |pair|
            var path = pair[0];
            var steps = this.prCheckedPath(selection, path, label);
            this.prCheckedLeaf(pair[1], steps, label);
            selection.recordAtPath(steps) ??
                { this.prRefuseAddress(selection, steps, label) };
            if (wanted.includesKey(steps)) {
                this.prRefuseDuplicate(steps, label)
            };
            wanted[steps] = pair[1];
        };
        ^Validator.validate(
            selection.where { |record| wanted.includesKey(record[\path]) }
                .mapLeaves { |leaf, record| wanted[record[\path]] })
    }

    // a non-empty list of `[path, leaf]`
    *prCheckedPairs { |replacements, label|
        var list;

        if (replacements.isSequenceableCollection.not
            or: { replacements.isKindOf(String) }) {

            Error(
                "ScoreEdit.%: replacements must be an Array of [path, leaf] pairs, got %."
                .format(label, replacements.asCompileString)
            ).throw
        };
        list = replacements.asArray;
        if (list.isEmpty) {
            Error("ScoreEdit.%: replacements must not be empty.".format(label)).throw
        };
        list.do { |entry, index|
            if (entry.isSequenceableCollection.not
                or: { entry.isKindOf(String) } or: { entry.asArray.size != 2 }) {

                Error(
                    "ScoreEdit.%: replacement % must be [path, leaf], got %."
                    .format(label, index, entry.asCompileString)
                ).throw
            }
        };
        ^list.collect { |entry| entry.asArray }
    }

    // Check path syntax before trying to read it.
    *prCheckedPath { |selection, path, label|
        var steps = if (path.isNumber) { [path] } { path };
        var failed = false;

        { selection.recordAtPath(path) }.try { |error| failed = true };
        if (failed) {
            Error(
                "ScoreEdit.%: % is not a path. Use an Array of non-negative child indexes."
                .format(label, path.asCompileString)
            ).throw
        };
        ^steps
    }

    // A container path and a stale path are different mistakes.
    //
    // ^ Never
    *prRefuseAddress { |selection, steps, label|
        var found = this.prResolved(selection, steps, label);

        Error(
            "ScoreEdit.%: % names a %, not a leaf. Use replaceElementAt for containers."
            .format(label, steps.asCompileString, found.class.name)
        ).throw
    }

    // Answer what the path names, or refuse if it names nothing.
    //
    // ^ ScoreElement
    *prResolved { |selection, steps, label|
        var found = nil;
        var failed = false;

        { found = selection.elementAtPath(steps) }.try { |error| failed = true };
        if (failed) {
            Error(
                "ScoreEdit.%: no element at %. Path is outside or stale."
                .format(label, steps.asCompileString)
            ).throw
        };
        ^found
    }

    // The tree with one node replaced, everything else copied.
    *prRebuiltWith { |element, path, target, replacement|
        if (path == target) { ^replacement };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, index|
                this.prRebuiltWith(child, path ++ [index], target, replacement) })
    }

    // The tree with one node gone, every survivor copied.
    //
    // nil marks the node that goes and is dropped one level up.
    *prRemovedWith { |element, path, target|
        if (path == target) { ^nil };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children
                .collect { |child, index|
                    this.prRemovedWith(child, path ++ [index], target) }
                .reject { |each| each.isNil })
    }

    // The tree with one node added, every survivor copied.
    *prInsertedWith { |element, path, parent, index, insertion|
        var children;

        if (path == parent) {
            children = element.children.asArray
                .collect { |child| ScorePrepare.copyOf(child) };
            ^ScorePrepare.rebuilt(element,
                children.copyRange(0, index - 1) ++ [insertion]
                    ++ children.copyRange(index, children.size - 1))
        };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, at|
                this.prInsertedWith(child, path ++ [at], parent, index,
                    insertion) })
    }

    // The tree with one node moved into another container, in one walk.
    *prMovedWith { |element, path, from, parent, index, moved|
        var children;

        if (path == from) { ^nil };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        children = element.children.asArray
            .collect { |child, at|
                this.prMovedWith(child, path ++ [at], from, parent, index,
                    moved) }
            .reject { |each| each.isNil };
        if (path == parent) {
            children = children.copyRange(0, index - 1) ++ [moved]
                ++ children.copyRange(index, children.size - 1)
        };
        ^ScorePrepare.rebuilt(element, children)
    }

    // The tree with one container's children taken in a new order.
    *prReorderedWith { |element, path, parent, order|
        var children;

        if (path == parent) {
            children = element.children.asArray;
            ^ScorePrepare.rebuilt(element,
                order.collect { |from| ScorePrepare.copyOf(children[from]) })
        };
        if (element.isLeaf) { ^ScorePrepare.copyOf(element) };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, at|
                this.prReorderedWith(child, path ++ [at], parent, order) })
    }

    // Checked here so the refusal names the call the caller made.
    *prCheckedLeaf { |leaf, steps, label|
        if (leaf.isKindOf(ScoreLeaf).not) {
            Error(
                "ScoreEdit.%: replacement for % must be a ScoreLeaf, got %."
                .format(
                    label,
                    steps.asCompileString,
                    if (leaf.isNil) { "nil" } { leaf.class.name }
                )
            ).throw
        };
        ^leaf
    }

    // Wider than `prCheckedLeaf`: containers are `ScoreElement`s too.
    *prCheckedElement { |element, steps, label|
        if (element.isKindOf(ScoreElement).not) {
            Error(
                "ScoreEdit.%: element for % must be a ScoreElement, got %."
                .format(
                    label,
                    steps.asCompileString,
                    if (element.isNil) { "nil" } { element.class.name }
                )
            ).throw
        };
        ^element
    }

    // The empty path names the tree itself.
    //
    // ^ Never
    *prRefuseRoot { |label|
        Error(
            "ScoreEdit.%: [] names the whole tree. Pass a child path."
            .format(label)
        ).throw
    }

    // A slot path split into `[parentPath, index]`.
    //
    // ^ ([Integer], Integer)
    *prCheckedSlot { |root, path, label|
        var selection = ScoreSelection(root);
        var steps = this.prCheckedPath(selection, path, label);
        var parentPath, index, holder;

        if (steps.isEmpty) { this.prRefuseRoot(label) };
        parentPath = steps.copyRange(0, steps.size - 2);
        index = steps.last;
        holder = if (parentPath.isEmpty) { root } {
            this.prResolved(selection, parentPath, label) };
        if (holder.isLeaf) { this.prRefuseLeafParent(parentPath, label) };
        if (index > holder.children.size) {
            this.prRefuseSlot(steps, index, holder.children.size, label)
        };
        ^[parentPath, index]
    }

    // Every child index exactly once.
    *prCheckedPermutation { |order, count, label|
        var list, seen;

        if (order.isSequenceableCollection.not or: { order.isKindOf(String) }) {
            Error(
                "ScoreEdit.%: order must be an Array of child indexes, got %."
                .format(label, order.asCompileString)
            ).throw
        };
        list = order.asArray;
        list.do { |each|
            if (each.isKindOf(Integer).not) {
                Error(
                    "ScoreEdit.%: order entry % is not an Integer index."
                    .format(label, each.asCompileString)
                ).throw
            };
            if (each < 0 or: { each >= count }) {
                if (count == 0) {
                    Error(
                        "ScoreEdit.%: order index % out of range. Container has no children."
                        .format(label, each)
                    ).throw
                };
                Error(
                    "ScoreEdit.%: order index % out of range for % children. Use 0 to %."
                    .format(label, each, count, count - 1)
                ).throw
            }
        };
        if (list.size != count) {
            Error(
                "ScoreEdit.%: order has % indexes for % children. Name each child once."
                .format(label, list.size, count)
            ).throw
        };
        seen = list.as(Set);
        if (seen.size != list.size) {
            Error(
                "ScoreEdit.%: order names a child twice. Name each child once."
                .format(label)
            ).throw
        };
        ^list
    }

    // Removal policies beyond plain validation.
    *prCheckedRemoval { |root, steps, found, label|
        var parent = steps.copyRange(0, steps.size - 2);
        var holder = if (parent.isEmpty) { root } {
            ScoreSelection(root).elementAtPath(parent) };
        var siblings = holder.children.size;

        if (siblings > 1) { ^found };
        if (holder.isKindOf(MusicScore)) { this.prRefuseLastStaff(steps, label) };
        if (holder.isKindOf(Staff)) { this.prRefuseLastMeasure(steps, label) };
        ^found
    }

    // Move-specific address refusals before rebuilding.
    *prCheckedMove { |root, from, parent, index, label|
        var depth = from.size - 1;
        var holder = from.copyRange(0, depth - 1);

        if (parent == holder) {
            Error(
                "ScoreEdit.%: % and slot % are in one container. Use reorderChildrenAt, which names a whole permutation and has no destination to misread."
                .format(
                    label,
                    from.asCompileString,
                    (parent ++ [index]).asCompileString
                )
            ).throw
        };
        if (parent.size > depth and: {
            parent.copyRange(0, depth - 1) == holder
        }) {
            if (parent[depth] == from[depth]) {
                Error(
                    "ScoreEdit.%: slot % is inside %, which is the element being moved."
                    .format(
                        label,
                        (parent ++ [index]).asCompileString,
                        from.asCompileString
                    )
                ).throw
            };
            if (parent[depth] > from[depth]) {
                Error(
                    "ScoreEdit.%: slot % is reached through a later sibling of %, so removing % would change what the slot names."
                    .format(
                        label,
                        (parent ++ [index]).asCompileString,
                        from.asCompileString,
                        from.asCompileString
                    )
                ).throw
            }
        };
        ^this.prCheckedMoveSource(root, from, label)
    }

    // Moving may empty the source container, as removal does.
    *prCheckedMoveSource { |root, from, label|
        var parent = from.copyRange(0, from.size - 2);
        var holder = if (parent.isEmpty) { root } {
            ScoreSelection(root).elementAtPath(parent) };

        if (holder.children.size > 1) { ^root };
        if (holder.isKindOf(Staff)) {
            Error(
                "ScoreEdit.%: % is the only measure in its staff. Moving it would leave the staff empty."
                .format(
                    label,
                    from.asCompileString
                )
            ).throw
        };
        ^root
    }

    // Empty scores and staves validate, but removal refuses to create
    // them.
    //
    // ^ Never
    *prRefuseLastStaff { |steps, label|
        Error(
            "ScoreEdit.%: % is the only staff. Replace it or build a different score."
            .format(label, steps.asCompileString)
        ).throw
    }

    // ^ Never
    *prRefuseLastMeasure { |steps, label|
        Error(
            "ScoreEdit.%: % is the only measure in its staff. Replace it or remove the staff."
            .format(label, steps.asCompileString)
        ).throw
    }

    // Shared by insert and reorder.
    //
    // ^ Never
    *prRefuseLeafParent { |parentPath, label|
        Error(
            "ScoreEdit.%: % names a leaf. Address its container."
            .format(label, parentPath.asCompileString)
        ).throw
    }

    // `children.size` is the append and the last legal slot.
    //
    // ^ Never
    *prRefuseSlot { |steps, index, size, label|
        Error(
            "ScoreEdit.%: slot % out of range at %. Valid slots: 0 to % (% appends)."
            .format(
                label,
                steps.asCompileString,
                index,
                size,
                size,
                size
            )
        ).throw
    }

    // The pair list is a set of edits, not a sequence.
    //
    // ^ Never
    *prRefuseDuplicate { |steps, label|
        Error(
            "ScoreEdit.%: % is listed twice. One replacement per leaf."
            .format(label, steps.asCompileString)
        ).throw
    }
}
