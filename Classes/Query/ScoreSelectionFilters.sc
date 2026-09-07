+ ScoreSelection {
    // Filters answer another selection, so they compose. Path lookups in this
    // file are terminal by design.
    //
    // ^ ScoreSelection
    where {
        |function| ^ScoreSelection.fromRecords(records.select(function), source)
    }

    // Note [Index helpers are filters, not reordering]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // These filter the receiver and keep its order.
    //
    // Explicit indices are unique, non-negative Integers inside the
    // selection. `everyNth` is the periodic form.


    // Selected positions, still in the receiver's order.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).atIndices([3, 0]).leaves.collect { |leaf| leaf.pitch.letter }
    // [ c, f ]
    //
    // ^ ScoreSelection
    atIndices { |indices|
        var wanted = ScoreSelection.prCheckedIndices(
            indices, records.size, "atIndices");
        ^ScoreSelection.fromRecords(records.select { |record, index|
            wanted.includes(index) }, source)
    }

    // Everything except the selected positions.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).excludingIndices([1, 3]).leaves.collect { |leaf| leaf.pitch.letter }
    // [ c, e ]
    //
    // ^ ScoreSelection
    excludingIndices { |indices|
        var unwanted = ScoreSelection.prCheckedIndices(
            indices, records.size, "excludingIndices");
        ^ScoreSelection.fromRecords(records.select { |record, index|
            unwanted.includes(index).not }, source)
    }

    // Every nth selected record, starting at offset.
    //
    // >>> ScoreSelection(Measure("4/4", "c8 d8 e8 f8 g8 a8 b8 c'8")).everyNth(3, 1).leaves.collect { |leaf| leaf.pitch.letter }
    // [ d, g, c ]
    //
    // ^ ScoreSelection
    everyNth { |n, offset = 0|
        ScoreSelection.prCheckedStride(n, offset, "everyNth");
        ^ScoreSelection.fromRecords(records.select { |record, index|
            (index >= offset) and: { ((index - offset) % n) == 0 }
        }, source)
    }

    // ^ Set
    *prCheckedIndices { |indices, size, label|
        var list, seen = Set.new;

        // Indices name positions in this selection, not paths in the source.
        if (indices.isSequenceableCollection.not
            or: { indices.isKindOf(String) }) {
            Error(
                "ScoreSelection.%: % is not an index list. Use an Array of non-negative "
                "Integers."
                .format(label, indices.asCompileString)
            )
                .throw
        };
        list = indices.asArray;
        list.do { |index|
            if (index.isKindOf(Integer).not or: { index < 0 }) {
                Error(
                    "ScoreSelection.%: % is not an index. Use non-negative Integers."
                    .format(label, index.asCompileString)
                ).throw
            };
            if (index >= size) {
                Error(
                    "ScoreSelection.%: index % is outside a selection of % leaves."
                    .format(label, index, size)
                ).throw
            };
            if (seen.includes(index)) {
                Error(
                    "ScoreSelection.%: index % is repeated."
                    .format(label, index)
                ).throw
            };
            seen.add(index)
        };
        // Callers only need membership after validation.
        ^seen
    }

    // Check a positive step and a non-negative offset. The offset may sit beyond
    // the selection.
    //
    // ^ (Integer, Integer)
    *prCheckedStride { |n, offset, label|
        if (n.isKindOf(Integer).not or: { n < 1 }) {
            Error(
                "ScoreSelection.%: % is not a step. Use a positive Integer."
                .format(label, n.asCompileString)
            ).throw
        };
        if (offset.isKindOf(Integer).not or: { offset < 0 }) {
            Error(
                "ScoreSelection.%: % is not an offset. Use a non-negative Integer."
                .format(label, offset.asCompileString)
            ).throw
        };
        ^[n, offset]
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).notes.size   -> 1
    //
    // ^ ScoreSelection
    notes { ^this.where { |record| record[\leaf].isKindOf(MusicNote) } }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).rests.size   -> 1
    //
    // ^ ScoreSelection
    rests { ^this.where { |record| record[\leaf].isKindOf(MusicRest) } }

    // ^ ScoreSelection
    chords { ^this.where { |record| record[\leaf].isKindOf(Chord) } }

    // ^ ScoreSelection
    pitched { ^this.where { |record| record[\leaf].isKindOf(MusicRest).not } }

    // By staff index or staff name.
    //
    // ^ ScoreSelection
    inStaff { |which|
        if (which.isNumber) {
            ^this.where { |record| record[\staffIndex] == which }
        };
        ^this.where { |record| record[\staffName] == which }
    }

    // ^ ScoreSelection
    inMeasure { |index| ^this.where { |record| record[\measureIndex] == index } }

    // Inclusive bar range. Unlike a time window, `inMeasures(2, 2)` selects
    // bar 2.
    //
    // >>> ScoreSelection(MusicScore.oneStaff([Measure("1/4", "c4"),
    //     Measure("1/4", "d4"), Measure("1/4", "e4")], "V"))
    //     .inMeasures(0, 1).size
    // 2
    //
    // ^ ScoreSelection
    inMeasures { |lo, hi|
        var from = this.prBarIndex(lo, "inMeasures");
        var to = this.prBarIndex(hi, "inMeasures");
        if (from > to) {
            Error(
                "ScoreSelection.inMeasures: % to % names no bar. The range is "
                "inclusive, so the first bar must not follow the last."
                .format(from, to)
            ).throw
        };
        ^this.where { |record|
            (record[\measureIndex] >= from) and: { record[\measureIndex] <= to } }
    }

    // ^ Integer
    prBarIndex { |index, label|
        if (index.isKindOf(Integer).not or: { index < 0 }) {
            Error(
                "ScoreSelection.%: % is not a bar number. Use non-negative Integers, "
                "counting from 0."
                .format(label, index.asCompileString)
            ).throw
        };
        ^index
    }

    // ^ ScoreSelection
    inVoice { |which|
        if (which.isNumber) {
            ^this.where { |record| record[\voiceIndex] == which }
        };
        ^this.where { |record| record[\voiceName] == which }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).paths   -> [ [ 0 ], [ 1 ] ]
    //
    // ^ [[Integer]]
    paths { ^records.collect { |record| record[\path] } }

    // Note [A path is an address, not an index]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A path is a child-index address from `source`: `[0]` from a bar,
    // `[0, 0, 0]` from a score.

    // `recordAtPath` and `leafAtPath` search this selection. `elementAtPath`
    // walks `source` and treats a miss as stale addressing.
    //
    // ^ [Integer]
    prPath { |path, label|
        var steps = if (path.isNumber) { [path] } { path };
        if (steps.isArray.not) {
            Error(
                "ScoreSelection.%: path must be an Array of child indices or one Integer, got % (%)."
                .format(label, path.asCompileString, path.class)
            ).throw
        };
        steps.do { |step|
            if (step.isKindOf(Integer).not or: { step < 0 }) {
                Error(
                    "ScoreSelection.%: path % has invalid step %. Use non-negative Integers."
                    .format(label, steps.asCompileString, step.asCompileString)
                ).throw
            }
        };
        ^steps
    }

    // ^ IdentityDictionary | Nil
    recordAtPath { |path|
        var steps = this.prPath(path, "recordAtPath");
        ^records.detect { |record| record[\path] == steps }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).leafAtPath([1]).class
    // MusicRest
    //
    // ^ ScoreLeaf | Nil
    leafAtPath { |path|
        ^this.recordAtPath(path) !? { |record| record[\leaf] }
    }

    // >>> ScoreSelection(Measure("2/4", "c4 r4")).elementAtPath([0]).pitch
    // MusicPitch("c[4]")
    elementAtPath { |path|
        var steps = this.prPath(path, "elementAtPath");
        var here = source;
        steps.do { |step|
            var children = if (here.respondsTo(\children)) { here.children } { nil };
            if (children.isNil or: { step >= children.size }) {
                Error(
                    "ScoreSelection.elementAtPath: % does not resolve. % has % "
                    "children."
                    .format(
                        steps.asCompileString,
                        here.class,
                        children !? { |each| each.size } ?? { 0 }
                    )
                ).throw
            };
            here = children[step]
        };
        ^here
    }

    // What a leaf carries, as filters, so a question about decoration composes
    // with the questions about place and time.
    //
    // ^ ScoreSelection
    withMarkings { ^this.where { |record| record[\leaf].markings.notEmpty } }

    // ^ ScoreSelection
    withDynamics { ^this.where { |record| record[\leaf].dynamics.notEmpty } }

    // ^ ScoreSelection
    withArticulations {
        ^this.where { |record| record[\leaf].articulations.notEmpty }
    }

    // Filters are by kind. New values need no new query.
    //
    // ^ ScoreSelection
    withTechnicals {
        ^this.where { |record| record[\leaf].technicals.notEmpty }
    }

    // ^ ScoreSelection
    withTexts { ^this.where { |record| record[\leaf].texts.notEmpty } }

    // ^ ScoreSelection
    withSpanners { ^this.where { |record| record[\leaf].hasSpanners } }

    // ^ ScoreSelection
    withGraces { ^this.where { |record| record[\leaf].hasGraces } }

    // Notes tie by flag. Chords tie when any pitch continues. Rests
    // do not tie.
    //
    // ^ ScoreSelection
    withTies {
        ^this.where { |record|
            var leaf = record[\leaf];
            case
            { leaf.isKindOf(Chord) } { leaf.tiesAnything }
            { leaf.isKindOf(MusicNote) } { leaf.tiesToNext == true }
            { true } { false }
        }
    }

    // Exact windows over sounding time, counted from each staff start.
    //
    // ^ ScoreSelection
    startingAt { |offset|
        var at = Duration.asDuration(offset);
        ^this.where { |record| record[\offset] == at }
    }

    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).within(0, "2").size
    // 2
    //
    // ^ ScoreSelection
    within { |start, end|
        var window = this.prWindow(start, end, "within");
        ^this.where { |record|
            (record[\offset] >= window[0])
                and: { (record[\offset] + record[\prolated]) <= window[1] }
        }
    }

    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).overlapping("4", "2").size
    // 1
    //
    // ^ ScoreSelection
    overlapping { |start, end|
        var window = this.prWindow(start, end, "overlapping");
        ^this.where { |record|
            (record[\offset] < window[1])
                and: { (record[\offset] + record[\prolated]) > window[0] }
        }
    }

    // The same window rules, but in each bar's coordinate. Use
    // `inMeasure` to narrow to one bar.
    //
    // >>> ScoreSelection(MusicScore.oneStaff([Measure("2/4", "c4 d4"), Measure("2/4", "e4 f4")], "V")).withinBar(0, "1/4").size
    // 2
    //
    // ^ ScoreSelection
    withinBar { |start, end|
        var window = this.prWindow(start, end, "withinBar");
        ^this.where { |record|
            (record[\barOffset] >= window[0])
                and: { (record[\barOffset] + record[\prolated]) <= window[1] }
        }
    }

    // >>> ScoreSelection(Measure("4/4", "c2 d2")).overlappingBar(0, "1/4").size
    // 1
    //
    // ^ ScoreSelection
    overlappingBar { |start, end|
        var window = this.prWindow(start, end, "overlappingBar");
        ^this.where { |record|
            (record[\barOffset] < window[1])
                and: { (record[\barOffset] + record[\prolated]) > window[0] }
        }
    }

    // A zero-length window is a point question. Use `startingAt`.
    //
    // ^ (Duration, Duration)
    prWindow { |start, end, label|
        var from = Duration.asDuration(start), to = Duration.asDuration(end);
        if (to > from) { ^[from, to] };
        Error(
            "ScoreSelection.%: window % to % has no length. Use an end after the start, or startingAt for one point."
            .format(label, from, to)
        ).throw
    }
}
