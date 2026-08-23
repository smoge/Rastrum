// RhythmCell: a proportion list, checked once and manipulated as a value.
//
// The list is `RhythmTree`'s shape: a whole number is a share,
// negative means silence, and `[weight, subdivisions]` divides a
// share. Input only: a cell is not score model. It holds no
// durations, enters no tree, and reaches no writer.
RhythmCell {
    var proportions;

    // A String is a parenthesized RTM cell.
    //
    // >>> RhythmCell([1, [2, [1, 1]]]).proportions   -> [ 1, [ 2, [ 1, 1 ] ] ]
    // >>> RhythmCell("(1 (2 (1 1)))").proportions    -> [ 1, [ 2, [ 1, 1 ] ] ]
    *new { |proportions|
        if (proportions.isKindOf(String)) { ^this.rtm(proportions) };
        ^super.newCopyArgs(this.checkedProportions(proportions))
    }

    // Note [A cell as an RTM string]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The parser answers the list this class already checks.
    //
    // The grammar is two lines and stays two:
    //
    //   cell  := "(" share+ ")"
    //   share := integer | "(" integer cell ")"
    //
    // A subdivided share is always `(weight (subshares))`; the second element is
    // a cell, not a bare share.
    //
    // Integers only. No floats, symbols, quotes, comments or commas.
    //
    // A String stands for a cell wherever a cell is expected, never for a share.
    //
    // >>> RhythmCell.rtm("(1 (1 (1 1 1)) -1 2)").proportions
    // [ 1, [ 1, [ 1, 1, 1 ] ], -1, 2 ]
    *rtm { |text| ^this.new(this.prRtmList(text, "RhythmCell.rtm")) }

    // The older name, kept as the same call.
    //
    // >>> RhythmCell.sexpr("(1 2)").proportions   -> [ 1, 2 ]
    *sexpr { |text| ^this.new(this.prRtmList(text, "RhythmCell.sexpr")) }

    *prRtmList { |text, label|
        var tokens, parsed;
        if (text.isKindOf(String).not) {
            Error("%: expected RTM String like \"(1 (1 1) 2)\", got %."
                .format(label, text.class)).throw
        };
        tokens = this.prRtmTokens(text);
        if (tokens.isEmpty) {
            Error("%: \"%\" names no cell. Use \"(1 (1 1) 2)\"."
                .format(label, text)).throw
        };
        parsed = this.prRtmCell(tokens, 0, text, label);
        if (parsed[1] != tokens.size) {
            Error("%: \"%\" has text after the closing paren."
                .format(label, text)).throw
        };
        ^parsed[0]
    }

    // Parens are tokens. Whitespace separates the rest.
    *prRtmTokens { |text|
        var out = [], current = "";
        var flush = { if (current.notEmpty) { out = out.add(current); current = "" } };
        text.do { |char|
            case
            { char == $( }   { flush.value; out = out.add("(") }
            { char == $) }   { flush.value; out = out.add(")") }
            { char.isSpace } { flush.value }
            { true }         { current = current ++ char };
        };
        flush.value;
        ^out
    }

    // Answers [list, index after the closing paren].
    *prRtmCell { |tokens, at, whole, label|
        var items = [], index = at, share;
        if (tokens[at] != "(") {
            Error("%: \"%\" needs a cell where % stands. Use parens around "
                "shares.".format(label, whole,
                    this.prRtmAt(tokens, at))).throw
        };
        index = index + 1;
        while { (index < tokens.size) and: { tokens[index] != ")" } } {
            share = this.prRtmShare(tokens, index, whole, label);
            items = items.add(share[0]);
            index = share[1];
        };
        if (index >= tokens.size) {
            Error("%: \"%\" leaves a cell unclosed.".format(label, whole)).throw
        };
        ^[items, index + 1]
    }

    // Answers [share, index after it]. A subdivided share is `(weight (cell))`.
    *prRtmShare { |tokens, at, whole, label|
        var weight, inner;
        if (tokens[at] != "(") {
            ^[this.prRtmWeight(tokens[at], whole, label), at + 1]
        };
        weight = this.prRtmWeight(tokens[at + 1], whole, label);
        inner = this.prRtmCell(tokens, at + 2, whole, label);
        if (tokens[inner[1]] != ")") {
            Error("%: \"%\" leaves a subdivided share unclosed. Use "
                "(weight (subshares)).".format(label, whole)).throw
        };
        ^[[weight, inner[0]], inner[1] + 1]
    }

    // Whole numbers. The sign says whether the share sounds.
    *prRtmWeight { |token, whole, label|
        var digits;
        if (token.isNil or: { token == "(" } or: { token == ")" }) {
            Error("%: \"%\" needs an integer share where % stands."
                .format(label, whole,
                    token ?? { "the end of the text" })).throw
        };
        digits = if (token.beginsWith("-")) { token.drop(1) } { token };
        if (digits.isEmpty or: {
            digits.every { |char| char.isDecDigit }.not
        }) {
            Error("%: \"%\" is not an integer share.".format(label, token)).throw
        };
        ^token.asInteger
    }

    *prRtmAt { |tokens, at| ^(tokens[at] ?? { "the end of the text" }) }

    // A deep copy, so callers can't mutate the checked value.
    //
    // >>> { var c = RhythmCell([1, [2, [3, 4]]]); var p = c.proportions;
    //     p[1][1][0] = 99; c.proportions }.value
    // [ 1, [ 2, [ 3, 4 ] ] ]
    proportions { ^proportions.deepCopy }

    // Answers the proportion list inside, or the value itself.
    //
    // >>> RhythmCell.asProportions([1, 2])                -> [ 1, 2 ]
    // >>> RhythmCell.asProportions(RhythmCell([1, 2]))    -> [ 1, 2 ]
    *asProportions { |value|
        if (value.isKindOf(RhythmCell)) { ^value.proportions };
        if (value.isKindOf(String)) { ^this.prRtmList(value, "RhythmCell.rtm") };
        ^value
    }

    // Checked recursively before lowering.
    *checkedProportions { |list|
        var entries = this.asProportions(list);
        if (entries.isSequenceableCollection.not or: { entries.isKindOf(String) }) {
            Error("RhythmCell: % is not a proportion list. Use shares or [weight, subdivisions].".format(list)).throw
        };
        if (entries.isEmpty) {
            Error("RhythmCell: an empty proportion list divides nothing.").throw
        };
        ^entries.collect { |entry| this.checkedEntry(entry) }
    }

    *checkedEntry { |entry|
        if (entry.isNumber) { ^this.checkedWeight(entry) };
        if (entry.isSequenceableCollection.not or: { entry.isKindOf(String) }) {
            Error("RhythmCell: % is not a share. Use an integer or "
                "[weight, subdivisions].".format(entry)).throw
        };
        if (entry.size != 2) {
            Error("RhythmCell: subdivided share % must be [weight, subdivisions].".format(entry)).throw
        };
        ^[this.checkedWeight(entry[0]), this.checkedProportions(entry[1])]
    }

    // A weight is whole. Its sign says whether the share sounds.
    *checkedWeight { |value|
        if (value.isKindOf(Integer).not) {
            Error("RhythmCell: weight % must be an integer.".format(value)).throw
        };
        if (value == 0) {
            Error("RhythmCell: weight 0 occupies no time. Leave it out.").throw
        };
        ^value
    }

    // Note [A cell answers the thing it is for]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Most operations answer another cell. These two lower into score objects.
    //
    // A cell holds no meter.
    //
    // Thin delegates; `RhythmTree` owns lowering.
    //
    // >>> RhythmCell([1, 1, 2]).measure("4/4", "c d e").leaves.size   -> 3
    measure { |meter, pitches| ^RhythmTree.measure(meter, this, pitches) }

    // `span` is a Meter for a whole bar, or a Duration for any other stretch.
    //
    // >>> RhythmCell([1, 1]).voice(Meter(2, 4), "c d", "upper").name   -> upper
    voice { |span, pitches, name| ^RhythmTree.voice(span, this, pitches, name) }

    // Rotates top-level shares. A positive `n` moves them right.
    //
    // >>> RhythmCell([1, 2, 3]).rotated       -> RhythmCell([ 3, 1, 2 ])
    // >>> RhythmCell([1, 2, 3]).rotated(-1)   -> RhythmCell([ 2, 3, 1 ])
    rotated { |n = 1| ^RhythmCell(proportions.rotate(n)) }

    // Answers every weight multiplied.
    //
    // `RhythmTree` reduces common factors. Scaling is for combining cells before
    // lowering.
    //
    // >>> RhythmCell([1, [2, [1, 1]]]).scaledBy(2)
    // RhythmCell([ 2, [ 4, [ 2, 2 ] ] ])
    scaledBy { |factor|
        if (factor.isKindOf(Integer).not or: { factor < 1 }) {
            Error("RhythmCell: scale factor must be a positive Integer, got %.".format(factor)).throw
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

    // Answers the shares at these positions silenced. Top level. A
    // subdivided share becomes one rest of its own weight.
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

    // Answers the cell backwards, subdivisions included. Recursive:
    // the sounding rhythm read end to end is reversed.
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
    // inside top-level share 1. Paths are zero-based whole numbers.

    // Answers the share at this path, copied.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).shareAt([1, 0])   -> 3
    shareAt { |path|
        var steps = this.prCheckedPath(path);
        var list = proportions;
        (steps.size - 1).do { |depth| list = list[steps[depth]][1] };
        ^list[steps.last].deepCopy
    }

    // Answers the subdivisions of a subdivided share, as a cell of their own.
    //
    // A plain share has no cell.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).cellAt([1])   -> RhythmCell([ 3, 4 ])
    cellAt { |path| ^RhythmCell(this.prSubdividedShareAt(path)[1]) }

    // Note [A path is where a cell was]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Every path `cellAt` accepts. Parents come before their contents. The root
    // is the receiver and has no path.
    //
    // Paths, not cells: a cell cannot say where it was.
    //
    // Negative heads are included; they are time, not rests.
    // See Note [A negative head does not silence what is under it].
    //
    // >>> RhythmCell("(1 (2 (1 1)) 1)").cellPaths   -> [ [ 1 ] ]
    cellPaths { ^RhythmCell.prCellPaths(proportions, []) }

    // The same walk with the cells beside their paths.
    //
    // One IdentityDictionary per row, matching `segmentsIn`.
    //
    // >>> RhythmCell("(1 (2 (1 1)) 1)").subcells.first[\cell]   -> RhythmCell([ 1, 1 ])
    subcells {
        ^this.cellPaths.collect { |path|
            IdentityDictionary[\path -> path, \cell -> this.cellAt(path)]
        }
    }

    // Fresh at every step, so a path answered here is the caller's to edit.
    *prCellPaths { |list, prefix|
        var out = [];
        list.do { |share, i|
            var here = prefix ++ [i];
            if (share.isNumber.not) {
                out = out.add(here) ++ this.prCellPaths(share[1], here)
            }
        };
        ^out
    }

    // Note [Subdividing is the inverse of reading one]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `cellAt` reads an inside. This gives a terminal share one,
    // keeping its weight. Existing subdivisions use `replaceCellAt`.
    // Negative terminal shares are rests; building negative heads is
    // explicit with `replaceAt`.
    //
    // >>> RhythmCell([1, 2]).subdivideAt(1, [1, 1])   -> RhythmCell([ 1, [ 2, [ 1, 1 ] ] ])
    subdivideAt { |path, cell|
        var share = this.shareAt(path);
        if (share.isNumber.not) {
            Error("RhythmCell: share at % is already subdivided. Use "
                "replaceCellAt."
                .format(path)).throw
        };
        if (share < 0) {
            Error("RhythmCell: the share at % is a rest. Use replaceAt to build "
                "a negative subdivided head explicitly."
                .format(path)).throw
        };
        ^this.replaceAt(path, [share, RhythmCell(cell).proportions])
    }

    // Replaces one share's subdivisions, keeping its weight and sign. `replaceAt` replaces the whole share. This names the common inside-only
    // edit.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).replaceCellAt([1], [1, 1])
    // RhythmCell([ 1, [ 2, [ 1, 1 ] ] ])
    replaceCellAt { |path, cell|
        var share = this.prSubdividedShareAt(path);
        ^this.replaceAt(path, [share[0], RhythmCell(cell).proportions])
    }

    // Rewrites one share's subdivisions.
    //
    // The function receives and answers a cell.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).rewriteCellAt([1], { |c| c.retrograde })
    // RhythmCell([ 1, [ 2, [ 4, 3 ] ] ])
    rewriteCellAt { |path, func|
        ^this.replaceCellAt(path, func.value(this.cellAt(path)))
    }

    // Shared subdivided-share check.
    prSubdividedShareAt { |path|
        var share = this.shareAt(path);
        if (share.isNumber) {
            Error("RhythmCell: the share at % is not subdivided. It is %."
                .format(path, share)).throw
        };
        ^share
    }

    // Replaces one share. The result is checked like any other cell.
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

    // Silences shares at paths. A subdivided share becomes one rest of its
    // weight, as in `mutedAt`.
    //
    // One path is a list of positions. Several paths are a list of those.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).mutedAtPath([[1, 0]])
    // RhythmCell([ 1, [ 2, [ -3, 4 ] ] ])
    mutedAtPath { |paths|
        // Check existence before overlap, so missing paths say they are missing.
        var wanted = RhythmCell.prPathList(paths)
            .collect { |path| this.prCheckedPath(path) };
        var result = this;

        RhythmCell.prCheckNoOverlap(wanted);
        wanted.do { |path| result = result.prMutedAtOnePath(path) };
        ^result
    }

    // A path may not sit inside another path. Silencing both would make the
    // result order-dependent.
    //
    // Without overlap, replacement order cannot matter.
    *prCheckNoOverlap { |paths|
        paths.do { |outer|
            paths.do { |inner|
                if (inner.size > outer.size and: {
                    inner.copyRange(0, outer.size - 1) == outer
                }) {
                    Error("RhythmCell: path % is inside path %. Silence one or "
                        "the other.".format(inner, outer)).throw
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

    // Answers one path as a list of positions.
    *prOnePath { |path|
        var steps;
        if (path.isNumber) { ^[path] };
        if (path.isSequenceableCollection.not or: { path.isKindOf(String) }) {
            Error("RhythmCell: % is not a path. Use an index or index Array.".format(path)).throw
        };
        steps = path.asArray;
        if (steps.isEmpty) {
            Error("RhythmCell: an empty path names no share.").throw
        };
        if (steps.every { |step| step.isNumber }) { ^steps };
        // A list of lists is multiple paths. Mixed content is one bad path.
        if (steps.every { |step| step.isSequenceableCollection }) {
            Error("RhythmCell: % names multiple paths. Use mutedAtPath.".format(path)).throw
        };
        steps.do { |step|
            if (step.isNumber.not) {
                Error("RhythmCell: path step % must be an Integer of zero or more.".format(step)).throw
            }
        };
    }

    *prPathList { |paths|
        var list;
        if (paths.isNumber) { ^[[paths]] };
        if (paths.isSequenceableCollection.not or: { paths.isKindOf(String) }) {
            Error("RhythmCell: % is not a path. Use an index or index Array.".format(paths)).throw
        };
        list = paths.asArray;
        if (list.isEmpty) {
            Error("RhythmCell: an empty path names no share.").throw
        };
        if (list.every { |step| step.isNumber }) { ^[list] };
        if (list.every { |step| step.isSequenceableCollection }) {
            ^list.collect { |step| this.prOnePath(step) }
        };
        Error("RhythmCell: % mixes path steps and paths. Use one path or a list of paths.".format(paths)).throw
    }

    // Answers the path as positions, checked against this cell's shape.
    prCheckedPath { |path|
        var steps = RhythmCell.prOnePath(path);
        var list = proportions;

        steps.do { |step, depth|
            var entry;
            if (step.isKindOf(Integer).not) {
                Error("RhythmCell: path step % must be an Integer of zero or more.".format(step)).throw
            };
            if (step < 0 or: { step >= list.size }) {
                Error("RhythmCell: path % has no share at position %. This level "
                    "has % shares.".format(steps, step, list.size)).throw
            };
            if (depth < (steps.size - 1)) {
                entry = list[step];
                if (entry.isNumber) {
                    Error("RhythmCell: the path % descends into the share at "
                        "position %, which is the plain share % and has nothing "
                        "inside it.".format(steps, step, entry)).throw
                };
                list = entry[1];
            }
        };
        ^steps
    }

    // A position names an existing share.
    //
    // Naming a position twice is allowed: silence applied twice is silence.
    prCheckedIndex { |index|
        if (index.isKindOf(Integer).not) {
            Error("RhythmCell: position must be a non-negative Integer, got %.".format(index)).throw
        };
        if (index < 0 or: { index >= this.size }) {
            Error("RhythmCell: no share at position %. This cell has % shares.".format(index, this.size)).throw
        };
        ^index
    }

    // Analysis for choosing material before lowering.
    //
    // Proportional time, not written time. Notation choices live in
    // `RhythmTree.fill`, so nothing here reduces weights or knows tuplets.
    //
    // One row per terminal share: `path`, `offset`, `duration`, `weight`,
    // `sounds`.
    //
    // Note [A negative head does not silence what is under it]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Only terminal shares can be rests. A negative subdivided head is time
    // holding sounding shares, matching `RhythmTree.fill`.
    //
    // >>> RhythmCell([1, 1]).segmentsIn(Meter(4, 4)).collect { |row| row[\duration] }
    // [ Duration(1/2), Duration(1/2) ]
    segmentsIn { |span|
        var found = List.new;
        RhythmCell.prSegments(proportions,
            RhythmTree.spanOf(span ?? { Duration(1, 1) }),
            [], Duration(0, 1), found);
        ^found.asArray
    }

    *prSegments { |list, span, prefix, start, found|
        var weights = list.collect { |entry| RhythmCell.prWeightOf(entry) };
        var sum = weights.sum;
        var running = start;
        list.do { |entry, index|
            var here = span * Duration(weights[index], sum);
            var path = prefix ++ [index];
            if (entry.isNumber) {
                var row = IdentityDictionary.new;
                row[\path] = path;
                row[\offset] = running;
                row[\duration] = here;
                row[\weight] = weights[index];
                row[\sounds] = entry >= 0;
                found.add(row)
            } {
                this.prSegments(entry[1], here, path, running, found)
            };
            running = running + here
        }
    }

    // `RhythmTree.weightOf`'s rule, local to this walk.
    *prWeightOf { |entry| ^if (entry.isNumber) { entry.abs } { entry[0].abs } }

    // Terminal shares, rests included. Span-free, a count being a count.
    //
    // >>> RhythmCell([1, [2, [3, 4]]]).shareCount   -> 3
    shareCount { ^RhythmCell.prCount(proportions, false) }

    // Terminal shares that sound.
    //
    // >>> RhythmCell([1, -1, [2, [3, -4]]]).attackCount   -> 2
    attackCount { ^RhythmCell.prCount(proportions, true) }

    *prCount { |list, soundingOnly|
        var total = 0;
        list.do { |entry|
            if (entry.isNumber) {
                if (soundingOnly.not or: { entry >= 0 }) { total = total + 1 }
            } {
                total = total + this.prCount(entry[1], soundingOnly)
            }
        };
        ^total
    }

    // Rhythmic levels. A flat cell has one.
    //
    // >>> [RhythmCell([1, 1]).depth, RhythmCell([1, [2, [3, 4]]]).depth]   -> [ 1, 2 ]
    depth { ^RhythmCell.prDepth(proportions) }

    *prDepth { |list|
        var deepest = 0;
        list.do { |entry|
            if (entry.isNumber.not) {
                deepest = max(deepest, this.prDepth(entry[1]))
            }
        };
        ^1 + deepest
    }

    // This cell with every list of siblings divided by their common
    // factor: the canonical form for common-factor scaling at each
    // level, and nothing wider.
    //
    // `==` is structural, so `[1, 1]` and `[2, 2]` are different
    // cells and the same rhythm. `RhythmTree.fill` reduces each level
    // before lowering, so this is that rule made askable rather than
    // a new one: a reduced cell writes the document its original
    // writes.
    //
    // Not a rhythm equivalence. `[1, [1, [1, 1]]]` and `[2, 1, 1]`
    // have the same segment timing and engrave the same page, and
    // neither reduces to the other: one nests where the other
    // doesn't, and `reduced` changes no structure. `sameTimingAs` and
    // `sameAttacksAs` are the questions about rhythm, by Note
    // [Comparing two cells].
    //
    // Per level and independently, as the lowering does it. A share's
    // weight means its part of *its own* siblings, so a factor common
    // to one list says nothing about the list inside it.
    //
    // >>> RhythmCell([2, [4, [2, 2]], -6]).reduced
    // RhythmCell([ 1, [ 2, [ 1, 1 ] ], -3 ])
    reduced { ^RhythmCell(RhythmCell.prReduced(proportions)) }

    // `Rational.gcd` and not sclang's `gcd:`, which answers -2 for
    // two negatives and would leave a bar of rests unreduced.
    *prReduced { |list|
        var factor = list.collect { |entry| RhythmCell.prWeightOf(entry) }
            .reduce { |a, b| Rational.gcd(a, b) } ? 1;
        ^list.collect { |entry| this.prReducedEntry(entry, max(factor, 1)) }
    }

    // The factor is a gcd of absolute weights, so it divides exactly
    // and the sign rides through untouched.
    *prReducedEntry { |entry, factor|
        if (entry.isNumber) { ^entry div: factor };
        ^[entry[0] div: factor, this.prReduced(entry[1])]
    }

    // Where the attacks fall in a span, which is the question a comparison
    // between two cells usually is.
    //
    // >>> RhythmCell([1, -1, 1, 1]).attackOffsetsIn(Meter(4, 4)).size   -> 3
    attackOffsetsIn { |span|
        ^this.segmentsIn(span).select { |row| row[\sounds] }
            .collect { |row| row[\offset] }
    }

    // Note [Comparing two cells]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The question `reduced` doesn't answer, asked directly. `==` is
    // structural and `reduced` only removes common factors, so both
    // call two spellings of one rhythm two cells. These read the
    // segments instead, which is what the lowering will hear.
    //
    // `other` is a cell or a bare proportion list, on the pattern
    // `RhythmTree` sets. `span` is what `segmentsIn` takes, a whole
    // note when omitted. It scales both sides alike, so it is there
    // to read alongside the rest of the analysis rather than to
    // decide anything.

    // Whether two cells fall the same way: every segment at the same
    // place, for the same length, sounding or silent alike.
    //
    // Structure isn't part of it, so a nesting compares against a
    // flat cell, and neither is scaling. Silence is, a rest being a
    // share of the span.
    //
    // >>> RhythmCell([1, [1, [1, 1]]]).sameTimingAs([2, 1, 1])   -> true
    // >>> RhythmCell([1, 1, 1, 1]).sameTimingAs([1, 1, -1, 1])   -> false
    sameTimingAs { |other, span|
        ^this.prTimingIn(span) == RhythmCell(other).prTimingIn(span)
    }

    // Whether two cells attack in the same places, and nothing about
    // what fills the time after each one.
    //
    // The weaker question, and often the one being asked: where a
    // share ends is a matter of how much silence follows it, so two
    // cells can agree on every attack and differ there.
    //
    // >>> RhythmCell([1, 1, 1, 1]).sameAttacksAs([1, 1, [1, [1, -1]], 1])   -> true
    sameAttacksAs { |other, span|
        ^this.attackOffsetsIn(span) == RhythmCell(other).attackOffsetsIn(span)
    }

    // A segment row reduced to what timing means: when, how long, and
    // whether it sounds. `path` and `weight` say how the cell was
    // written, which is the part these comparisons are for ignoring.
    prTimingIn { |span|
        ^this.segmentsIn(span).collect { |row|
            [row[\offset], row[\duration], row[\sounds]] }
    }

    // Note [Distinct keeps representatives]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Distinctness is order-dependent: the first cell for a rhythm
    // survives, and later equivalent cells are dropped. This is a
    // fold, not a filter, because each decision depends on the
    // representatives already kept.
    //
    // The answer contains representatives, not equivalence classes.
    // Raw proportion lists are checked on entry. RhythmCell instances
    // pass through unchanged so identity-based pool lookups still
    // work.

    // The first cell for each rhythm. `\timing` compares with
    // `sameTimingAs`. `\attacks` compares with `sameAttacksAs`. The
    // mode is required because the two questions keep different
    // pools.
    //
    // >>> RhythmCell.distinct([[1, 1], [2, 2], [1, -1]], \timing).size   -> 2
    // >>> RhythmCell.distinct([[1, 1], [1, [1, [1, -1]]], [1, -1]], \attacks).size
    // 2
    *distinct { |cells, mode, span|
        var selector = RhythmCell.prModeKeys[mode];
        var kept = List.new, keys = List.new;

        if (selector.isNil) {
            Error("RhythmCell.distinct: mode must be timing or attacks, got %."
                .format(mode)).throw
        };
        if (cells.isSequenceableCollection.not or: { cells.isKindOf(String) }) {
            Error("RhythmCell.distinct: expected an Array of cells or proportion "
                "lists, got %.".format(cells)).throw
        };
        cells.do { |cell|
            // Existing cells keep identity, so callers can find them in the
            // source pool with `includes`.
            var one = if (cell.isKindOf(RhythmCell)) { cell } { RhythmCell(cell) };
            var key = one.perform(selector, span);
            if (keys.any { |seen| seen == key }.not) {
                kept.add(one);
                keys.add(key)
            }
        };
        ^kept.asArray
    }

    *prModeKeys { ^(timing: \prTimingIn, attacks: \attackOffsetsIn) }

    // Note [Enumerating a field is not composing with it]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Every flat cell whose weights come from `weights`, whose share
    // count is one of `counts`, and whose weights sum to one of
    // `sums`.
    //
    // The three inputs are ordered sets. Duplicates are refused,
    // order is preserved, and nothing is reduced, filtered or
    // measured against a span. That is caller policy. Silence is
    // added later with `mutedAt`, since a negative share would make
    // the sum mean two things.
    //
    // >>> RhythmCell.allFrom([1, 2], 3, 4).collect { |cell| cell.proportions }
    // [ [ 1, 1, 2 ], [ 1, 2, 1 ], [ 2, 1, 1 ] ]
    // >>> RhythmCell.allFrom([1, 2], 2, [2, 4]).size   -> 2
    *allFrom { |weights, counts, sums|
        var pool = this.prCheckedWeights(weights);
        var wanted = this.prIntegerList(counts, "counts", 1);
        var totals = this.prIntegerList(sums, "sums", 1);
        var ceiling = totals.maxItem;
        var found = List.new;

        wanted.do { |count|
            this.prTuplesOf(pool, count, ceiling, totals, [], 0, found)
        };
        ^found.asArray
    }

    // Depth-first in the order the weights were given, so this
    // answers what filtering every tuple would answer without
    // building the ones that can't reach a sum: every place still to
    // fill takes at least the smallest weight.
    *prTuplesOf { |pool, count, ceiling, totals, sofar, running, found|
        if (sofar.size == count) {
            if (totals.any { |each| each == running }) {
                found.add(RhythmCell(sofar))
            };
            ^this
        };
        pool.do { |weight|
            var next = running + weight;
            var least = (count - sofar.size - 1) * pool.minItem;
            if ((next + least) <= ceiling) {
                this.prTuplesOf(pool, count, ceiling, totals, sofar ++ [weight],
                    next, found)
            }
        }
    }

    // Every distinct cell that fills this meter's count.
    //
    // One weight is one unit of the meter's denominator, so a 7/8 bar
    // is every cell coming to 7 and a 3/4 bar every cell coming to 3.
    // That is all the meter decides here: the grouping matters to
    // what a caller *keeps*, not to what is built. Other sums stay
    // with explicit `allFrom` and `distinct`. This names the common
    // case rather than replacing them.
    //
    // `mode` is required, as it is for `distinct`: `\timing` and
    // `\attacks` keep different pools, so a default would be a
    // decision made for the caller.
    //
    // >>> RhythmCell.fieldFor("3/4", [1, 2], (2..3), \timing).collect { |c| c.proportions }
    // [ [ 1, 2 ], [ 2, 1 ], [ 1, 1, 1 ] ]
    *fieldFor { |meter, weights, counts, mode|
        var span = Meter.asMeter(meter);
        if (span.isNil) {
            Error("RhythmCell.fieldFor: expected a Meter or time signature, got "
                "nil.").throw
        };
        ^this.distinct(this.allFrom(weights, counts, span.count), mode, span)
    }

    *prCheckedWeights { |weights|
        var pool = this.prIntegerList(weights, "weights");
        pool.do { |each|
            if (each < 1) {
                Error("RhythmCell.allFrom: weight must be positive, got %."
                    .format(each)).throw
            }
        };
        ^pool
    }

    // A number stands for a list of one, which is how counts and sums
    // are usually written. `least` is the floor where there is one.
    // Weights have their own, and say why in their own words.
    //
    // All three arguments are option sets: each names what a cell may
    // be, so a repeat asks for nothing new. In two of the three it
    // would also be heard, because a repeated weight or count builds
    // every cell again, and refusing all three is one contract rather
    // than a rule with an exception.
    *prIntegerList { |value, what, least|
        var list;
        if (value.isKindOf(String)) {
            Error("RhythmCell.allFrom: % must be whole numbers, got String %."
                .format(what, value.asCompileString)).throw
        };
        list = if (value.isNumber) { [value] } { value.asArray };
        if (list.isEmpty) {
            Error("RhythmCell.allFrom: % cannot be empty.".format(what)).throw
        };
        list.do { |each, index|
            if (each.isKindOf(Integer).not) {
                Error("RhythmCell.allFrom: % must contain Integers, got %."
                    .format(what, each.asCompileString)).throw
            };
            if (least.notNil and: { each < least }) {
                Error("RhythmCell.allFrom: % must be % or more, got %."
                    .format(what, least, each.asCompileString)).throw
            };
            if (list.indexOf(each) != index) {
                Error("RhythmCell.allFrom: % contain duplicate value %."
                    .format(what, each)).throw
            }
        };
        ^list
    }

    // >>> RhythmCell([1, [2, [3, 4]]]).size   -> 2
    size { ^proportions.size }

    // Structural identity. Timing questions use `sameTimingAs` or
    // `sameAttacksAs`.
    //
    // >>> RhythmCell([1, 1]) == RhythmCell([2, 2])           -> false
    // >>> RhythmCell([1, 1]).hash == RhythmCell([1, 1]).hash -> true
    == { |that| ^that.isKindOf(RhythmCell) and: { proportions == that.proportions } }
    hash { ^proportions.hash }

    printOn { |stream| stream << "RhythmCell(" << proportions << ")" }
}
