// Note [Written time and sounding time]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A tupleted leaf has written duration and sounding duration.
// Sounding time applies every enclosing multiplier. Metric boundaries
// use sounding time. Note heads and bracket cuts use written time.
// The walk stores both so nested splits do not rederive the tree.

// Note [Rests are respelled, not split]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Adjacent plain rests in one timeline are one silence. Rewrite them as the
// fewest readable rests at that offset.

// The meter decides the cut lines. See Note [A rest carries the beat, a note
// carries the line] in Meter.sc.
//
// Rests never tie. Attached rests aren't plain, so their position is preserved.

// Note [Copy, never adopt]
// ~~~~~~~~~~~~~~~~~~~~~~~~
//
// Preparation never mutates the source tree. `run` returns fresh elements.
// Reusing children would repoint parents.
// See Note [Adding a child repoints its parent] in ScoreElement.sc.
//
// Parent links aren't consulted while a tree is being rebuilt.

// Note [Staff preparation carries forward]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Staff preparation walks each timeline across authored bar spans. Overflow
// moves forward. Underfull bars are refused unless previous overflow fills them.
// Voices match by position; optional names must agree.
//
// Containers crossing a barline are split and rewrapped in each bar.

// Note [Split leaf adornments]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Markings and grace groups belong to the attack, so only the first fragment
// keeps them. Spanner starts stay on the first fragment. Stops stay on the last.
// The last fragment inherits the original forward tie. Earlier fragments tie
// onward.


// ScorePrepare: the notation preparation pass.
//
// `run` returns a fresh tree with writer-facing notation facts made explicit.
// Measuring helpers expose bar-local leaf offsets and written-time scale.
//
// Measuring helpers demand a Measure, so offsets stay local to one bar.
//
// No output syntax and no writer knowledge.
ScorePrepare {

    // Exact sounding offsets from the bar's metric origin. Pickups
    // start late in their notional bar.
    //
    // >>> { |m| m.leaves.collect { |x| ScorePrepare.leafOffsetsIn(m)[x] } }
    //     .value(RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]))
    // [ Duration(0/1), Duration(1/4), Duration(1/2), Duration(3/4) ]
    //
    // >>> { |m| m.leaves.collect { |x| ScorePrepare.leafOffsetsIn(m)[x] } }
    //     .value(Measure.pickup(Meter(4, 4),
    //         [MusicNote(60, Duration(1, 8)), MusicNote(62, Duration(1, 8))],
    //         Duration(1, 4)))
    // [ Duration(3/4), Duration(7/8) ]
    *leafOffsetsIn { |measure|
        var acc = IdentityDictionary.new;
        this.prRequireMeasure(measure, thisMethod);
        measure.voices.do { |voice|
            this.prWalk(voice, measure.metricOffset, Duration(1, 1), acc, nil)
        };
        ^acc
    }

    // Product of enclosing multipliers for each leaf. Outside tuplets: 1.
    //
    // >>> { |m| m.leaves.collect { |x| ScorePrepare.leafMultipliersIn(m)[x] } }
    //     .value(Measure(Meter(4, 4), [Tuplet.ratio(3, 2,
    //         [MusicNote(60, Duration(1, 4)), MusicNote(62, Duration(1, 4)),
    //         MusicNote(64, Duration(1, 4))]), MusicNote(65, Duration(1, 2))]))
    // [ Duration(2/3), Duration(2/3), Duration(2/3), Duration(1/1) ]
    *leafMultipliersIn { |measure|
        var acc = IdentityDictionary.new;
        this.prRequireMeasure(measure, thisMethod);
        measure.voices.do { |voice|
            this.prWalk(voice, measure.metricOffset, Duration(1, 1), nil, acc)
        };
        ^acc
    }

    // A fresh writer-ready tree: notatable leaves, split fragments
    // tied onward. Explicit and facade-assisted: the facade calls
    // this before writing, and no writer calls it. \
	// See Note [A writer refuses what it depends on] in Writers/ScoreWriter.sc.
    //
    // A staff or score can carry overflow across barlines. A lone
    // Measure must already fill its own span.
	//
	// Five eighths leaves as a half tied to an eighth:
    //
    // >>> ScorePrepare.run(Measure(Meter(4, 4), [MusicNote(60, Duration(5, 8)),
    //     MusicNote(62, Duration(3, 8))])).leaves.collect { |x| x.duration }
    // [ Duration(1/2), Duration(1/8), Duration(3/8) ]
    *run { |element|
        if (element.isKindOf(MusicScore)) {
            ^MusicScore(
                element.children.collect { |child| this.run(child) },
                element.title, element.composer)
        };
        if (element.isKindOf(Staff)) { ^this.prPrepareStaff(element) };
        if (element.isKindOf(Measure)) {
            // Keep the entry-point name in the refusal.
            this.prRequireMeasure(element, thisMethod);
            ^this.prPrepareMeasure(element)
        };
        Error("ScorePrepare.run: expected a MusicScore, Staff or Measure, got a %.".format(element.class)).throw
    }

    // Prepare complete timelines across the staff's meter sequence.
    // See Note [Staff preparation carries forward].
    *prPrepareStaff { |staff|
        var measures = staff.children.asArray;
        var flat, count, names, bars;

        if (measures.isEmpty) { ^Staff([], staff.name, staff.clef, staff.shortName) };
        measures.do { |bar|
            if (bar.isKindOf(Measure).not) {
                Error("ScorePrepare: Staff may hold only measures, got a %.".format(bar.class)).throw
            };
            this.prRequireMeasure(bar, thisMethod);
        };

        flat = measures.every { |bar| bar.hasVoices.not };
        count = this.prVoiceCount(measures, flat);
        names = this.prVoiceNames(measures, count, flat);

        bars = this.prLayOut(measures, count, names, flat);
        ^Staff(bars.collect { |bar| this.prPrepareMeasure(bar) }, staff.name,
            staff.clef, staff.shortName)
    }

    // Timelines match by position, so every bar must expose the same count.
    *prVoiceCount { |measures, flat|
        var counts;
        if (flat) { ^1 };
        measures.do { |bar|
            if (bar.hasVoices.not) {
                Error("ScorePrepare: a % bar has no voices, but other bars in "
                    "this staff do.".format(bar.meter)).throw
            }
        };
        counts = measures.collect { |bar| bar.voices.size }.asSet;
        if (counts.size != 1) {
            Error("ScorePrepare: this staff has % different voice counts."
                .format(counts.size)).throw
        };
        ^counts.asArray.first
    }

    // Optional names must agree at each timeline position.
    *prVoiceNames { |measures, count, flat|
        var names = Array.fill(count, { nil });
        if (flat) { ^names };
        measures.do { |bar|
            bar.voices.do { |voice, j|
                if (voice.name.notNil) {
                    if (names[j].isNil) {
                        names[j] = voice.name
                    } {
                        if (names[j] != voice.name) {
                            Error("ScorePrepare: voice % is named \"%\" in one "
                                "bar and \"%\" in another.".format(
                                    j + 1, names[j], voice.name)).throw
                        }
                    }
                }
            }
        };
        ^names
    }

    // Lay each timeline over the authored bar spans.
    // See Note [Staff preparation carries forward].
    *prLayOut { |measures, count, names, flat|
        var meters = measures.collect { |bar| bar.meter };
        // Use declared spans; meter spans would overfill pickups.
        var spans = measures.collect { |bar| bar.barDuration };
        var perBar = Array.fill(meters.size, { Array.fill(count, { List.new }) });
        count.do { |j|
            var barIndex = 0, position = Duration(0, 1);
                // Overflow moves forward. Short bars cannot borrow
                // ahead.
            measures.do { |authored, i|
                authored.voices[j].children.do { |element|
                    var sounding = element.duration * element.multiplier;
                    var placed = false, first = true;
                    if (element.isLeaf.not) {
                        // Split and rewrap containers at barlines.
                        var remaining = element, placedHere = false;
                        while { placedHere.not } {
                            var capacity, halves;
                            if (barIndex >= meters.size) {
                                this.prOverflowedTheStaff(element)
                            };
                            capacity = spans[barIndex] - position;
                            sounding = remaining.duration * remaining.multiplier;
                            if (sounding <= capacity) {
                                perBar[barIndex][j].add(this.copyOf(remaining));
                                position = position + sounding;
                                placedHere = true;
                            } {
                                // Convert the sounding capacity into
                                // the bracket's written time.
                                halves = this.prSplitElement(remaining,
                                    capacity / remaining.multiplier);
                                perBar[barIndex][j].add(halves[0]);
                                remaining = halves[1];
                                barIndex = barIndex + 1;
                                position = Duration(0, 1);
                            }
                        }
                    } {
                        while { placed.not } {
                            var remaining;
                            if (barIndex >= meters.size) {
                                this.prOverflowedTheStaff(element)
                            };
                            remaining = spans[barIndex] - position;
                            if (sounding <= remaining) {
                                perBar[barIndex][j].add(
                                    this.prCopyLeaf(element, sounding, element, first));
                                first = false;
                                position = position + sounding;
                                placed = true;
                            } {
                                // The piece that reaches the barline
                                // ties onward.
                                perBar[barIndex][j].add(
                                    this.prCopyLeaf(element, remaining, nil, first));
                                first = false;
                                sounding = sounding - remaining;
                                barIndex = barIndex + 1;
                                position = Duration(0, 1);
                            }
                        }
                    };
                    if (barIndex < spans.size and: {
                        position == spans[barIndex]
                    }) {
                        barIndex = barIndex + 1;
                        position = Duration(0, 1);
                    }
                };
                // This authored bar must now be full, either by its
                // own material or prior carry.
                if (barIndex == i) {
                    Error("ScorePrepare: bar % declares % but is short by %%."
                        .format(
                            i + 1, spans[i], spans[i] - position,
                            if (count > 1) { " in voice " ++ (j + 1) } { "" })).throw
                }
            }
        };
        ^measures.collect { |authored, i|
            // Directions and clef are bar-local facts. Rebuilding
            // drops any field not named here.
            Measure.partial(authored.meter, if (flat) {
                perBar[i][0].asArray
            } {
                perBar[i].collect { |elements, j| Voice(elements.asArray, names[j]) }
            }, authored.barDuration, authored.metricOffset)
                .clef_(authored.clef)
                .directions_(authored.directions)
        }
    }

    // Structural copy, no splitting. See Note [Copy, never adopt].
    *copyOf { |element|
        if (element.isLeaf) { ^this.prCopyLeaf(element, element.dur, element) };
        ^this.rebuilt(element, element.children.collect { |c| this.copyOf(c) })
    }

    // Rebuild a container with new children while preserving its
    // metadata. Specific classes come before ScoreContainer so their
    // fields survive.
    *rebuilt { |element, children|
        if (element.isKindOf(Voice)) { ^Voice(children, element.name) };
        if (element.isKindOf(Tuplet)) { ^Tuplet.like(element, children) };
        if (element.isKindOf(Measure)) {
            // Preserve the full bar placement and notation metadata.
            ^Measure.partial(element.meter, children,
                element.barDuration, element.metricOffset)
                    .clef_(element.clef)
                    .directions_(element.directions)
        };
        if (element.isKindOf(Staff)) {
            ^Staff(children, element.name, element.clef, element.shortName)
        };
        if (element.isKindOf(MusicScore)) {
            ^MusicScore(children, element.title, element.composer)
        };
        if (element.isKindOf(ScoreContainer)) { ^ScoreContainer(children) };
        Error("ScorePrepare.rebuilt: expected a container, got a %.".format(
            element.class)).throw
    }

    // Answers [head, tail], cut at `at` in the element's written
    // time. See Note [Written time and sounding time].
    *prSplitElement { |element, at|
        var head = List.new, tail = List.new, cursor = Duration(0, 1);
        if (element.isLeaf) {
            ^[this.prCopyLeaf(element, at, nil, true),
              this.prCopyLeaf(element, element.dur - at, element, false)]
        };
        element.children.do { |child|
            // Children occupy their written duration scaled by their multiplier.
            var next = cursor + (child.duration * child.multiplier);
            case
                { next <= at } { head.add(this.copyOf(child)) }
                { cursor >= at } { tail.add(this.copyOf(child)) }
                { true } {
                    // Cross into the child's written frame.
                    var halves = this.prSplitElement(child,
                        (at - cursor) / child.multiplier);
                    head.add(halves[0]);
                    tail.add(halves[1]);
                };
            cursor = next;
        };
        ^[this.prRewrap(element, head.asArray), this.prRewrap(element, tail.asArray)]
    }

    // Keeps fragments in the same kind of container. Empty sides are
    // nil. Callers cut only inside an element.
    *prRewrap { |element, children|
        if (children.isEmpty) { ^nil };
        if (element.isKindOf(Tuplet)) { ^Tuplet.like(element, children) };
        if (element.isKindOf(ScoreContainer)) { ^ScoreContainer(children) };
        Error("ScorePrepare: cannot split a %.".format(element.class)).throw
    }

    *prOverflowedTheStaff { |element|
        Error("ScorePrepare: % runs past the last barline.".format(element)).throw
    }

    *prPrepareMeasure { |measure|
        var offsets, multipliers;
        if (measure.isFull.not) {
            Error("ScorePrepare: a % bar declares % but holds %. A lone measure "
                "must already fit its own span.".format(
                    measure.meter, measure.barDuration, measure.duration)).throw
        };
        offsets = this.leafOffsetsIn(measure);
        multipliers = this.leafMultipliersIn(measure);
        ^Measure.partial(measure.meter,
            this.prRebuildAll(measure.children, measure, offsets, multipliers),
            measure.barDuration, measure.metricOffset)
                .clef_(measure.clef)
                .directions_(measure.directions)
    }

    // Rebuilds answer arrays: leaves can split, rest runs can
    // collapse.
    *prRebuildAll { |children, measure, offsets, multipliers|
        var acc = List.new, run = List.new;
        children.do { |child|
            if (this.prIsPlainRest(child)) {
                run.add(child)
            } {
                if (run.notEmpty) {
                    this.prRestRun(run, measure, offsets, multipliers)
                        .do { |x| acc.add(x) };
                    run = List.new;
                };
                this.prRebuild(child, measure, offsets, multipliers).do { |x| acc.add(x) }
            }
        };
        if (run.notEmpty) {
            this.prRestRun(run, measure, offsets, multipliers).do { |x| acc.add(x) }
        };
        ^acc.asArray
    }

    // Attachments and grace groups pin a rest. Merging would lose
    // their position.
    *prIsPlainRest { |element|
        ^element.isKindOf(MusicRest)
            and: { element.hasMarkings.not }
            and: { element.hasSpanners.not }
            and: { element.hasGraces.not }
    }

    // The fewest rests that spell this run's span where the run begins. See
    // Note [Rests are respelled, not split].
    *prRestRun { |run, measure, offsets, multipliers|
        var first = run.first;
        var written = run.inject(Duration(0, 1), { |sum, rest| sum + rest.dur });
        var pieces;
        // A full bar of silence is one rest even in meters such as
        // 5/8. `Measure#wholeBarRests` recognizes this case.
        if (multipliers[first] == Duration(1, 1)
            and: { measure.isPartial.not }
            and: { offsets[first] == measure.metricOffset }
            and: { written == measure.barDuration }) { ^[MusicRest(written)] };
        pieces = if (multipliers[first] == Duration(1, 1)) {
            this.prMetricPieces(offsets[first], written, measure.meter, 0, true)
        } {
            // Inside a tuplet the bar's grid doesn't reach, so the
            // written span decomposes binarily, exactly as a tupleted
            // note does.
            if (written.isNotatable) { [written] } {
                written.tieRuns ?? {
                    Error("ScorePrepare: rest % inside a tuplet cannot be split "
                        "into notatable pieces.".format(written)).throw
                }
            }
        };
        ^pieces.collect { |piece| MusicRest(piece) }
    }

    *prRebuild { |element, measure, offsets, multipliers|
        if (element.isKindOf(Voice)) {
            ^[Voice(
                this.prRebuildAll(element.children, measure, offsets, multipliers),
                element.name)]
        };
        if (element.isKindOf(Tuplet)) {
            ^[Tuplet.like(element,
                this.prRebuildAll(element.children, measure, offsets, multipliers))]
        };
        if (element.isKindOf(ScoreContainer)) {
            ^[ScoreContainer(
                this.prRebuildAll(element.children, measure, offsets, multipliers))]
        };
        ^this.prSplitLeaf(element, measure.meter, offsets[element],
            multipliers[element])
    }

    // Copy the leaf as one or more notatable pieces. Outside tuplets
    // the meter grid decides. Inside tuplets the written duration
    // decomposes binarily.
    *prSplitLeaf { |leaf, meter, offset, multiplier|
        var pieces;
        if (leaf.dur.isNotatable) { ^[this.prCopyLeaf(leaf, leaf.dur, leaf)] };
        pieces = if (multiplier == Duration(1, 1)) {
            this.prMetricPieces(offset, leaf.dur, meter, 0)
        } {
            leaf.dur.tieRuns ?? {
                Error("ScorePrepare: % inside a tuplet cannot be split into tied "
                    "notatable pieces.".format(leaf.dur)).throw
            }
        };
        // Every piece but the last ties into the one after it. The
        // last carries whatever the original leaf tied to next.
        ^pieces.collect { |piece, i|
            this.prCopyLeaf(leaf, piece, if (i == (pieces.size - 1)) { leaf } { nil },
                i == 0)
        }
    }

    // Copy one split fragment. See Note [Split leaf adornments].
    *prCopyLeaf { |leaf, dur, tieSource, keepMarkings = true|
        var mask, copy;
        copy = case
            { leaf.isKindOf(MusicRest) } { MusicRest(dur) }
            { leaf.isKindOf(Chord) } {
                // `false` is the accepted spelling for "no chord ties".
                mask = if (tieSource.isNil) { true } { tieSource.tiesToNext };
                if (mask.isSequenceableCollection and: { mask.every { |f| f.not } }) {
                    mask = false
                };
                Chord(leaf.pitches, dur, mask)
            }
            { leaf.isKindOf(MusicNote) } {
                MusicNote(leaf.pitch, dur,
                    if (tieSource.isNil) { true } { tieSource.tiesToNext })
            }
            { true } {
                Error("ScorePrepare: cannot copy a %.".format(leaf.class)).throw
            };
        // Markings and grace groups stay on the attack.
        if (keepMarkings) {
            copy.markings_(leaf.markings);
            copy.graces_(
                leaf.graces.collect { |grace| this.prCopyLeaf(grace, grace.dur, grace) },
                leaf.graceStyle);
        };

        // Starts stay on the first fragment. Stops stay on the last.
        copy.spanners_(leaf.spanners.select { |endpoint|
            if (endpoint.isStart) { keepMarkings } { tieSource.notNil }
        });
        ^copy
    }

    // Split at the strongest crossed metric line, then recurse. Notes settle
    // when notatable. A rest is also placed, by
    // Note [A rest carries the beat, a note carries the line] in Meter.sc.
    //
    // Meter owns the hierarchy. See
    // Note [The metric hierarchy belongs to Meter] in Meter.sc.
    *prMetricPieces { |offset, dur, meter, depth, forRest = false|
        var point, acc, cursor;
        var settled = if (forRest) {
            meter.admitsRest(offset, dur)
        } {
            dur.isNotatable
        };
        if (settled) { ^[dur] };
        if (depth > 16) {
            Error("ScorePrepare: gave up splitting % at offset % in %.".format(
                dur, offset, meter)).throw
        };
        point = meter.strongestInteriorPoint(offset, dur);
        if (point.isNil) {
            Error("ScorePrepare: % at offset % in % crosses no writable metric "
                "line.".format(dur, offset, meter)).throw
        };
        acc = List.new;
        cursor = offset;
        [point, offset + dur].do { |edge|
            acc.addAll(this.prMetricPieces(
                cursor, edge - cursor, meter, depth + 1, forRest));
            cursor = edge;
        };
        ^acc.asArray
    }

    // Public helpers need one coherent bar. Mixed voice/loose-element
    // bars have no meaningful leaf offsets.
    *prRequireMeasure { |element, method|
        if (element.isKindOf(Measure).not) {
            Error("ScorePrepare.%: expected a Measure, got a %.".format(
                method.name, element.class)).throw
        };
        if (element.mixesVoicesWithElements) {
            Error("ScorePrepare.%: a % bar mixes voices with loose elements."
                .format(
                    method.name, element.meter)).throw
        };
        ^this
    }

    // Records each leaf it reaches and returns the offset just past
    // `element`, so a container can thread the cursor through its
    // children. Either dictionary may be nil when a caller wants only
    // the other one.
    *prWalk { |element, offset, multiplier, offsets, multipliers|
        var inner, cursor;
        if (element.isLeaf) {
            offsets !? { |d| d.put(element, offset) };
            multipliers !? { |d| d.put(element, multiplier) };
            ^offset + (element.duration * multiplier)
        };
        inner = multiplier * element.multiplier;
        cursor = offset;
        element.children.do { |child|
            cursor = this.prWalk(child, cursor, inner, offsets, multipliers)
        };
        ^cursor
    }
}
