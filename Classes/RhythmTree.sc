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
// See Note [Choosing a divisor] for written durations and brackets.
//
// Generated leaves may need tie splitting. `ScorePrepare` handles that later.
RhythmTree {


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
    // `span` is a Meter for a full bar, or a Duration for any other stretch.
    //
    // >>> RhythmTree.voice(Meter(4, 4), [1, 1]).leaves.size   -> 2
    *voice { |span, proportions, pitches, name|
        var voice = Voice([], name);
        this.fill(voice, this.spanOf(span), proportions,
            this.pitchStream(pitches, "RhythmTree.voice"));
        ^voice
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

    // Pitches cycle. A String is a run of spellings, and nil is middle C.
    //
    // A written token may carry marking suffixes.
    // See Note [A pitch list says no duration] in ScoreNotation.sc.
    //
    // >>> RhythmTree.pitchStream("c e").next   -> MusicPitch("c[4]")
    // >>> RhythmTree.pitchStream(nil).next     -> 60
    *pitchStream { |pitches, label = "RhythmTree.pitchStream"|
        var marked;
        if (pitches.isKindOf(String)) {
            marked = ScoreNotation.prMarkedPitches(pitches, label).collect {
                |each| if (each.value.isEmpty) { each.key } { each } };
            // A Routine yields Associations whole; `Pseq` splits them.
            ^Routine({ loop { marked.do { |each| each.yield } } })
        };
        ^Pseq((pitches ? [60]).asArray, inf).asStream
    }

    // One draw from the pitch stream, as a note of the written length.
    //
    // Markings are value objects, so cycling them is safe.
    //
    // >>> RhythmTree.noteFrom(60, Duration(1, 4)).dur          -> Duration(1/4)
    // >>> RhythmTree.noteFrom(MusicPitch("c") -> [Marking.dynamic(\mp)],
    //     Duration(1, 4)).markings.first.value   -> mp
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

    // Takes a proportion list, an RTM String, or a RhythmCell.
    *fill { |container, span, argProportions, pitchStream|
        ^this.prFill(container, span,
            RhythmCell.checkedProportions(argProportions), pitchStream)
    }

    *prFill { |container, span, proportions, pitchStream|
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
                    if (p < 0) { MusicRest(written) } {
                        this.noteFrom(pitchStream.next, written) }
                )
            } {
                // The subdivision decides its own spelling.
                this.prFill(target, written, p[1], pitchStream)
            }
        };

        ^container
    }

    // Answers the weights in lowest terms.
    //
    // Proportions are relative; common factors say nothing.
    //
    // >>> RhythmTree.reduceWeights([3, 3, 3, 3, 3, 3]) -> [ 1, 1, 1, 1, 1, 1 ]
    // >>> RhythmTree.reduceWeights([2, 4, 6])          -> [ 1, 2, 3 ]
    *reduceWeights { |weights|
        var common = weights.reduce { |a, b| a gcd: b } ? 1;
        ^if (common > 1) { weights.collect { |w| w div: common } } { weights }
    }

    // See Note [Choosing a divisor]. Weights must be in lowest terms.
    //
    // Meter-aware: three equal shares of 3/4 are quarters; in 4/4
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
    // printed counts; equal counts mean no bracket.
    //
    // >>> RhythmTree.chooseSpelling(Duration(7, 8), [1, 2, 1], true)[\actual] -> 8
    // >>> RhythmTree.chooseSpelling(Duration(3, 16), [1, 1], true)[\actual]  -> 2
    // >>> RhythmTree.chooseSpelling(Duration(1, 1), [1, 1])[\normal]          -> 2
    *chooseSpelling { |span, weights, allowTies = false|
        var sum = weights.sum;
        var n = span.numerator;
        var candidates = Set[sum];
        var best, bestScore, k = 0;

        if (span <= Duration(0, 1)) {
            Error("RhythmTree: spelling a rhythm needs a positive span, got %.".format(span)).throw
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
            Error("RhythmTree: % over span % cannot be written as note heads or "
                "ties. Use a tuplet or different proportions.".format(weights, span)).throw
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
        // Count pieces after tie splitting; unsplit tied durations have no dots.
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
            // Distance from one, kept exact: the larger of the ratio
            // and its reciprocal orders the same way |log2| would,
            // without a float.
            if (multiplier >= one) { multiplier } { one / multiplier }
        ]
    }

    // Lexicographic, and equal isn't better, so the earliest
    // candidate keeps a tie.
    *prIsBetterScore { |score, best|
        score.size.do { |i|
            if (score[i] < best[i]) { ^true };
            if (score[i] > best[i]) { ^false };
        };
        ^false
    }

    // A share's size, whatever shape it arrived in and whether it sounds.
    //
    // >>> RhythmTree.weightOf(-2)           -> 2
    // >>> RhythmTree.weightOf([3, [1, 1]])  -> 3
    *weightOf { |p| ^if (p.isNumber) { p.abs } { p[0].abs } }
}
