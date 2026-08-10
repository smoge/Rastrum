// RhythmTree: proportional rhythm trees (RTM), the canonical rhythmic input.
//
// An element of a proportion list is either
//   a number        : a leaf, negative means rest
//   [weight, list]  : a subdivided leaf
//
// RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2])
//
// How the weights of a span become written durations, and when they need a
// bracket, is Note [Choosing a divisor] below.
//
// A divisor may leave durations no single note head can spell: [5, 3] over 4/4
// is five eighths and three, and the five needs a tie. Those are built as they
// are and left for `ScorePrepare`, rather than refusing ordinary proportions at
// construction, before anything can repair them.


// Note [Choosing a divisor]
// ~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A span divided into weights w_i is written as w_i/D of the span, with tuplet
// multiplier D/sum(w). Which D is the whole game: D = 3 in a 3/4 bar gives
// plain quarters, where a blind power-of-two rule would print a spurious 3:2.
//
// `chooseDivisor` enumerates {sum(w)} together with {numerator(span) * 2^k},
// discards the candidates whose durations cannot be written at all, and scores
// what is left.
//
// The score is a tuple read left to right, lower better, each position
// outranking every later one outright: no ties, then fewer dots, then a
// multiplier at or below one, then one closer to one. A tuple rather than a
// weighted sum of constants, because a sum has to be justified by arithmetic
// about how large each term can grow, a calculation nobody writes down, and
// one that quietly stops holding when the search space changes.
//
// Ties are the coarsest position deliberately. A divisor whose durations are
// all directly notatable must beat one that needs ties however flattering its
// multiplier, and that is what keeps the tie-capable mode from ever regressing
// a proportion that already worked.
//
// `allowTies` is the difference between "one note head each" and "notatable
// once ScorePrepare has split them". The relaxed test is local and needs no
// metric knowledge: a duration becomes tied notatable pieces exactly when its
// denominator is a power of two, because every notatable value is one. A third
// of a span is unwritable either way and must go through a tuplet.
//
// Weights arrive in lowest terms, `fill` reduces first, so scaling a
// proportion list cannot move the answer. Handed raw weights it answers
// honestly for those, which is a different question.
//
// This is the most arguable code in the library. It is one short method with
// one job: replace it, don't work around it.
RhythmTree {


    // A subdivided share becomes a nested group, so the leaf count is the
    // shares read all the way down. A negative share becomes a rest.
    //
    // >>> RhythmTree.measure(Meter(4, 4), [1, [1, [1, 1, 1]], 2]).leaves.size
    // 5
    // >>> RhythmTree.measure(Meter(4, 4), [1, -1, 1, 1]).leaves[1].class
    // MusicRest
    *measure { |meter, proportions, pitches|
        var bar = Measure(meter);
        this.fill(bar, meter.duration, proportions, this.pitchStream(pitches));
        ^bar
    }

    // Returns a Voice filled the same way, for a bar that holds more than one
    // timeline.
    //
    // Built directly rather than by filling a measure and lifting its children
    // out. See Note [Adding a child repoints its parent] in ScoreElement.sc.
    //
    // `span` is a Meter for the ordinary full-bar case, or a Duration for a
    // partial bar or any other stretch that is not a whole measure.
    //
    // >>> RhythmTree.voice(Meter(4, 4), [1, 1]).leaves.size   -> 2
    *voice { |span, proportions, pitches, name|
        var voice = Voice([], name);
        this.fill(voice, this.spanOf(span), proportions, this.pitchStream(pitches));
        ^voice
    }

    // A Meter says how long a bar is. A Duration says how long anything is.
    // Both answer the one question `fill` asks.
    //
    // >>> RhythmTree.spanOf(Meter(3, 4))      -> Duration(3/4)
    // >>> RhythmTree.spanOf(Duration(1, 2))   -> Duration(1/2)
    *spanOf { |span|
        if (span.isKindOf(Meter)) { ^span.duration };
        ^Duration.asDuration(span)
    }

    // Pitches cycle, so a row shorter than the rhythm repeats rather than
    // running out. Middle C when none is given, which is the shape `measure`
    // always had.
    *pitchStream { |pitches|
        ^Pseq((pitches ? [60]).asArray, inf).asStream
    }

    // Takes a proportion list or a RhythmCell without asking which: a cell
    // holds the same shape, checked, and this is the one place both arrive.
    *fill { |container, span, argProportions, pitchStream|
        var proportions = RhythmCell.asProportions(argProportions);
        // Reduced first, so the same proportions written coarsely or finely are
        // one notation. The divisor search picks from an absolute set of
        // candidates, so scaling the weights used to move the answer: [3, 3, 3,
        // 3, 3, 3] came out as six dotted eighths under 18:16 where [1, 1, 1,
        // 1, 1, 1] came out as six quarters under 6:4, same sounding rhythm,
        // different note heads. Everything below reads the reduced weights, so
        // the search itself never needs a notion of scale.
        var weights, sum, divisor, target;

        weights = this.reduceWeights(proportions.collect { |p| this.weightOf(p) });
        sum = weights.sum;

        if (sum < 1) { Error("RhythmTree: empty proportion list").throw };

        // Tie-capable: ScorePrepare rewrites whatever needs splitting
        // afterwards, so the divisor search need not restrict itself to single
        // note heads.
        divisor = this.chooseDivisor(span, weights, true);
        target = container;

        if (divisor != sum) {
            // Authored as the counts, not as the multiplier they reduce to. Six
            // weights over a divisor of four is a 6:4 bracket. Storing only the
            // multiplier would reduce it to 3:2 and print a "3" over six notes.
            target = Tuplet.ratio(sum, divisor);
            container.add(target);
        };

        proportions.do { |p, i|
            var written = span * Duration(weights[i], divisor);
            if (p.isNumber) {
                target.add(
                    if (p < 0) { MusicRest(written) } { MusicNote(pitchStream.next, written) }
                )
            } {
                // subdivided: recurse into target, which inserts its own tuplet
                // only if the subdivision actually needs one
                this.fill(target, written, p[1], pitchStream)
            }
        };

        ^container
    }

    // Returns the weights in lowest terms.
    //
    // Proportions are relative: what a weight means is its share of the sum, so
    // a common factor says nothing. Removing it is what makes the notation
    // depend on the rhythm rather than on how large the numbers happened to be.
    //
    // >>> RhythmTree.reduceWeights([3, 3, 3, 3, 3, 3]) -> [ 1, 1, 1, 1, 1, 1 ]
    *reduceWeights { |weights|
        var common = weights.reduce { |a, b| a gcd: b } ? 1;
        ^if (common > 1) { weights.collect { |w| w div: common } } { weights }
    }

    // See Note [Choosing a divisor]. Weights must be in lowest terms.
    //
    // The meter-awareness in two answers: three equal shares of a 3/4 bar are
    // plain quarters, and of a 4/4 bar a 3:2 bracket over halves. A
    // power-of-two rule would bracket both.
    //
    // >>> RhythmTree.chooseDivisor(Duration(3, 4), [1, 1, 1])   -> 3
    // >>> RhythmTree.chooseDivisor(Duration(1, 1), [1, 1, 1])   -> 2
    //
    // And what `allowTies` buys: [5, 3] over a whole bar is unwritable as
    // single note heads, and eighths once ScorePrepare may tie them.
    //
    // >>> RhythmTree.chooseDivisor(Duration(1, 1), [5, 3], true)   -> 8
    *chooseDivisor { |span, weights, allowTies = false|
        var sum = weights.sum;
        var n = span.numerator;
        var candidates = Set[sum];
        var best, bestScore, k = 0;

        while { (n * (2 ** k)) <= (8 * sum) } {
            candidates.add((n * (2 ** k)).asInteger);
            k = k + 1;
        };

        candidates.asArray.sort.do { |d|
            var written = weights.collect { |w| span * Duration(w, d) };
            var writable = written.every { |x|
                x.isNotatable or: { allowTies and: { x.isTieSplittable } }
            };
            if (writable) {
                var score = this.divisorScore(written, d, sum);
                if (bestScore.isNil or: { this.prIsBetterScore(score, bestScore) }) {
                    best = d; bestScore = score;
                }
            }
        };

        if (best.isNil) {
            Error("RhythmTree: % over span % cannot be written. Some part of it "
                "would need a denominator that is not a power of two, which no "
                "note head or tie can spell - only a tuplet can.".format(
                    weights, span)).throw
        };

        ^best
    }

    // How good a divisor is, as a tuple: lower is better at every position and
    // an earlier position outranks every later one. See
    // Note [Choosing a divisor] for what the four are and why it is not a
    // weighted sum.
    //
    // >>> RhythmTree.divisorScore([Duration(1, 4), Duration(1, 4)], 2, 2)
    // [ 0, 0, 0, Duration(1/1) ]
    *divisorScore { |written, divisor, sum|
        // Counted over the pieces each duration becomes, not the duration
        // itself: `dots` is undefined for one that needs a tie.
        var runs = written.collect { |x| x.tieRuns };
        var multiplier = Duration(divisor, sum);
        var one = Duration(1, 1);
        ^[
            runs.sum { |r| r.size - 1 },
            runs.sum { |r| r.sum { |piece| piece.dots } },
            if (multiplier > one) { 1 } { 0 },
            // Distance from one, kept exact: the larger of the ratio and its
            // reciprocal orders the same way |log2| would, without a float.
            if (multiplier >= one) { multiplier } { one / multiplier }
        ]
    }

    // Lexicographic, and equal is not better, so the earliest candidate keeps
    // a tie.
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
