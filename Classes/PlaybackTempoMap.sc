// PlaybackTempoMap: a score's tempo directions, in order, with a speed.
//
// Two jobs stay separate: read where tempo marks are, then decide what they
// mean.
//
// See Note [Structure is derived, interpretation is chosen] in PlaybackMap.sc
// for the layer boundary. Tempo marks belong to a score moment, not to a note
// event, so they need their own list.
//
// A `Direction` attaches to a `Measure`, and every timeline in the staff crosses
// that bar together. There may be no event at the tempo change. A voice tied
// through a barline has no attack there, but the tempo still changes.
//
// Writing `\tempo` on note events would make several voices set one shared
// clock. This class answers one ordered list instead.
//
// Prose is notation. A number is an interpretation.
// `Direction.tempo("Allegro")` stays prose because that is what the writers
// print, and "Allegro" is not 120. Named speeds live in this table. A prose mark
// with no table entry is refused rather than played at a guessed speed.
//
// A metronome mark needs no table. `4 = 132` on the page is not an
// interpretation of anything. The number is the notation, so it is read from
// the score.
//
// Where a direction says both "Allegro" and `4 = 132`, the score's number wins.
// Notation beats interpretation.
PlaybackTempoMap {
    // Prose to beats per minute. Not readable directly. See `tempos`.
    var tempos;

    *new { ^super.new.init }

    init {
        tempos = Dictionary.new;
        ^this
    }

    // Answers this, so calls chain. Checked into locals first, on the rule
    // Note [A refused call changes nothing] states in PlaybackMap.sc.
    tempo { |text, bpm|
        var checkedText = Marking.checkedText(text);
        var checkedBpm = this.prCheckedBpm(bpm, text);

        tempos[checkedText] = checkedBpm;
        ^this
    }

    // A copy. See Note [A table is handed out as a copy] in PlaybackMap.sc.
    //
    // >>> PlaybackTempoMap.new.tempos.size   -> 0
    // >>> PlaybackTempoMap.new.tempo("Andante", 72).tempos["Andante"]
    // 72
    tempos { ^tempos.copy }

    copy {
        var out = PlaybackTempoMap.new;
        tempos.keysValuesDo { |text, bpm| out.tempo(text, bpm) };
        ^out
    }

    // Returns the score's tempo directions in order, each as `(offset:,
    // measure:, text:, unit:, perMinute:, bpm:)`, with the speed this map gives
    // it.
    //
    // `bpm` comes from the score where the mark carries a metronome and from
    // the table where it is prose. The score wins when a mark says both. `unit`
    // and `perMinute` are the mark as written. `bpm` is that mark in quarter
    // notes, which is what a clock counts.
    //
    // Refuses a prose mark it has no speed for. The alternative is to leave the
    // tempo out and play the passage at whatever came before, which is a
    // performance decision made by omission.
    records { |element, prepare = true|
        var found = PlaybackTempoMap.directionsIn(element, prepare);
        var unmapped = found.select { |record|
            record[\bpm].isNil and: { tempos[record[\text]].isNil }
        };

        if (unmapped.notEmpty) { this.prRefuseUnmapped(unmapped.first) };
        ^found.collect { |record|
            var out = record.copy;
            out[\bpm] = record[\bpm] ?? { tempos[record[\text]] };
            out
        }
    }

    // Returns the tempo changes as SC Events, ready to be streamed beside the
    // music: `(type: \rest, dur:, tempo:, rastrum:)`, in order.
    //
    // Rests, because a tempo change is not a note. SC applies `~tempo` in
    // `~play` *before* it asks whether the event is a rest, so a rest sets the
    // clock and sounds nothing. That is exactly what a tempo change is.
    //
    // `\tempo` is beats per second, because that is what `TempoClock.tempo` is.
    // the table is in beats per minute, because that is what a score means. One
    // divided by sixty at one place, here.
    //
    // Two events are not tempo changes and are here anyway, because the list
    // has to be schedulable as it stands:
    //
    // 1. A *lead-in* when the first change is not at the start, carrying `dur`
    //    and no `\tempo`. An event stream plays its first event at once, so
    //    without it a tempo written over bar five would take effect at bar one.
    // 2. A single inert event when the score has no tempo directions. An empty
    //    pattern is not available to be the no-op: `ListPattern` refuses a
    //    `Pseq([])` where it is built, so the no-op is one rest of no length.
    //
    // Both are told apart by having no `\tempo` key, and neither carries a
    // `\rastrum` payload, because nothing in the score put them there.
    tempoEvents { |element, prepare = true|
        ^PlaybackTempoMap.eventsFrom(this.records(element, prepare))
    }

    // The scheduling, over a list of records that already have their speeds.
    //
    // A class method because it needs no table: given `bpm`, the lead-in, the
    // gaps and the rests are arithmetic. `withScoreTempo` uses it on the subset
    // of a score's marks that carry their own number, which is a list no
    // instance of this ever saw.
    *eventsFrom { |records|
        var out = List.new;

        if (records.isEmpty) { ^[this.prSilence(0)] };
        if (records.first[\offset] > Duration(0, 1)) {
            out.add(this.prSilence(this.prBeats(records.first[\offset])))
        };
        records.do { |record, index|
            var next = records[index + 1];
            var beats = next !? {
                this.prBeats(next[\offset] - record[\offset])
            } ? 0;
            var event = this.prSilence(beats);

            event[\tempo] = record[\bpm] / 60;
            event[\rastrum] = record;
            out.add(event);
        };
        ^out.asArray
    }

    // The music with the score's own metronome marks laid over it, in the order
    // that works, or unchanged when the score states none. What
    // `Rastrum.pattern` composes.
    //
    // Only marks carrying a number take part, and prose is passed over rather
    // than refused, unlike `records`, which refuses an unmapped word because
    // someone asked *this map* what the score means. A caller asking a score
    // for its pattern asked nothing of the kind.
    //
    // Takes a tree the caller has already prepared, which is what
    // `Rastrum.prepared` is public for.
    *withScoreTempo { |pattern, tree|
        var marked = this.prWalk(tree, { |direction| direction.hasMetronome });

        if (marked.isEmpty) { ^pattern };
        ^Ppar([Pseq(this.eventsFrom(marked), 1), pattern])
    }

    // The same list as one pattern, to sit beside the music.
    //
    // A `Pseq` of whole Events rather than a `Pbind` of key streams, which is
    // the opposite of what `PatternWriter` does and for a reason: a lead-in
    // sets no tempo, and a `Pbind` key that is nil on one step ends the stream
    // there. The composability a Pbind buys is worth nothing here anyway,
    // since there is no `\amp` to lay over a tempo change.
    //
    // Put it first in the `Ppar`. Two children due at the same moment are taken
    // in order, so a tempo change written over the bar a note begins on reaches
    // the clock before that note is scheduled. The other order plays one note
    // at the old speed:
    //
    // Ppar([~map.tempoPattern(~score), Rastrum.pattern(~score)])
    tempoPattern { |element, prepare = true|
        ^Pseq(this.tempoEvents(element, prepare), 1)
    }

    // A rest that sounds nothing and sets nothing: `dur` and no more.
    *prSilence { |beats| ^(type: \rest, dur: beats) }

    // Whole notes to beats, the one conversion in this file and the same one
    // `EventWriter` makes: a quarter is one beat, so a bpm here is quarter
    // notes per minute.
    *prBeats { |duration| ^duration.asFloat * 4 }

    // The same walk without the table: what the score says, before anyone has
    // decided what it means. `bpm` is the score's own number where a mark
    // carries a metronome, and nil where it is prose. Nothing here invents
    // one.
    //
    // Bar starts are accumulated exactly as `EventWriter` accumulates them, by
    // `barDuration`, which is the bar's real span and so already right for a
    // pickup or any other short bar. That is not a coincidence to be maintained
    // by hand: an offset here that disagreed with an event offset would put a
    // tempo change in the wrong place relative to the music it governs.
    *directionsIn { |element, prepare = true|
        ^this.prWalk(Rastrum.prepared(element, prepare), { true })
    }

    // The walk itself, over a tree the caller has already prepared and
    // validated, keeping the tempo directions `keep` answers true for.
    //
    // The predicate is not a convenience. `withScoreTempo` looks only at marks
    // carrying a number, and it has to filter *before* the conflict check
    // rather than after: two contradictory words at one moment is a mistake
    // this class refuses when somebody asks it what the score means, but
    // `Rastrum.pattern` asks nothing of the kind and passes prose over.
    // Filtering afterwards would have the pattern refuse a score over marks it
    // then ignores, which it did, until this predicate existed.
    *prWalk { |tree, keep|
        var byOffset = Dictionary.new;
        var order = List.new;
        var conflicts = List.new;

        this.prStavesOf(tree).do { |staff|
            var barStart = Duration(0, 1);
            this.prMeasuresOf(staff).do { |measure, measureIndex|
                var span = if (measure.isKindOf(Measure)) {
                    measure.barDuration
                } {
                    measure.duration * measure.multiplier
                };
                if (measure.isKindOf(Measure)) {
                    measure.directions.do { |direction|
                        if (direction.isTempo and: { keep.value(direction) }) {
                            // `barStart + offset`: a direction's offset is
                            // local to its bar, and everything downstream works
                            // in absolute offsets: the cross-staff dedupe, the
                            // conflict refusal, the lead-in and the gaps
                            // between changes all key on the absolute value.
                            this.prAdd(byOffset, order, conflicts,
                                barStart + direction.offset, measureIndex,
                                direction)
                        }
                    }
                };
                barStart = barStart + span;
            };
        };

        if (conflicts.notEmpty) { this.prRefuseConflict(conflicts.first) };
        ^order.asArray.sort { |a, b| a[\offset] <= b[\offset] }
    }

    // One record per moment, however many staves say it.
    //
    // A tempo is a fact about the score rather than about a part, so the same
    // word written over bar 5 of every staff, which is how it is engraved, is
    // one tempo change, not four. Two *different* marks at one moment is not a
    // duplicate but a contradiction, and there is no reading of it to choose.
    //
    // Same means what it *says*, not where it stands: the mark is compared by
    // prose and metronome, never by offset. Staves can be barred differently,
    // so one absolute moment may be a different distance into its bar in each
    // of them. `Direction ==` would call that a contradiction when it is the
    // same instruction.
    *prAdd { |byOffset, order, conflicts, offset, measureIndex, direction|
        var seen = byOffset[offset];
        var record = IdentityDictionary[
            \offset -> offset, \measure -> measureIndex,
            \text -> direction.text, \unit -> direction.unit,
            \perMinute -> direction.perMinute,
            \bpm -> direction.quarterPerMinute];

        if (seen.isNil) {
            byOffset[offset] = record;
            order.add(record);
            ^this
        };
        if (this.prSaysTheSame(seen, record).not) {
            conflicts.add([offset, this.prDescribe(seen), this.prDescribe(record)])
        };
        ^this
    }

    *prSaysTheSame { |a, b|
        ^(a[\text] == b[\text]) and: { a[\unit] == b[\unit] }
            and: { a[\perMinute] == b[\perMinute] }
    }

    // The mark as it reads on the page, for an error message about two of them.
    *prDescribe { |record|
        var parts = List.new;
        record[\text] !? { |text| parts.add("\"" ++ text ++ "\"") };
        record[\unit] !? { |unit|
            parts.add("%/% = %".format(unit.numerator, unit.denominator,
                record[\perMinute]))
        };
        ^parts.join(" ")
    }

    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        ^[element]
    }

    *prMeasuresOf { |staff|
        if (staff.isKindOf(Staff)) { ^staff.children.asArray };
        ^[staff]
    }

    *prRefuseConflict { |entry|
        Error("PlaybackTempoMap: two tempo directions at %, % and %. One moment "
            "cannot be at two speeds. The same mark over every staff is one "
            "change, two different ones is a mistake in the score.".format(
                entry[0], entry[1], entry[2])).throw
    }

    prRefuseUnmapped { |record|
        Error("PlaybackTempoMap: this score is marked \"%\" at % and this map has "
            "no speed for it. It has %. Give it one with tempo(\"%\", bpm), or "
            "write a metronome mark on the score, which needs no table.".format(
                record[\text], record[\offset],
                if (tempos.isEmpty) { "none" } {
                    tempos.keys.asArray.sort.collect { |each|
                        "\"" ++ each ++ "\"" }.join(", ")
                },
                record[\text])).throw
    }

    // Beats per minute is a positive number. Zero is not a slow tempo, it is a
    // clock that never advances, and a nil would reach whatever schedules this.
    prCheckedBpm { |bpm, text|
        if (bpm.isNumber.not or: { bpm <= 0 }) {
            Error("PlaybackTempoMap: \"%\" needs a tempo in beats per minute "
                "above zero, not %.".format(text, bpm.asCompileString)).throw
        };
        ^bpm
    }
}
