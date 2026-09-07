// Note [Choosing a divisor]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A weight `w_i` over a span is written as `w_i / D` of that span,
// with multiplier `D / sum(w)`. `chooseSpelling` tests candidate
// divisors and ranks fewer ties, plainer spelling, fewer dots and
// gentler multipliers.
//
// `allowTies` widens "one note head" to "ScorePrepare can split it."
// Reduced weights keep scaled lists stable.


// RhythmTree: proportional rhythm trees (RTM), lowered into score leaves.
//
// An element of a proportion list is either
//   a number        : a leaf, negative means rest
//   [weight, list]  : a subdivided leaf
//
// RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2])
//
// Written durations and brackets follow the divisor policy.
// See Note [One call, one row] for how pitch material is scoped.
//
// Generated leaves may need tie splitting. `ScorePrepare` handles that later.
RhythmTree {

    // Note [One call, one row]
    // ~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Direct material is local to the call that received it.
    // `measures` builds one stream for the run. A `PitchStream` passed
    // directly is shared state.

    // Subdivided shares become nested groups. Negative shares become rests.
    //
    // >>> RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2]).leaves.size
    // 5
    // >>> RhythmTree.measure(Meter(4, 4), [1, -1, 1, 1]).leaves[1].class
    // MusicRest
    *measure { |meter, proportions, pitches|
        var exact = Meter.asMeter(meter);
        var bar = Measure(exact);
        this.fill(bar, exact.duration, proportions,
            this.pitchStream(pitches, "RhythmTree.measure"));
        ^bar
    }

    // Answers a Voice filled the same way.
    //
    // Built directly, not by lifting children from a measure.
    // See Note [Adding a child repoints its parent] in ScoreElement.sc.
    //
    // `span` is a Meter for a full bar or a Duration for any other stretch.
    //
    // >>> RhythmTree.voice(Meter(4, 4), [1, 1]).leaves.size   -> 2
    *voice { |span, proportions, pitches, name|
        var voice = Voice([], name);
        this.fill(voice, this.spanOf(span), proportions,
            this.pitchStream(pitches, "RhythmTree.voice"));
        ^voice
    }

    // A run of bars. One meter for all cells or one meter per cell.
    // Cells and meters are checked before the pitch stream is touched.
    //
    // >>> RhythmTree.measures("3/4", [[1, 1, 1], [1, 1, 1]], "c d e f g").size
    // 2
    // >>> RhythmTree.measures("3/4", [[1, 1, 1], [1, 1, 1]], "c d e f g").last.leaves.collect { |leaf| leaf.pitch.letter }
    // [ f, g, c ]
    // >>> RhythmTree.measures(["3/4", "2/4"], ["(1 1 1)", "(1 1)"], "c").collect { |bar| bar.meter }
    // [ Meter(3/4), Meter(2/4) ]
    *measures { |meters, cells, pitches|
        var run = this.prCheckedCells(cells);
        var bars = this.prCheckedMeters(meters, run.size);
        var stream = this.pitchStream(pitches, "RhythmTree.measures");
        ^run.collect { |cell, i|
            this.prFill(Measure(bars[i]), bars[i].duration, cell,
                this.prCyclingLeaves(stream))
        }
    }

    // A run is a collection of cells. If the argument is one cell, say so here.
    // Answers checked cells.
    *prCheckedCells { |cells|
        if (cells.isKindOf(String) or: { cells.isKindOf(RhythmCell) }
            or: { RhythmCell.isShareList(cells) }) {
            Error(
                "RhythmTree.measures: % is one cell, not a run of cells. Did you mean "
                "[%]?"
                .format(cells.asCompileString, cells.asCompileString)
            ).throw
        };
        if (cells.isSequenceableCollection.not) {
            Error(
                "RhythmTree.measures: % is not a run of cells. Use a collection of "
                "proportion lists, RTM Strings or RhythmCells."
                .format(cells.asCompileString)
            ).throw
        };
        if (cells.isEmpty) {
            Error("RhythmTree.measures: empty run; no bars to build.").throw
        };
        ^cells.asArray.collect { |cell| RhythmCell.checkedProportions(cell) }
    }

    // A String is one meter. A collection must match the cell count.
    *prCheckedMeters { |meters, count|
        var listed;
        if (meters.isSequenceableCollection.not or: { meters.isKindOf(String) }) {
            ^Array.fill(count, Meter.asMeter(meters))
        };
        listed = meters.asArray;
        if (listed.size != count) {
            Error(
                "RhythmTree.measures: % meter(s) for % cell(s). Give one meter, or one "
                "per cell."
                .format(listed.size, count)
            ).throw
        };
        ^listed.collect { |each| Meter.asMeter(each) }
    }

    // A Meter says how long a bar is. A Duration says how long anything is.
    //
    // >>> RhythmTree.spanOf(Meter(3, 4))      -> Duration(3/4)
    // >>> RhythmTree.spanOf(Duration(1, 2))   -> Duration(1/2)
    // >>> RhythmTree.spanOf("3/8")            -> Duration(3/8)
    *spanOf { |span|
        if (span.isKindOf(Meter)) { ^span.duration };
        ^Duration.asDuration(span)
    }

    // Pitch material for generated attacks. Strings may carry marking suffixes.
    // See Note [A pitch list says no duration] in ScoreNotationLeaves.sc.
    //
    // A `PitchStream` is shared. Any other material builds a fresh cycle.
    // See Note [One call, one row].
    //
    // >>> RhythmTree.pitchStream("c e").next   -> MusicPitch("c[4]")
    // >>> RhythmTree.pitchStream(nil).next     -> 60
    // >>> RhythmTree.pitchStream(RhythmTree.pitchStream("c e")).next   -> MusicPitch("c[4]")
    *pitchStream { |pitches, label = "RhythmTree.pitchStream"|
        var marked, items;
        if (pitches.isKindOf(PitchStream)) { ^pitches };
        if (pitches.isKindOf(Stream)) {
            Error(
                "%: % is a foreign stream. Use RhythmTree.pitchStream(pitches)."
                .format(label, pitches.class)
            ).throw
        };
        if (pitches.isKindOf(String)) {
            marked = ScoreNotation.prMarkedPitches(pitches, label).collect {
                |each| if (each.value.isEmpty) { each.key } { each } };
            // A stream yields Associations whole, Pseq splits them.
            ^PitchStream.prCycling(marked, label)
        };
        items = (pitches ? [60]).asArray;
        ^PitchStream.prCycling(items, label)
    }

    // A bar whose sounded attacks are counted rather than cycled: one
    // row atom per attack, in order. An atom is a list of pitch specs,
    // so one spec is a note and more than one is a chord. A rest is
    // the cell's to say through a negative share and draws no atom.
    //
    // The counterpart to `measure`, which cycles.
    // See Note [Two material contracts, one lowering].
    //
    // `label` names the caller's position, so a matrix says which bar refused.
    //
    // >>> RhythmTree.measureRow("3/4", [1, 1, 1], [[60], [62], [64]]).leaves.collect { |leaf| leaf.pitch.letter }
    // [ c, d, e ]
    // >>> RhythmTree.measureRow("2/4", [1, 1], [["c#[5]"], [60, 64]]).leaves.collect { |leaf| leaf.class.name }
    // [ MusicNote, Chord ]
    // >>> RhythmTree.measureRow("3/4", [1, -1, 1], [[60], [62]]).leaves[1].class
    // MusicRest
    *measureRow { |meter, cell, row, label = "RhythmTree.measureRow"|
        var exact = Meter.asMeter(meter);
        var proportions = RhythmCell.checkedProportions(cell);
        var atoms = this.checkedRow(row, label);
        var wanted = RhythmCell(proportions).attackCount;
        var at = 0;

        if (atoms.size != wanted) {
            Error(
                "%: % attack(s) and % atom(s) in the row. A row says one atom per "
                "sounded attack."
                .format(label, wanted, atoms.size)
            ).throw
        };
        ^this.prFill(Measure(exact), exact.duration, proportions,
            { |written|
                var leaf = this.leafFrom(atoms[at], written);
                at = at + 1;
                leaf })
    }

    // A row checked into atoms of `MusicPitch`. An atom is a list of specs, so
    // `[[60], [64]]` is two notes and `[[60, 64]]` is one chord.
    //
    // The atom level claims one layer of nesting, so a list-shaped spec needs
    // its own: `[[\c, 4]]` is one atom of two specs, `[[[\c, 4]]]` one atom of
    // one spec.
    //
    // Markings are refused. A mark belongs to a whole atom. `measure` is
    // their route.
    //
    // >>> RhythmTree.checkedRow([[60], [60, 64]]).collect { |atom| atom.size }
    // [ 1, 2 ]
    // >>> RhythmTree.checkedRow([[\c, 4]]).first.collect { |each| each.spelling }
    // [ c[4], e[-1] ]
    // >>> RhythmTree.checkedRow([[[\c, 4]]]).first.collect { |each| each.spelling }
    // [ c[4] ]
    *checkedRow { |row, label = "RhythmTree.checkedRow"|
        if (row.isSequenceableCollection.not or: { row.isKindOf(String) }) {
            Error(
                "%: % is not a row. Use a list of atoms, each a list of pitches."
                .format(label, row.asCompileString)
            ).throw
        };
        ^row.collect { |atom, index|
            if (atom.isSequenceableCollection.not or: { atom.isKindOf(String) }) {
                Error(
                    "%: attack % holds %, not a list. Write [%] for one pitch."
                    .format(
                        label,
                        index,
                        atom.asCompileString,
                        atom.asCompileString
                    )
                ).throw
            };
            if (atom.isEmpty) {
                Error(
                    "%: attack % is empty. A rest is written in the rhythm, not in the "
                    "row."
                    .format(label, index)
                ).throw
            };
            atom.collect { |spec|
                if (spec.isKindOf(Association)) {
                    Error(
                        "%: attack % carries a marking. A row says pitch only; attach "
                        "marks with ScoreEdit.setMarking, or use RhythmTree.measure for "
                        "sketch material."
                        .format(label, index)
                    ).throw
                };
                MusicPitch.fromSpec(spec) }
        }
    }

    // One checked atom as a leaf: a note from one pitch, a chord from more.
    //
    // `noteFrom` answers a note and says so in its name. This is the one that
    // reads a whole atom.
    //
    // >>> RhythmTree.leafFrom([MusicPitch("c")], Duration(1, 4)).class      -> MusicNote
    // >>> RhythmTree.leafFrom(MusicPitch.asPitches("c e"), Duration(1, 4)).class
    // Chord
    *leafFrom { |pitches, written|
        if (pitches.isSequenceableCollection.not or: { pitches.isKindOf(String) }) {
            Error(
                "RhythmTree.leafFrom: expected a checked atom, got a %."
                .format(pitches.class)
            ).throw
        };
        if (pitches.isEmpty) {
            Error("RhythmTree.leafFrom: empty atom. Use MusicRest for silence.").throw
        };
        pitches.do { |pitch, index|
            if (pitch.isKindOf(MusicPitch).not) {
                Error(
                    "RhythmTree.leafFrom: atom item % is %, not MusicPitch. Use "
                    "checkedRow for row syntax."
                    .format(index, pitch.class)
                ).throw
            }
        };
        if (pitches.size == 1) { ^MusicNote(pitches.first, written) };
        ^Chord(pitches, written)
    }

    // one draw from the pitch stream as a note of the written length
    //
    // Markings are value objects, so cycling them is safe.
    //
    // >>> RhythmTree.noteFrom(60, Duration(1, 4)).dur          -> Duration(1/4)
    // >>> RhythmTree.noteFrom(MusicPitch("c") -> [Marking.dynamic(\mp)], Duration(1, 4)).markings.first.value   -> mp
    *noteFrom { |spec, written|
        if (spec.isKindOf(Association)) {
            var note = MusicNote(spec.key, written);
            spec.value.do { |mark| note.attach(mark) };
            ^note
        };
        ^MusicNote(spec, written)
    }

    // Note [A share is checked once, at the top]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `RhythmCell` checks on construction. A bare list gets the same
    // pass here, once, before the private recursion starts.
    //
    // Takes a cell and pitch material. Answers the filled container.
    // Direct material is local. A `PitchStream` is shared.
    // No material means middle C.
    //
    // >>> RhythmTree.fill(Measure("3/4"), Duration(3, 4), [1, 1, 1], "c d").leaves.collect { |leaf| leaf.pitch.letter }
    // [ c, d, c ]
    *fill { |container, span, argProportions, pitches|
        ^this.prFill(container, span,
            RhythmCell.checkedProportions(argProportions),
            this.prCyclingLeaves(this.pitchStream(pitches, "RhythmTree.fill")))
    }

    // One sounded attack from a cycling stream.
    // See Note [Two material contracts, one lowering].
    *prCyclingLeaves { |stream|
        ^{ |written| this.noteFrom(stream.next, written) }
    }

    // Note [Two material contracts, one lowering]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `prFill` takes a Function answering the leaf for one sounded attack of a
    // written length, not a pitch source, so the cycling path and the counted
    // row path share every tuplet and duration decision. `measure`, `voice`,
    // `fill` and `measures` wrap a `PitchStream`, which cycles by contract.
    // `measureRow` wraps a counted cursor over atoms, which refuses instead.

    *prFill { |container, span, proportions, leafFor|
        // Reduce first so common-factor scaling cannot change notation.
        var weights, sum, spelling, target;

        weights = this.reduceWeights(proportions.collect { |p| this.weightOf(p) });
        sum = weights.sum;

        // ScorePrepare will split ties later, so generated RTM may use them.
        spelling = this.chooseSpelling(span, weights, true);
        target = container;

        if (spelling[\actual] != spelling[\normal]) {
            // Print generated counts, not the reduced multiplier.
            target = Tuplet.ratio(spelling[\actual], spelling[\normal]);
            container.add(target);
        };

        proportions.do { |p, i|
            var written = span * Duration(
                weights[i] * spelling[\actual], sum * spelling[\normal]);
            if (p.isNumber) {
                target.add(
                    if (p < 0) { MusicRest(written) } { leafFor.value(written) }
                )
            } {
                // The subdivision decides its own spelling.
                this.prFill(target, written, p[1], leafFor)
            }
        };

        ^container
    }

    // Answers the weights in lowest terms.
    //
    // Proportions are relative. Common factors say nothing.
    //
    // >>> RhythmTree.reduceWeights([3, 3, 3, 3, 3, 3]) -> [ 1, 1, 1, 1, 1, 1 ]
    // >>> RhythmTree.reduceWeights([2, 4, 6])          -> [ 1, 2, 3 ]
    *reduceWeights { |weights|
        var common = weights.reduce { |a, b| a gcd: b } ? 1;
        ^if (common > 1) { weights.collect { |w| w div: common } } { weights }
    }

    // See Note [Choosing a divisor]. Weights must be in lowest terms.
    //
    // Meter-aware: three equal shares of 3/4 are quarters. In 4/4
    // they need a 3:2 bracket.
    //
    // >>> RhythmTree.chooseDivisor(Duration(3, 4), [1, 1, 1])   -> 3
    // >>> RhythmTree.chooseDivisor(Duration(1, 1), [1, 1, 1])   -> 2
    //
    // `allowTies` lets [5, 3] over a whole bar use eighths.
    //
    // >>> RhythmTree.chooseDivisor(Duration(1, 1), [5, 3], true)   -> 8
    *chooseDivisor { |span, weights, allowTies = false|
        ^this.chooseSpelling(span, weights, allowTies)[\normal]
    }

    // The generated notation decision. `actual` and `normal` are
    // printed counts. Equal counts mean no bracket.
    //
    // >>> RhythmTree.chooseSpelling(Duration(7, 8), [1, 2, 1], true)[\actual] -> 8
    // >>> RhythmTree.chooseSpelling(Duration(3, 16), [1, 1], true)[\actual]   -> 2
    // >>> RhythmTree.chooseSpelling(Duration(1, 1), [1, 1])[\normal]          -> 2
    *chooseSpelling { |span, weights, allowTies = false|
        var sum = weights.sum;
        var n = span.numerator;
        var candidates = Set[sum];
        var best, bestScore, k = 0;

        if (span <= Duration(0, 1)) {
            Error(
                "RhythmTree: spelling a rhythm needs a positive span, got %."
                .format(span)
            ).throw
        };

        while { (n * (2 ** k)) <= (8 * sum) } {
            candidates.add((n * (2 ** k)).asInteger);
            k = k + 1;
        };

        candidates.asArray.sort.do { |normal|
            var actual = this.prActualFor(sum, normal);
            var written = this.prWrittenDurations(span, weights, sum, actual, normal);
            var writable = written.every { |x|
                x.isNotatable or: { allowTies and: { x.isTieSplittable } }
            };
            if (writable) {
                var score = this.divisorScore(written, normal, actual);
                if (bestScore.isNil or: { this.prIsBetterScore(score, bestScore) }) {
                    best = (actual: actual, normal: normal);
                    bestScore = score;
                }
            }
        };

        if (best.isNil) {
            Error(
                "RhythmTree: % over span % cannot be written as note heads or ties. Use "
                "a tuplet or different proportions."
                .format(weights, span)
            ).throw
        };

        ^best
    }

    // Expansion tuplets double actual until the multiplier is not above one.
    // Contractions keep the count the proportions gave them.
    *prActualFor { |sum, normal|
        var actual = sum;
        while { actual < normal } { actual = actual * 2 };
        ^actual
    }

    *prWrittenDurations { |span, weights, sum, actual, normal|
        ^weights.collect { |w| span * Duration(w * actual, sum * normal) }
    }

    // Spelling score tuple: lower wins, left to right.
    // See Note [Choosing a divisor].
    //
    // >>> RhythmTree.divisorScore([Duration(1, 4), Duration(1, 4)], 2, 2)
    // [ 0, 0, 0, 0, Duration(1/1) ]
    *divisorScore { |written, normal, actual|
        // Count pieces after tie splitting. Unsplit tied durations have no dots.
        var runs = written.collect { |x| x.tieRuns };
        var multiplier = Duration(normal, actual);
        var one = Duration(1, 1);
        var ties = runs.sum { |r| r.size - 1 };
        var dots = runs.sum { |r| r.sum { |piece| piece.dots } };
        var simplePlain = (actual == normal)
            and: { ties == 0 }
            and: { written.every { |x| x.dots <= 1 } };
        ^[
            ties,
            if (simplePlain) { 0 } { 1 },
            dots,
            if (multiplier > one) { 1 } { 0 },
            // distance from one, kept exact: the larger of the ratio
            // and its reciprocal orders the same way |log2| would,
            // without a float.
            if (multiplier >= one) { multiplier } { one / multiplier }
        ]
    }

    // Lexicographic: equal isn't better, so the earliest candidate keeps a tie.
    *prIsBetterScore { |score, best|
        score.size.do { |i|
            if (score[i] < best[i]) { ^true };
            if (score[i] > best[i]) { ^false };
        };
        ^false
    }

    // A share's size whatever shape it arrived in and whether it sounds.
    //
    // >>> RhythmTree.weightOf(-2)           -> 2
    // >>> RhythmTree.weightOf([3, [1, 1]])  -> 3
    *weightOf { |p| ^if (p.isNumber) { p.abs } { p[0].abs } }
}
