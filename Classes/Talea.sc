// Talea: signed counts over a power-of-two denominator, cycled to fill a span.
//
// A talea is duration-first where an RTM cell is proportional: each
// count is that many units of `1/denominator`. A negative count is a
// rest of the same length, so one row says its own silences.
//
//   Talea([1, 2, -1, 3, 1], 16).durations
//   Talea([5, 1], 16).durationsIn(Meter(2, 4))
//
// Score-free like PitchMaterial. `durationsIn` answers Durations and
// `Measure.durations` turns them into a bar.
//
// See Note [What comes free] for the two talea behaviors that are not
// written here.
//
// Across a run of spans it answers leaves instead.
//
// See Note [A split count is a tie].
//
// A duration can't say that a fragment continues the count before it.


// The proportional generator is `RhythmCell`.
// One doesn't express the other, either way.
Talea {

    // Note [What comes free]
    // ~~~~~~~~~~~~~~~~~~~~~~
    //
    // A count no note head spells reaches `ScorePrepare` as one leaf
    // and comes back as tied notatable ones.
    //
    // `Measure.durations` draws a pitch in its note branch only, so a
    // rest doesn't eat the row.
    //
    // Both rules already exist. Restating them here would put two
    // rules where one belongs.
    //
    // That leaves two jobs: say durations and cycle them to a span.


    var <counts, <denominator;

    // Counts first, denominator second: the counts are the subject,
    // and the value comes before the unit in `Duration(num, den)` too.
    //
    // >>> Talea([1, 2, -1], 16).counts        -> [ 1, 2, -1 ]
    // >>> Talea([1, 2, -1], 16).denominator   -> 16
    *new { |counts, denominator = 16|
        ^super.new.initTalea(this.checkedCounts(counts),
            this.checkedDenominator(denominator))
    }

    // A zero count is refused rather than dropped. It is a written
    // mistake. Dropping it would silently shift every later cell.
    //
    // >>> Talea.checkedCounts([1, -2])   -> [ 1, -2 ]
    *checkedCounts { |value|
        if (value.isSequenceableCollection.not or: { value.isKindOf(String) }) {
            Error(
                "Talea: % is not a list of counts. Use nonzero Integers, negative for a rest."
                .format(value.asCompileString)
            ).throw
        };
        if (value.isEmpty) {
            Error("Talea: an empty count list says no rhythm.").throw
        };
        ^value.asArray.collect { |each|
            if (each.isKindOf(Integer).not) {
                Error(
                    "Talea: count % is a %, not an Integer."
                    .format(each.asCompileString, each.class)
                ).throw
            };
            if (each == 0) {
                Error(
                    "Talea: a zero count has no length. Drop it, or write a negative count for a rest."
                ).throw
            };
            each
        }
    }

    // Duration-first, so the unit has to be a note value.
    //
    // >>> Talea.checkedDenominator(16)   -> 16
    *checkedDenominator { |value|
        if (value.isKindOf(Integer).not or: { value < 1 }) {
            Error(
                "Talea: denominator % is not a positive Integer."
                .format(value.asCompileString)
            ).throw
        };
        if (value.bitAnd(value - 1) != 0) {
            Error(
                "Talea: denominator % is not a power of two. A talea counts note "
                "values; use RhythmCell for a proportional division."
                .format(value)
            ).throw
        };
        ^value
    }

    initTalea { |argCounts, argDenominator|
        counts = argCounts;
        denominator = argDenominator;
        ^this
    }

    // One cycle, in written order. Negative means rest.
    //
    // >>> Talea([1, 2, -1], 16).durations
    // [ Duration(1/16), Duration(1/8), Duration(-1/16) ]
    durations { ^counts.collect { |each| Duration(each, denominator) } }

    // The whole talea, unsigned. One cycle covers it.
    //
    // >>> Talea([1, 2, -1], 16).cycleDuration   -> Duration(1/4)
    cycleDuration {
        ^counts.inject(Duration(0, 1)) { |sum, each|
            sum + Duration(each.abs, denominator) }
    }

    // Cycled to fill `span`. The final cell is truncated to the
    // remainder with its sign kept. A truncated cell may be a length
    // no note head spells; `ScorePrepare` splits it later.
    //
    // `span` is a Meter, a Duration or anything `RhythmTree.spanOf`
    // coerces, so a caller never writes the coercion.
    //
    // >>> Talea([3], 16).durationsIn(Duration(1, 2))
    // [ Duration(3/16), Duration(3/16), Duration(1/8) ]
    // >>> Talea([-3], 16).durationsIn(Duration(1, 4)).last   -> Duration(-1/16)
    // >>> Talea([5, 1], 16).durationsIn(Meter(2, 4))
    // [ Duration(5/16), Duration(1/16), Duration(1/8) ]
    durationsIn { |span|
        var length = RhythmTree.spanOf(span);
        var zero = Duration(0, 1);
        var filled = zero, index = 0;
        var out = [];

        if (length <= zero) {
            Error(
                "Talea.durationsIn: % is not a positive span."
                .format(length)
            ).throw
        };
        while { filled < length } {
            var count = counts.wrapAt(index);
            var cell = Duration(count.abs, denominator);
            var left = length - filled;
            if (cell > left) { cell = left };
            out = out.add(if (count < 0) { zero - cell } { cell });
            filled = filled + cell;
            index = index + 1
        };
        ^out
    }

    // Note [A split count is a tie]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // One count cut by a span end is one sounded thing, so it's a
    // tied note, not two attacks.
    //
    // Signed durations can't say that. Filling each span alone
    // answers the same list for `Talea([5], 16)` and `Talea([3, 2],
    // 16)`, different music that draws a different number of pitches.
    //
    // `MusicNote#tiesToNext` is where that fact already lives, so
    // this answers leaves instead of inventing a cell record.


    // One Array of leaves per span, in span order with one count
    // cursor carried across all of them.
    //
    // The pitch row advances once per *sounded count*, not once per
    // fragment, so a count split over three spans draws one pitch and
    // wears it three times. A split rest does not tie, having nothing
    // to tie.
    //
    // Markings on a row token ride the first fragment only. The last
    // span truncates as `durationsIn` does. That final fragment does
    // not tie.
    //
    // >>> Talea([5], 16).leavesInSpans([Duration(3, 16), Duration(2, 16)], "c d").collect { |row| row.size }
    // [ 1, 1 ]
    // >>> Talea([5], 16).leavesInSpans([Duration(3, 16), Duration(2, 16)], "c d").first.first.tiesToNext
    // true
    // >>> Talea([5], 16).leavesInSpans([Duration(3, 16), Duration(2, 16)], "c d").last.first.pitch.letter
    // c
    // >>> Talea([-5], 16).leavesInSpans([Duration(3, 16), Duration(2, 16)]).first.first.class
    // MusicRest
    leavesInSpans { |spans, pitches|
        var lengths = Talea.checkedSpans(spans);
        var stream = RhythmTree.pitchStream(pitches, "Talea.leavesInSpans");
        var zero = Duration(0, 1);
        var index = 0, left = zero, sounded = true, spec = nil, first = true;

        ^lengths.collect { |length, spanIndex|
            // The run truncates at its end the way one span does.
            // A fragment cut there has nothing to continue into.
            var last = spanIndex == (lengths.size - 1);
            var filled = zero;
            var row = [];
            while { filled < length } {
                var room, piece;
                if (left.isZero) {
                    var count = counts.wrapAt(index);
                    index = index + 1;
                    left = Duration(count.abs, denominator);
                    sounded = count > 0;
                    // Drawn once for the whole count, however it is cut.
                    spec = if (sounded) { stream.next };
                    first = true
                };
                room = length - filled;
                piece = if (left > room) { room } { left };
                left = left - piece;
                // More of this count left means the span ended inside it.
                row = row.add(this.prFragment(spec, sounded, piece, first,
                    left.isZero.not and: { last.not }));
                filled = filled + piece;
                first = false
            };
            row
        }
    }

    // One fragment of one count. A continuation takes the pitch
    // without the markings, which belong to the attack.
    prFragment { |spec, sounded, length, first, continues|
        var note;
        if (sounded.not) { ^MusicRest(length) };
        note = if (first) { RhythmTree.noteFrom(spec, length) } {
            MusicNote(if (spec.isKindOf(Association)) { spec.key } { spec },
                length)
        };
        note.tiesToNext = continues;
        ^note
    }

    // Nothing to fill is a caller mistake here, as it is for
    // `RhythmTree.measures` and `Measure.durations`.
    //
    // >>> Talea.checkedSpans([Meter(2, 4), "1/4"])   -> [ Duration(1/2), Duration(1/4) ]
    *checkedSpans { |spans|
        var listed;
        if (spans.isSequenceableCollection.not or: { spans.isKindOf(String) }) {
            Error(
                "Talea.leavesInSpans: % is not a list of spans. Use Meters, Durations or written spans."
                .format(spans.asCompileString)
            ).throw
        };
        if (spans.isEmpty) {
            Error("Talea.leavesInSpans: empty span list; nothing to fill.").throw
        };
        listed = spans.asArray.collect { |each| RhythmTree.spanOf(each) };
        listed.do { |length, i|
            if (length <= Duration(0, 1)) {
                Error(
                    "Talea.leavesInSpans: span % is %, which is not a positive length."
                    .format(i, length)
                ).throw
            }
        };
        ^listed
    }

    == { |that|
        ^that.isKindOf(Talea) and: { counts == that.counts }
            and: { denominator == that.denominator }
    }

    hash { ^counts.hash bitXor: denominator.hash }

    size { ^counts.size }

    printOn { |stream|
        stream << "Talea(" << counts << ", " << denominator << ")"
    }

    // Default elision is wanted: sixteenth-note taleas store counts
    // only.
    //
    // >>> Talea([1, 2, 3], 16).asCompileString   -> Talea([1, 2, 3])
    // >>> Talea([1, 2, 3], 8).asCompileString    -> Talea([1, 2, 3], 8)
    storeArgs { ^[counts, denominator] }
}
