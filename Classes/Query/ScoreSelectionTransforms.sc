+ ScoreSelection {
    // These transforms change a copy. `mapLeaves` gives the callback a copied
    // leaf and the source record. Records and runs are live views; edit only the
    // copy.

    // Every selected leaf replaced by what the function answers, as a copy of
    // the source. The function gets a copy of the leaf and its source record.
    mapLeaves { |function|
        var replacements = Dictionary.new;
        if (source.isNil) {
            Error("ScoreSelection.mapLeaves: selection has no source to copy.").throw
        };
        records.do { |record|
            // nil would store no replacement and silently leave the leaf. Copy
            // the answer before installation; adding a child can repoint it.
            replacements[record[\path]] = ScorePrepare.copyOf(
                ScoreSelection.prCheckedLeaf(
                    function.value(ScorePrepare.copyOf(record[\leaf]), record),
                    record[\path]))
        };
        ^ScoreSelection.prRebuild(source, [], replacements)
    }

    // One call per logical tie, answering the pitch that run becomes.
    //
    // A result equal to `run[\pitch]` leaves it alone. Results are
    // coerced with `MusicPitch.fromSpec`. Graces are carried, not
    // rewritten. They are not logical ties.
    mapLogicalTies { |function|
        var edits = Dictionary.new;
        this.logicalTies.do { |run, index|
            var becomes = MusicPitch.fromSpec(function.value(run, index));
            run[\records].do { |record|
                // Compare pitch contents with `==`; the run pitch may not be
                // the same live object.
                var at = ScoreSelection.prPitchesOf(record[\leaf])
                    .detectIndex { |pitch| pitch == run[\pitch] };
                var perLeaf = edits[record[\path]] ?? { Dictionary.new };
                perLeaf[at] = becomes;
                edits[record[\path]] = perLeaf
            }
        };
        ^this.mapLeaves { |leaf, record|
            ScoreSelection.prRepitched(leaf, edits[record[\path]])
        }
    }

    // Every selected leaf moved by an interval. Rests stay. Grace groups move
    // with their host leaf.
    //
    // >>> ScoreSelection(Measure("1/4", "c4")).transposeBy(MusicInterval.named(\major, 3)).leaves.first.pitch.letter
    // e
    transposeBy { |interval|
        ^this.mapLeaves { |leaf| ScoreSelection.prTransposed(leaf, interval) }
    }

    // Spell selected pitches with passage context.
    // One answer per logical tie; only spelling changes.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 c#4 d4 c#4")).spellPitches(\flats).leaves.collect { |leaf| leaf.pitch.spelling }
    // [ c[4], db[4], d[4], db[4] ]
    // >>> ScoreSelection(Measure("4/4", "c4 c#4 d4 c#4")).spellPitches(SpellingPolicy.byMotion).leaves.collect { |leaf| leaf.pitch.spelling }
    // [ c[4], c#[4], d[4], db[4] ]
    spellPitches { |policy|
        var label = "ScoreSelection.spellPitches";
        var contexts;

        MusicPitch.checkedSpelling(policy, label);
        SpellingPolicy.prCheckedPassageCapable(policy, label);
        contexts = this.prSpellingContexts;
        if (policy.isKindOf(SpellingPolicy) and: { policy.prAnswersPassage }) {
            ^this.prSpelledByPassage(policy, contexts, label)
        };
        ^this.mapLogicalTies { |run, index|
            MusicPitch.prFromNumberInPassage(run[\pitch].height, policy,
                run[\pitch].cents, contexts[index])
        }
    }

    // Call a passage resolver once, then check every positional answer.
    //
    // ^ ScoreElement
    prSpelledByPassage { |policy, contexts, label|
        // Add scalar keys to every passage context.
        var full = contexts.collect { |ctx|
            MusicPitch.prContextForPitch(ctx[\currentPitch], ctx) };
        var answers = policy.prValueAll(full);
        var checked;

        if (answers.isSequenceableCollection.not) {
            Error(
                "%: % answered % for a passage of %. A passage policy answers a "
                "list, one spelling per context."
                .format(label, policy, answers.class, contexts.size)
            ).throw
        };
        if (answers.size != contexts.size) {
            Error(
                "%: % answered % spellings for a passage of %. A passage policy "
                "answers one per context, in the order they were given."
                .format(label, policy, answers.size, contexts.size)
            ).throw
        };
        // Check all answers before writing.
        checked = answers.collect { |answer, at|
            MusicPitch.prCheckedChoice(answer, full[at],
                full[at][\currentPitch].height, policy)
        };
        ^this.mapLogicalTies { |run, index| checked[index] }
    }

    // Contexts in logical-tie order, using source spellings.
    // Melodic neighbors may cross barlines; bar rules read `measureIndex`.
    //
    // `runIndex` addresses one run; chord members share the other
    // location keys. Passage answers stay positional.
    //
    // ^ [Event]
    prSpellingContexts {
        var runs = this.logicalTies;
        var out = runs.collect { |run, at|
            // Bar-local position of the first selected head.
            var first = run[\records].first;

            (currentPitch: run[\pitch], runIndex: at, staffIndex: run[\staffIndex],
                voiceIndex: run[\voiceIndex], offset: run[\offset],
                measureIndex: first[\measureIndex], barOffset: first[\barOffset])
        };
        var timelines = Dictionary.new;

        runs.do { |run, index|
            var key = [run[\staffIndex], run[\voiceIndex]];
            timelines[key] = (timelines[key] ?? { List.new }).add(index)
        };
        timelines.do { |indices|
            ScoreSelection.prFillNeighbors(runs, out,
                ScoreSelection.prTimelinePositions(runs, indices.asArray))
        };
        ^out
    }

    // Run indices grouped by onset; multiple runs mean a chord.
    //
    // ^ [[Duration, [Integer]]]
    *prTimelinePositions { |runs, indices|
        var out = [];

        indices.do { |index|
            var offset = runs[index][\offset];
            var last = out.last;
            if (last.notNil and: { last[0] == offset }) {
                last[1] = last[1].add(index)
            } {
                out = out.add([offset, [index]])
            }
        };
        ^out
    }

    // Monophonic contiguous neighbors. Chords, rests and holes break
    // the line.
    //
    // ^ Self
    *prFillNeighbors { |runs, contexts, positions|
        positions.do { |position, place|
            var index = position[1].first;
            var context = contexts[index];
            var behind, ahead;

            // Chord members share one timeline index.
            position[1].do { |each| contexts[each][\index] = place };
            if (this.prIsMelodic(runs, position)) {
                var before = positions[place - 1];
                var after = positions[place + 1];

                if (this.prMeets(runs, before, position)) {
                    behind = runs[before[1].first]
                };
                if (this.prMeets(runs, position, after)) {
                    ahead = runs[after[1].first]
                };
                context[\previousPitch] = behind !? { |run| run[\pitch] };
                context[\nextPitchNumber] = ahead !? { |run| run[\pitch].number };
                context[\nextCents] = ahead !? { |run| run[\pitch].cents };
                context[\direction] = this.prRunMotion(
                    behind, runs[index], ahead)
            }
        };
        ^this
    }

    // One run at this onset, carried by a single-pitch leaf.
    //
    // ^ Boolean
    *prIsMelodic { |runs, position|
        var run;
        if (position.isNil or: { position[1].size != 1 }) { ^false };
        run = runs[position[1].first];
        ^this.prPitchesOf(run[\leaves].first).size == 1
    }

    // Both melodic; the earlier one ends at the later one's onset.
    //
    // ^ Boolean
    *prMeets { |runs, earlier, later|
        var run;
        if (this.prIsMelodic(runs, earlier).not) { ^false };
        if (this.prIsMelodic(runs, later).not) { ^false };
        run = runs[earlier[1].first];
        ^(run[\offset] + run[\duration]) == later[0]
    }

    // Incoming motion, or outgoing at the start. Height before cents.
    //
    // ^ Symbol
    *prRunMotion { |behind, here, ahead|
        if (behind.notNil) {
            ^this.prPitchMotion(behind[\pitch], here[\pitch])
        };
        if (ahead.notNil) { ^this.prPitchMotion(here[\pitch], ahead[\pitch]) };
        ^nil
    }

    // ^ Symbol
    *prPitchMotion { |from, to|
        if (to.height > from.height) { ^\up };
        if (to.height < from.height) { ^\down };
        if (to.cents > from.cents) { ^\up };
        if (to.cents < from.cents) { ^\down };
        ^\same
    }

    // Pitch cycle over logical ties. Tied continuations take one pitch, chord
    // streams advance independently and rests are skipped.
    //
    // >>> ScoreSelection(Measure("4/4", "c4 d4 e4 f4")).assignPitches("g a").leaves.collect { |leaf| leaf.pitch.letter }
    // [ g, a, g, a ]
    assignPitches { |pitches|
        var cycle;
        // Pitch only. These leaves already carry their own markings.
        if (pitches.isKindOf(String) and: { pitches.includes($:) }) {
            Error(
                "ScoreSelection.assignPitches: \"%\" carries a marking. This writes "
                "pitch onto leaves that already have their own, so attach one with "
                "`dynamic` or `articulation`, or write the bar out."
                .format(pitches)
            ).throw
        };
        cycle = MusicPitch.asPitches(pitches);
        if (cycle.isEmpty) {
            Error("ScoreSelection.assignPitches: needs at least one pitch.")
                .throw
        };
        ^this.mapLogicalTies { |run, index| cycle.wrapAt(index) }
    }

    // Rebuild by child-index path, so a replacement lands on the same leaf in a
    // tree of the same shape.
    *prRebuild { |element, path, replacements|
        if (element.isLeaf) {
            ^replacements[path] ?? { ScorePrepare.copyOf(element) }
        };
        ^ScorePrepare.rebuilt(element,
            element.children.collect { |child, index|
                this.prRebuild(child, path ++ [index], replacements) })
    }

    *prCheckedLeaf { |leaf, path|
        if (leaf.isKindOf(ScoreLeaf).not) {
            Error(
                "ScoreSelection.mapLeaves: function returned % for leaf at %. Return a ScoreLeaf."
                .format(leaf.class, path)
            ).throw
        };
        ^leaf
    }

    *prTransposed { |leaf, interval|
        var moved;
        // A rest has no pitch to move. It may still carry a grace group, which
        // does.
        if (leaf.isKindOf(MusicRest)) { ^this.prMoveGraces(leaf, leaf, interval) };
        if (leaf.isKindOf(Chord)) {
            moved = this.prChordLike(leaf,
                leaf.pitches.collect { |pitch| interval.transpose(pitch) });
            ^this.prMoveGraces(moved, leaf, interval)
        };
        if (leaf.isKindOf(MusicNote)) {
            leaf.pitch = interval.transpose(leaf.pitch);
            ^this.prMoveGraces(leaf, leaf, interval)
        };
        ^leaf
    }

    // Substitutions are by pitch position, so a leaf no run touched comes back
    // as it was and a chord keeps the pitches no run claimed.
    *prRepitched { |leaf, substitutions|
        if (substitutions.isNil) { ^leaf };
        if (leaf.isKindOf(Chord)) {
            ^this.prChordLike(leaf, leaf.pitches.collect { |pitch, index|
                substitutions[index] ? pitch })
        };
        if (leaf.isKindOf(MusicNote)) {
            leaf.pitch = substitutions[0] ? leaf.pitch;
            ^leaf
        };
        ^leaf
    }

    // A Chord's pitches are read-only and it refuses an all-false mask,
    // so one that changes pitch is rebuilt and its attachments
    // carried across by hand.
    *prChordLike { |chord, pitches|
        var fresh = Chord(pitches, chord.dur, this.prTieMask(chord));
        fresh.markings_(chord.markings);
        fresh.spanners_(chord.spanners);
        ^fresh.graces_(chord.graces, chord.graceStyle)
    }

    *prTieMask { |chord|
        var mask = chord.tiesToNext;
        if (mask.isSequenceableCollection and: { mask.every { |flag| flag.not } }) {
            ^false
        };
        ^mask
    }

    *prMoveGraces { |target, from, interval|
        ^target.graces_(
            from.graces.collect { |grace| this.prTransposed(grace, interval) },
            from.graceStyle)
    }
}
