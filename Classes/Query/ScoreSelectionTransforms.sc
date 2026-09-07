+ ScoreSelection {
    // These transforms change a copy. `mapLeaves` gives the callback a copied
    // leaf and the source record. Records and runs are live views; edit only the
    // copy.

    // Every selected leaf replaced by what the function answers, as a copy of
    // the source. The function gets a copy of the leaf and its source record.
    mapLeaves { |function|
        var replacements = Dictionary.new;
        if (source.isNil) {
            Error("ScoreSelection.mapLeaves: selection has no source to copy.")
                .throw
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
