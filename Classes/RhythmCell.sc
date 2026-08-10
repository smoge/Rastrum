// RhythmCell: a proportion list, checked once and manipulated as a value.
//
// The list itself is `RhythmTree`'s existing shape and nothing about it
// changes: a number is a share, negative means silence, and `[weight,
// subdivisions]` is a share divided further. What this adds is a place to check
// that shape before it reaches the lowering, and a place for the operations
// that were otherwise written out by hand at every call site.
//
// That surgery is the reason this exists. Every operation here has to know an
// entry is either a number or a pair, and getting it wrong is silent: a
// rotation that skips nested entries still returns a list, a scaling that
// misses a nested head changes the rhythm it claims to leave alone. Both were
// written wrong by hand in the examples before this class.
//
// Input only: a cell is not part of the score model. It holds no durations,
// enters no tree, and answers to no writer. `RhythmTree` reads its proportions
// and builds the neutral tree from those, exactly as it does from a bare array.
RhythmCell {
    var proportions;

    *new { |proportions| ^super.newCopyArgs(this.checkedProportions(proportions)) }

    // A copy, and a deep one: the nested lists are as reachable as the outer.
    // Checked once is only true if it stays checked, and handing out the
    // backing array would put one assignment between a caller and a share of no
    // time. Methods below read the variable directly, so this costs at the
    // boundary rather than on every internal walk.
    proportions { ^proportions.deepCopy }

    // Returns the proportion list inside, or the value itself if it is already
    // one.
    //
    // The way `RhythmTree` takes either without asking which it was given, on
    // the pattern `Duration.asDuration` sets.
    //
    // >>> RhythmCell.asProportions([1, 2])                -> [ 1, 2 ]
    // >>> RhythmCell.asProportions(RhythmCell([1, 2]))    -> [ 1, 2 ]
    *asProportions { |value|
        ^if (value.isKindOf(RhythmCell)) { value.proportions } { value }
    }

    // Checked as a whole, recursively, so a malformed cell is refused where it
    // is written rather than several layers into the lowering, where the
    // message is about a divisor and the mistake was a typo.
    *checkedProportions { |list|
        var entries = this.asProportions(list);
        if (entries.isSequenceableCollection.not or: { entries.isKindOf(String) }) {
            Error("RhythmCell: % is not a proportion list. One is an array of "
                "shares: a whole number, negative for silence, or [weight, "
                "subdivisions] for a share divided further.".format(list)).throw
        };
        if (entries.isEmpty) {
            Error("RhythmCell: an empty proportion list divides nothing.").throw
        };
        ^entries.collect { |entry| this.checkedEntry(entry) }
    }

    *checkedEntry { |entry|
        if (entry.isNumber) { ^this.checkedWeight(entry) };
        if (entry.isSequenceableCollection.not or: { entry.isKindOf(String) }) {
            Error("RhythmCell: % is not a share. A share is a whole number, or "
                "[weight, subdivisions].".format(entry)).throw
        };
        if (entry.size != 2) {
            Error("RhythmCell: a subdivided share is [weight, subdivisions], two "
                "things; % has %.".format(entry, entry.size)).throw
        };
        ^[this.checkedWeight(entry[0]), this.checkedProportions(entry[1])]
    }

    // A weight is a count of shares, so it is whole. Its sign says whether the
    // share sounds, its size says how much of the span it takes.
    *checkedWeight { |value|
        if (value.isKindOf(Integer).not) {
            Error("RhythmCell: a weight of % is not a whole number of shares. "
                "Proportions are counted, not measured.".format(value)).throw
        };
        if (value == 0) {
            Error("RhythmCell: a weight of zero occupies no time. A share that "
                "sounds for nothing is not a share; leave it out.").throw
        };
        ^value
    }

    // Returns the shares moved round, none of them changed inside. Top level
    // only, because rotating a subdivision would be an operation on a different
    // cell. A positive `n` moves them to the right, so the last comes first.
    //
    // >>> RhythmCell([1, 2, 3]).rotated       -> RhythmCell([ 3, 1, 2 ])
    // >>> RhythmCell([1, 2, 3]).rotated(-1)   -> RhythmCell([ 2, 3, 1 ])
    rotated { |n = 1| ^RhythmCell(proportions.rotate(n)) }

    // Returns every weight multiplied, nested heads and their contents alike.
    //
    // Notationally this changes nothing: `RhythmTree` reduces to lowest terms,
    // so a cell and the same cell doubled are one rhythm. What it is for is
    // bringing two cells to a common measure before they are joined, where the
    // relative sizes between them do matter.
    //
    // >>> RhythmCell([1, [2, [1, 1]]]).scaledBy(2)
    // RhythmCell([ 2, [ 4, [ 2, 2 ] ] ])
    scaledBy { |factor|
        if (factor.isKindOf(Integer).not or: { factor < 1 }) {
            Error("RhythmCell: a scale factor of % is not a whole number of "
                "times.".format(factor)).throw
        };
        ^RhythmCell(RhythmCell.prScaled(proportions, factor))
    }

    *prScaled { |list, factor|
        ^list.collect { |entry|
            if (entry.isNumber) {
                entry * factor
            } {
                [entry[0] * factor, this.prScaled(entry[1], factor)]
            }
        }
    }

    // Returns the shares at these positions silenced.
    //
    // Top level, and a subdivided share becomes one rest of its own weight
    // rather than a subdivision full of rests: silence has no internal rhythm,
    // and the subdivision was a statement about how the share is articulated.
    //
    // >>> RhythmCell([1, 2, 3]).mutedAt([1])   -> RhythmCell([ 1, -2, 3 ])
    // >>> RhythmCell([2, [4, [1, 1]]]).mutedAt([1])   -> RhythmCell([ 2, -4 ])
    mutedAt { |indices|
        var wanted = indices.asArray.collect { |index| this.prCheckedIndex(index) };
        ^RhythmCell(proportions.collect { |entry, i|
            if (wanted.includes(i)) {
                if (entry.isNumber) { entry.abs.neg } { entry[0].abs.neg }
            } {
                entry
            }
        })
    }

    // Returns the cell backwards, subdivisions included.
    //
    // Recursive on purpose, and this is the definition worth stating: reversing
    // only the top level would leave every subdivided share playing forwards
    // inside a cell that plays backwards, which is not what a retrograde is.
    // The sounding rhythm read end to end is the reverse of the original.
    //
    // >>> RhythmCell([1, [2, [1, 3]]]).retrograde
    // RhythmCell([ [ 2, [ 3, 1 ] ], 1 ])
    retrograde { ^RhythmCell(RhythmCell.prReversed(proportions)) }

    *prReversed { |list|
        ^list.reverse.collect { |entry|
            if (entry.isNumber) { entry } { [entry[0], this.prReversed(entry[1])] }
        }
    }

    // Note [A path names one share]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `0` or `[0]` is the first top-level share. `[1, 2]` is the third share
    // inside top-level share 1's subdivision. Zero-based, whole numbers, and
    // loud about a path descending through a share that was never divided, or
    // naming one that is not there.

    // Returns the share at this path, copied.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).shareAt([1, 0])   -> 3
    shareAt { |path|
        var steps = this.prCheckedPath(path);
        var list = proportions;
        (steps.size - 1).do { |depth| list = list[steps[depth]][1] };
        ^list[steps.last].deepCopy
    }

    // Returns the subdivisions of a subdivided share, as a cell of their own.
    //
    // A plain share has none, and answering an empty cell would be a way of not
    // saying so. A cell of nothing divides nothing, which this class refuses
    // at construction anyway.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).cellAt([1])   -> RhythmCell([ 3, 4 ])
    cellAt { |path| ^RhythmCell(this.prSubdividedShareAt(path)[1]) }

    // Returns this cell with the subdivisions of one share replaced, that share
    // keeping its own weight and sign.
    //
    // `replaceAt` is the lower-level operation and replaces the whole share,
    // which means rebuilding `[weight, subdivisions]` by hand, and remembering
    // that shape is what this class exists to save. Changing what is inside a
    // share is the common case, and the weight is not part of it.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).replaceCellAt([1], [1, 1])
    // RhythmCell([ 1, [ 2, [ 1, 1 ] ] ])
    replaceCellAt { |path, cell|
        var share = this.prSubdividedShareAt(path);
        ^this.replaceAt(path, [share[0], RhythmCell(cell).proportions])
    }

    // Returns this cell with one share's subdivisions passed through a
    // function.
    //
    // The function is handed a cell and answers one, so every operation here
    // applies inside a share as readily as it does at the top.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).rewriteCellAt([1], { |c| c.retrograde })
    // RhythmCell([ 1, [ 2, [ 4, 3 ] ] ])
    rewriteCellAt { |path, func|
        ^this.replaceCellAt(path, func.value(this.cellAt(path)))
    }

    // The one place a share is required to be subdivided, so the two methods
    // above refuse a plain one in the same words.
    prSubdividedShareAt { |path|
        var share = this.shareAt(path);
        if (share.isNumber) {
            Error("RhythmCell: the share at % is not subdivided, so there is no "
                "cell inside it. It is a share of %.".format(path, share)).throw
        };
        ^share
    }

    // Returns this cell with one share replaced. The result is checked like any
    // other, so a replacement that is not a share is refused here rather than
    // later.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).replaceAt([1], 5)
    // RhythmCell([ 1, 5 ])
    replaceAt { |path, share|
        ^RhythmCell(RhythmCell.prReplaced(
            proportions, this.prCheckedPath(path), share))
    }

    *prReplaced { |list, steps, share|
        ^list.collect { |entry, i|
            if (i != steps.first) {
                entry
            } {
                if (steps.size == 1) {
                    share
                } {
                    [entry[0], this.prReplaced(entry[1], steps[1..], share)]
                }
            }
        }
    }

    // Returns this cell with the shares at these paths silenced, however deep.
    // A subdivided share becomes one rest of its own weight, as in `mutedAt`.
    //
    // One path is a list of positions. Several are a list of those. All whole
    // numbers means one path, all lists means several, and a mixture is refused
    // rather than guessed at, `[1, 2]` cannot be both.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).mutedAtPath([[1, 0]])
    // RhythmCell([ 1, [ 2, [ -3, 4 ] ] ])
    mutedAtPath { |paths|
        // Each path is checked against this cell before any of them is compared
        // with the others, so a path that names no share says so. Checking
        // overlap first would report [[9], [9, 0]] as an overlap, which is true
        // of two paths that both name nothing.
        var wanted = RhythmCell.prPathList(paths)
            .collect { |path| this.prCheckedPath(path) };
        var result = this;

        RhythmCell.prCheckNoOverlap(wanted);
        wanted.do { |path| result = result.prMutedAtOnePath(path) };
        ^result
    }

    // Silencing a share and something inside it is a contradiction whose
    // outcome would depend on which was applied first: collapsing the ancestor
    // removes the share the descendant names, so one order throws and the other
    // quietly drops half the request. Refused rather than ordered, because
    // neither answer is the one that was asked for.
    //
    // Once no path lies inside another, muting only ever replaces a share in
    // place, so the paths are independent and the order cannot matter.
    *prCheckNoOverlap { |paths|
        paths.do { |outer|
            paths.do { |inner|
                if (inner.size > outer.size and: {
                    inner.copyRange(0, outer.size - 1) == outer
                }) {
                    Error("RhythmCell: the path % names a share inside the one %  "
                        "names. Silencing the outer one removes the inner, so "
                        "asking for both asks for something that would not be "
                        "there.".format(inner, outer)).throw
                }
            }
        };
        ^paths
    }

    prMutedAtOnePath { |path|
        var share = this.shareAt(path);
        ^this.replaceAt(path,
            if (share.isNumber) { share.abs.neg } { share[0].abs.neg })
    }

    // Returns one path, as a list of positions. A list of paths is refused
    // rather than narrowed to its first: quietly doing part of a request is
    // worse than refusing it.
    *prOnePath { |path|
        var steps;
        if (path.isNumber) { ^[path] };
        if (path.isSequenceableCollection.not or: { path.isKindOf(String) }) {
            Error("RhythmCell: % is not a path. A path is a position or a list of "
                "them, naming one share from the top down.".format(path)).throw
        };
        steps = path.asArray;
        if (steps.isEmpty) {
            Error("RhythmCell: an empty path names no share.").throw
        };
        if (steps.every { |step| step.isNumber }) { ^steps };
        // Every step a list is a list of paths. Anything else is one bad step,
        // and naming that step is more use than calling the whole thing several
        // paths when it was not.
        if (steps.every { |step| step.isSequenceableCollection }) {
            Error("RhythmCell: % names more than one share. This asks for one; a "
                "list of paths belongs to mutedAtPath.".format(path)).throw
        };
        steps.do { |step|
            if (step.isNumber.not) {
                Error("RhythmCell: % is not a position. A path is whole numbers, "
                    "counting shares from zero.".format(step)).throw
            }
        };
    }

    *prPathList { |paths|
        var list;
        if (paths.isNumber) { ^[[paths]] };
        if (paths.isSequenceableCollection.not or: { paths.isKindOf(String) }) {
            Error("RhythmCell: % is not a path. A path is a position or a list of "
                "them, naming a share from the top down.".format(paths)).throw
        };
        list = paths.asArray;
        if (list.isEmpty) {
            Error("RhythmCell: an empty path names no share.").throw
        };
        if (list.every { |step| step.isNumber }) { ^[list] };
        if (list.every { |step| step.isSequenceableCollection }) {
            ^list.collect { |step| this.prOnePath(step) }
        };
        Error("RhythmCell: % is neither one path nor a list of paths. All whole "
            "numbers is one path; all lists is several; a mixture is neither."
            .format(paths)).throw
    }

    // Returns the path as a list of positions, checked against this cell's
    // shape.
    //
    // Every step is checked where it lands, so the message says which position
    // of which depth was wrong rather than that something somewhere was.
    prCheckedPath { |path|
        var steps = RhythmCell.prOnePath(path);
        var list = proportions;

        steps.do { |step, depth|
            var entry;
            if (step.isKindOf(Integer).not) {
                Error("RhythmCell: % is not a position. A path is whole numbers, "
                    "counting shares from zero.".format(step)).throw
            };
            if (step < 0 or: { step >= list.size }) {
                Error("RhythmCell: there is no share at position % of the path %; "
                    "there are % shares to choose from at that depth.".format(
                        step, steps, list.size)).throw
            };
            if (depth < (steps.size - 1)) {
                entry = list[step];
                if (entry.isNumber) {
                    Error("RhythmCell: the path % descends into the share at "
                        "position %, which is a plain share of % and has nothing "
                        "inside it.".format(steps, step, entry)).throw
                };
                list = entry[1];
            }
        };
        ^steps
    }

    // A position names a share, so it is a whole number and there is a share
    // there. Silently ignoring one that is not is the same class of mistake
    // this class exists to prevent. A mask that quietly does nothing still
    // returns a cell, and still renders.
    //
    // Naming a position twice is allowed: silence applied twice is silence.
    prCheckedIndex { |index|
        if (index.isKindOf(Integer).not) {
            Error("RhythmCell: % is not a position. Positions are whole numbers, "
                "counting the shares of this cell from zero.".format(index)).throw
        };
        if (index < 0 or: { index >= this.size }) {
            Error("RhythmCell: there is no share at position % - this cell has %, "
                "numbered 0 to %.".format(index, this.size, this.size - 1)).throw
        };
        ^index
    }

    size { ^proportions.size }

    == { |that| ^that.isKindOf(RhythmCell) and: { proportions == that.proportions } }
    hash { ^proportions.hash }

    printOn { |stream| stream << "RhythmCell(" << proportions << ")" }
}
