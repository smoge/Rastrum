// ScorePrepare: the notation preparation pass.
//
// `run` is the entry point. Everything else is a fact it needs or a step it
// takes. The two measuring helpers are public because callers may need leaf
// offsets and written time scale.
//
// Both measuring helpers demand a Measure. A metric offset needs a barline.
// Accepting a Staff would return plausible offsets that accumulate across
// barlines and fail later as bad notation.
//
// No output syntax and no writer knowledge.


// Note [Written time and sounding time]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A tupleted leaf has two durations: the one it is written as, and the one it
// sounds for. They differ by the product of the multipliers above it.
//
// Metric boundaries are questions about *sounding* time, so offsets are
// prolated. Splits produce *written* durations under that same multiplier, and
// `prSplitElement` cuts at a point measured in the element's own written time.
// Recursion can then handle nested brackets without special cases. Each level
// divides by one multiplier, as the barline did for the outermost bracket.
//
// Keeping both facts per leaf is what lets the splitting pass work without
// re-deriving the tree.


// Note [Rests are respelled, not split]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Adjacent plain rests in one timeline are one silence however they were typed.
// The pass reads a run as one span and writes the fewest rests that spell it
// where it sits. Four quarter rests filling 4/4 become one whole rest. Three
// filling beats 2 to 4 become a quarter and a half.
//
// Not the dotted half they add up to, because that hides the half bar. Summing
// is not respelling. The same metric rule that splits an overlong note decides
// rest spelling, with the run's own start as the offset.
//
// Rests never tie, so the pieces are separate rests. The same rewrite for notes
// would introduce ties nobody wrote.


// Note [Copy, never adopt]
// ~~~~~~~~~~~~~~~~~~~~~~~~
//
// Nothing here is mutated. The walks read the tree and return dictionaries
// beside it. `run` builds a new tree. Note [Adding a child repoints its parent]
// in ScoreElement.sc is why reused elements would corrupt the caller's tree.
// Whole elements, split halves, rebuilt containers, markings, spanners, and
// graces are copied. Grace leaves are copied too because they are mutable.
//
// Parent links are never consulted either: the walks descend from the element
// they are given rather than climbing, so their answers stay correct while a
// tree is half-rebuilt.
ScorePrepare {

    // Each leaf maps to its exact offset from the start of the bar, in sounding
    // time.
    //
    // Every voice restarts at the same place, because voices run alongside each
    // other rather than one after the next. Threading one cursor through the
    // whole measure would put the second voice after the first and hand every
    // one of its leaves a metric position it does not have.
    //
    // That place is the bar's `metricOffset`, which is zero for an ordinary bar
    // and not for a pickup: a quarter-note anacrusis to 4/4 is the last beat of
    // its notional bar, so its contents are measured from 3/4 in. Measuring
    // them from zero would spell them as if they began the bar.
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

    // Each leaf maps to the product of every multiplier above it, i.e. what
    // turns its written duration into its sounding one. 1 for a leaf outside
    // any tuplet.
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

    // Returns a new tree ready for a writer: every leaf notatable, split leaves
    // joined by forward ties. The tree it is given is never touched.
    //
    // Explicit and facade-assisted: the facade calls this before writing a
    // file, and no writer calls it. See
    // Note [A writer refuses what it depends on] in ScoreWriter.sc.
    //
    // Given a staff or a score, overflow moves across barlines: a duration
    // running past a bar is cut at the barline and tied onward, and the bar
    // after it is measured from the barline. Given a lone Measure there is
    // nowhere to put overflow, so it must match its meter exactly.
    //
    // Material only ever moves forward. A bar short of its meter is an error
    // unless the bar before it spills into it, so a short bar cannot quietly
    // borrow from the next one.
    //
    // Five eighths is no note head, so it leaves as a half tied to an eighth:
    // two leaves in, three out, and the sounding rhythm unchanged:
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
            // Checked here rather than inside prPrepareMeasure so the message
            // names the entry point the caller actually used.
            this.prRequireMeasure(element, thisMethod);
            ^this.prPrepareMeasure(element)
        };
        Error("ScorePrepare.run: expected a MusicScore, Staff or Measure, got a %. "
            "A leaf has no bar to be prepared against.".format(element.class)).throw
    }

    // A staff is prepared as a whole, because a duration that runs past a
    // barline is only resolvable with the next bar in hand.
    //
    // Two phases. First the bars are re-cut: each timeline becomes one stream
    // of elements, and that stream is laid back down against the meter
    // sequence, so anything overflowing a bar is split at the barline and tied
    // onward. Every bar that comes out of that is exactly full by construction.
    // Then each bar goes through the ordinary within-measure pass, which is
    // untouched.
    //
    // Splitting the stream first and measuring second is what keeps the metric
    // rule honest: the carried piece begins a bar at offset zero, and whatever
    // follows it is measured from there rather than from where it was authored.
    *prPrepareStaff { |staff|
        var measures = staff.children.asArray;
        var flat, count, names, bars;

        if (measures.isEmpty) { ^Staff([], staff.name, staff.clef) };
        measures.do { |bar|
            if (bar.isKindOf(Measure).not) {
                Error("ScorePrepare: a Staff holds a %. Only measures can be laid "
                    "out against a meter sequence.".format(bar.class)).throw
            };
            this.prRequireMeasure(bar, thisMethod);
        };

        flat = measures.every { |bar| bar.hasVoices.not };
        count = this.prVoiceCount(measures, flat);
        names = this.prVoiceNames(measures, count, flat);

        bars = this.prLayOut(measures, count, names, flat);
        ^Staff(bars.collect { |bar| this.prPrepareMeasure(bar) }, staff.name, staff.clef)
    }

    // Timelines are matched across bars by position, so every bar must offer
    // the same number of them. There is no way to tell which voice of a
    // three-voice bar continues which of a two-voice one, and guessing would
    // tie the wrong notes together.
    *prVoiceCount { |measures, flat|
        var counts;
        if (flat) { ^1 };
        measures.do { |bar|
            if (bar.hasVoices.not) {
                Error("ScorePrepare: a % bar has no voices while others in the same "
                    "staff do. Voices are matched across barlines by position, so "
                    "every bar must carry the same ones.".format(bar.meter)).throw
            }
        };
        counts = measures.collect { |bar| bar.voices.size }.asSet;
        if (counts.size != 1) {
            Error("ScorePrepare: this staff has bars with % different voice counts. "
                "A voice continues across a barline by position, so the count "
                "cannot change mid-staff.".format(counts.size)).throw
        };
        ^counts.asArray.first
    }

    // Names are optional, but where they exist they must agree: a voice called
    // "upper" in one bar and "lower" in the next is not one timeline.
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
                            Error("ScorePrepare: voice % is called \"%\" in one bar "
                                "and \"%\" in another. Matched by position, those "
                                "are one timeline, so the names must agree.".format(
                                    j + 1, names[j], voice.name)).throw
                        }
                    }
                }
            }
        };
        ^names
    }

    // Lay each stream back down against the meter sequence, cutting at
    // barlines. A leaf that runs past a barline is split and tied onward. A
    // rest is split without a tie. A tuplet is not split: a bracket spanning a
    // barline is exotic notation, and producing one by accident would be worse
    // than saying so.
    *prLayOut { |measures, count, names, flat|
        var meters = measures.collect { |bar| bar.meter };
        // A bar holds what it declares, which is its meter only when it is not
        // partial. Carrying overflow against the meter would overfill a pickup.
        var spans = measures.collect { |bar| bar.barDuration };
        var perBar = Array.fill(meters.size, { Array.fill(count, { List.new }) });
        count.do { |j|
            var barIndex = 0, position = Duration(0, 1);
            // Bar by authored bar, not one flat stream. Streaming everything
            // and re-cutting would let a short bar quietly borrow from the next
            // one, which is the opposite of the rule: overflow moves forward,
            // never backward. Checking after each authored bar is what keeps a
            // bar that is simply short an error rather than a silent rebalance.
            measures.do { |authored, i|
                authored.voices[j].children.do { |element|
                    var sounding = element.duration * element.multiplier;
                    var placed = false, first = true;
                    if (element.isLeaf.not) {
                        // A bracket crossing a barline is cut where the barline
                        // falls and rewrapped on both sides, so no Tuplet ever
                        // spans two bars: that shape has nowhere to live in the
                        // tree and neither format can draw it. The loop repeats
                        // because a long bracket may cross several.
                        var remaining = element, placedHere = false;
                        while { placedHere.not } {
                            var capacity, halves;
                            if (barIndex >= meters.size) {
                                this.prOverflowedTheStaff(element)
                            };
                            capacity = spans[barIndex] - position;
                            sounding = remaining.duration * remaining.multiplier;
                            if (sounding <= capacity) {
                                perBar[barIndex][j].add(this.prCopyElement(remaining));
                                position = position + sounding;
                                placedHere = true;
                            } {
                                // The cut is a barline, which is a fact about
                                // sounding time, but a bracket is divided in
                                // written time, so the capacity is divided by
                                // the multiplier to ask where inside the
                                // bracket that barline actually falls.
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
                                // The piece that reaches the barline ties
                                // onward.
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
                // Its own bar must be accounted for by now, whether by its own
                // contents or by what the bar before it spilled in.
                if (barIndex == i) {
                    Error("ScorePrepare: bar % declares % and is short of it by %"
                        "%. A bar short of what it declares is an error unless the "
                        "bar before it spills into it - material never moves "
                        "backward.".format(
                            i + 1, spans[i], spans[i] - position,
                            if (count > 1) { " in voice " ++ (j + 1) } { "" })).throw
                }
            }
        };
        ^measures.collect { |authored, i|
            // Directions and the clef mark a place in the music, and re-cutting
            // the contents does not move that place. The bar is rebuilt rather
            // than copied, so a field not named here is a field dropped.
            Measure.partial(authored.meter, if (flat) {
                perBar[i][0].asArray
            } {
                perBar[i].collect { |elements, j| Voice(elements.asArray, names[j]) }
            }, authored.barDuration, authored.metricOffset)
                .clef_(authored.clef)
                .directions_(authored.directions)
        }
    }

    // A structural copy, no splitting: the laying-out phase needs elements it
    // can put into fresh bars. See Note [Copy, never adopt].
    *prCopyElement { |element|
        if (element.isKindOf(Voice)) {
            ^Voice(element.children.collect { |c| this.prCopyElement(c) }, element.name)
        };
        if (element.isKindOf(Tuplet)) {
            ^Tuplet.like(element, element.children.collect { |c| this.prCopyElement(c) })
        };
        if (element.isKindOf(Measure)) {
            // Everything the bar declares, not just its meter. No Measure
            // reaches here today, a bar being a child of a Staff, but a copy
            // that dropped the span, offset, clef and directions is a trap to
            // leave loaded.
            ^Measure.partial(element.meter,
                element.children.collect { |c| this.prCopyElement(c) },
                element.barDuration, element.metricOffset)
                    .clef_(element.clef)
                    .directions_(element.directions)
        };
        if (element.isKindOf(ScoreContainer)) {
            ^ScoreContainer(element.children.collect { |c| this.prCopyElement(c) })
        };
        ^this.prCopyLeaf(element, element.dur, element)
    }

    // Returns [head, tail], the element cut at `at`, measured in the element's
    // own written time, per Note [Written time and sounding time]. The halves'
    // durations sum to the original's.
    //
    // A leaf becomes two leaves, tied where a tie means anything: the near
    // piece continues into the far one, and the far one inherits whatever the
    // original tied onward. A rest ties nothing, two rests in a row being two
    // rests.
    //
    // A container is cut by walking its children until one straddles the point,
    // splitting that one, and rewrapping each side, with the straddling child
    // asked the same question in its own written time.
    *prSplitElement { |element, at|
        var head = List.new, tail = List.new, cursor = Duration(0, 1);
        if (element.isLeaf) {
            ^[this.prCopyLeaf(element, at, nil, true),
              this.prCopyLeaf(element, element.dur - at, element, false)]
        };
        element.children.do { |child|
            // What a child takes of its parent is its own written duration
            // scaled by its own multiplier, which is how
            // `ScoreContainer#duration` adds them up: a nested bracket occupies
            // less of the parent than its contents measure. Walking by the raw
            // duration would put the cut somewhere the barline is not.
            var next = cursor + (child.duration * child.multiplier);
            case
                { next <= at } { head.add(this.prCopyElement(child)) }
                { cursor >= at } { tail.add(this.prCopyElement(child)) }
                { true } {
                    // The point crosses into the child's own frame the same way
                    // the barline crossed into this one.
                    var halves = this.prSplitElement(child,
                        (at - cursor) / child.multiplier);
                    head.add(halves[0]);
                    tail.add(halves[1]);
                };
            cursor = next;
        };
        ^[this.prRewrap(element, head.asArray), this.prRewrap(element, tail.asArray)]
    }

    // A fragment is the same kind of container at the same ratio: half a
    // triplet is still a triplet, and the bracket over each half says so.
    //
    // An empty side is nil rather than an empty bracket. The placing loop never
    // asks for one, since it cuts only where a barline falls strictly inside the
    // element, but a bracket over nothing would be a lie either way.
    *prRewrap { |element, children|
        if (children.isEmpty) { ^nil };
        if (element.isKindOf(Tuplet)) { ^Tuplet.like(element, children) };
        if (element.isKindOf(ScoreContainer)) { ^ScoreContainer(children) };
        Error("ScorePrepare: cannot split a %".format(element.class)).throw
    }

    *prOverflowedTheStaff { |element|
        Error("ScorePrepare: % runs past the last barline. There is no bar left for "
            "it to continue into.".format(element)).throw
    }

    *prPrepareMeasure { |measure|
        var offsets, multipliers;
        if (measure.isFull.not) {
            Error("ScorePrepare: a % bar declares % of music and holds %. Every "
                "voice must fill the bar's own span; a staff redistributes overflow "
                "across barlines, but a lone measure has nowhere to put it.".format(
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

    // A leaf may become several, so every rebuild answers an array, and a run
    // of rests may become fewer, which is why rests are gathered before they
    // are rebuilt rather than one at a time.
    //
    // A run reaches only as far as the next sibling that is not a plain rest,
    // so nothing merges across a note, across a voice or across a barline:
    // those are different child lists and this is only ever asked about one.
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

    // A rest carrying an attachment is not plain, and does not join a run. Two
    // rests merged into one have one place left to put two dynamics, and no
    // answer for which of them keeps it, so an attachment pins its rest, and
    // the ordinary leaf path rebuilds it unchanged. A grace group pins it for
    // the same reason, and a merge that dropped one would lose written music.
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
        // A bar of silence is one rest whatever the meter, so it is not split
        // at all. Notation draws it as the bar's silence rather than as a note
        // value, which is what lets a bar of 5/8 have one, and splitting it
        // into the two rests a note value would need is exactly what would take
        // that away. `Measure#wholeBarRests` recognizes what this leaves
        // behind.
        if (multipliers[first] == Duration(1, 1)
            and: { measure.isPartial.not }
            and: { offsets[first] == measure.metricOffset }
            and: { written == measure.barDuration }) { ^[MusicRest(written)] };
        pieces = if (multipliers[first] == Duration(1, 1)) {
            this.prMetricPieces(offsets[first], written, measure.meter, 0, true)
        } {
            // Inside a tuplet the bar's grid does not reach, so the written
            // span decomposes binarily, exactly as a tupleted note does.
            if (written.isNotatable) { [written] } {
                written.tieRuns ?? {
                    Error("ScorePrepare: % of rest inside a tuplet cannot be "
                        "written as notatable pieces".format(written)).throw
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

    // Returns the leaf, copied, as one or more notatable pieces.
    //
    // Which timeline decides the split depends on where the leaf sits. Outside
    // a tuplet, written and sounding time agree, so the bar's metric grid
    // decides. Inside one they do not, and the bar's grid maps back to written
    // positions that are not notatable at all, so the tuplet's own written
    // span is the metric frame there, and the split is the duration's binary
    // decomposition.
    *prSplitLeaf { |leaf, meter, offset, multiplier|
        var pieces;
        if (leaf.dur.isNotatable) { ^[this.prCopyLeaf(leaf, leaf.dur, leaf)] };
        pieces = if (multiplier == Duration(1, 1)) {
            this.prMetricPieces(offset, leaf.dur, meter, 0)
        } {
            leaf.dur.tieRuns ?? {
                Error("ScorePrepare: % inside a tuplet cannot be written as tied "
                    "notatable pieces".format(leaf.dur)).throw
            }
        };
        // Every piece but the last ties into the one after it. The last carries
        // whatever the original leaf tied to next.
        ^pieces.collect { |piece, i|
            this.prCopyLeaf(leaf, piece, if (i == (pieces.size - 1)) { leaf } { nil },
                i == 0)
        }
    }

    // `tieSource` is the leaf whose forward tie this piece inherits, or nil for
    // a piece that ties into the next one because it was split. `keepMarkings`
    // is true only for the first piece: a dynamic or an articulation belongs to
    // the attack, and repeating it on every tied continuation would
    // re-articulate a note that is still sounding.
    *prCopyLeaf { |leaf, dur, tieSource, keepMarkings = true|
        var mask, copy;
        copy = case
            { leaf.isKindOf(MusicRest) } { MusicRest(dur) }
            { leaf.isKindOf(Chord) } {
                // Chord reports an all-false mask but refuses one, so a chord
                // that ties nothing has to be rebuilt with `false` rather than
                // its own tiesToNext read straight back.
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
                Error("ScorePrepare: cannot copy a %".format(leaf.class)).throw
            };
        // A grace group ornaments the attack, so it goes where a marking goes.
        // Sounding it again in front of a note still ringing is the mistake a
        // repeated dynamic makes, and `keepMarkings` marks the first piece.
        //
        // The grace leaves are copied, not shared. A Marking has no setters, so
        // `markings_` copying only the list is enough. A grace leaf is a
        // MusicNote, whose dur, pitch and tiesToNext all are writable.
        if (keepMarkings) {
            copy.markings_(leaf.markings);
            copy.graces_(
                leaf.graces.collect { |grace| this.prCopyLeaf(grace, grace.dur, grace) },
                leaf.graceStyle);
        };

        // A spanner's two ends move to opposite pieces, which is why `ScoreLeaf`
        // keeps them in a list of their own. The slur still begins where the
        // note began and ends where it ended, so a start rides the first piece
        // and a stop the last. Copying both onto every piece would open and
        // close the same slur once per fragment.
        //
        // `keepMarkings` marks the first piece. A non-nil `tieSource` marks the
        // last, because only the last inherits the original's forward tie. An
        // unsplit leaf is both, and keeps both ends.
        copy.spanners_(leaf.spanners.select { |endpoint|
            if (endpoint.isStart) { keepMarkings } { tieSource.notNil }
        });
        ^copy
    }

    // Split [offset, offset + dur) at the strongest metric line it crosses,
    // then recurse on the pieces until each is settled. One line at a time, not
    // every line of that strength: a bar of five eighths would otherwise
    // shatter a 5/8 into five, rather than reading it as 2 + 3.
    //
    // `wellPlaced` chooses what "settled" means. A note only has to be
    // notatable, because splitting it further would add ties nobody asked for.
    // A rest has to be notatable *and* sit where it can be read, which is the
    // stricter question `Meter#isWellPlaced` asks, and rests cost nothing to
    // split.
    //
    // Where the lines are is the meter's own answer, not this class's, which is
    // what lets a bar grouped 2+3 and one grouped 3+2 split differently while
    // everything here stays the same. See
    // Note [The metric hierarchy belongs to Meter] in Duration.sc.
    *prMetricPieces { |offset, dur, meter, depth, wellPlaced = false|
        var point, acc, cursor;
        var settled = if (wellPlaced) {
            meter.isWellPlaced(offset, dur)
        } {
            dur.isNotatable
        };
        if (settled) { ^[dur] };
        if (depth > 16) {
            Error("ScorePrepare: gave up splitting % at offset % in %".format(
                dur, offset, meter)).throw
        };
        point = meter.strongestInteriorPoint(offset, dur);
        if (point.isNil) {
            Error("ScorePrepare: % at offset % in % crosses no metric line that "
                "would let it be written".format(dur, offset, meter)).throw
        };
        acc = List.new;
        cursor = offset;
        [point, offset + dur].do { |edge|
            acc.addAll(this.prMetricPieces(
                cursor, edge - cursor, meter, depth + 1, wellPlaced));
            cursor = edge;
        };
        ^acc.asArray
    }

    // The precondition every public entry point shares: the argument is a bar,
    // and the bar is one this class can say anything coherent about.
    //
    // The mixed-bar check belongs here rather than on `run` alone. The offsets
    // a mixed bar produces are not merely unprepared, they are fiction. A
    // Voice beside a loose note gives both of them offset zero, because there
    // is no answer to when the loose note starts. Anything reading those
    // numbers is worse off than if it had been refused.
    *prRequireMeasure { |element, method|
        if (element.isKindOf(Measure).not) {
            Error("ScorePrepare.%: needs a Measure, got a %. Offsets are only "
                "meaningful from the start of a bar; anything wider would count "
                "straight through barlines.".format(method.name, element.class)).throw
        };
        if (element.mixesVoicesWithElements) {
            Error("ScorePrepare.%: a % bar mixes voices with loose elements. A bar "
                "is either one timeline or a set of voices, not both - mixed, there "
                "is no answer to when the loose elements start.".format(
                    method.name, element.meter)).throw
        };
        ^this
    }

    // Records each leaf it reaches and returns the offset just past `element`,
    // so a container can thread the cursor through its children. Either
    // dictionary may be nil when a caller wants only the other one.
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
